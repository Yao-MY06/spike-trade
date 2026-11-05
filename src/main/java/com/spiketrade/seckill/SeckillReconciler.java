package com.spiketrade.seckill;

import com.spiketrade.entity.SeckillOutbox;
import com.spiketrade.entity.VoucherOrder;
import com.spiketrade.mapper.SeckillOutboxMapper;
import com.spiketrade.mapper.SeckillReconcileMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@ConditionalOnProperty(name = "trade.seckill.reconcile.enabled", havingValue = "true", matchIfMissing = true)
public class SeckillReconciler {

    private final SeckillScripts seckillScripts;
    private final SeckillReconcileMapper reconcileMapper;
    private final SeckillOutboxMapper outboxMapper;
    private final long graceSeconds;

    public SeckillReconciler(SeckillScripts seckillScripts,
                             SeckillReconcileMapper reconcileMapper,
                             SeckillOutboxMapper outboxMapper,
                             @Value("${trade.seckill.reconcile.grace-seconds:30}") long graceSeconds) {
        this.seckillScripts = seckillScripts;
        this.reconcileMapper = reconcileMapper;
        this.outboxMapper = outboxMapper;
        this.graceSeconds = graceSeconds;
    }

    @Scheduled(fixedDelayString = "${trade.seckill.reconcile.interval-ms:60000}")
    public void reconcileAll() {
        for (Long voucherId : reconcileMapper.selectVoucherIds()) {
            try {
                logIssues(reconcile(voucherId));
            } catch (Exception ex) {
                log.error("秒杀对账失败，voucherId={}", voucherId, ex);
            }
        }
    }

    private void logIssues(ReconcileReport report) {
        for (ReconcileReport.Issue issue : report.getIssues()) {
            log.warn("秒杀对账发现不一致，voucherId={}, type={}, subject={}, action={}, detail={}",
                    report.getVoucherId(), issue.getType(), issue.getSubject(),
                    issue.getAction(), issue.getDetail());
        }
    }

    public ReconcileReport reconcile(Long voucherId) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime cutoff = now.minusSeconds(graceSeconds);
        ReconcileReport report = new ReconcileReport();
        report.setVoucherId(voucherId);

        Snapshot snapshot = parseSnapshot(seckillScripts.snapshot(voucherId));
        report.setRedisStock(snapshot.stock);
        report.setRedisReservationCount(snapshot.reservations);

        ReconcileCounters counters = reconcileMapper.selectCounters(voucherId);
        if (counters == null) {
            throw new IllegalArgumentException("秒杀券不存在");
        }
        report.setDbStock(counters.getDbStock());
        report.setOrderCount(counters.getOrderCount());

        List<Reservation> reservations = seckillScripts.reservations(voucherId);
        List<SeckillOutbox> outboxes = reconcileMapper.selectOutboxByVoucherId(voucherId);
        List<VoucherOrder> orders = reconcileMapper.selectOrdersByVoucherId(voucherId);

        Map<Long, Long> reservationTimes = new HashMap<>();
        for (Reservation reservation : reservations) {
            reservationTimes.put(reservation.getUserId(), reservation.getReservedAt());
        }

        Set<Long> orderIds = new HashSet<>();
        Set<Long> orderUsers = new HashSet<>();
        for (VoucherOrder order : orders) {
            orderIds.add(order.getId());
            orderUsers.add(order.getUserId());
        }

        Map<Long, SeckillOutbox> outboxByUser = new HashMap<>();
        for (SeckillOutbox outbox : outboxes) {
            outboxByUser.put(outbox.getUserId(), outbox);
        }

        repairOrphans(report, voucherId, reservationTimes, orderUsers, outboxByUser, cutoff);
        reportStalledOutbox(report, outboxes, cutoff);
        repairSentWithoutOrder(report, outboxes, orderIds, orderUsers, cutoff);
        reportOrdersWithoutReservation(report, reservationTimes, orders);
        reportStockDrift(report, snapshot, counters);

