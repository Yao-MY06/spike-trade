package com.spiketrade;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableAspectJAutoProxy(exposeProxy = true)
@MapperScan("com.spiketrade.mapper")
@EnableScheduling
@SpringBootApplication
public class SpikeTradeApplication {

    public static void main(String[] args) {
        SpringApplication.run(SpikeTradeApplication.class, args);
    }

}