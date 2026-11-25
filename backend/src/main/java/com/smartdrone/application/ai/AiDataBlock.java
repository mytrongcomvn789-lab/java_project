package com.smartdrone.application.ai;

import java.util.regex.Pattern;

/** Một khối dữ liệu có nhãn (đơn hàng, câu hỏi của người dùng, số liệu...). Nhãn do code đặt, giá trị là dữ liệu không tin cậy. */
public record AiDataBlock(String label, String value) {
    private static final Pattern LABEL = Pattern.compile("[a-z][a-z0-9_]{0,39}");

    public AiDataBlock {
        if (label == null || !LABEL.matcher(label).matches()) {
            throw new IllegalArgumentException("Nhãn khối dữ liệu không hợp lệ");
        }
        value = value == null ? "" : value;
    }
}
