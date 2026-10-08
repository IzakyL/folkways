package io.github.izakyl.folkways.plugins.dispatch;

import net.minecraft.core.BlockPos;

/**
 * A hidden address every package port answers to besides the one its owner typed, so the colony can send a
 * parcel to the pick-up point it chose whatever that point is called. Create routes by matching glob filters,
 * and its globs read {@code {a,b}} as either, so the port's filter becomes {@code {typed,alias}}.
 */
public final class PortAliases {

    private static final String PREFIX = "folkways-";

    private PortAliases() {
    }

    static String of(BlockPos port) {
        return PREFIX + port.getX() + "_" + port.getY() + "_" + port.getZ();
    }

    /**
     * The filter a port routes by: its own widened by its alias. A lone {@code *} is left alone, since Create ranks
     * catch-all ports last only while their filter is exactly that, and so is a filter with its own braces, commas or
     * escapes, which Create's globs cannot nest in a group.
     */
    public static String widen(String filter, BlockPos port) {
        if (filter == null || filter.trim().equals("*") || filter.chars().anyMatch(c -> "{},\\".indexOf(c) >= 0)) {
            return filter;
        }
        return "{" + filter + "," + of(port) + "}";
    }
}
