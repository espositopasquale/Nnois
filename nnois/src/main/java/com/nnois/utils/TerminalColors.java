package com.nnois.utils;

/** Shared terminal color policy for the banner and CLI reports. */
public final class TerminalColors {
    private TerminalColors() { }
    public static boolean enabled() {
        if (System.getenv("NO_COLOR") != null || "dumb".equals(System.getenv("TERM"))) return false;
        String forced = System.getenv("FORCE_COLOR");
        return forced != null ? !forced.equals("0") : System.console() != null;
    }
    public static String style(String text, String code) {
        return enabled() ? "\u001B[" + code + "m" + text + "\u001B[0m" : text;
    }
    public static String scoreColor(double score) {
        return score >= 0.7 ? "1;92" : score >= 0.4 ? "1;93" : "1;95";
    }
}
