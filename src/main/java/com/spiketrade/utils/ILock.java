package com.spiketrade.utils;

public interface ILock {
    boolean tryLock(long timeoutSec);

    void unlock();
}
