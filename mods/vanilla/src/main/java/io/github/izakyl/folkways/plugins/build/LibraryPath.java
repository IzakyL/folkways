package io.github.izakyl.folkways.plugins.build;

import java.util.List;
import java.util.Optional;

public record LibraryPath(List<String> segments) {

    public static final int MAX_SEGMENT = 64;
    public static final int MAX_DEPTH = 8;

    public LibraryPath {
        segments = List.copyOf(segments);
    }

    public static Optional<LibraryPath> of(String text) {
        if (text == null || text.isBlank() || text.startsWith("/") || text.startsWith("\\")
            || text.contains(":")) {
            return Optional.empty();
        }
        List<String> segments = List.of(text.split("[/\\\\]"));
        if (segments.isEmpty() || segments.size() > MAX_DEPTH) {
            return Optional.empty();
        }
        for (String segment : segments) {
            if (!isPlainSegment(segment)) {
                return Optional.empty();
            }
        }
        return Optional.of(new LibraryPath(segments));
    }

    private static boolean isPlainSegment(String segment) {
        if (segment.isEmpty() || segment.length() > MAX_SEGMENT
            || segment.equals(".") || segment.equals("..")) {
            return false;
        }
        for (int i = 0; i < segment.length(); i++) {
            char ch = segment.charAt(i);
            boolean plain = ch == '_' || ch == '-' || ch == '.' || ch == ' '
                || (ch >= '0' && ch <= '9')
                || (ch >= 'a' && ch <= 'z')
                || (ch >= 'A' && ch <= 'Z');
            if (!plain) {
                return false;
            }
        }
        return !segment.chars().allMatch(ch -> ch == '.' || ch == ' ');
    }

    public String name() {
        return segments.getLast();
    }
}
