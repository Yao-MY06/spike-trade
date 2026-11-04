package com.spiketrade;

import com.spiketrade.entity.Shop;
import com.spiketrade.service.impl.ShopServiceImpl;
import com.spiketrade.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.data.geo.Circle;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.Metrics;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import static com.spiketrade.utils.RedisConstants.SHOP_GEO_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;

@ActiveProfiles("test")
@SpringBootTest
class SpikeTradeApplicationTests {

    @Resource
    private ShopServiceImpl shopService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private ExecutorService es = Executors.newFixedThreadPool(50);

    @Test
    void testIdWorker() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(300);
        Set<Long> ids = ConcurrentHashMap.newKeySet();
        Runnable task = () -> {
            for (int i = 0; i < 100; i++) {
                ids.add(redisIdWorker.nextId("order"));
            }
            latch.countDown();
        };
        long begin = System.currentTimeMillis();
        for (int i = 0; i < 300; i++) {
            es.submit(task);
        }
        latch.await();

        long end = System.currentTimeMillis();
        assertEquals(30000, ids.size());
        System.out.println("generated_unique_ids=" + ids.size() + ", elapsed_ms=" + (end-begin));
    }

    @Test
    void testSaveShop() throws InterruptedException {
        shopService.saveShop2Redis(1L, 10L);
    }

    @Test
    void loadShopData(){

        // 1.查询店铺信息
        List<Shop> list = shopService.list();
        // 2.将店铺分组，按照typeId进行分组，typeId一致的放到一个集合
        Map<Long, List<Shop>> map = list.stream().collect(Collectors.groupingBy(Shop::getTypeId));
        // 3.分批完成写入Redis
        for (Map.Entry<Long, List<Shop>> entry : map.entrySet()) {
            // 3.1.获取类型id
            Long typeId = entry.getKey();
            String key = SHOP_GEO_KEY + typeId;
            // 3.2.获取同类型的店铺的集合
            List<Shop> value = entry.getValue();
            List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>(value.size());

            // 3.3.写入redis GEOADD key 经度 纬度 member
            for (Shop shop : value) {
//                stringRedisTemplate.opsForGeo().add(key, new Point(shop.getX(), shop.getY()),shop.getId().toString());
                locations.add(
                        new RedisGeoCommands.GeoLocation<>(
                                shop.getId().toString(),new Point(shop.getX(), shop.getY())
                        )
                );
            }

            stringRedisTemplate.opsForGeo().add(key, locations);

        }
    }
    @Test
    void testRedis() {
        Circle circle = new Circle(new Point(120.0, 30.0), new Distance(5, Metrics.KILOMETERS));
        RedisGeoCommands.GeoRadiusCommandArgs args1 = RedisGeoCommands.GeoRadiusCommandArgs.newGeoRadiusArgs().includeDistance().sortAscending().limit(10);
        System.out.println(circle);
        System.out.println(args1);
    }

}
