package com.spiketrade.mapper;

import com.spiketrade.entity.SeckillVoucher;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface SeckillVoucherMapper extends BaseMapper<SeckillVoucher> {

    @Update("UPDATE tb_seckill_voucher SET stock = stock - 1, update_time = CURRENT_TIMESTAMP " +
            "WHERE voucher_id = #{voucherId} AND stock > 0")
    int decrementStock(@Param("voucherId") Long voucherId);
}