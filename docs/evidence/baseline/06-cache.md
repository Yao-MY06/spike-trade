# 商户缓存证据（未命中 / 命中未过期 / 命中已过期重建）

采集时间：2026-10-20 18:25–18:26（应用 PID 47084，`voucher_id` 与业务无关，此处用 `shop_id=1`）

入口：`GET /shop/1`（**需要登录**，MvcConfig 里 `/shop/**` 的放行被注释掉了，无 token 直接 401）
缓存实现：`ShopServiceImpl.queryBiId()` → `cacheClient.queryWithLogicalExpire(CACHE_SHOP_KEY, id, ..., 20L, TimeUnit.SECONDS, LOCK_SHOP_KEY)`
Key：`cache:shop:{id}`（`RedisConstants.CACHE_SHOP_KEY = "cache:shop:"`）

## A. 未命中（key 不存在）

```
DEL cache:shop:1                     → 1
GET /shop/1 (带 token)               → 200
{"success":false,"errorMsg":"店铺不存在!"}
```

**这是缺陷，不是预期行为。** `queryWithLogicalExpire()` 第一步：

```java
String json = stringRedisTemplate.opsForValue().get(key);
if (StrUtil.isBlank(json)) {
    return null;          // ← 未命中直接返回 null，不查库、不写回
}
```

逻辑过期方案默认「缓存一定存在」，所以**没有查库兜底、没有空值缓存**。后果：

- 冷启动 / Redis 被清 / key 过期 → 该商户详情**全部返回「店铺不存在」**；
- 本 fork **没有任何缓存预热入口**（不像课程原版有测试类灌数据），所以必须手工把 `cache:shop:*` 写进 Redis 才有的看；
- 这属于已知问题 #11，是「缓存击穿」那道题的现成反面教材。

## B. 命中且未过期

手工写入一个 `expireTime = now + 1h` 的 RedisData（字段取自 `tb_shop` id=1）：

```
SET cache:shop:1 <json>              → OK
GET /shop/1                          → 200，13.6ms
{"success":true,"data":{"id":1,"name":"103茶餐厅","typeId":1,...,"x":120.149192,"y":30.316078}}
```

无 SQL 日志、无锁日志 → 纯缓存命中，**未触库**。

## C. 命中但已过期 → 异步重建

把同一份数据改成 `expireTime = now - 1h` 再写入：

```
SET cache:shop:1 <expired json>      → OK
GET /shop/1                          → 200，24.6ms（返回的是旧值，不阻塞）
sleep 2
GET cache:shop:1                     → 值被整体重写
```

重建后的值（尾部）：

```json
...,"typeId":1,"id":1},"expireTime":1792491981715}
```

`1792491981715` = **2026-10-20 18:26:21**，而触发重建的时刻是 18:26:01 —— **正好 +20 秒**，与代码里传的 `20L, TimeUnit.SECONDS` 完全对上。

同时可以确认重建确实查了库：重写后的 JSON 是 Hutool 序列化格式（key 按字母序、`expireTime` 是 epoch 毫秒），与我手写的那份（`"data": {...}`、`expireTime` 是日期字符串）**格式不同**，说明是 `dbFallback.apply(id)` 取到实体后由 `setWithLogicalExpire()` 重新写入的。

## 结论对照 2.2 表「商户缓存」这一格

| 要求 | 结果 |
| --- | --- |
| cache miss 与 cache hit 各一次 | 有（A / B 两次调用 + Redis 前后状态） |
| 缓存更新或失效路径 | 有（C：过期 → 加锁 → 线程池异步重建 → 重写 key，过期时间精确 +20s） |
| 穿透 / 击穿 / 雪崩的边界 | **部分**：穿透在本实现**未做**（未命中直接 null，没有空值缓存也没有查库）；击穿用逻辑过期+互斥锁规避；雪崩未处理（`setWithLogicalExpire` **不设 Redis TTL**，key 永不过期，靠业务 `expireTime` 判断） |

## 顺带查到的两件事

1. **「二级缓存」名不副实**：`CaffeineConfig` 只注册了一个 `Cache` Bean，**Shop 路径（`CacheClient`）完全没引用它**；真正用 Caffeine 的只有 `VoucherServiceImpl`（`cache.get(key, ...)` 查优惠券详情）。README 里「Caffeine + Redis 搭建二级缓存架构」这句话只对优惠券成立，对商户详情不成立 → 已知问题 #12。
2. **Redis 实例与阶段四共用**：同一个 `redis` 容器里躺着阶段四留下的 `shop:geo:1` / `shop:geo:2`。两个项目共享 key 空间，一旦 key 命名撞车会互相污染 → 需要记一笔环境风险（改造时按前缀隔离）。
