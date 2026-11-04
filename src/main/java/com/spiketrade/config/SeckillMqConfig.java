package com.spiketrade.config;

import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.amqp.rabbit.retry.RepublishMessageRecoverer;
import org.springframework.amqp.support.converter.SimpleMessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.support.RetryTemplate;

import javax.annotation.PostConstruct;
import java.util.Collections;

@Configuration
public class SeckillMqConfig {

    private final RabbitTemplate rabbitTemplate;

    public SeckillMqConfig(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    @PostConstruct
    public void configurePublisherCallbacks() {
        rabbitTemplate.setConfirmCallback((correlationData, ack, cause) -> {
            // RepublishMessageRecoverer 使用独立模板时不携带 correlationData。
            if (correlationData != null) {
                correlationData.getFuture().set(new CorrelationData.Confirm(ack, cause));
            }
        });
    }

    @Bean
    public MessageRecoverer seckillMessageRecoverer(ConnectionFactory connectionFactory) {
        RabbitTemplate errorTemplate = new RabbitTemplate(connectionFactory);
        errorTemplate.setMessageConverter(new SimpleMessageConverter());
        return new RepublishMessageRecoverer(
                errorTemplate,
                RabbitMQTopicConfig.DEAD_LETTER_EXCHANGE,
                RabbitMQTopicConfig.DEAD_LETTER_ROUTING_KEY);
    }

    @Bean
    public MethodInterceptor seckillRetryInterceptor(
            MessageRecoverer seckillMessageRecoverer,
            @Value("${trade.seckill.consumer.max-attempts:3}") int maxAttempts,
            @Value("${trade.seckill.consumer.initial-interval-ms:500}") long initialIntervalMs,
            @Value("${trade.seckill.consumer.max-interval-ms:5000}") long maxIntervalMs) {
        RetryTemplate retryTemplate = new RetryTemplate();
        retryTemplate.setRetryPolicy(new SimpleRetryPolicy(
                maxAttempts,
                Collections.<Class<? extends Throwable>, Boolean>emptyMap(),
                true));
        ExponentialBackOffPolicy backOffPolicy = new ExponentialBackOffPolicy();
        backOffPolicy.setInitialInterval(initialIntervalMs);
        backOffPolicy.setMaxInterval(maxIntervalMs);
        backOffPolicy.setMultiplier(2.0);
        retryTemplate.setBackOffPolicy(backOffPolicy);

        return RetryInterceptorBuilder.stateless()
                .retryOperations(retryTemplate)
                .recoverer(seckillMessageRecoverer)
                .build();
    }

    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            MethodInterceptor seckillRetryInterceptor) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setMessageConverter(new SimpleMessageConverter());
        factory.setAdviceChain(seckillRetryInterceptor);
        return factory;
    }
}