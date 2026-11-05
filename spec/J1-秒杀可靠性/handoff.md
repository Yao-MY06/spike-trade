# J1 执行交接计划

> 给接手的执行方。需求、名称、键、配置项以 `plan.md` 为准；本文只补充「已确认的修订」「执行顺序」「读代码发现的坑」。
> 状态基准：分支 `claude/j1-seckill-reliability-244dc1`，HEAD `e979557`，尚无任何实现提交。本文所有判断来自读代码，**未编译、未实跑**。

## 0. 当前状态

| 项 | 状态 |
| --- | --- |
| 遗留 git 锁与 `tmp_obj_*` | 已清理（主仓库 `.git`），无需再做 |
| JDK | `JAVA_HOME=F:\path`（Temurin 11）；默认 `java` 是 25，不能用。代码按 Java 8 写，不用 `var`、`List.of` |
| Maven / MySQL | Maven 3.9.16；MySQL 9.5 的 3306 已开放 |
| Docker | 已发起启动 Docker Desktop，**就绪未确认**；Redis、RabbitMQ 容器要手动启动 |
| 凭据 | `config/application.yaml` 被 git 忽略，只在主 checkout 有；复制到工作目录，不提交、不打印 |
| `MQ_CONTAINER` 等环境变量 | 均未设置；容器名用 `docker ps -a` 查，只放进当前 shell |

## 1. 硬性约束

- 每个 T 一个 commit，信息以 `J1-Tn: ` 开头；作者 `Yao-MY06 <2315686880@qq.com>`；不要 push。
- 不改现有 `@RateLimiter`；不碰 `05-资源库` 和 `E:\50-个人系统`。
- 仓库文件不得出现原课程项目名及相关禁用词、本机容器名、口令。
- 发现设计错误先说明，再改文档，不要静默偏离。跑不了的标 `[未验证]` 并写原因；没测的数字写「未测」。
- Redis 与其他阶段共用实例：**禁止 FLUSH**，只删自己用到的 key。不要使用或重建 `spike` 库。

## 2. 第一步：修订文档（一个 commit，`J1-T0: 修订...`）

| 文件 | 修订 |
| --- | --- |
| spec §2 | 目标 1 加例外：数据库库存少于 Redis（W5）时进死信，不保证落库 |
| spec §5、plan §7 | J1 只支持新券，不迁移旧 `seckill:order:*`；已有订单的券上，老用户可重复预约，E、D 类也会误报 |
| plan §2.4 | 增加 `spring.task.scheduling.pool.size: 2`（避免 relay 卡住对账）；增加 `trade.seckill.relay.enabled=true`（供测试关闭 relay） |
| plan §5.2 | 发送遇连接类异常时结束当前批次，不再逐行等超时 |
| plan §5.1 | outbox 写入设超时，且明显小于宽限期 |
| plan §2.2、task | `src/main/resources/schema.sql` 改为 `src/main/resources/db/schema.sql` |
| ADR §4 | 修正残余风险：慢请求被 A 类误回滚后 INSERT 仍会成功，唯一索引只挡同一用户；别的用户抢走库存时，可能有人拿了订单号却进死信。INSERT 已提交但客户端报超时同理。E 类查不出，只有 D 类能报告 |
| task T10 | 增加 `e2e-concurrent.sh`、`e2e-rollback.sh`、`lint-repo.sh` |
| checklist | 未登录改为「HTTP 401 + 单测验证 Service 判空」；并发改为 N=3、M=5，注明上限来自全局限流（每 100 秒 5 次），大并发留给 J4；`mvn` 取证去掉 `-q`；E 类故意制造不等时不适用恒等式断言，修复后再断言；增加回滚场景 |

## 3. 实现顺序

| T | 内容 | 完成标准与要点 |
| --- | --- | --- |
| T1 | 迁移脚本 + `db/schema.sql` | 空库、现有库各跑一次。空库用临时库，导入需 `sql_mode=NO_ENGINE_SUBSTITUTION`（schema 有零日期默认值）。先跑重复数据检查 |
| T2 | 三个 Lua + `test-lua.sh` | 退出码 0。去掉 `seckill.lua` 头部的个人署名注释 |
| T3 | 实体与 Mapper | `decrementStock` 带 `stock > 0`；`(stock, 订单数)` 用一条 SQL |
| T4 | 拓扑、回调、容器工厂、yaml、`@EnableScheduling` | 不改 `seckillQueue` 声明；移除 `template.retry`；同步 `application.yaml.example` |
| T5 | `OutboxService` + 下单流程 | 判空先于取 ID；任何异常都 unreserve；清掉无用 import |
| T6 | `OutboxRelay` | 领取、发布、回调、退避、lease |
| T7 | Handler、Consumer、异常类；删 `rebbitmq` 包 | 消息格式不变，仍为 `{"id","userId","voucherId"}` |
| T8 | Reconciler、Report、Controller | HTTP 接口默认关；A、C 修复，其余只报告 |
| T9 | 单测 | plan 所列用例 + Service 未登录判空。纯 Mockito，不依赖中间件 |
| T10 | `lib.sh`、全部 e2e、`lint-repo.sh` | 见第 5、6 节 |
| T11 | 全量真跑、文档 | 见第 7 节 |

