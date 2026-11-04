package com.spiketrade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.spiketrade.entity.SeckillOutbox;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

public interface SeckillOutboxMapper extends BaseMapper<SeckillOutbox> {

    @Select("SELECT id, user_id, voucher_id, payload, status, attempts, resend_count, " +
            "next_retry_at, last_error, create_time, update_time " +
            "FROM tb_seckill_outbox " +
            "WHERE status = 0 AND next_retry_at <= #{now} " +
            "ORDER BY next_retry_at, id LIMIT #{limit}")
    List<SeckillOutbox> selectDue(@Param("now") LocalDateTime now, @Param("limit") int limit);

    @Update("UPDATE tb_seckill_outbox " +
            "SET attempts = attempts + 1, next_retry_at = #{leaseUntil}, update_time = CURRENT_TIMESTAMP " +
            "WHERE id = #{id} AND status = 0 AND next_retry_at <= #{now}")
    int claim(@Param("id") Long id,
              @Param("now") LocalDateTime now,
              @Param("leaseUntil") LocalDateTime leaseUntil);

    @Update("UPDATE tb_seckill_outbox SET status = 1, last_error = NULL, " +
            "update_time = CURRENT_TIMESTAMP WHERE id = #{id} AND status = 0")
    int markSent(@Param("id") Long id);

    @Update("UPDATE tb_seckill_outbox SET status = 0, next_retry_at = #{nextRetryAt}, " +
            "last_error = LEFT(#{lastError}, 255), update_time = CURRENT_TIMESTAMP " +
            "WHERE id = #{id} AND status = 0")
    int markFailed(@Param("id") Long id,
                   @Param("nextRetryAt") LocalDateTime nextRetryAt,
                   @Param("lastError") String lastError);

    @Update("UPDATE tb_seckill_outbox SET status = 2, last_error = LEFT(#{reason}, 255), " +
            "update_time = CURRENT_TIMESTAMP WHERE id = #{id}")
    int markDead(@Param("id") Long id, @Param("reason") String reason);

    @Update("UPDATE tb_seckill_outbox SET status = 0, resend_count = resend_count + 1, " +
            "next_retry_at = #{now}, last_error = NULL, update_time = CURRENT_TIMESTAMP " +
            "WHERE id = #{id} AND status = 1 AND resend_count < 3")
    int requeueForResend(@Param("id") Long id, @Param("now") LocalDateTime now);
}