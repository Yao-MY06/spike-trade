# Runbook：从零重建本项目运行环境

> 由 2026-10-20 首次跑通基线的真实命令整理，**照抄可复现**。环境变量与密码按本机实际值写死，换机器只改这几处。
> 计划依据：`E:\50-个人系统\02-项目管理\微服务高并发交易平台实施与验收.md` 2.1 基线登记。

## 前置（一次性）

| 依赖 | 本机实际 | 备注 |
| --- | --- | --- |
| JDK | 11.0.32.1 Temurin，`F:\path` | 默认 `java` 是 25，构建/运行都要显式指定 |
| Maven | 3.9.16，`E:\work\apache-maven-3.9.16` | 它自己跑在 JDK 25 上，靠 `JAVA_HOME` 切换 |
| MySQL | 9.5，3306，root / `${TRADE_DB_PASSWORD}` | 密码随命令行会告警，正常 |
| Redis | 容器 `redis`（`redis:6.2`），6379，密码 `${TRADE_REDIS_PASSWORD}` | `docker start redis` |
| RabbitMQ | 容器 `spike-rabbitmq`（`rabbitmq:3.8-management`），5672 / 15672，`qqq` / `${TRADE_MQ_PASSWORD}` | 与基线 README 声明版本对齐 |
| 前端 | nginx 1.18.0，`F:\study\spike-nginx`，占 80 | 只用浏览器验证时启动，接口验证不需要 |

## 第 1 步：起中间件

```bash
docker start redis            # 已存在则复用，勿重建（数据要留）
docker start spike-rabbitmq    # 首次创建用下面这条
# docker run -d --name spike-rabbitmq -p 5672:5672 -p 15672:15672 \
#   -e RABBITMQ_DEFAULT_USER=qqq -e RABBITMQ_DEFAULT_PASS=${TRADE_MQ_PASSWORD} rabbitmq:3.8-management

# 自检
docker exec redis redis-cli -a ${TRADE_REDIS_PASSWORD} ping          # 期望 PONG
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:15672   # 期望 200
```

## 第 2 步：建库并导入基线 SQL

```bash
MY="/c/Program Files/MySQL/MySQL Server 9.5/bin/mysql.exe"
"$MY" -uroot -p${TRADE_DB_PASSWORD} -e "drop database if exists spike_trade; create database spike_trade default charset utf8mb4 collate utf8mb4_general_ci;"
"$MY" -uroot -p${TRADE_DB_PASSWORD} --init-command="SET SESSION sql_mode='NO_ENGINE_SUBSTITUTION'" spike_trade < src/main/resources/db/schema.sql

# 自检：11 张表、0 条订单
"$MY" -uroot -p${TRADE_DB_PASSWORD} -e "use spike_trade; select count(*) from information_schema.tables where table_schema='spike_trade'; select count(*) from tb_voucher_order;"
```

**不要复用 `spike` 库**：那是阶段四（课程原版）的库，有实跑残留订单与已扣减库存，基线必须从干净状态起。

## 第 3 步：核对凭据（四处，不是一处）

| 位置 | 键 | 期望值 |
| --- | --- | --- |
| `application.yaml` | `spring.datasource.password` | `"${TRADE_DB_PASSWORD}"` |
| `application.yaml` | `spring.datasource.url` | 库名 `spike_trade`，driver `com.mysql.cj.jdbc.Driver` |
| `application.yaml` | `spring.redis.password` | `${TRADE_REDIS_PASSWORD}` |
| `application.yaml` | `spring.rabbitmq.host` | `127.0.0.1`（上游写的是作者虚拟机 `192.168.3.236`） |
| `RedissonConfig.java` | `setPassword(...)` | `${TRADE_REDIS_PASSWORD}` ← **硬编码，改 yaml 不会生效** |

## 第 4 步：构建与启动

```bash
cd /f/study/spike-base
export JAVA_HOME="F:\path"
mvn -B -DskipTests package          # 首次约 3 分钟（拉依赖），增量约 20 秒

# 必须显式指定端口，否则后台启动会被注入随机端口
"F:/path/bin/java.exe" -jar target/spike-trade-0.0.1-SNAPSHOT.jar --server.port=8081
```

## 第 5 步：判定启动成功
看日志四行齐全：

```
Tomcat started on port(s): 8081
Created new connection: rabbitConnectionFactory ... [delegate=amqp://qqq@127.0.0.1:5672/]
Started SpikeTradeApplication in 15.6 seconds
HikariPool-1 - Start completed.
```

再打一个读接口：

```bash
curl -s http://localhost:8081/shop-type/list | head -c 120
# 期望：{"success":true,"data":[{"id":1,"name":"美食",...
```

## 第 6 步：造券与预热（跑秒杀前必做）

**基线 SQL 里没有秒杀券**（`tb_seckill_voucher` 0 行），必须先造一张，否则秒杀无从验证。

```sql
-- id=100 仅作基线验证用
insert into tb_voucher(id,shop_id,title,sub_title,rules,pay_value,actual_value,type,status,create_time,update_time)
values (100,1,'测试秒杀券','基线验证专用','仅用于基线链路验证',1,10,1,1,now(),now());
insert into tb_seckill_voucher(voucher_id,stock,create_time,begin_time,end_time,update_time)
values (100,100,now(),date_sub(now(),interval 1 hour),date_add(now(),interval 1 day),now());
```

```bash
# 预热 Redis 库存（必须建，否则 Lua 里 tonumber(nil/false) 直接报错）
docker exec redis redis-cli -a ${TRADE_REDIS_PASSWORD} set seckill:stock:100 100
# seckill:order:100 不必预建，sismember 对不存在的 key 返回 0
```

## 四个已踩过的坑（照抄错误信息可搜索本文）

| 现象 | 根因 | 处理 |
| --- | --- | --- |
| 导库报 `ERROR 1067 Invalid default value for 'begin_time'` | SQL 用了 `0000-00-00` 零日期，MySQL 9.5 严格模式拒绝 | 导入时 `--init-command="SET SESSION sql_mode='NO_ENGINE_SUBSTITUTION'"` |
| 启动报 `Unable to connect ... /127.0.0.1:6379: WRONGPASS` | `RedissonConfig` 硬编码 `${TRADE_MQ_PASSWORD}`，与 yaml 是两套凭据 | 改 `RedissonConfig` 源码 |
| 连库报认证失败 / `Public Key Retrieval is not allowed` | `mysql-connector 5.1.47` 不支持 MySQL 9.5 的 `caching_sha2_password` | 驱动升 `8.0.33`，driver 类名改 `com.mysql.cj.jdbc.Driver` |
| `Web server failed to start. Port 49695 was already in use` | 后台托管启动时端口被外部注入，配置文件里的 8081 未生效 | 启动命令显式 `--server.port=8081` |

## 未完成（基线待补）

- [ ] 登录拿 token（`POST /user/code` + `POST /user/login`）
- [ ] 手工预热 `seckill:stock:{voucherId}` / `seckill:order:{voucherId}`（本 fork **没有预热入口**）
- [ ] 秒杀一次并确认 `MQReceiver` 落库
