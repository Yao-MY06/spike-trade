package com.spiketrade.seckill;

import com.spiketrade.entity.SeckillOutbox;
import com.spiketrade.entity.VoucherOrder;
import com.spiketrade.mapper.SeckillOutboxMapper;
import com.spiketrade.mapper.SeckillReconcileMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class SeckillReconcilerTest {

    private final SeckillScripts scripts = mock(SeckillScripts.class);
    private final SeckillReconcileMapper reconcileMapper = mock(SeckillReconcileMapper.class);
    private final SeckillOutboxMapper outboxMapper = mock(SeckillOutboxMapper.class);
    private final SeckillReconciler reconciler = new SeckillReconciler(scripts, reconcileMapper, outboxMapper, 30);

    @BeforeEach
    void baseStubs() {
        when(scripts.reservations(1L)).thenReturn(Collections.<Reservation>emptyList());
        when(reconcileMapper.selectOutboxByVoucherId(1L)).thenReturn(Collections.<SeckillOutbox>emptyList());
        when(reconcileMapper.selectOrdersByVoucherId(1L)).thenReturn(Collections.<VoucherOrder>emptyList());
    }

    @Test
    void identifiesAndRepairsOrphanReservation() {
        when(scripts.snapshot(1L)).thenReturn("99:1");
        when(scripts.reservations(1L)).thenReturn(Collections.singletonList(
                new Reservation(7L, LocalDateTime.now().minusMinutes(2).toEpochSecond(java.time.ZoneOffset.UTC))));
        when(scripts.unreserve(1L, 7L)).thenReturn(1L);
        stubCounters(100, 0);

        ReconcileReport report = reconciler.reconcile(1L);

        assertIssue(report, ReconcileReport.TYPE_A, ReconcileReport.ACTION_REPAIRED);
        verify(scripts).unreserve(1L, 7L);
    }

    @Test
    void reportsStalledNewOutbox() {
        when(scripts.snapshot(1L)).thenReturn("100:0");
        SeckillOutbox outbox = outbox(SeckillOutbox.STATUS_NEW, 0);
        when(reconcileMapper.selectOutboxByVoucherId(1L)).thenReturn(Collections.singletonList(outbox));
        stubCounters(100, 0);

        ReconcileReport report = reconciler.reconcile(1L);

        assertIssue(report, ReconcileReport.TYPE_B, ReconcileReport.ACTION_REPORT);
    }

    @Test
    void requeuesSentOutboxWithoutOrder() {
        when(scripts.snapshot(1L)).thenReturn("100:0");
        SeckillOutbox outbox = outbox(SeckillOutbox.STATUS_SENT, 0);
        when(reconcileMapper.selectOutboxByVoucherId(1L)).thenReturn(Collections.singletonList(outbox));
        when(outboxMapper.requeueForResend(eq(100L), any(LocalDateTime.class))).thenReturn(1);
        stubCounters(100, 0);

        ReconcileReport report = reconciler.reconcile(1L);

        assertIssue(report, ReconcileReport.TYPE_C, ReconcileReport.ACTION_REQUEUED);
        verify(outboxMapper).requeueForResend(eq(100L), any(LocalDateTime.class));
    }

    @Test
    void reportsOrderWithoutReservation() {
        when(scripts.snapshot(1L)).thenReturn("100:0");
        VoucherOrder order = new VoucherOrder().setId(100L).setUserId(7L).setVoucherId(1L);
        when(reconcileMapper.selectOrdersByVoucherId(1L)).thenReturn(Collections.singletonList(order));
        stubCounters(100, 1);

        ReconcileReport report = reconciler.reconcile(1L);

        assertIssue(report, ReconcileReport.TYPE_D, ReconcileReport.ACTION_REPORT);
    }

    @Test
    void reportsStockDrift() {
        when(scripts.snapshot(1L)).thenReturn("99:0");
        stubCounters(100, 0);

        ReconcileReport report = reconciler.reconcile(1L);

        assertIssue(report, ReconcileReport.TYPE_E, ReconcileReport.ACTION_REPORT);
    }

    private void stubCounters(int stock, long orderCount) {
        ReconcileCounters counters = new ReconcileCounters();
        counters.setDbStock(stock);
        counters.setOrderCount(orderCount);
        when(reconcileMapper.selectCounters(1L)).thenReturn(counters);
    }

    private SeckillOutbox outbox(int status, int resendCount) {
        return new SeckillOutbox()
                .setId(100L)
                .setUserId(7L)
                .setVoucherId(1L)
                .setStatus(status)
                .setAttempts(1)
                .setResendCount(resendCount)
                .setCreateTime(LocalDateTime.now().minusMinutes(2))
                .setUpdateTime(LocalDateTime.now().minusMinutes(2));
    }

    private void assertIssue(ReconcileReport report, String type, String action) {
        assertTrue(report.getIssues().stream()
                        .anyMatch(issue -> type.equals(issue.getType()) && action.equals(issue.getAction())),
                "missing issue type=" + type + " action=" + action);
    }
}