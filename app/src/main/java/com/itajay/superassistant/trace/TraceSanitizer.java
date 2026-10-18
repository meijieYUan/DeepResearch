package com.itajay.superassistant.trace;

import java.util.regex.Pattern;

/** Turns tool arguments/results into short, secret-free text for the trace. */
public final class TraceSanitizer {

    private static final Pattern SECRET = Pattern.compile(
            "(?i)(\"?(?:password|passwd|token|secret|authorization|api[_-]?key)\"?\\s*[:=]\\s*)"
                    + "(\"[^\"]*\"|[^,\\s}]+)");

    private TraceSanitizer() {
    }

    public static String summarize(String raw, int max) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String redacted = SECRET.matcher(raw).replaceAll("$1\"***\"");
        String flat = redacted.replaceAll("\\s+", " ").strip();
        return flat.length() <= max ? flat : flat.substring(0, max) + "…";
    }

    public static String error(Throwable error, int max) {
        if (error == null) {
            return "unknown error";
        }
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            message = error.getClass().getSimpleName();
        }
        return summarize(error.getClass().getSimpleName() + ": " + message, max);
    }
}
