package com.spiketrade.seckill;

import com.spiketrade.entity.SeckillOutbox;
import com.spiketrade.mapper.SeckillOutboxMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OutboxService {

    private final SeckillOutboxMapper outboxMapper;

    public OutboxService(SeckillOutboxMapper outboxMapper) {
        this.outboxMapper = outboxMapper;
    }

    @Transactional(timeout = 5)
    public void insertNew(Long id, Long userId, Long voucherId, String payload) {
        SeckillOutbox outbox = new SeckillOutbox()
                .setId(id)
                .setUserId(userId)
                .setVoucherId(voucherId)
                .setPayload(payload)
                .setStatus(SeckillOutbox.STATUS_NEW)
                .setAttempts(0)
                .setResendCount(0);
        outboxMapper.insert(outbox);
    }
}