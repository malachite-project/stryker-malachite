package com.zalexdev.stryker.wifi;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** Decodes iw SSID hex escapes as text without invoking a shell. */
public final class SsidDecoder {
    private SsidDecoder() {}
    public static String decode(String input) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int i = 0; i < input.length();) {
            if (i + 3 < input.length() && input.charAt(i) == '\\' && input.charAt(i + 1) == 'x') {
                int hi = Character.digit(input.charAt(i + 2), 16);
                int lo = Character.digit(input.charAt(i + 3), 16);
                if (hi >= 0 && lo >= 0) { bytes.write((hi << 4) | lo); i += 4; continue; }
            }
            int cp = input.codePointAt(i);
            byte[] encoded = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8);
            bytes.write(encoded, 0, encoded.length);
            i += Character.charCount(cp);
        }
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }
}
