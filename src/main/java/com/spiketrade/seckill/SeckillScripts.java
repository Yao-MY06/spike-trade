package com.spiketrade.seckill;

import com.spiketrade.utils.RedisConstants;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

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

    private List<String> keys(Long voucherId) {
        return Arrays.asList(
                RedisConstants.SECKILL_STOCK_KEY + voucherId,
                RedisConstants.SECKILL_RESV_KEY + voucherId);
    }
}