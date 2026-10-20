# spike 生产级改造 · 项目一（微服务高并发交易平台）

> 本仓库从外部 fork 起步，**改造方不是我**。上游原文保留在文末「上游 README 原文」，本文件上半部分是我自己的基线登记、已知问题与改造日志，随改造同步更新。
> 计划与验收标准见 `E:\50-个人系统\02-项目管理\微服务高并发交易平台实施与验收.md`（2.1 基线登记 / 3.1.1 底座选定 / 第四章三方向）。

## 基线登记

按实施与验收计划 2.1 节登记，开工时一次性写清，后续只追加不改写。

| 项目 | 事实 |
| --- | --- |
| 源代码 | 上游：`https://github.com/Madgeaaaaa/spike`；分支 `master`；commit `0bfd67148578229bc25b98fa592a2cc6b142e0f7`（2025-08-01T15:04:23Z，最后一次提交为 `Update README.md`）；本地路径 `F:\study\spike-base` |
| fork 来源 | 非 GitHub 显式 fork（`fork: false`，独立上传的改造版，0 star，9 个 commit 且全为 README 修改，历史已被压平）；业务与代码源自交易平台课程原仓 `cs001020/spike`，本次未逐文件比对，**同源关系记为“推定，未核实”** |
| 运行环境 | Windows（win32）；JDK 11.0.32.1 Temurin（`F:\path`，项目 `java.version=1.8`）；Maven 3.9.16（`E:\work\apache-maven-3.9.16`，默认跑在 JDK 25，构建前须 `export JAVA_HOME="F:\path"`）；MySQL 9.5（本机 3306，密码 `${TRADE_DB_PASSWORD}`）；Redis 6.2 容器 `redis`（Docker 29.4.3，6379，密码 `${TRADE_REDIS_PASSWORD}`）；RabbitMQ **未就绪**（本机无 5672 监听，需 Docker 起）；前端 nginx 1.18.0（`F:\study\spike-nginx`，80 端口当前未监听） |
| 启动方式 | 配置来源 `src/main/resources/application.yaml`（`server.port=8081`）；启动 `mvn spring-boot:run` 或运行 `SpikeTradeApplication`；**必改 3 处**：`spring.rabbitmq.host` 由 `192.168.3.236` 改本机、`spring.redis.password` 由 `${TRADE_MQ_PASSWORD}` 改 `${TRADE_REDIS_PASSWORD}`、`spring.datasource.password` 由 `1234` 改 `${TRADE_DB_PASSWORD}`；初始化：MySQL 导入 `src/main/resources/db/schema.sql`（建 `spike` 库），**Redis 无自动预热**，`seckill:stock:{voucherId}` 与 `seckill:order:{voucherId}` 须手工写入；健康检查：`GET /shop-type/list` 返回 `code:1` 即应用与 DB 通 |
| 课程差异 | 相对课程原版：① 消息中间件由 **Redis Stream 换成 RabbitMQ**（`RabbitMQTopicConfig` + `MQSender` + `MQReceiver`，`seckill.lua` 里 `xadd` 分支整段注释）；② 新增 `CaffeineConfig`（本地缓存，部分读路径）；③ 新增限流模块 `com.spiketrade.limiter`（注解 `@RateLimiter` + AOP + `limiter.lua`，支持全局/用户/IP 三维度）；④ 新增 `RedissonConfig` 与 `RedissonTest`（Redisson 3.13.6 已引入）；⑤ 登录改 JWT 无状态（`JwtProperties`，未实现令牌续期）；⑥ **订单模块为 TODO**（README 第 5 条自述，`Spring Task` 定时关单未实现）。未复核处一律未写成“已完成” |

## 已知问题（基线自带，改造依据）

