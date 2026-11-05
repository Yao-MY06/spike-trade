# J1 秒杀可靠性主链 · checklist

> 每条 = 行为 +（验证：命令 → 期望）。证据原始输出放 `evidence/J1/`；跑不了的写原因并标 `[未验证]`。
> 环境变量：`MQ_CONTAINER`（RabbitMQ 容器名）、`BASE_URL`、`TOKEN`（登录后得到），见 `scripts/j1/lib.sh`。

## 一、实现完整性
- [x] Lua 判定 0/1/2/3 全覆盖，含缺库存 key 返回 3（验证：`bash scripts/j1/test-lua.sh` → 全部 PASS，退出码 0）
- [x] 预约带时间戳写入 zset（验证：同脚本中 `ZSCORE` 非空）
- [x] 回滚预约幂等（验证：同脚本中连续两次 unreserve，库存只加一次）
- [x] 订单表有 `(user_id, voucher_id)` 唯一索引（验证：`SHOW INDEX FROM tb_voucher_order` → 含 `uk_user_voucher`）
- [x] outbox 表存在且有唯一键（验证：`SHOW CREATE TABLE tb_seckill_outbox` → 含 `uk_outbox_user_voucher`）
- [x] 迁移在空库与现有库都能执行，且现有订单保留、重复单被拒（验证：`bash scripts/j1/test-migration.sh` → PASS）
- [x] 旧 `rebbitmq` 包已删除（验证：`ls src/main/java/com/spiketrade/rebbitmq` → 不存在）
- [x] 未登录请求不再空指针（验证：不带 token `POST /voucher-order/seckill/1` → HTTP 401；另用单测验证 Service 在 `UserHolder` 为空时判空返回）
- [x] 仓库无禁用词与口令（验证：`bash scripts/j1/lint-repo.sh` → 无输出）

## 二、集成
- [x] 应用启动并声明 DLX/DLQ，主队列参数不变（验证：启动日志无 `PRECONDITION_FAILED`；`rabbitmqctl list_queues name arguments` → `seckillQueue` 无新增参数）
- [x] 下单走 outbox → relay → 队列 → 落库（验证：下单后 `SELECT status FROM tb_seckill_outbox` 变 1，`tb_voucher_order` 有同 id 行）
- [x] 订单号贯穿（验证：接口返回值 = outbox.id = 订单 id）

## 三、编译与测试
- [x] `mvn clean test` 通过（验证：`bash scripts/j1/run-mvn.sh test` 输出 `Tests run: 34, Failures: 0, Errors: 0`）
- [x] `mvn package -DskipTests` 成功（验证：`bash scripts/j1/run-mvn.sh package` → `BUILD SUCCESS`）

## 四、端到端场景
- [x] **重复投递**：同一消息发布 5 次，订单只 1 行、库存只扣 1（验证：`bash scripts/j1/e2e-duplicate.sh` → PASS，输出订单数=1）
- [x] **broker 宕机**：停 `$MQ_CONTAINER` 期间下单 3 笔，接口均返回订单号；恢复后 ≤ 120 s 全部落库（验证：`bash scripts/j1/e2e-broker-down.sh` → PASS，输出恢复耗时）
- [x] **不可路由**：解绑队列期间下单，broker 退回消息，outbox 保持 NEW；恢复绑定后自动补发并落库（验证：`bash scripts/j1/e2e-unroutable.sh` → PASS）
- [x] **孤儿预约**：只做 Lua 不写 outbox，对账后预约被回滚、库存恢复（验证：`bash scripts/j1/e2e-orphan.sh` → PASS）
- [x] **死信**：库存已空的消息、同用户第二单的消息进入 `seckillDlq`，消息头含异常信息，主队列无积压（验证：`bash scripts/j1/e2e-deadletter.sh` → PASS，DLQ 计数 = 注入数）
- [x] **不可重试不重试**：真实监听器上两条不可重试消息均首次执行即进死信（验证：`e2e-deadletter.sh` 输出 `listener_finished_on_first_attempt=2 listener_retried=0`）
- [ ] [未验证] **可重试错误重试耗尽**：真实数据库故障下重试 3 次后进死信。只有单测（`SeckillRetryPolicyTest`）；原因：`e2e` 未制造消费期间的数据库故障，停 MySQL 还会同时影响 relay 与对账，需要单独设计场景
- [x] **定时对账可被发现**：定时任务把每条不一致写入 WARN 日志（验证：`evidence/J1/scheduled-reconcile.log`）
- [x] **对账五类**：A–E 各制造一次均被识别，A、C 被修复；E 类制造期间跳过恒等式断言，修复后再断言（验证：`bash scripts/j1/e2e-reconcile.sh` → PASS，输出 `ReconcileReport`）
- [x] **库存恒等式**：任一时刻 `R+Z = D+O`（验证：每个非 E 类场景结束时脚本断言；E 类故意制造不等时不适用恒等式断言）
- [x] **并发不超卖**：N=3、M=5（多个 token）抢购，订单数 = 3。上限来自现有全局限流（每 100 秒 5 次），更大并发留给 J4（验证：`bash scripts/j1/e2e-concurrent.sh` → PASS；脚本先清 `limit/seckill*`，见 plan §8）
- [x] **写入失败回滚**：预插相同 `(user_id, voucher_id)` 的 outbox 行再下单 → 返回失败、预约回滚、库存恢复（验证：`bash scripts/j1/e2e-rollback.sh` → PASS）

## 五、证据与文档
- [x] 原始输出已保存（验证：`ls evidence/J1/` → 每个脚本一份 `.log`）
- [x] 未跑项均标 `[未验证]` 并写明原因（验证：`grep -rn "未验证" evidence/J1 docs/dev-log/J1.md`）
- [x] `docs/dev-log/J1.md`、`docs/interview/J1.md`（5 题）已写
- [x] 提交信息均带 T 编号（验证：`git log --oneline | grep -c "J1-T"` ≥ 11）
- [x] 数字均注明环境与来源，未测写「未测」
