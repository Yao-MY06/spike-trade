package com.spiketrade.seckill;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SeckillConsumerTest {

    private final SeckillOrderHandler handler = mock(SeckillOrderHandler.class);
    private final SeckillConsumer consumer = new SeckillConsumer(handler);

    @Test
    void invalidJsonIsNonRetryable() {
        NonRetryableSeckillException ex = assertThrows(NonRetryableSeckillException.class,
                () -> consumer.onMessage("not-json", message(null)));

        assertEqualsMessage("INVALID_MESSAGE_JSON", ex);
        verifyNoInteractions(handler);
    }

    @Test
    void missingFieldsAreNonRetryable() {
        NonRetryableSeckillException ex = assertThrows(NonRetryableSeckillException.class,
                () -> consumer.onMessage("{\"id\":1}", message("1")));

        assertEqualsMessage("INVALID_MESSAGE_FIELDS", ex);
        verifyNoInteractions(handler);
    }

    @Test
    void validMessageIsDelegated() {
        when(handler.createOrder(any(SeckillMessage.class)))
                .thenReturn(SeckillOrderHandler.CreateOrderResult.CREATED);

        consumer.onMessage("{\"id\":1,\"userId\":2,\"voucherId\":3}", message("1"));

        verify(handler).createOrder(new SeckillMessage(1L, 2L, 3L));
    }

    private Message message(String messageId) {
        MessageProperties properties = new MessageProperties();
        properties.setMessageId(messageId);
        return new Message("{}".getBytes(StandardCharsets.UTF_8), properties);
    }

    private void assertEqualsMessage(String expected, NonRetryableSeckillException ex) {
        org.junit.jupiter.api.Assertions.assertEquals(expected, ex.getMessage());
    }
}