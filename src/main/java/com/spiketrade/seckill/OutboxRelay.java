package com.spiketrade.seckill;

import com.spiketrade.config.RabbitMQTopicConfig;
import com.spiketrade.entity.SeckillOutbox;
import com.spiketrade.mapper.SeckillOutboxMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.AmqpIOException;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.ConnectException;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
@ConditionalOnProperty(name = "trade.seckill.relay.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelay {

    private final SeckillOutboxMapper outboxMapper;
    private final RabbitTemplate rabbitTemplate;
    private final int batchSize;
    private final long leaseMs;

    private final AtomicBoolean running = new AtomicBoolean(false);

    public OutboxRelay(SeckillOutboxMapper outboxMapper,
                       RabbitTemplate rabbitTemplate,
                       @Value("${trade.seckill.relay.batch-size:50}") int batchSize,
                       @Value("${trade.seckill.relay.lease-ms:5000}") long leaseMs) {
        this.outboxMapper = outboxMapper;
        this.rabbitTemplate = rabbitTemplate;
        this.batchSize = batchSize;
        this.leaseMs = leaseMs;
    }

    @Scheduled(fixedDelayString = "${trade.seckill.relay.interval-ms:200}")
    public void poll() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            LocalDateTime now = LocalDateTime.now();
            List<SeckillOutbox> due = outboxMapper.selectDue(now, batchSize);
            for (SeckillOutbox outbox : due) {
                LocalDateTime claimedAt = LocalDateTime.now();
                LocalDateTime leaseUntil = claimedAt.plusNanos(TimeUnit.MILLISECONDS.toNanos(leaseMs));
                int claimed = outboxMapper.claim(outbox.getId(), claimedAt, leaseUntil);
                if (claimed != 1) {
                    continue;
                }
                PublishOutcome outcome = publishOne(outbox);
                if (outcome == PublishOutcome.STOP_BATCH) {
                    break;
                }
            }
        } catch (Exception ex) {
            log.error("outbox relay 轮询失败", ex);
        } finally {
            running.set(false);
        }
    }

    PublishOutcome publishOne(SeckillOutbox outbox) {
        Long id = outbox.getId();
        CorrelationData correlationData = new CorrelationData(String.valueOf(id));
        MessagePostProcessor messagePostProcessor = message -> {
            message.getMessageProperties().setMessageId(String.valueOf(id));
            message.getMessageProperties().setContentType("application/json");
            message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            return message;
        };

        try {
            rabbitTemplate.convertAndSend(
                    RabbitMQTopicConfig.EXCHANGE,
                    RabbitMQTopicConfig.SECKILL_ROUTING_KEY,
                    outbox.getPayload(),
                    messagePostProcessor,
                    correlationData);

            CorrelationData.Confirm confirm = correlationData.getFuture().get(leaseMs, TimeUnit.MILLISECONDS);
            if (confirm != null && confirm.isAck() && correlationData.getReturnedMessage() == null) {
                outboxMapper.markSent(id);
                return PublishOutcome.CONTINUE;
            }
            String reason;
            if (confirm == null) {
                reason = "CONFIRM_MISSING";
            } else if (!confirm.isAck()) {
                reason = "NACK:" + valueOrEmpty(confirm.getReason());
            } else {
                reason = "RETURNED:" + returnedReason(correlationData);
            }
            markFailed(outbox, reason);
            return PublishOutcome.CONTINUE;
        } catch (TimeoutException ex) {
            markFailed(outbox, "CONFIRM_TIMEOUT");
            return PublishOutcome.STOP_BATCH;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            markFailed(outbox, "INTERRUPTED");
            return PublishOutcome.STOP_BATCH;
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            markFailed(outbox, "CONFIRM_ERROR:" + cause.getMessage());
            return isConnectionFailure(cause) ? PublishOutcome.STOP_BATCH : PublishOutcome.CONTINUE;
        } catch (Exception ex) {
            markFailed(outbox, ex.getClass().getSimpleName() + ":" + ex.getMessage());
            if (isConnectionFailure(ex)) {
                return PublishOutcome.STOP_BATCH;
            }
            return PublishOutcome.CONTINUE;
        }
    }

    private void markFailed(SeckillOutbox outbox, String reason) {
        LocalDateTime retryAt = LocalDateTime.now().plusSeconds(backoffSeconds(outbox.getAttempts()));
        outboxMapper.markFailed(outbox.getId(), retryAt, reason);
    }

    private long backoffSeconds(Integer priorAttempts) {
        int prior = priorAttempts == null ? 0 : Math.max(0, priorAttempts);
        long seconds = 1L << Math.min(prior, 6);
        return Math.min(60L, seconds);
    }

    private String returnedReason(CorrelationData correlationData) {
        if (correlationData.getReturnedMessage() == null
                || correlationData.getReturnedMessage().getMessageProperties() == null) {
            return "UNROUTABLE";
        }
        String replyText = correlationData.getReturnedMessage()
                .getMessageProperties().getHeader("x-reply-text");
        return valueOrEmpty(replyText);
    }

    private String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    private boolean isConnectionFailure(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof AmqpConnectException
                    || current instanceof AmqpIOException
                    || current instanceof ConnectException
                    || current instanceof SocketException
                    || current instanceof UnknownHostException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    enum PublishOutcome {
        CONTINUE,
        STOP_BATCH
    }
}