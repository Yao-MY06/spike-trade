package com.spiketrade.utils;


import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.spiketrade.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.spiketrade.utils.RedisConstants.*;

@Slf4j
@Component
public class CacheClient {

    private  final StringRedisTemplate stringRedisTemplate;
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    // 在Spring中，当一个Bean的构造函数需要参数的时候，pring 会自动从容器中查找 类型匹配的 Bean（这里是 StringRedisTemplate 类型的 Bean），并将其作为参数传入构造函数，完成成员变量的初始化。
    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void set(String key, Object value, Long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit){
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    public <R,ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit){
        String key  = keyPrefix + id;
        // 1.从redis中查询商户缓存
        String json = stringRedisTemplate.opsForValue().get(key);

        // 2.判断是否存在
        if(StrUtil.isNotBlank(json)){
            // 3.存在，直接返回
            return JSONUtil.toBean(json, type);
        }

        // 判断命中的是否是空值，防止缓存穿透
        if(json != null){
            return null;
        }

        // 4.不存在，根据id查询数据库
        R r = dbFallback.apply(id);


        // 5.不存在，返回错误
        if(r == null){
            // 将空值写入redis
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);

            return null;
        }
        // 6.写入redis
        this.set(key, r, time, unit);
        // 7.返回
        return r;
    }



    public <R, ID> R queryWithLogicalExpire(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit, String lock){
        String key = keyPrefix + id;
        // 1.从redis中查询商户缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在
        if(StrUtil.isBlank(json)){
            // 3.不存在，直接返回
            return null;
        }
        // 4.命中，需要先把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        R r = JSONUtil.toBean(data, type);
        LocalDateTime expireTime = redisData.getExpireTime();
        // 5.判断是否过期
        if(expireTime.isAfter(LocalDateTime.now())){
            // 5.1未过期，直接返回店铺信息
            log.info("商品信息未过期");
            return r;
        }

        // 5.2已过期，需要缓存重建
        log.info("商品信息已过期，需要缓存重建");
        // 6.缓存重建
        // 6.1获取互斥锁
        String lockKey = lock + id;
        boolean isLock = tryLock(lockKey);
        // 6.2判断是否获取锁成功
        if(isLock){
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    // 重建缓存
                    R r1 = dbFallback.apply(id);
                    this.setWithLogicalExpire(key, r1, time, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    // 释放锁
                    unlock(lockKey);
                }
            });

        }
        // 6.4返回过期信息
        return r;
    }

    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }
}
