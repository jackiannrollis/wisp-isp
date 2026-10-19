package com.corebalt.isp.notifications.domain;

/**
 * How many SMS parts a text costs, so tenants see when an edit doubles their bill. GSM-7 (3GPP TS
 * 23.038 default alphabet; extension characters count twice): 160 characters in one part, 153 per
 * part when split. Any other character makes the whole message UCS-2: 70, or 67 per part.
 */
public record SmsSegments(String encoding, int length, int segments) {

    private static final String GSM7_BASIC =
            "@£$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?"
                    + "¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà";
    private static final String GSM7_EXTENSION = "^{}\\[~]|€\f";

    public static SmsSegments of(final String text) {
        int septets = 0;
        boolean gsm = true;
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            if (GSM7_BASIC.indexOf(c) >= 0) {
                septets += 1;
            } else if (GSM7_EXTENSION.indexOf(c) >= 0) {
                septets += 2;
            } else {
                gsm = false;
                break;
            }
        }
        if (gsm) {
            return new SmsSegments("GSM-7", septets, septets <= 160 ? 1 : (septets + 152) / 153);
        }
        final int units = text.length();
        return new SmsSegments("UCS-2", units, units <= 70 ? 1 : (units + 66) / 67);
    }
}
