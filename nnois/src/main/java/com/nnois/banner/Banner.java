package com.nnois.banner;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.nnois.utils.TerminalColors;

public class Banner {

    private static final String RESET = "\u001B[0m";
    private static final String BOLD = "\u001B[1m";
    private static final String RED = "\u001B[91m";
    private static final String CYAN = "\u001B[38;2;85;187;255m";
    private static final String WHITE = "\u001B[97m";
    private static final String DARK_GRAY = "\u001B[90m";

    private static final int WIDTH = 86;
    private static final int OFFSET = 1;

    private static final String SUBTITLE = "Normalized Natural-language Orthographic Index of Similarity";

    private static final String[] ASCII_LOGO = new String[]{
        "░███    ░██                        ░██            ",
        "░████   ░██                                       ",
        "░██░██  ░██ ░████████   ░███████   ░██ ░███████   ",
        "░██ ░██ ░██ ░██    ░██ ░██    ░██  ░██░██         ",
        "░██  ░██░██ ░██    ░██ ░██    ░██  ░██ ░███████   ",
        "░██   ░████ ░██    ░██ ░██    ░██  ░██        ░██ ",
        "░██    ░███ ░██    ░██  ░███████   ░██ ░███████   ",
        "                                                  "
    };

    public static String format() {
        String styledSubtitle = center(formatSubtitleAcronym(SUBTITLE), WIDTH, SUBTITLE.length());

        StringBuilder logoBuilder = new StringBuilder();
        for (String line : ASCII_LOGO) {
            logoBuilder.append(applyAnaglyph(center(line, WIDTH, line.length()))).append("\n");
        }

        String banner = new StringBuilder()
                .append("\n")
                .append(logoBuilder)
                .append(styledSubtitle).append("\n")
                .toString();
        return TerminalColors.enabled() ? banner : banner.replaceAll("\u001B\\[[0-9;]*m", "");
    }

    public static void print() {
        System.out.println(format());
    }

    private static String formatSubtitleAcronym(String text) {
        String[] anaglyphColors = {RED, WHITE, CYAN};
        Matcher matcher = Pattern.compile("\\b([A-Za-z])(\\w*)").matcher(text);
        StringBuilder sb = new StringBuilder();
        int colorIndex = 0;

        while (matcher.find()) {
            String firstLetter = matcher.group(1);
            String restOfWord = matcher.group(2);

            String color = anaglyphColors[colorIndex % anaglyphColors.length];
            colorIndex++;

            String replacement = BOLD + color + firstLetter + RESET
                    + BOLD + DARK_GRAY + restOfWord + RESET;
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private static String applyAnaglyph(String line) {
        StringBuilder result = new StringBuilder();
        int len = line.length();

        for (int i = 0; i < len; i++) {
            int redSourceIndex = i + OFFSET;
            int cyanSourceIndex = i - OFFSET;

            char redChar = (redSourceIndex >= 0 && redSourceIndex < len) ? line.charAt(redSourceIndex) : ' ';
            char cyanChar = (cyanSourceIndex >= 0 && cyanSourceIndex < len) ? line.charAt(cyanSourceIndex) : ' ';

            boolean hasRed = redChar != ' ';
            boolean hasCyan = cyanChar != ' ';

            if (hasRed && hasCyan) {
                result.append(BOLD).append(WHITE).append(line.charAt(i) != ' ' ? line.charAt(i) : redChar).append(RESET);
            } else if (hasRed) {
                result.append(BOLD).append(RED).append(redChar).append(RESET);
            } else if (hasCyan) {
                result.append(BOLD).append(CYAN).append(cyanChar).append(RESET);
            } else {
                result.append(' ');
            }
        }
        return result.toString();
    }

    private static String center(String formattedText, int targetWidth, int visibleLength) {
        if (visibleLength >= targetWidth) {
            return formattedText;
        }
        int padding = targetWidth - visibleLength;
        int left = padding / 2;
        int right = padding - left;
        return " ".repeat(left) + formattedText + " ".repeat(right);
    }

    private static String colorize(String text, String color) {
        return BOLD + color + text + RESET;
    }

    public static void main(String[] args) {
        print();
    }
}
