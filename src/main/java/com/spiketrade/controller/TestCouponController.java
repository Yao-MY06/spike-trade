package com.spiketrade.controller;

import com.spiketrade.limiter.annotation.RateLimiter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * @author qqq
 * @create 2025-08-01-21:38
 * @description
 */
@RestController
@RequestMapping("/coupon")
public class TestCouponController {
    /**
     * 全局秒杀接口限流（全局限流）
     * 10秒内最多允许100次请求
     */
    @GetMapping("seckill")
    @RateLimiter(
            key = "coupon:seckill:global",
            window = 100,
            limit = 2,
            message = "秒杀活动太火爆，请稍后再试",
            type = RateLimiter.LimitType.METHOD
    )
    public String seckillCoupon() {
        // 秒杀业务逻辑
        return "秒杀成功";
    }

    /**
     * 按用户限流的优惠券领取接口
     * 每个用户60秒内最多领取3张优惠券
     */
    @GetMapping("claim")
    @RateLimiter(
            key = "coupon:claim:",
            window = 60,
            limit = 3,
            message = "您领取优惠券过于频繁，请稍后再试",
            type = RateLimiter.LimitType.USER
    )
    public String claimCoupon() {
        // 优惠券领取逻辑
        return "领取成功";
    }

    /**
     * 按IP限流的商家信息查询接口
     * 每个IP每秒最多查询5次商家信息
     */
    @GetMapping("merchant")
    @RateLimiter(
            key = "merchant:info:",
            window = 100,
            limit = 5,
            message = "查询过于频繁，请稍后再试",
            type = RateLimiter.LimitType.IP
    )
    public String getMerchantInfo() {
        // 查询商家信息
        return "商家信息";
    }
}
