package com.spiketrade.seckill;

import com.spiketrade.entity.VoucherOrder;
import com.spiketrade.mapper.SeckillVoucherMapper;
import com.spiketrade.mapper.VoucherOrderMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class SeckillOrderHandler {

    private final VoucherOrderMapper voucherOrderMapper;
    private final SeckillVoucherMapper seckillVoucherMapper;

    public SeckillOrderHandler(VoucherOrderMapper voucherOrderMapper,
                               SeckillVoucherMapper seckillVoucherMapper) {
        this.voucherOrderMapper = voucherOrderMapper;
        this.seckillVoucherMapper = seckillVoucherMapper;
    }

    @Transactional
    public CreateOrderResult createOrder(SeckillMessage message) {
        VoucherOrder order = new VoucherOrder()
                .setId(message.getId())
                .setUserId(message.getUserId())
                .setVoucherId(message.getVoucherId());

        try {
            voucherOrderMapper.insert(order);
        } catch (DuplicateKeyException ex) {
            VoucherOrder existing = voucherOrderMapper.selectById(message.getId());
            if (existing != null) {
                return CreateOrderResult.DUPLICATE_DELIVERY;
            }
            throw new NonRetryableSeckillException("USER_ALREADY_HAS_ORDER", ex);
        }

        int updated = seckillVoucherMapper.decrementStock(message.getVoucherId());
        if (updated != 1) {
            throw new NonRetryableSeckillException("DB_STOCK_EXHAUSTED");
        }
        log.info("秒杀订单落库成功，orderId={}, userId={}, voucherId={}",
                message.getId(), message.getUserId(), message.getVoucherId());
        return CreateOrderResult.CREATED;
    }

    public enum CreateOrderResult {
        CREATED,
        DUPLICATE_DELIVERY
    }
}