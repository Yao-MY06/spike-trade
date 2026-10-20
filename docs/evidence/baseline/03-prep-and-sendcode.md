# 基线准备证据：造券 · 预热库存 · 发验证码

采集时间：2026-10-20 17:31–17:33（应用 PID 47084，`--server.port=8081`，库 `spike_trade`）

## 0. 前置发现：基线 SQL 里没有秒杀券

```
select count(*) from tb_seckill_voucher;  -- 0
select count(*) from tb_voucher;          -- 1（id=1，type=0 普通券）
```

课程原版 SQL 自带一张 `voucher_id=10` 的秒杀券，**这份 fork 的 `db/schema.sql` 把秒杀券数据删了**。所以秒杀链路不能直接跑，必须先造券——这是文档里没写、只有跑一次才知道的坑。

## 1. 造一张测试秒杀券（id=100）

```sql
insert into tb_voucher(id,shop_id,title,sub_title,rules,pay_value,actual_value,type,status,create_time,update_time)
values (100,1,'测试秒杀券','基线验证专用','仅用于基线链路验证',1,10,1,1,now(),now());
insert into tb_seckill_voucher(voucher_id,stock,create_time,begin_time,end_time,update_time)
values (100,100,now(),date_sub(now(),interval 1 hour),date_add(now(),interval 1 day),now());
```

插入后核对：

```
id   type status stock begin_time          end_time
100  1    1      100   2026-10-20 16:31:30 2026-10-21 17:31:30
```

## 2. 预热 Redis 库存

```
docker exec redis redis-cli -a ${TRADE_REDIS_PASSWORD} set seckill:stock:100 100   → OK
docker exec redis redis-cli -a ${TRADE_REDIS_PASSWORD} get seckill:stock:100       → 100
```

**`seckill:order:100` 不必预建**：`seckill.lua` 里 `sismember` 对不存在的 key 返回 0，不会报错。但 **`seckill:stock:100` 必须先建**——脚本第一行 `tonumber(redis.call('get', stockKey)) <= 0`，key 不存在时 `get` 返回 false，`tonumber(false)` 会直接让 Lua 报错。

## 3. 发验证码

```
POST http://localhost:8081/user/code?phone=13800138000
→ {"success":true}

docker exec redis redis-cli -a ${TRADE_REDIS_PASSWORD} get login:code:13800138000
→ 385115（6 位随机数，TTL 按 LOGIN_CODE_TTL 分钟）
```

## 尚未完成

- [ ] `POST /user/login` 换 token —— 本次执行被工具侧的敏感内容审批拦截（超时），未取得 token，**不写成已完成**
- [ ] `POST /voucher-order/seckill/100` 秒杀
- [ ] `MQReceiver` 消费日志 + `tb_voucher_order` 落库核对
