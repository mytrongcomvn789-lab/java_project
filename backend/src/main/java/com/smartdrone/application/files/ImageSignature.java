package com.smartdrone.application.files;

public final class ImageSignature {
    public static final int HEAD_BYTES = 12;

    private ImageSignature() { }

    // Trả về content-type thật, hoặc null nếu không phải JPEG/PNG/WebP.
    public static String detect(byte[] h) {
        if (h == null) return null;
        if (h.length >= 3 && u(h[0]) == 0xFF && u(h[1]) == 0xD8 && u(h[2]) == 0xFF) return "image/jpeg";
        if (h.length >= 8 && u(h[0]) == 0x89 && h[1] == 'P' && h[2] == 'N' && h[3] == 'G'
                && u(h[4]) == 0x0D && u(h[5]) == 0x0A && u(h[6]) == 0x1A && u(h[7]) == 0x0A) return "image/png";
        if (h.length >= 12 && h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F'
                && h[8] == 'W' && h[9] == 'E' && h[10] == 'B' && h[11] == 'P') return "image/webp";
        return null;
    }

    private static int u(byte b) { return b & 0xFF; }
}