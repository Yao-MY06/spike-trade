package com.spiketrade.service.impl;

import com.spiketrade.dto.Result;
import com.spiketrade.dto.UserDTO;
import com.spiketrade.seckill.OutboxService;
import com.spiketrade.seckill.SeckillScripts;
import com.spiketrade.utils.RedisIdWorker;
import com.spiketrade.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class VoucherOrderServiceImplTest {

    private final RedisIdWorker idWorker = mock(RedisIdWorker.class);
    private final SeckillScripts scripts = mock(SeckillScripts.class);
    private final OutboxService outboxService = mock(OutboxService.class);
    private final VoucherOrderServiceImpl service = new VoucherOrderServiceImpl(idWorker, scripts, outboxService);

    @AfterEach
    void clearUser() {
        UserHolder.removeUser();
    }

    @Test
    void unauthenticatedRequestFailsBeforeRedis() {
        Result result = service.seckillVoucher(1L);

        assertFalse(result.getSuccess());
        assertEquals("需要登录", result.getErrorMsg());
        verifyNoInteractions(scripts, idWorker, outboxService);
    }

    @Test
    void successfulReservationWritesOutboxAndReturnsOrderId() {
        loginAs(7L);
        when(scripts.tryReserve(eq(1L), eq(7L), anyLong())).thenReturn(0L);
        when(idWorker.nextId("order")).thenReturn(100L);

        Result result = service.seckillVoucher(1L);

        assertTrue(result.getSuccess());
        assertEquals(100L, result.getData());
        verify(outboxService).insertNew(eq(100L), eq(7L), eq(1L), any(String.class));
        verify(scripts, never()).unreserve(anyLong(), anyLong());
    }

    @Test
    void outboxFailureRollsBackReservation() {
        loginAs(7L);
        when(scripts.tryReserve(eq(1L), eq(7L), anyLong())).thenReturn(0L);
        when(idWorker.nextId("order")).thenReturn(100L);
        doThrow(new RuntimeException("db down")).when(outboxService)
                .insertNew(eq(100L), eq(7L), eq(1L), any(String.class));

        Result result = service.seckillVoucher(1L);

        assertFalse(result.getSuccess());
        assertEquals("系统繁忙，请稍后重试", result.getErrorMsg());
        verify(scripts).unreserve(1L, 7L);
    }

    @Test
    void outboxCommitUncertainButStoredReturnsSuccess() {
        loginAs(7L);
        when(scripts.tryReserve(eq(1L), eq(7L), anyLong())).thenReturn(0L);
        when(idWorker.nextId("order")).thenReturn(100L);
        doThrow(new RuntimeException("commit response lost")).when(outboxService)
                .insertNew(eq(100L), eq(7L), eq(1L), any(String.class));
        when(outboxService.matches(100L, 7L, 1L)).thenReturn(true);

        Result result = service.seckillVoucher(1L);

        assertTrue(result.getSuccess());
        assertEquals(100L, result.getData());
        verify(scripts, never()).unreserve(anyLong(), anyLong());
    }

    @Test
    void outboxCheckFailureKeepsReservationForReconciliation() {
        loginAs(7L);
        when(scripts.tryReserve(eq(1L), eq(7L), anyLong())).thenReturn(0L);
        when(idWorker.nextId("order")).thenReturn(100L);
        doThrow(new RuntimeException("db down")).when(outboxService)
                .insertNew(eq(100L), eq(7L), eq(1L), any(String.class));
        when(outboxService.matches(100L, 7L, 1L)).thenThrow(new RuntimeException("db still down"));

        Result result = service.seckillVoucher(1L);

        assertFalse(result.getSuccess());
        verify(scripts, never()).unreserve(anyLong(), anyLong());
    }

    private void loginAs(Long userId) {
        UserDTO user = new UserDTO();
        user.setId(userId);
        UserHolder.saveUser(user);
    }
}