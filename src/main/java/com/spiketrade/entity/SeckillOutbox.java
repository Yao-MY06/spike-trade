package com.spiketrade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_seckill_outbox")
public class SeckillOutbox implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final int STATUS_NEW = 0;
    public static final int STATUS_SENT = 1;
    public static final int STATUS_DEAD = 2;

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private Long userId;

    private Long voucherId;

    private String payload;

    private Integer status;

    private Integer attempts;

    private Integer resendCount;

    private LocalDateTime nextRetryAt;

    private String lastError;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}