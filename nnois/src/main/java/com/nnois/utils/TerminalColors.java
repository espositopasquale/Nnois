package com.nnois.utils;

import java.nio.charset.Charset;
import java.util.Locale;

public final class TerminalColors {
    private TerminalColors() { }
    public static boolean enabled() {
        if (System.getenv("NO_COLOR") != null || "dumb".equals(System.getenv("TERM"))) return false;
        String forced = System.getenv("FORCE_COLOR");
        return forced != null ? !forced.equals("0") : System.console() != null;
    }
    public static boolean unicodeEnabled() {
        String forced = System.getenv("NNOIS_UNICODE");
        if (forced != null) return !forced.equals("0");
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) return false;
        Charset charset = System.console() == null ? Charset.defaultCharset() : System.console().charset();
        String name = charset.name().toLowerCase(Locale.ROOT).replace("-", "");
        return name.equals("utf8") || name.equals("utf16") || name.equals("utf16le") || name.equals("utf16be");
    }
    public static String style(String text, String code) {
        return enabled() ? "\u001B[" + code + "m" + text + "\u001B[0m" : text;
    }
    public static String scoreColor(double score) {
        return score >= 0.7 ? "1;92" : score >= 0.4 ? "1;93" : "1;95";
    }
}