## 4. 需要留意的坑

**Spring AMQP（以实际编译为准，有出入记入 dev-log）**
- `RepublishMessageRecoverer` 若复用同一个模板，confirm 回调的 `correlationData` 会是 null，必须判空。
- 该版本的 recoverer 不等 broker 确认就 ack 原消息，死信可能丢。这种情况由对账 C 类兜底，写进 dev-log。
- 监听异常会被 `ListenerExecutionFailedException` 包裹，重试策略要开 `traverseCauses=true`。
- 非法 JSON（fastjson 异常）要转成 `NonRetryableSeckillException`。
- 判断「ack 且无 return」可用 `CorrelationData.getReturnedMessage()`。

**事务**
- `createOrder` 在事务内捕获 `DuplicateKeyException`，再按 id 查询以区分「重复投递」和「同用户另一订单」。
- Handler 必须是独立 Bean，否则 `@Transactional` 不生效。

**接口行为**
- `WebExceptionAdvice` 把 `RuntimeException` 转成 `Result.fail`，HTTP 仍是 200。脚本要断言响应体的 `success`，不能只看状态码。
- 未登录在 `LoginInterceptor` 返回 401，请求到不了 Service。
- 实际限流 key 是 `limit/seckill` 接类名和方法名，`limit/seckill*` 能匹配。

**运行**
- runbook 记录：后台启动要显式指定 `--server.port=8081`，否则端口会被注入随机值。
- 现有 `@SpringBootTest` 依赖全套中间件，且会写 Redis。`mvn test` 前先启动中间件；测试配置中关闭 relay、对账和监听（`spring.rabbitmq.listener.simple.auto-startup=false`），防止动业务数据。

## 5. 场景脚本要点

- `lib.sh` 只读环境变量：`MQ_CONTAINER`、`REDIS_CONTAINER`、`BASE_URL`、`TOKEN` 和口令。Redis 用 `REDISCLI_AUTH` 传口令，不放命令行。
- 每个场景：用**新券 ID**，清 `limit/seckill*`，结束时断言 `R+Z = D+O`（E 类制造期间除外）。

| 脚本 | 做法 | 断言 |
| --- | --- | --- |
| `e2e-duplicate.sh` | 同一消息发布 5 次 | 订单 1 行，库存只扣 1 |
| `e2e-broker-down.sh` | 停 `$MQ_CONTAINER`，下单 3 笔，再启动 | 接口都返回订单号；恢复后 ≤120 s 全部落库；输出实测耗时 |
| `e2e-orphan.sh` | 只执行 Lua，把 score 改到宽限期之前，触发对账 | 预约回滚，库存恢复 |
| `e2e-deadletter.sh` | 注入库存已空、同用户第二单的消息 | DLQ 计数 = 注入数，消息头含异常信息，主队列无积压 |
| `e2e-reconcile.sh` | A–E 各制造一次（需 `http-enabled=true`） | 全部识别，A、C 被修复 |
| `e2e-rollback.sh` | 预插相同 `(user_id, voucher_id)` 的 outbox 行再下单 | 返回失败，预约回滚，库存恢复 |
| `e2e-concurrent.sh` | N=3、M=5，5 个 token 并发 | 订单数 = 3 |

## 6. 去标识化

- `lint-repo.sh` 用 `git ls-files` 加未跟踪文件作为范围。禁用词不能明文写在脚本里，否则会命中自己，用 base64 存放；容器名和口令从环境变量读。
- `redis` 太通用，只匹配 `docker start "$REDIS_CONTAINER"`、`docker exec "$REDIS_CONTAINER"` 这类用法。
- 需清理的现有文件：`docs/runbook.md`、`docs/evidence/environment.md`、`docs/evidence/baseline/` 下的 `01-startup.log`、`02-healthcheck.md`、`03-prep-and-sendcode.md`。容器名换成 `$MQ_CONTAINER` 等占位符，MQ 用户名换成 `${TRADE_MQ_USERNAME}`。基线证据只做这种替换，并记入 dev-log。
- `@author` 里的署名与 MQ 用户名字面值相同，不属于本模块改动范围，不要让 lint 把它算作命中；在 dev-log 说明。
- 存入 `evidence/J1/` 前统一脱敏：Spring Boot 启动日志会打印含原项目名的绝对路径，还可能带容器名和连接用户名。在 `lib.sh` 放一个过滤函数，所有日志都经过它。

## 7. 收尾与汇报

1. 证据：`mvn test`、`mvn package -DskipTests`、`test-lua.sh`、全部 e2e，每个一份 `.log` 存入 `evidence/J1/`。三类重点证据：重复投递、broker 宕机（含恢复耗时）、孤儿预约对账。
2. 所有数字注明环境和来源。性能不下结论（留给 J4）。
3. 写 `docs/dev-log/J1.md`（坑、与 plan 的偏差）、`docs/interview/J1.md`（5 题：是什么、为什么选它、具体流程、出错怎么兜底、数据怎么测的）；README 改造日志加一行；勾选 checklist。
4. 汇报：每个 T 的 commit id、checklist 通过数/总数、`[未验证]` 清单及原因、与 plan 的偏差。
