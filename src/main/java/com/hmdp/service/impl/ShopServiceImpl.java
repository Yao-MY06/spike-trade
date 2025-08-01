package com.spiketrade.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.spiketrade.dto.Result;
import com.spiketrade.entity.Shop;
import com.spiketrade.mapper.ShopMapper;
import com.spiketrade.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.spiketrade.utils.CacheClient;
import com.spiketrade.utils.RedisData;
import com.spiketrade.utils.SystemConstants;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.logging.Log;
import org.springframework.data.geo.*;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.GeoOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.Metrics;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.spiketrade.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author spike
 * @since 2021-12-22
 */
@Service
@Slf4j
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;
    

    @Override
    public Result queryBiId(Long id) {
        // 缓存穿透
//        Shop shop = queryWithPassThrough(id);

//        // 互斥锁解决缓存击穿
//        Shop shop = queryWithMutex(id);
        // 逻辑过期解决缓存击穿
        Shop shop = cacheClient.queryWithLogicalExpire(CACHE_SHOP_KEY, id, Shop.class, this::getById, 20L, TimeUnit.SECONDS, LOCK_SHOP_KEY);
        if(shop==null){
            return Result.fail("店铺不存在!");
        }
        return Result.ok(shop);
    }

    public Shop queryWithMutex(Long id){
        // 1.从redis中查询商户缓存
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);

        // 2.判断是否存在
        if(StrUtil.isNotBlank(shopJson)){
            // 3.存在，直接返回
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }

        // 判断命中的是否是空值，防止缓存穿透
        if(shopJson != null){
            return null;
        }
        Shop shop = null;

        // 4.实现缓存重建
        // 4.1获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;

        try {
            boolean isLock = tryLock(lockKey);
            // 4.2判断是否获取成功
            if(!isLock){
                // 4.3失败，则休眠并重试从Reids获取商铺id
                Thread.sleep(50);
                return queryWithMutex(id);
            }

            // 4.4成功，根据id查询数据库
            shop = getById(id);
            // 模拟重建延时
            Thread.sleep(200);
            // 5.不存在，返回错误
            if(shop==null){
                // 将空值写入redis
                stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, "", CACHE_NULL_TTL, TimeUnit.MINUTES);

                return null;
            }
            // 6.存在，写入redis
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }finally {
            // 7.释放互斥锁
            unlock(lockKey);
        }
        // 8.返回
        return shop;
    }

//    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
//
//    public Shop queryWithLogicalExpire(Long id){
//        // 1.从redis中查询商户缓存
//        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
//
//        // 2.判断是否存在
//        if(StrUtil.isBlank(shopJson)){
//            // 3.不存在，直接返回
//            return null;
//        }
//
//        // 4.命中，需要先把json反序列化为对象
          // JSON 反序列化时，RedisData 中的 data 字段会被解析为 JSONObject（JSON 工具类的对象）
//        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
//        JSONObject data = (JSONObject) redisData.getData();
//        Shop shop = JSONUtil.toBean(data, Shop.class);
//        LocalDateTime expireTime = redisData.getExpireTime();
//        // 5.判断是否过期
//        if(expireTime.isAfter(LocalDateTime.now())){
//            // 5.1未过期，直接返回店铺信息
//            log.info("商品信息未过期");
//            return shop;
//        }
//
//        // 5.2已过期，需要缓存重建
//        log.info("商品信息已过期，需要缓存重建");
//        // 6.缓存重建
//        // 6.1获取互斥锁
//        String lockKey = LOCK_SHOP_KEY + id;
//        boolean isLock = tryLock(lockKey);
//        // 6.2判断是否获取锁成功
//        if(isLock){
//            CACHE_REBUILD_EXECUTOR.submit(()->{
//                try {
//                    // 重建缓存
//                    this.saveShop2Redis(id, 20L);
//                } catch (Exception e) {
//                    throw new RuntimeException(e);
//                } finally {
//                    // 释放锁
//                    unlock(lockKey);
//                }
//            });
//
//        }
//        // 6.4返回过期信息
//        return shop;
//    }


    public Shop queryWithPassThrough(Long id){
        return cacheClient
                .queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if(id == null){
            return Result.fail("店铺id不能为空");
        }

        // 1. 更新数据库
        updateById(shop);

        // 2.删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        return Result.ok();
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        // 1.判断是否需要根据坐标查询
        if(x==null||y==null){
            // 不需要坐标查询
            // 根据类型分页查询
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }
        // 2.计算分页参数
        int from = (current-1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;
        String key = SHOP_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo()
                .radius(key, new Circle(new Point(x, y), new Distance(5000)),
                        RedisGeoCommands.GeoRadiusCommandArgs.newGeoRadiusArgs()
                                .includeDistance()
                                .sortAscending()
                                .limit(end)
                );
        // 3.查询redis、按照距离排序、分页。结果：shopId、distance
        if(results == null){
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        // 如果list中的个数小于from,说明当前页没有了，直接返回
        if(list.size() <= from){
            return Result.ok(Collections.emptyList());
        }
        // 4.解析出shopId,根据id查询shop
        List<Long> ids = new ArrayList<>(list.size());
        Map<String, Distance> distanceMap = new HashMap<>(list.size());
        list.stream().skip(from).forEach(result ->{
            String shopIdStr = result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            Distance distance = result.getDistance();
            distanceMap.put(shopIdStr, distance);
        });
        String idsStr = StrUtil.join(",",ids);
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id," + idsStr + ")").list();

        for (Shop shop : shops) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        return Result.ok(shops);
    }

    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }

    public void saveShop2Redis(Long id, Long expireSeconds) throws InterruptedException {
        // 1.查询店铺数据
        Shop shop = getById(id);
        Thread.sleep(200);
        // 2.封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        // 3.写入Redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id, JSONUtil.toJsonStr(redisData));
    }
}
