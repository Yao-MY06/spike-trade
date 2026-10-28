package com.spiketrade.limiter.exception;

/**
 * @author qqq
 * @create 2025-08-01-21:17
 * @description
 */

public class RateLimitException extends RuntimeException{
    public RateLimitException(String message){
        super(message);
    }
}
