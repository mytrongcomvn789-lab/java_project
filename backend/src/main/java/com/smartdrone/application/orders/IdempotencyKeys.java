package com.smartdrone.application.orders;

import java.util.regex.Pattern;

import com.smartdrone.application.common.ApiException;
import com.smartdrone.application.common.ErrorCode;

public final class IdempotencyKeys {

    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9._:\\-]{1,100}");

    private IdempotencyKeys() { }

    /** Trả về key đã kiểm tra, hoặc ném 400 nếu thiếu/sai định dạng. */
    public static String require(String header) {
        if (header == null || !VALID.matcher(header.trim()).matches()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "Thiếu hoặc sai header Idempotency-Key (1-100 ký tự chữ, số, . _ : -)");
        }
        return header.trim();
    }
}