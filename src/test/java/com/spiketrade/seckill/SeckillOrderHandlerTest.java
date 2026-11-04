package com.spiketrade.seckill;

import com.spiketrade.entity.VoucherOrder;
import com.spiketrade.mapper.SeckillVoucherMapper;
import com.spiketrade.mapper.VoucherOrderMapper;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SeckillOrderHandlerTest {

    private final VoucherOrderMapper orderMapper = mock(VoucherOrderMapper.class);
    private final SeckillVoucherMapper voucherMapper = mock(SeckillVoucherMapper.class);
    private final SeckillOrderHandler handler = new SeckillOrderHandler(orderMapper, voucherMapper);

    @Test
    void createsOrderThenDecrementsStock() {
        when(voucherMapper.decrementStock(2L)).thenReturn(1);

        SeckillOrderHandler.CreateOrderResult result = handler.createOrder(message(100L, 7L, 2L));

        assertEquals(SeckillOrderHandler.CreateOrderResult.CREATED, result);
        verify(orderMapper).insert(any(VoucherOrder.class));
        verify(voucherMapper).decrementStock(2L);
    }

    @Test
    void duplicateDeliveryIsIdempotent() {
        doThrow(new DuplicateKeyException("duplicate")).when(orderMapper).insert(any(VoucherOrder.class));
        when(orderMapper.selectById(100L)).thenReturn(new VoucherOrder().setId(100L));

        SeckillOrderHandler.CreateOrderResult result = handler.createOrder(message(100L, 7L, 2L));

        assertEquals(SeckillOrderHandler.CreateOrderResult.DUPLICATE_DELIVERY, result);
        verify(voucherMapper, never()).decrementStock(anyLong());
    }

    @Test
    void sameUserWithDifferentOrderIsNonRetryable() {
        doThrow(new DuplicateKeyException("unique(user,voucher)")).when(orderMapper).insert(any(VoucherOrder.class));
        when(orderMapper.selectById(100L)).thenReturn(null);

        NonRetryableSeckillException ex = assertThrows(NonRetryableSeckillException.class,
                () -> handler.createOrder(message(100L, 7L, 2L)));

        assertEquals("USER_ALREADY_HAS_ORDER", ex.getMessage());
        verify(voucherMapper, never()).decrementStock(anyLong());
    }

    @Test
    void databaseStockExhaustionRollsBackByException() {
        when(voucherMapper.decrementStock(2L)).thenReturn(0);

        NonRetryableSeckillException ex = assertThrows(NonRetryableSeckillException.class,
                () -> handler.createOrder(message(100L, 7L, 2L)));

        assertEquals("DB_STOCK_EXHAUSTED", ex.getMessage());
        verify(orderMapper).insert(any(VoucherOrder.class));
    }

    private SeckillMessage message(Long id, Long userId, Long voucherId) {
        return new SeckillMessage(id, userId, voucherId);
    }
}