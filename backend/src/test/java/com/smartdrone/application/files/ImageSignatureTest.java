package com.smartdrone.application.files;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ImageSignatureTest {
    @Test
    void detectsRealImageTypes() {
        assertEquals("image/jpeg", ImageSignature.detect(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00}));
        assertEquals("image/png", ImageSignature.detect(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A}));
        assertEquals("image/webp", ImageSignature.detect("RIFF\0\0\0\0WEBP".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1)));
    }

    @Test
    void rejectsNonImagesEvenIfClientClaimsImage() {
        assertNull(ImageSignature.detect("<html><script>alert(1)</script>".getBytes()));
        assertNull(ImageSignature.detect("MZ\u0090\u0000".getBytes()));
        assertNull(ImageSignature.detect(new byte[0]));
        assertNull(ImageSignature.detect(null));
    }
}