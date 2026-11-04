# J1 秒杀可靠性主链 · task

> 每个 T 一个 commit，提交信息带 T 编号（如 `J1-T3: ...`）。最后一步是全量自动化验证。
> 名称、键、配置项以 `plan.md` 为准。

## 文件清单

| 操作 | 文件 | 职责 |
| --- | --- | --- |
| 新增 | `src/main/resources/db/migration/J1-001-reliability.sql` | 订单唯一索引 + outbox 表（含重复数据检查 SQL） |
| 修改 | `src/main/resources/db/schema.sql` | 同步迁移内容 |
| 修改 | `src/main/resources/seckill.lua` | 资格判断：zset 预约 + 缺库存 key 返回 3 |
| 新增 | `src/main/resources/seckill_unreserve.lua` | 回滚预约（幂等） |
| 新增 | `src/main/resources/seckill_snapshot.lua` | 原子读 (库存, 预约数) |
| 新增 | `scripts/j1/test-lua.sh` | 用真实 Redis 验证三个 Lua |
| 新增 | `entity/SeckillOutbox.java`、`mapper/SeckillOutboxMapper.java`、`mapper/SeckillReconcileMapper.java` | outbox 与对账 SQL |
| 修改 | `mapper/SeckillVoucherMapper.java` | 加 `decrementStock`（条件扣减） |
| 新增 | `config/SeckillMqConfig.java`、修改 `config/RabbitMQTopicConfig.java` | 死信拓扑、confirm/return 回调、重试容器 |
| 修改 | `application.yaml`、`config/application.yaml.example`、`SpikeTradeApplication.java` | 配置项、`@EnableScheduling` |
| 新增 | `seckill/*`（见 plan §4） | 业务类 |
| 修改 | `service/impl/VoucherOrderServiceImpl.java` | 新下单流程 + #4 |
| 删除 | `rebbitmq/MQSender.java`、`rebbitmq/MQReceiver.java` | 被取代 |
| 新增 | `src/test/java/com/spiketrade/seckill/*Test.java` | 单测 |
| 新增 | `scripts/j1/e2e-*.sh`、`scripts/j1/lib.sh` | 场景脚本（重复投递、broker 宕机、孤儿预约、死信） |
| 新增 | `evidence/J1/*`、`docs/dev-log/J1.md`、`docs/interview/J1.md` | 取证与文档 |

## 任务

### T1 数据库迁移
- 写迁移脚本与 `schema.sql`；脚本头部含检查重复 `(user_id, voucher_id)` 的 SQL。
- 完成标准：在空库与现有库上各执行一次成功。

### T2 Lua 三脚本 + 真实 Redis 测试
- 改 `seckill.lua`，新增 `seckill_unreserve.lua`、`seckill_snapshot.lua`。
- `scripts/j1/test-lua.sh` 覆盖：成功、库存不足、重复、缺库存 key、unreserve 幂等、snapshot 一致、并发 200 次只放行库存数。
- 完成标准：脚本退出码 0。

### T3 实体与 Mapper
- `SeckillOutbox`、`SeckillOutboxMapper`（claim / markSent / markFailed / 查询）、`SeckillReconcileMapper`（订单、outbox、`(stock, 订单数)` 一条 SQL）、`decrementStock`。

### T4 消息拓扑与配置
- 死信交换机/队列；`RabbitTemplate` 开启 confirm + mandatory + return 回调；监听容器工厂挂重试拦截器与 `RepublishMessageRecoverer`；yaml 配置；`@EnableScheduling`。
- 不改 `seckillQueue` 声明参数。

### T5 下单入口与 OutboxService
- `OutboxService.insertNew`；`seckillVoucher` 新流程：判空登录、Lua、写 outbox、异常 unreserve、返回订单号。

### T6 OutboxRelay
- 领取、发布、confirm 回调、退避、lease。

### T7 消费端
- `SeckillOrderHandler`（事务内先插订单后扣库存）、`SeckillConsumer`、`NonRetryableSeckillException`；删除 `rebbitmq` 包。

### T8 对账
- `SeckillReconciler`（A–E）、`ReconcileReport`、`ReconcileController`（默认关）、定时触发。

### T9 单元测试
- Handler：正常、重复投递、同用户第二单、库存不足回滚；Relay：ack/nack/return/异常；Reconciler：A–E 各一例；失败分类。

### T10 场景脚本
- `e2e-duplicate.sh`（同一消息重复发布）、`e2e-broker-down.sh`（停 `$MQ_CONTAINER` 期间下单，恢复后落库）、`e2e-orphan.sh`（手工制造孤儿预约，对账回滚）、`e2e-deadletter.sh`（不可重试消息入死信）、`e2e-reconcile.sh`（五类各制造一次）、`e2e-rollback.sh`（outbox 写入失败回滚预约）、`e2e-concurrent.sh`（N=3、M=5 并发）。另增 `lint-repo.sh`。
- 脚本的容器名、口令等一律读环境变量，不写进仓库。

### T11 全量自动化验证与文档
- `mvn test`、`mvn package -DskipTests`、`scripts/j1/test-lua.sh`、全部 e2e 脚本；原始输出入 `evidence/J1/`。
- 写 `docs/dev-log/J1.md`、`docs/interview/J1.md`，README 改造日志加一行，勾选 checklist。
