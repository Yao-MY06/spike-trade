package com.spiketrade.seckill;

import com.spiketrade.utils.RedisConstants;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

@Component
public class SeckillScripts {

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    private static final DefaultRedisScript<Long> UNRESERVE_SCRIPT;
    private static final DefaultRedisScript<String> SNAPSHOT_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);

        UNRESERVE_SCRIPT = new DefaultRedisScript<>();
        UNRESERVE_SCRIPT.setLocation(new ClassPathResource("seckill_unreserve.lua"));
        UNRESERVE_SCRIPT.setResultType(Long.class);

        SNAPSHOT_SCRIPT = new DefaultRedisScript<>();
        SNAPSHOT_SCRIPT.setLocation(new ClassPathResource("seckill_snapshot.lua"));
        SNAPSHOT_SCRIPT.setResultType(String.class);
    }

    private final StringRedisTemplate redisTemplate;

    public SeckillScripts(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public Long tryReserve(Long voucherId, Long userId, long nowSeconds) {
        return redisTemplate.execute(
                SECKILL_SCRIPT,
                keys(voucherId),
                userId.toString(),
                Long.toString(nowSeconds));
    }

    public Long unreserve(Long voucherId, Long userId) {
        return redisTemplate.execute(UNRESERVE_SCRIPT, keys(voucherId), userId.toString());
    }

    public String snapshot(Long voucherId) {
        return redisTemplate.execute(SNAPSHOT_SCRIPT, keys(voucherId));
    }


    public List<Reservation> reservations(Long voucherId) {
        Set<ZSetOperations.TypedTuple<String>> tuples = redisTemplate.opsForZSet()
                .rangeWithScores(RedisConstants.SECKILL_RESV_KEY + voucherId, 0, -1);
        List<Reservation> reservations = new ArrayList<>();
        if (tuples == null) {
            return reservations;
        }
        for (ZSetOperations.TypedTuple<String> tuple : tuples) {
            if (tuple.getValue() != null && tuple.getScore() != null) {
                reservations.add(new Reservation(
                        Long.valueOf(tuple.getValue()),
                        tuple.getScore().longValue()));
            }
        }
        return reservations;
    }

    private List<String> keys(Long voucherId) {
        return Arrays.asList(
                RedisConstants.SECKILL_STOCK_KEY + voucherId,
                RedisConstants.SECKILL_RESV_KEY + voucherId);
    }
}