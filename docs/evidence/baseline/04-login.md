# 登录链路证据

采集时间：2026-10-20 18:18–18:19（应用 PID 47084，库 `spike_trade`）

## 请求与响应

```
POST http://localhost:8081/user/code?phone=13900001111
→ {"success":true}                                   （04-sendcode.json）

Redis: login:code:13900001111 → 6 位随机码（TTL 按 LOGIN_CODE_TTL）

POST http://localhost:8081/user/login
Content-Type: application/json
{"phone":"13900001111","code":"<上一步的码>"}
→ {"success":true,"data":"eyJhbGciOiJIUzI1NiJ9.eyJ......"}（04-login.json，签名已截断）
```

## 关键日志

```
18:19:02.857  UserMapper.selectOne : SELECT id,phone,password,... FROM tb_user WHERE (phone = ?)
18:19:02.857  Parameters: 13900001111(String)
18:19:02.865  <== Total: 1
```

第二次登录命中已有用户（`Total: 1`），不再 insert；首次登录（17:31、18:18）走的是 `INSERT INTO tb_user (phone,nick_name,icon)`，自动注册 `user_15vusrkfsd` / `id=1011`。

## 这条证据证明了什么

| 证明点 | 依据 |
| --- | --- |
| 发码链路通 | `POST /user/code` 返回 `success:true`，Redis 出现 `login:code:13900001111` |
| 验证码校验用的是 Redis 不是 Session | 源码 `UserServiceImpl.login()` 读 `LOGIN_CODE_KEY + phone`；登录成功后该 key 被 `delete` |
| 登录成功签发 JWT | 返回 `data` 为三段式 JWT（header.payload.signature），长度 155 |
| 首次登录自动注册 | `UserMapper.insert` → `tb_user` 新增 `id=1011` |

## 与课程原版的差异（口径必须按本 fork 记）

- 原版：token 存 Redis `login:token:*`，Hash 存用户，拦截器每次读 Redis 并续期。
- **本 fork**：纯 JWT 无状态。`RefreshTokenInterceptor` 里读 `login:token:*` 的整段被注释，改为 `JwtUtil.parseJWT(adminSecretKey, token)` 后直接 `BeanUtil.fillBeanWithMap` 塞进 ThreadLocal。**Redis 里没有登录态，也没有续期**。
- 因此“登录态如何在 Redis 保存与续期”这条**在本 fork 不适用**，如实标记为「当前源版本未实现」。

## 一个隐患（已知问题 #9）

签发用 `jwtProperties.getUserSecretKey()`，校验用 `jwtProperties.getAdminSecretKey()`。当前 yaml 里两串相同（`${TRADE_JWT_SECRET}...`），所以能跑；若按语义拆成不同密钥，**所有请求立刻 401**。
