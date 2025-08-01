package com.spiketrade.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.alibaba.fastjson.JSON;
import com.spiketrade.dto.Result;
import com.spiketrade.entity.VoucherOrder;
import com.spiketrade.mapper.VoucherOrderMapper;
import com.spiketrade.rebbitmq.MQSender;
import com.spiketrade.service.ISeckillVoucherService;
import com.spiketrade.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.spiketrade.utils.RedisIdWorker;
import com.spiketrade.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import com.google.common.util.concurrent.RateLimiter;

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
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private MQSender mqSender;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private RateLimiter rateLimiter=RateLimiter.create(10);

    //lua脚本
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }


    @Override
    public Result seckillVoucher(Long voucherId) {
        //令牌桶算法 限流
//        if (!rateLimiter.tryAcquire(1000, TimeUnit.MILLISECONDS)){
//            return Result.fail("目前网络正忙，请重试");
//        }
        //1.执行lua脚本
        Long userId = UserHolder.getUser().getId();

        if(userId==null){
            return Result.fail("需要登录");
        }

        Long r = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString()
        );
        //2.判断结果为0
        int result = r.intValue();
        if (result != 0) {
            //2.1不为0代表没有购买资格
            return Result.fail(r == 1 ? "库存不足" : "该用户重复下单");
        }
        //2.2为0代表有购买资格,将下单信息保存到阻塞队列

        //2.3创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        //2.4订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        //2.5用户id
        voucherOrder.setUserId(userId);
        //2.6代金卷id
        voucherOrder.setVoucherId(voucherId);

        //2.7将信息放入MQ中
        mqSender.sendSeckillMessage(JSON.toJSONString(voucherOrder));


        //2.7 返回订单id
        return Result.ok(orderId);
    }

}
