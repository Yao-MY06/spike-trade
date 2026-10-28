package com.spiketrade.limiter.annotation;

import java.lang.annotation.*;

/**
 * @author qqq
 * @create 2025-08-01-15:59
 * @description
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RateLimiter {
    /**
     * 限流key前缀
     */
    String key() default "rate_limit";
    /**
     * 时间窗口大小（秒）
     */
    int window() default 10;
    /**
     * 时间窗口内允许得请求数
     */
    int limit() default 20;
    /**
     * 限流提示信息
     */
    String message() default "系统繁忙，请稍后再试";
    /**
     * 限流维度（默认按方法限流）
     */
    LimitType type() default LimitType.METHOD;

    enum LimitType {
        /**
         * 按调用方IP限流
         */
        IP,
        /**
         * 按用户ID限流
         */
        USER,
        /**
         * 按方法限流/全局限流（默认）
         */
        METHOD
    }
}
