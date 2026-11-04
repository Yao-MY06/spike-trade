package com.spiketrade.seckill;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Reservation {

    private Long userId;

    private Long reservedAt;
}