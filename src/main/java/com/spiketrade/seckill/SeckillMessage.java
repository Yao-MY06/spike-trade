package com.spiketrade.seckill;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SeckillMessage {

    private Long id;

    private Long userId;

    private Long voucherId;
}