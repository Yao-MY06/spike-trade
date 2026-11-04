package com.spiketrade.service.impl;

import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.spiketrade.dto.Result;
import com.spiketrade.entity.VoucherOrder;
import com.spiketrade.mapper.VoucherOrderMapper;
import com.spiketrade.seckill.OutboxService;
import com.spiketrade.seckill.SeckillMessage;
import com.spiketrade.seckill.SeckillScripts;
import com.spiketrade.service.IVoucherOrderService;
import com.spiketrade.utils.RedisIdWorker;
import com.spiketrade.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 秒杀下单入口：Redis 预约成功后落本地消息表，订单由消费者异步落库。
 */
@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder>
        implements IVoucherOrderService {

    private final RedisIdWorker redisIdWorker;
    private final SeckillScripts seckillScripts;
    private final OutboxService outboxService;

    public VoucherOrderServiceImpl(RedisIdWorker redisIdWorker,
                                   SeckillScripts seckillScripts,
                                   OutboxService outboxService) {
        this.redisIdWorker = redisIdWorker;
        this.seckillScripts = seckillScripts;
        this.outboxService = outboxService;
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        if (UserHolder.getUser() == null || UserHolder.getUser().getId() == null) {
            return Result.fail("需要登录");
        }
        Long userId = UserHolder.getUser().getId();

        Long result = seckillScripts.tryReserve(
                voucherId,
                userId,
                System.currentTimeMillis() / 1000L);
        if (result == null) {
            return Result.fail("系统繁忙，请稍后重试");
        }
        if (result == 1L) {
            return Result.fail("库存不足");
        }
        if (result == 2L) {
            return Result.fail("该用户重复下单");
        }
        if (result == 3L) {
            return Result.fail("秒杀券尚未预热");
        }
        if (result != 0L) {
            return Result.fail("系统繁忙，请稍后重试");
        }

        Long orderId = null;
        try {
            orderId = redisIdWorker.nextId("order");
            SeckillMessage message = new SeckillMessage(orderId, userId, voucherId);
            outboxService.insertNew(orderId, userId, voucherId, JSON.toJSONString(message));
            return Result.ok(orderId);
        } catch (Exception ex) {
            log.error("秒杀预约写入 outbox 失败，回滚预约，voucherId={}, userId={}, orderId={}",
                    voucherId, userId, orderId, ex);
            try {
                seckillScripts.unreserve(voucherId, userId);
            } catch (Exception rollbackError) {
                log.error("回滚 Redis 预约失败，等待对账处理，voucherId={}, userId={}, orderId={}",
                        voucherId, userId, orderId, rollbackError);
            }
            return Result.fail("系统繁忙，请稍后重试");
        }
    }
}