package com.smartdrone.application.ai;

import java.util.regex.Pattern;

/**
 * Che dữ liệu nhạy cảm trước khi gửi ra nhà cung cấp bên ngoài. Token/khóa luôn bị che.
 * Email, số điện thoại và dãy số dài (mã nhận hàng, OTP) chỉ được giữ khi smartdrone.ai.send-personal-data=true.
 */
public final class PersonalDataRedactor {
    private PersonalDataRedactor() { }

    private static final Pattern BEARER = Pattern.compile("(?i)bearer\\s+[A-Za-z0-9._~+/=-]+");
    private static final Pattern JWT = Pattern.compile("eyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]*");
    private static final Pattern API_KEY_LIKE = Pattern.compile("\\b[A-Za-z0-9_-]{32,}\\b");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern PHONE = Pattern.compile("(?<![\\w])\\+?\\d[\\d\\s().-]{6,}\\d");
    private static final Pattern LONG_DIGITS = Pattern.compile("\\d{5,}");

    public static String redact(String text, boolean keepPersonalData) {
        if (text == null || text.isEmpty()) return "";
        String s = BEARER.matcher(text).replaceAll("[token]");
        s = JWT.matcher(s).replaceAll("[token]");
        s = API_KEY_LIKE.matcher(s).replaceAll("[token]");
        if (!keepPersonalData) {
            s = EMAIL.matcher(s).replaceAll("[email]");
            s = PHONE.matcher(s).replaceAll("[phone]");
            s = LONG_DIGITS.matcher(s).replaceAll("[number]");
        }
        return s;
    }
}
