package com.landrop.protocol;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe utility for generating sequential request IDs.
 */
public class RequestIdGenerator {

    private final AtomicLong counter;

    /**
     * Constructs a RequestIdGenerator initialized to 1.
     */
    public RequestIdGenerator() {
        this(1L);
    }

    /**
     * Constructs a RequestIdGenerator initialized to the specified starting value.
     *
     * @param initialValue the initial value
     */
    public RequestIdGenerator(long initialValue) {
        this.counter = new AtomicLong(initialValue);
    }

    /**
     * Returns the next sequential request ID.
     *
     * @return the generated request ID
     */
    public long nextId() {
        return counter.getAndIncrement();
    }

    /**
     * Returns the current request ID without incrementing.
     *
     * @return the current request ID
     */
    public long currentId() {
        return counter.get();
    }
}
