package com.smartdrone.application.pickup;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

import com.smartdrone.domain.entity.PickupChallenge;

/** Sinh và kiểm tra OTP/QR từ HMAC. DB chỉ có nonce, nên lộ DB không lộ mã. Không bao giờ ghi mã vào log. */
@Component
public class PickupCodeCodec {
    private static final SecureRandom RANDOM = new SecureRandom();

    private final byte[] key;
    private final int digits;

    public PickupCodeCodec(PickupProperties props) {
        this.key = props.secret().getBytes(StandardCharsets.UTF_8);
        this.digits = props.otpDigits();
    }

    public String newNonce() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    public String otp(PickupChallenge c) {
        byte[] mac = hmac("otp|" + c.getId() + "|" + c.getNonce());
        int value = ByteBuffer.wrap(mac, 0, 4).getInt() & 0x7fffffff;
        int modulus = (int) Math.pow(10, digits);
        return String.format("%0" + digits + "d", value % modulus);
    }

    /** challengeId.chữ-ký: không chứa orderId nên không thể đoán hay dựng từ mã đơn. */
    public String qrToken(PickupChallenge c) {
        byte[] mac = hmac("qr|" + c.getId() + "|" + c.getNonce());
        return c.getId() + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(mac);
    }

    public boolean matchesOtp(PickupChallenge c, String candidate) {
        return candidate != null && constantTimeEquals(otp(c), candidate);
    }

    public boolean matchesQr(PickupChallenge c, String candidate) {
        return candidate != null && constantTimeEquals(qrToken(c), candidate);
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    private byte[] hmac(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("JVM không hỗ trợ HmacSHA256", e);
        }
    }
}