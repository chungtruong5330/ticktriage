package dev.ticktriage.core;

/**
 * Just enough JSON to post a Discord webhook.
 *
 * <p>Pulling in Gson for one string field would be silly, but hand-rolled
 * escaping is a classic way to ship an injection bug: diagnosis headlines are
 * built from world names, entity types and player-influenced content, so they
 * are untrusted input being placed inside a JSON document.
 *
 * <p>Everything outside printable ASCII is emitted as a {@code \\uXXXX} escape,
 * which is valid JSON and sidesteps every encoding question at the same time.
 */
public final class Json {

    private Json() {
    }

    public static String escape(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(raw.length() + 16);
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                default:
                    if (c < 0x20 || c > 0x7E) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    /** A one-field object, which is all a Discord webhook needs. */
    public static String object(String key, String value) {
        return "{\"" + escape(key) + "\":\"" + escape(value) + "\"}";
    }

    /**
     * Discord rejects messages over 2000 characters outright, so a long report
     * has to be cut rather than dropped.
     */
    public static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        if (text.length() <= max) {
            return text;
        }
        String suffix = "\n... (truncated)";
        if (max <= suffix.length()) {
            return text.substring(0, max);
        }
        return text.substring(0, max - suffix.length()) + suffix;
    }
}
