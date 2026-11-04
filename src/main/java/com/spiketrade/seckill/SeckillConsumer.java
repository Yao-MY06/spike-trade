package com.spiketrade.seckill;

import com.alibaba.fastjson.JSON;
import com.spiketrade.config.RabbitMQTopicConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class SeckillConsumer {

    private final SeckillOrderHandler orderHandler;

    public SeckillConsumer(SeckillOrderHandler orderHandler) {
        this.orderHandler = orderHandler;
    }

    @RabbitListener(queues = RabbitMQTopicConfig.QUEUE, containerFactory = "rabbitListenerContainerFactory")
    public void onMessage(String payload, Message amqpMessage) {
        SeckillMessage message;
        try {
            message = JSON.parseObject(payload, SeckillMessage.class);
        } catch (Exception ex) {
            throw new NonRetryableSeckillException("INVALID_MESSAGE_JSON", ex);
        }
        if (message == null || message.getId() == null
                || message.getUserId() == null || message.getVoucherId() == null) {
            throw new NonRetryableSeckillException("INVALID_MESSAGE_FIELDS");
        }

        String headerId = amqpMessage.getMessageProperties().getMessageId();
        if (headerId != null && !headerId.equals(String.valueOf(message.getId()))) {
            throw new NonRetryableSeckillException("MESSAGE_ID_MISMATCH");
        }

        SeckillOrderHandler.CreateOrderResult result = orderHandler.createOrder(message);
        if (result == SeckillOrderHandler.CreateOrderResult.DUPLICATE_DELIVERY) {
            log.info("重复投递已幂等忽略，orderId={}", message.getId());
        }
    }
}