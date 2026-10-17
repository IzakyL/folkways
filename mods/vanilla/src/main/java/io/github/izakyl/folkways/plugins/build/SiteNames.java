package io.github.izakyl.folkways.plugins.build;

/** The name a player gives a building site when they hand it to the colony; a site left unnamed is numbered. */
public final class SiteNames {

    public static final int MOST = 32;

    // A name's length on the wire, in UTF-16 units: every character it keeps may take two.
    static final int WIRE = MOST * 2;

    private SiteNames() {
    }

    /** What was typed, without control characters or the spaces around it, cut to the longest a name may run. */
    public static String typed(String raw) {
        if (raw == null) {
            return "";
        }
        String kept = raw.codePoints()
            .filter(c -> !Character.isISOControl(c))
            .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
            .toString()
            .trim();
        return kept.codePointCount(0, kept.length()) > MOST
            ? kept.substring(0, kept.offsetByCodePoints(0, MOST)).trim()
            : kept;
    }

    static String numbered(int number) {
        return "#" + number;
    }
}
