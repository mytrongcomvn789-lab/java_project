package com.smartdrone.application.common;

public final class IfMatchVersion {

    private IfMatchVersion() { }

    /** If-Match: "5" (hoặc W/"5", hoặc 5) -> 5. Thiếu header thì lỗi 428. */
    public static long parse(String header) {
        if (header == null || header.isBlank()) {
            throw new ApiException(ErrorCode.PRECONDITION_REQUIRED);
        }
        String v = header.trim();
        if (v.startsWith("W/")) v = v.substring(2);
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
            v = v.substring(1, v.length() - 1);
        }
        try {
            long n = Long.parseLong(v);
            if (n < 0) throw new NumberFormatException();
            return n;
        } catch (NumberFormatException e) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Giá trị If-Match không hợp lệ");
        }
    }

    /** 5 -> "5" (dùng cho header ETag khi trả đơn về client). */
    public static String format(long version) {
        return "\"" + version + "\"";
    }
}