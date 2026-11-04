# 健康检查证据

采集时间：2026-10-20 17:27:57（应用 PID 47084，`--server.port=8081`）

```
GET http://localhost:8081/shop-type/list

HTTP/1.1 200
{"success":true,"data":[{"id":1,"name":"美食","icon":"/types/ms.png","sort":1},
 {"id":2,"name":"KTV","icon":"/types/KTV.png","sort":2},
 {"id":3,"name":"丽人·美发","icon":"/types/lrmf.png","sort":3},
 {"id":10,"name":"美睫·美甲","icon":"/types/mjmj.png","sort":4},
 {"id":5,"name":"按摩·足疗", ...}]}
```

## 这条证据证明了什么

| 证明点 | 依据 |
| --- | --- |
| MySQL 9.5 + connector 8.0.33 打通 | 应用日志 `HikariPool-1 - Start completed.` + 本次请求触发 `SELECT ... FROM tb_shop_type`，返回 10 条（与 `spike_trade` 导入的基线数据一致） |
| 库是 `spike_trade` 不是阶段四的 `spike` | `spike` 库中 `tb_shop_type` 曾按阶段四需求改动过；本次返回 10 条为标准基线数据，且启动配置指向 `spike_trade` |
| Spring 上下文完整（含 Redisson、RabbitMQ） | 启动日志 `24 connections initialized for /127.0.0.1:6379` + `amqp://${TRADE_MQ_USERNAME}@127.0.0.1:5672/` |
| 端口 8081 由本应用占用 | `Tomcat started on port(s): 8081` + `netstat` 显示 PID 47084 监听 8081 |

## 尚未证明（不要写成已完成）

- ~~登录链路~~ → 已由 `04-login.md` 覆盖（2026-10-20 18:19）
- ~~秒杀链路（含 MQ 消费落库）~~ → 已由 `05-seckill.md` 覆盖（2026-10-20 18:19）
- 缓存 miss/hit 路径 —— **仍未验证**
