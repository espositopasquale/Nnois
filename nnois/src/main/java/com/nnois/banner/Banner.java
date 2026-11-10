package com.nnois.banner;

import com.nnois.utils.TerminalColors;

/** Compact terminal wordmark shared by the interactive CLI and demos. */
public final class Banner {
    private static final int INNER_WIDTH = 68;
    private static final int BOX_WIDTH = INNER_WIDTH + 2;
    private static final String SUBTITLE = "Normalized  Natural-language  Orthographic  Index  of  Similarity";
    private static final String CYAN = "1;38;2;98;232;234";
    private static final String RED = "1;38;2;255;95;114";
    private static final String CREAM = "1;38;2;242;239;231";
    private static final String MUTED = "38;2;132;145;148";

    private Banner() { }

    public static String format() {
        boolean unicode = TerminalColors.unicodeEnabled();
        String indent = " ".repeat(Math.max(0, (terminalColumns() - BOX_WIDTH) / 2));
        String horizontal = unicode ? "─" : "-";
        String top = indent + (unicode ? "╭" : "+") + horizontal.repeat(INNER_WIDTH) + (unicode ? "╮" : "+");
        String middle = indent + (unicode ? "├" : "+") + horizontal.repeat(INNER_WIDTH) + (unicode ? "┤" : "+");
        String bottom = indent + (unicode ? "╰" : "+") + horizontal.repeat(INNER_WIDTH) + (unicode ? "╯" : "+");
        String side = indent + (unicode ? "│ " : "| ");
        String sideEnd = unicode ? " │" : " |";
        String[] logo = {
            " _   _             _       ",
            "| \\ | |_ __   ___ (_)___   ",
            "|  \\| | '_ \\ / _ \\| / __|  ",
            "| |\\  | | | | (_) | \\__ \\; ",
            "|_| \\_|_| |_|\\___/|_|___/  "
        };

        StringBuilder output = new StringBuilder("\n");
        output.append(TerminalColors.style(top, MUTED)).append('\n');
        for (int row = 0; row < logo.length; row++) {
            String color = row % 3 == 0 ? CYAN : row % 3 == 1 ? CREAM : RED;
            output.append(TerminalColors.style(side, MUTED))
                    .append(TerminalColors.style(center(logo[row], INNER_WIDTH), color))
                    .append(TerminalColors.style(sideEnd, MUTED)).append('\n');
        }

        int subtitleLeft = Math.max(0, (INNER_WIDTH - SUBTITLE.length()) / 2);
        int subtitleRight = Math.max(0, INNER_WIDTH - SUBTITLE.length() - subtitleLeft);
        output.append(TerminalColors.style(middle, MUTED)).append('\n')
                .append(TerminalColors.style(side + " ".repeat(subtitleLeft), MUTED))
                .append(TerminalColors.style("N", CYAN)).append(TerminalColors.style("ormalized  ", MUTED))
                .append(TerminalColors.style("N", RED)).append(TerminalColors.style("atural-language  ", MUTED))
                .append(TerminalColors.style("O", CREAM)).append(TerminalColors.style("rthographic  ", MUTED))
                .append(TerminalColors.style("I", CYAN)).append(TerminalColors.style("ndex  ", MUTED))
                .append(TerminalColors.style("o", RED)).append(TerminalColors.style("f  ", MUTED))
                .append(TerminalColors.style("S", CREAM)).append(TerminalColors.style("imilarity", MUTED))
                .append(TerminalColors.style(" ".repeat(subtitleRight) + sideEnd, MUTED)).append('\n')
                .append(TerminalColors.style(bottom, MUTED)).append('\n');
        return output.toString();
    }

    private static String center(String text, int width) {
        if (text.length() >= width) return text.substring(0, width);
        int left = (width - text.length()) / 2;
        return " ".repeat(left) + text + " ".repeat(width - text.length() - left);
    }

    private static int terminalColumns() {
        try {
            return Math.max(BOX_WIDTH, Math.min(240,
                    Integer.parseInt(System.getenv().getOrDefault("COLUMNS", "100"))));
        } catch (NumberFormatException ignored) {
            return 100;
        }
    }

    public static void print() {
        System.out.print(format());
    }

    public static void main(String[] args) {
        print();
    }
}