| # | 问题 | 位置 | 影响 | 对应改造方向 |
| --- | --- | --- | --- | --- |
| 1 | **Lua 成功但 broker 未确认**：Lua 只扣 Redis 库存 + 记用户，MQ 发送在 Java 侧，发送失败则券永久损失且用户不可再抢 | `seckill.lua` + `VoucherOrderServiceImpl.seckillVoucher()` | 库存与订单永久不一致，无补偿无对账 | 方向二（可靠发布 + 对账） |
| 2 | **幂等无 DB 兜底**：`MQReceiver` 用 `count` 判重，`tb_voucher_order` 无 `(user_id, voucher_id)` 唯一索引 | `MQReceiver` / `schema.sql` | 并发重复消费可写出重复订单 | 方向二（幂等） |
| 3 | **失败消息被静默丢弃**：失败仅 `log.error` 后 `return`，配 `default-requeue-rejected: true`，消息直接 ACK 消失 | `MQReceiver.receiveSeckillMessage` | 无重试、无死信、无补偿 | 方向二（重试 + 死信） |
| 4 | **判空顺序错**：先 `UserHolder.getUser().getId()` 再判 `userId == null` | `VoucherOrderServiceImpl.seckillVoucher()` | 未登录请求先 NPE，判空无效 | 首笔 bugfix commit |
| 5 | **限流单机化**：Guava `RateLimiter` 为单机令牌桶且已被注释；真正限流走 `@RateLimiter` + Redis Lua | `VoucherOrderServiceImpl` / `limiter` 包 | 集群下 Guava 路径失效（当前未启用） | 方向一（拆分后重定限流边界） |
| 6 | **包名拼写错误** `rebbitmq` | `com.spiketrade.rebbitmq` | 无功能影响 | 保留不改，避免污染改造 diff |
| 7 | **依赖组合风险**：`spring-boot 2.3.12.RELEASE` + 手动覆盖 `spring-data-redis 2.7.2` + `lettuce 6.1.9`；`mysql-connector-java 5.1.47` 不支持 `caching_sha2_password`（MySQL 9.5 已移除 `mysql_native_password`） | `pom.xml` / `application.yaml` | 可能启动即报错或连不上库 | 版本对齐（若触发，记为第一笔 commit） |

## 改造日志

> 只记真实小步 commit；每条写「改了什么 / 为什么 / 证据在哪」。基线阶段为空是正常的。

| 日期 | commit | 类型 | 内容 | 证据 |
| --- | --- | --- | --- | --- |
| 2026-10-20 | （本文件，未提交） | 基线登记 | clone 基线、登记 2.1 节四项、完成 3.1.1 代码质量核查并落 7 条已知问题 | 本 README；`docs/evidence/baseline/` |

## 待办前置（按顺序，未完成不进入改造）

- [ ] 起 RabbitMQ（Docker，5672 + 15672），改 `application.yaml` 三处凭据
- [ ] 释放 8081：当前被一个 `java.exe`（PID 32992）占用，疑为上一阶段实跑残留，起 fork 前须确认并停掉
- [ ] MySQL 建 `spike` 库并导入 `db/schema.sql`；解决 `mysql-connector 5.1.47` 与 MySQL 9.5 的认证兼容
- [ ] 应用启动成功 → 登录拿 token → 秒杀一次并看到 MQ 消费落库（`docs/evidence/baseline/` 留响应与日志）
- [ ] 手工预热 `seckill:stock:{voucherId}` / `seckill:order:{voucherId}`，记录所用命令

---

## 上游 README 原文

# trade
交易平台
简化版大众交易
项目简介:本项目是一个类似于大众交易等打卡类APP的交易类项目。实现了登陆、探店交易、优惠券秒杀、每日签到、好友关注、粉丝关注博主后，主动推送博主的相关博客等多个模块。项目可以分为用户模块、缓存模块、秒杀模块、订单模块以及限流模块。

## 克隆完整项目
git clone https://github.com/Madgeaaaaa/spike.git
## 前端环境部署
nginx-1.18.0   启动nginx.exe
## 后端环境部署
在application.yaml文件中，Mysql、Redis、RabbitMQ相关的配置需要自行更改

代码使用的Redis为3.2，推荐使用6.2以上，只影响商铺GEO部分

RabbitMQ版本为3.8

技术栈：SpringBoot + MySQL + Redis + Lua + MyBatisPlus + RabbitMQ + Caffeine + Nginx等

## 后端部分功能做了优化
### 优化点1:缓存模块
使用Caffeine本地缓存和Redis缓存搭建二级缓存架构，提高热点数据访问速度，降低数据库压力。

### 优化点2:秒杀模块
基于lua保证判断库存是否充足、判断用户是否下单、扣库存、下单并保存用户事件的原子性，同时采用RabbitMQ异步处理高并发情况下的请求。

### 优化点3:限流模块
使用Redis + AOP + 注解实现限流，支持全局、IP、用户多维度，从而防止系统过载、刷券、爬虫。

### 优化点4:用户模块
实现了无状态 JWT 认证登录的方式，但是尚未实现用户登录后刷新令牌有效期

### TODO优化点5:订单模块
使用Spring Task定时任务实现未支付订单的到期自动关闭，使用乐观锁解决支付和关单的并发问题。
