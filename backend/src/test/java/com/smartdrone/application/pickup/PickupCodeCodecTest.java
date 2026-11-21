package com.smartdrone.application.pickup;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.smartdrone.application.pickup.dto.ConfirmDeliveryRequest;
import com.smartdrone.application.pickup.dto.PickupCodeView;
import com.smartdrone.domain.entity.PickupChallenge;

class PickupCodeCodecTest {
    private final PickupCodeCodec codec = new PickupCodeCodec(
            new PickupProperties("unit-test-secret-at-least-32-characters!!", Duration.ofMinutes(30), 5, 6));

    private PickupChallenge challenge(UUID orderId, String nonce) {
        return new PickupChallenge(orderId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, nonce,
                Instant.now().plusSeconds(60), Instant.now());
    }

    @Test void otpIsSixDigitsStableAndVerifiable() {
        var c = challenge(UUID.randomUUID(), codec.newNonce());
        String otp = codec.otp(c);
        assertTrue(otp.matches("\\d{6}"));
        assertEquals(otp, codec.otp(c));
        assertTrue(codec.matchesOtp(c, otp));
        assertFalse(codec.matchesOtp(c, otp.equals("000000") ? "000001" : "000000"));
        assertFalse(codec.matchesOtp(c, null));
    }

    @Test void differentNonceGivesDifferentTokenAndQrDoesNotContainOrderId() {
        UUID orderId = UUID.randomUUID();
        var a = challenge(orderId, codec.newNonce());
        var b = challenge(orderId, codec.newNonce());
        assertNotEquals(codec.qrToken(a), codec.qrToken(b));
        assertFalse(codec.qrToken(a).contains(orderId.toString()));
        assertTrue(codec.matchesQr(a, codec.qrToken(a)));
        assertFalse(codec.matchesQr(a, codec.qrToken(b)));
    }

    @Test void secretsNeverAppearInToString() {
        var request = new ConfirmDeliveryRequest("123456", "token-abc", UUID.randomUUID(), null);
        assertFalse(request.toString().contains("123456"));
        assertFalse(request.toString().contains("token-abc"));
        var view = new PickupCodeView(UUID.randomUUID(), UUID.randomUUID(), "654321", "qr-secret", Instant.now());
        assertFalse(view.toString().contains("654321"));
        assertFalse(view.toString().contains("qr-secret"));
    }
}