# 秒杀链路证据（Lua → RabbitMQ → 落库）

采集时间：2026-10-20 18:19（应用 PID 47084，券 `voucher_id=100`，用户 `id=1011`）

## 1. 一次成功秒杀

```
POST http://localhost:8081/voucher-order/seckill/100
authorization: <JWT>

→ {"success":true,"data":650795538001690630}      （05-seckill.json）
```

Redis 库存：`seckill:stock:100` 由 `100` → **`99`**
Redis 已购集合：`SMEMBERS seckill:order:100` → **`1011`**

## 2. 消息链路日志（两个类各一条，可对上号）

```
18:19:04.847  INFO  com.spiketrade.rebbitmq.MQSender   : 发送消息{"id":650795538001690630,"userId":1011,"voucherId":100}
18:19:04.994  INFO  com.spiketrade.rebbitmq.MQReceiver : 接收到消息: {"id":650795538001690630,"userId":1011,"voucherId":100}
```

从发送到消费 **147ms**，消息体与返回的订单号**完全一致**。

## 3. 消费端做了什么（日志逐行）

```
18:19:05.031  VoucherOrderMapper.selectCount : SELECT COUNT(*) FROM tb_voucher_order WHERE (user_id = ? AND voucher_id = ?)
18:19:05.033  Parameters: 1011(Long), 100(Long)          ← 一人一单判重
18:19:05.042  MQReceiver : 扣减库存
18:19:05.060  SeckillVoucherMapper.update : UPDATE tb_seckill_voucher SET stock = stock-1 WHERE (voucher_id = ? AND stock > ?)
18:19:05.063  Parameters: 100(Long), 0(Integer)          ← cas 乐观锁 stock>0
18:19:05.069  <== Updates: 1
18:19:05.072  VoucherOrderMapper.insert : INSERT INTO tb_voucher_order ( id, user_id, voucher_id ) VALUES ( ?, ?, ? )
18:19:05.073  Parameters: 650795538001690630(Long), 1011(Long), 100(Long)
18:19:05.074  <== Updates: 1
```

## 4. 数据库核对

```
select count(*) from tb_voucher_order;                              -- 1
select id,user_id,voucher_id,pay_type,status,create_time from tb_voucher_order;
-- 650795538001690630 | 1011 | 100 | 1 | 1 | 2026-10-20 18:19:05
select voucher_id,stock from tb_seckill_voucher where voucher_id=100; -- 100 | 99
```

| 核对项 | 结果 |
| --- | --- |
| 接口返回 orderId | `650795538001690630` |
| 消息里的 id | `650795538001690630` |
| 数据库主键 | `650795538001690630` |
| **三者是否一致** | **是**（阶段四原版曾出现「异步返回号与入库号不一致」的 bug，本 fork 无此问题） |
| Redis 库存 / DB 库存 | 99 / 99（一致） |
| 订单条数 | 1 |

## 5. 重复下单被拒（同一用户再秒一次）

```
POST /voucher-order/seckill/100（同 token）
→ {"success":false,"errorMsg":"该用户重复下单"}      （05-seckill-duplicate.json）
```

被拒后 Redis 库存**仍为 99**，未产生第二条订单——拦截点在 Lua 的 `SISMEMBER`，没有进 MQ。

## 6. 这条证据的边界（不要外推）

- 这是**单个用户、单次请求**的串行验证，**不构成并发下的正确性证明**。并发一人一单依赖 Lua 原子性 + DB 唯一索引兜底，而本 fork **没有唯一索引**（已知问题 #2），必须用 JMeter 并发压测才能验证超卖与重复单，届时大概率能打出重复订单。
- 日志中文乱码是 Windows 控制台编码（GBK）导致，非代码缺陷。
- `MQReceiver` 的失败分支（消息被静默 ACK 丢弃）本次**未被触发**，因此没有证据——要验证必须人为制造消费失败。
