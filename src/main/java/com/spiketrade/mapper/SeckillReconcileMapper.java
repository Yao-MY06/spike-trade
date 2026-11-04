package com.spiketrade.mapper;

import com.spiketrade.entity.SeckillOutbox;
import com.spiketrade.entity.VoucherOrder;
import com.spiketrade.seckill.ReconcileCounters;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface SeckillReconcileMapper {

    @Select("SELECT voucher_id FROM tb_seckill_voucher")
    List<Long> selectVoucherIds();

    @Select("SELECT stock AS dbStock, " +
            "(SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id = #{voucherId}) AS orderCount " +
            "FROM tb_seckill_voucher WHERE voucher_id = #{voucherId}")
    ReconcileCounters selectCounters(@Param("voucherId") Long voucherId);

    @Select("SELECT id, user_id, voucher_id, pay_type, status, create_time, pay_time, " +
            "use_time, refund_time, update_time FROM tb_voucher_order WHERE voucher_id = #{voucherId}")
    List<VoucherOrder> selectOrdersByVoucherId(@Param("voucherId") Long voucherId);

    @Select("SELECT id, user_id, voucher_id, payload, status, attempts, resend_count, " +
            "next_retry_at, last_error, create_time, update_time " +
            "FROM tb_seckill_outbox WHERE voucher_id = #{voucherId}")
    List<SeckillOutbox> selectOutboxByVoucherId(@Param("voucherId") Long voucherId);
}