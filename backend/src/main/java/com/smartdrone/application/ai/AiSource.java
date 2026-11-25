package com.smartdrone.application.ai;

/** Nguồn của kết quả. Chỉ PROVIDER là kết quả AI thật; MOCK và FALLBACK không được trình bày như kết quả AI. */
public enum AiSource {
    PROVIDER, MOCK, FALLBACK,
    /** 7.2: công thức xác định, KHÔNG phải AI/ML, không gọi nhà cung cấp. */
    FORMULA
}