        report.setConsistent(report.getIssues().isEmpty());
        return report;
    }

    private void repairOrphans(ReconcileReport report,
                               Long voucherId,
                               Map<Long, Long> reservationTimes,
                               Set<Long> orderUsers,
                               Map<Long, SeckillOutbox> outboxByUser,
                               LocalDateTime cutoff) {
        long cutoffSeconds = Instant.now().minusSeconds(graceSeconds).getEpochSecond();
        for (Map.Entry<Long, Long> entry : reservationTimes.entrySet()) {
            Long userId = entry.getKey();
            Long reservedAt = entry.getValue();
            if (reservedAt != null && reservedAt < cutoffSeconds
                    && !orderUsers.contains(userId) && !outboxByUser.containsKey(userId)) {
                Long removed = seckillScripts.unreserve(voucherId, userId);
                if (removed != null && removed == 1L) {
                    report.addIssue(ReconcileReport.TYPE_A, "userId=" + userId,
                            ReconcileReport.ACTION_REPAIRED,
                            "过期预约已回滚，reservedAt=" + reservedAt);
                } else {
                    report.addIssue(ReconcileReport.TYPE_A, "userId=" + userId,
                            ReconcileReport.ACTION_REPORT,
                            "检测到孤儿预约，但回滚未生效");
                }
            }
        }
    }

    private void reportStalledOutbox(ReconcileReport report,
                                     List<SeckillOutbox> outboxes,
                                     LocalDateTime cutoff) {
        for (SeckillOutbox outbox : outboxes) {
            if (Integer.valueOf(SeckillOutbox.STATUS_NEW).equals(outbox.getStatus())
                    && outbox.getCreateTime() != null
                    && outbox.getCreateTime().isBefore(cutoff)) {
                report.addIssue(ReconcileReport.TYPE_B, "orderId=" + outbox.getId(),
                        ReconcileReport.ACTION_REPORT,
                        "outbox 停留 NEW 超过宽限期，attempts=" + outbox.getAttempts());
            }
        }
    }

    private void repairSentWithoutOrder(ReconcileReport report,
                                        List<SeckillOutbox> outboxes,
                                        Set<Long> orderIds,
                                        Set<Long> orderUsers,
                                        LocalDateTime cutoff) {
        for (SeckillOutbox outbox : outboxes) {
            if (!Integer.valueOf(SeckillOutbox.STATUS_SENT).equals(outbox.getStatus())
                    || orderIds.contains(outbox.getId())
                    || outbox.getUpdateTime() == null
                    || !outbox.getUpdateTime().isBefore(cutoff)) {
                continue;
            }

            if (orderUsers.contains(outbox.getUserId())) {
                outboxMapper.markDead(outbox.getId(), "SUPERSEDED");
                report.addIssue(ReconcileReport.TYPE_C, "orderId=" + outbox.getId(),
                        ReconcileReport.ACTION_MARKED_DEAD, "同用户已有其他订单");
            } else if (outbox.getResendCount() == null || outbox.getResendCount() < 3) {
                int requeued = outboxMapper.requeueForResend(outbox.getId(), LocalDateTime.now());
                String action = requeued == 1
                        ? ReconcileReport.ACTION_REQUEUED
                        : ReconcileReport.ACTION_REPORT;
                report.addIssue(ReconcileReport.TYPE_C, "orderId=" + outbox.getId(),
                        action, "已发送但无订单");
            } else {
                outboxMapper.markDead(outbox.getId(), "EXHAUSTED");
                report.addIssue(ReconcileReport.TYPE_C, "orderId=" + outbox.getId(),
                        ReconcileReport.ACTION_MARKED_DEAD, "重发次数已达上限");
            }
        }
    }

    private void reportOrdersWithoutReservation(ReconcileReport report,
                                                Map<Long, Long> reservationTimes,
                                                List<VoucherOrder> orders) {
        for (VoucherOrder order : orders) {
            if (!reservationTimes.containsKey(order.getUserId())) {
                report.addIssue(ReconcileReport.TYPE_D, "orderId=" + order.getId(),
                        ReconcileReport.ACTION_REPORT,
                        "订单缺少 Redis 预约，userId=" + order.getUserId());
            }
        }
    }

    private void reportStockDrift(ReconcileReport report,
                                  Snapshot snapshot,
                                  ReconcileCounters counters) {
        if (snapshot.stock == null) {
            report.addIssue(ReconcileReport.TYPE_E, "voucherId=" + report.getVoucherId(),
                    ReconcileReport.ACTION_REPORT, "Redis 库存 key 不存在");
            return;
        }
        long redisTotal = snapshot.stock + snapshot.reservations;
        long dbTotal = counters.getDbStock()
                + (counters.getOrderCount() == null ? 0L : counters.getOrderCount());
        if (redisTotal != dbTotal) {
            report.addIssue(ReconcileReport.TYPE_E, "voucherId=" + report.getVoucherId(),
                    ReconcileReport.ACTION_REPORT,
                    "R+Z=" + redisTotal + ", D+O=" + dbTotal);
        }
    }

    private Snapshot parseSnapshot(String value) {
        if (value == null) {
            throw new IllegalStateException("Redis snapshot 为空");
        }
        String[] parts = value.split(":", 2);
        if (parts.length != 2) {
            throw new IllegalStateException("Redis snapshot 格式非法: " + value);
        }
        Integer stock = "nil".equals(parts[0]) ? null : Integer.valueOf(parts[0]);
        return new Snapshot(stock, Long.valueOf(parts[1]));
    }

    private static class Snapshot {
        private final Integer stock;
        private final long reservations;

        private Snapshot(Integer stock, long reservations) {
            this.stock = stock;
            this.reservations = reservations;
        }
    }
}