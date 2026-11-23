package gr.pnet.camara.common.support;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps subscriber identifiers out of logs.
 */
public final class Masking {

    // E.164 numbers, raw or URL-encoded ("%2B"), as they appear in messages and TEP URLs
    private static final Pattern MSISDN = Pattern.compile("(\\+|%2B)(\\d{4})(\\d+)(\\d{2})");

    private Masking() {
    }

    /** {@code +306912345678} becomes {@code +3069******78}. */
    public static String msisdn(String value) {
        return value == null ? null : maskAll(value);
    }

    /** Masks every phone number found in free text, e.g. an exception message. */
    public static String maskAll(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = MSISDN.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String masked = m.group(1) + m.group(2) + "*".repeat(m.group(3).length()) + m.group(4);
            m.appendReplacement(out, Matcher.quoteReplacement(masked));
        }
        m.appendTail(out);
        return out.toString();
    }
}
