package com.smartdrone.application.common;

import java.util.UUID;

import org.slf4j.MDC;

/** traceId của request hiện tại (do RequestLoggingFilter đặt vào MDC). */
public final class TraceContext {

    public static final String KEY = "traceId";

    private TraceContext() { }

    public static String current() {
        String id = MDC.get(KEY);
        return id != null ? id : UUID.randomUUID().toString(); // phòng khi chạy ngoài request (test, job)
    }
}