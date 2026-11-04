-- J1-001 可靠发布迁移
-- 执行前先在目标库运行下方检查；必须返回 0 行。
SELECT user_id, voucher_id, COUNT(*) AS duplicate_count
FROM tb_voucher_order
GROUP BY user_id, voucher_id
HAVING COUNT(*) > 1;

ALTER TABLE tb_voucher_order
    ADD UNIQUE KEY uk_user_voucher (user_id, voucher_id);

CREATE TABLE IF NOT EXISTS tb_seckill_outbox (
    id BIGINT NOT NULL COMMENT '订单号，同时作为消息 ID',
    user_id BIGINT UNSIGNED NOT NULL COMMENT '用户 ID',
    voucher_id BIGINT UNSIGNED NOT NULL COMMENT '秒杀券 ID',
    payload VARCHAR(512) NOT NULL COMMENT '消息体 JSON',
    status TINYINT NOT NULL DEFAULT 0 COMMENT '0 NEW, 1 SENT, 2 DEAD',
    attempts INT NOT NULL DEFAULT 0 COMMENT 'relay 领取次数',
    resend_count INT NOT NULL DEFAULT 0 COMMENT '对账重发次数',
    next_retry_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '下次可领取时间',
    last_error VARCHAR(255) NULL COMMENT '最近失败原因',
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_outbox_user_voucher (user_id, voucher_id),
    KEY idx_outbox_status_retry (status, next_retry_at),
    KEY idx_outbox_voucher (voucher_id)
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '秒杀可靠投递本地消息表';