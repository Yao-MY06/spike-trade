# 基线证据（baseline evidence）

本目录存放**设计文档 2.1 节**要求的基线运行证据。命名约定：

```
baseline/
  01-startup.log            # 启动日志（端口、数据源、拦截器注册、Started ... in x s）
  02-login.http             # 登录请求与响应（token 截断显示）
  03-seckill.http           # 秒杀请求与响应（orderId）
  04-mq-consume.log         # MQSender 发送日志 + MQReceiver 接收日志
  05-db-check.md            # tb_voucher_order 查询结果 + 订单号与返回 id 对照
  06-redis-check.md         # seckill:stock / seckill:order 前后对照
```

规则：

- 每条证据必须能被第三人在同一环境复现；命令与输出一起留，不只手写结论。
- 失败与报错原样记录，不做美化；不能解释的写「未知」。
- 阶段四在 `cs001020/spike` 原版上取得的 10 项证据**不迁移**到本仓库——那是另一份代码。
