package com.spiketrade.seckill;

public class NonRetryableSeckillException extends RuntimeException {

    public NonRetryableSeckillException(String message) {
        super(message);
    }

    public NonRetryableSeckillException(String message, Throwable cause) {
        super(message, cause);
    }
}