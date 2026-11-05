package com.spiketrade.seckill;

import com.spiketrade.config.SeckillMqConfig;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.junit.jupiter.api.Test;
import org.springframework.aop.ProxyMethodInvocation;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.support.ListenerExecutionFailedException;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;

import java.nio.charset.StandardCharsets;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SeckillRetryPolicyTest {

    @Test
    void nonRetryableExceptionIsRecoveredImmediately() throws Throwable {
        MessageRecoverer recoverer = mock(MessageRecoverer.class);
        MethodInterceptor interceptor = config().seckillRetryInterceptor(recoverer, 3, 1, 1);
        MethodInvocation invocation = invocationThrowing(new NonRetryableSeckillException("deterministic"));

        interceptor.invoke(invocation);

        verify(invocation, times(1)).proceed();
        verify(recoverer).recover(any(Message.class), any(NonRetryableSeckillException.class));
    }

    @Test
    void transientExceptionIsRetriedThreeTimes() throws Throwable {
        MessageRecoverer recoverer = mock(MessageRecoverer.class);
        MethodInterceptor interceptor = config().seckillRetryInterceptor(recoverer, 3, 1, 1);
        MethodInvocation invocation = invocationThrowing(new IllegalStateException("transient"));

        interceptor.invoke(invocation);

        verify(invocation, times(3)).proceed();
        verify(recoverer).recover(any(Message.class), any(IllegalStateException.class));
    }

    @Test
    void nonRetryableWrappedByListenerAdapterIsRecoveredWithoutRetry() throws Throwable {
        MessageRecoverer recoverer = mock(MessageRecoverer.class);
        MethodInterceptor interceptor = config().seckillRetryInterceptor(recoverer, 3, 1, 1);
        MethodInvocation invocation = invocationThrowing(new ListenerExecutionFailedException(
                "listener failed", new NonRetryableSeckillException("deterministic"), message()));

        interceptor.invoke(invocation);

        verify(invocation, times(1)).proceed();
        verify(recoverer).recover(any(Message.class), any(ListenerExecutionFailedException.class));
    }

    @Test
    void transientWrappedByListenerAdapterIsRetriedThreeTimes() throws Throwable {
        MessageRecoverer recoverer = mock(MessageRecoverer.class);
        MethodInterceptor interceptor = config().seckillRetryInterceptor(recoverer, 3, 1, 1);
        MethodInvocation invocation = invocationThrowing(new ListenerExecutionFailedException(
                "listener failed", new IllegalStateException("transient"), message()));

        interceptor.invoke(invocation);

        verify(invocation, times(3)).proceed();
        verify(recoverer).recover(any(Message.class), any(ListenerExecutionFailedException.class));
    }

    private SeckillMqConfig config() {
        return new SeckillMqConfig(mock(RabbitTemplate.class));
    }

    private MethodInvocation invocationThrowing(Throwable throwable) throws Throwable {
        ProxyMethodInvocation invocation = mock(ProxyMethodInvocation.class);
        when(invocation.invocableClone()).thenReturn(invocation);
        when(invocation.getMethod()).thenReturn(SeckillRetryPolicyTest.class.getDeclaredMethod("dummy"));
        when(invocation.getArguments()).thenReturn(new Object[]{"{}", message()});
        doThrow(throwable).when(invocation).proceed();
        return invocation;
    }

    private void dummy() {
    }

    private Message message() {
        return new Message("{}".getBytes(StandardCharsets.UTF_8), new MessageProperties());
    }
}