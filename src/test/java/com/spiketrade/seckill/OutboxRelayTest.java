package com.spiketrade.seckill;

import com.spiketrade.config.RabbitMQTopicConfig;
import com.spiketrade.entity.SeckillOutbox;
import com.spiketrade.mapper.SeckillOutboxMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class OutboxRelayTest {

    private final SeckillOutboxMapper mapper = mock(SeckillOutboxMapper.class);
    private final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
    private final OutboxRelay relay = new OutboxRelay(mapper, rabbitTemplate, 50, 50);

    @Test
    void ackWithoutReturnMarksSent() {
        doAnswer(invocation -> {
            CorrelationData data = invocation.getArgument(4);
            data.getFuture().set(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbitTemplate).convertAndSend(
                eq(RabbitMQTopicConfig.EXCHANGE),
                eq(RabbitMQTopicConfig.SECKILL_ROUTING_KEY),
                anyString(),
                any(MessagePostProcessor.class),
                any(CorrelationData.class));

        OutboxRelay.PublishOutcome outcome = relay.publishOne(outbox());

        assertEquals(OutboxRelay.PublishOutcome.CONTINUE, outcome);
        verify(mapper).markSent(100L);
        verify(mapper, never()).markFailed(anyLong(), any(LocalDateTime.class), anyString());
    }

    @Test
    void ackWithReturnMarksFailed() {
        doAnswer(invocation -> {
            CorrelationData data = invocation.getArgument(4);
            Message returned = new Message("".getBytes(StandardCharsets.UTF_8), new MessageProperties());
            returned.getMessageProperties().setHeader("x-reply-text", "NO_ROUTE");
            data.setReturnedMessage(returned);
            data.getFuture().set(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbitTemplate).convertAndSend(
                eq(RabbitMQTopicConfig.EXCHANGE),
                eq(RabbitMQTopicConfig.SECKILL_ROUTING_KEY),
                anyString(),
                any(MessagePostProcessor.class),
                any(CorrelationData.class));

        relay.publishOne(outbox());

        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(mapper).markFailed(eq(100L), any(LocalDateTime.class), reason.capture());
        assertTrue(reason.getValue().contains("RETURNED"));
    }

    @Test
    void nackMarksFailedWithBackoff() {
        doAnswer(invocation -> {
            CorrelationData data = invocation.getArgument(4);
            data.getFuture().set(new CorrelationData.Confirm(false, "broker rejected"));
            return null;
        }).when(rabbitTemplate).convertAndSend(
                eq(RabbitMQTopicConfig.EXCHANGE),
                eq(RabbitMQTopicConfig.SECKILL_ROUTING_KEY),
                anyString(),
                any(MessagePostProcessor.class),
                any(CorrelationData.class));

        relay.publishOne(outbox());

        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(mapper).markFailed(eq(100L), any(LocalDateTime.class), reason.capture());
        assertTrue(reason.getValue().contains("NACK"));
    }

    @Test
    void connectionFailureStopsBatch() {
        doThrow(new AmqpConnectException(new ConnectException("connection refused")))
                .when(rabbitTemplate).convertAndSend(
                        eq(RabbitMQTopicConfig.EXCHANGE),
                        eq(RabbitMQTopicConfig.SECKILL_ROUTING_KEY),
                        anyString(),
                        any(MessagePostProcessor.class),
                        any(CorrelationData.class));

        OutboxRelay.PublishOutcome outcome = relay.publishOne(outbox());

        assertEquals(OutboxRelay.PublishOutcome.STOP_BATCH, outcome);
        verify(mapper).markFailed(eq(100L), any(LocalDateTime.class), anyString());
    }

    @Test
    void publishedMessageCarriesPersistentIdHeader() {
        ArgumentCaptor<MessagePostProcessor> processor = ArgumentCaptor.forClass(MessagePostProcessor.class);
        doAnswer(invocation -> {
            CorrelationData data = invocation.getArgument(4);
            data.getFuture().set(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbitTemplate).convertAndSend(
                eq(RabbitMQTopicConfig.EXCHANGE),
                eq(RabbitMQTopicConfig.SECKILL_ROUTING_KEY),
                anyString(),
                processor.capture(),
                any(CorrelationData.class));

        relay.publishOne(outbox());

        Message message = new Message("{}".getBytes(StandardCharsets.UTF_8), new MessageProperties());
        Message processed = processor.getValue().postProcessMessage(message);
        assertEquals("100", processed.getMessageProperties().getMessageId());
        assertEquals(MessageDeliveryMode.PERSISTENT, processed.getMessageProperties().getDeliveryMode());
    }

    private SeckillOutbox outbox() {
        return new SeckillOutbox()
                .setId(100L)
                .setUserId(7L)
                .setVoucherId(2L)
                .setPayload("{\"id\":100,\"userId\":7,\"voucherId\":2}")
                .setStatus(SeckillOutbox.STATUS_NEW)
                .setAttempts(0)
                .setResendCount(0);
    }
}