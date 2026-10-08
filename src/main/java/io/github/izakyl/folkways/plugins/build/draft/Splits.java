package io.github.izakyl.folkways.plugins.build.draft;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.starlark.java.annot.StarlarkBuiltin;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkValue;

final class Splits {

    private Splits() {
    }

    sealed interface Share {

        record Fixed(int cells) implements Share {}

        record Relative(double fraction) implements Share {}

        record Floating(double weight) implements Share {}

        record Repeat(int unit, boolean floating, Share gap, boolean edges) implements Share {}
    }

    enum Rest { ERROR, START, CENTER, END }

    record Cut(int size, String name, boolean gap) {}

    record Layout(int lead, List<Cut> cuts) {}

    @StarlarkBuiltin(name = "repeat", doc = "A share that tiles what is left over with whole units.")
    record RepeatValue(Object unit, Object gap, boolean edges) implements StarlarkValue {

        Share.Repeat on(int axis) throws EvalException {
            Share measured = Splits.unit(unit, axis);
            Share between = gap == null || gap == Starlark.NONE ? null : Splits.unit(gap, axis);
            return measured instanceof Share.Fixed fixed
                ? new Share.Repeat(fixed.cells(), false, between, edges)
                : new Share.Repeat(Math.max(1, (int) Math.round(((Share.Floating) measured).weight())), true,
                    between, edges);
        }

        @Override
        public boolean isImmutable() {
            return true;
        }
    }

    static Share parse(Object written, int axis) throws EvalException {
        if (written instanceof Piece piece) {
            return new Share.Fixed(piece.span(axis));
        }
        if (written instanceof RepeatValue repeat) {
            return repeat.on(axis);
        }
        if (written instanceof net.starlark.java.eval.StarlarkInt cells) {
            return new Share.Fixed(whole(Integer.toString(cells.toInt("share")), String.valueOf(written)));
        }
        if (!(written instanceof String)) {
            throw Starlark.errorf("a share is a string like '3', '0.5r', '~1', '4*' or '~4*', a piece, or"
                + " repeat(...); got %s", Starlark.type(written));
        }
        String text = ((String) written).trim();
        if (text.isEmpty()) {
            throw Starlark.errorf("a share is written '3', '0.5r', '~1', '4*' or '~4*', got an empty string");
        }
        try {
            if (text.endsWith("*")) {
                String unit = text.substring(0, text.length() - 1).trim();
                boolean floating = unit.startsWith("~");
                return new Share.Repeat(whole(floating ? unit.substring(1) : unit, text), floating, null, false);
            }
            if (text.startsWith("~")) {
                String weight = text.substring(1);
                double parsed = weight.isEmpty() ? 1.0D : Double.parseDouble(weight);
                if (parsed <= 0) {
                    throw Starlark.errorf("a floating share weighs more than nothing, got '%s'", text);
                }
                return new Share.Floating(parsed);
            }
            if (text.toLowerCase(Locale.ROOT).endsWith("r")) {
                return new Share.Relative(Double.parseDouble(text.substring(0, text.length() - 1)));
            }
            return new Share.Fixed(whole(text, text));
        } catch (NumberFormatException unreadable) {
            throw Starlark.errorf("a share is written '3', '0.5r', '~1', '4*' or '~4*', got '%s'", text);
        }
    }

    static Share unit(Object written, int axis) throws EvalException {
        Share share = parse(written, axis);
        if (share instanceof Share.Fixed || share instanceof Share.Floating) {
            return share;
        }
        throw Starlark.errorf("a repeated unit or gap is '4', '~4' or a piece, got '%s'", written);
    }

    static Rest rest(String written) throws EvalException {
        return switch (written.toLowerCase(Locale.ROOT)) {
            case "error" -> Rest.ERROR;
            case "drop", "start" -> Rest.START;
            case "center" -> Rest.CENTER;
            case "end" -> Rest.END;
            default -> throw Starlark.errorf("rest is 'error', 'drop', 'start', 'center' or 'end', not '%s'",
                written);
        };
    }

    private static int whole(String text, String written) throws EvalException {
        int cells;
        try {
            cells = Integer.parseInt(text.trim());
        } catch (NumberFormatException unreadable) {
            throw Starlark.errorf("a share is written '3', '0.5r', '~1', '4*' or '~4*', got '%s'", written);
        }
        if (cells < 1) {
            throw Starlark.errorf("a share measures at least one cell, got '%s'", written);
        }
        return cells;
    }

    static Layout resolve(int span, List<Share> shares, List<String> names, Rest rest, boolean symmetric)
            throws EvalException {
        if (names != null && names.size() != shares.size()) {
            throw Starlark.errorf("this split names %d shares and has %d", names.size(), shares.size());
        }
        int fixed = 0;
        int repeats = 0;
        List<Integer> floats = new ArrayList<>();
        for (int index = 0; index < shares.size(); index++) {
            switch (shares.get(index)) {
                case Share.Fixed each -> fixed += each.cells();
                case Share.Relative each -> fixed += (int) Math.floor(each.fraction() * span);
                case Share.Floating ignored -> floats.add(index);
                case Share.Repeat ignored -> repeats++;
            }
        }
        if (repeats > 1) {
            throw Starlark.errorf("one split takes at most one repeated share, got %d", repeats);
        }
        if (repeats == 1 && !floats.isEmpty()) {
            throw Starlark.errorf("a repeated share and a floating share both claim what is left over;"
                + " use one or the other");
        }
        if (symmetric && !palindrome(shares)) {
            throw Starlark.errorf("a symmetric split reads the same from both ends, and these shares do not");
        }
        int remaining = span - fixed;
        if (remaining < 0) {
            throw Starlark.errorf("this split asks for %d cells and the axis has %d", fixed, span);
        }

        List<List<Cut>> made = new ArrayList<>(shares.size());
        int leftover = 0;
        int[] floatSizes = floats.isEmpty() ? new int[0] : weigh(remaining, shares, floats, symmetric);
        int floatSeen = 0;
        for (int index = 0; index < shares.size(); index++) {
            String name = names == null ? "" : names.get(index);
            List<Cut> cuts = new ArrayList<>();
            switch (shares.get(index)) {
                case Share.Fixed each -> cuts.add(new Cut(each.cells(), name, false));
                case Share.Relative each -> cuts.add(new Cut((int) Math.floor(each.fraction() * span), name, false));
                case Share.Floating ignored -> cuts.add(new Cut(floatSizes[floatSeen++], name, false));
                case Share.Repeat each -> leftover = tile(remaining, each, name, cuts);
            }
            made.add(cuts);
        }
        if (floats.isEmpty() && repeats == 0) {
            leftover = remaining;
        }

        int lead = 0;
        if (leftover > 0) {
            Rest effective = symmetric ? Rest.CENTER : rest;
            lead = switch (effective) {
                case ERROR -> throw Starlark.errorf("this split leaves %d of %d cells unassigned; add a '~'"
                    + " share, or pass rest = 'drop', 'center' or 'end'", leftover, span);
                case START -> 0;
                case END -> leftover;
                case CENTER -> {
                    if (symmetric && leftover % 2 != 0) {
                        throw Starlark.errorf("a symmetric split cannot centre the %d cell(s) it leaves over on"
                            + " %d cells", leftover, span);
                    }
                    yield leftover / 2;
                }
            };
        }

        List<Cut> all = new ArrayList<>();
        for (int index = 0; index < made.size(); index++) {
            for (Cut cut : made.get(index)) {
                if (cut.size() < 1) {
                    throw Starlark.errorf("share %d of this split came out at nothing: %d cells do not divide"
                        + " this way", index, span);
                }
                all.add(cut);
            }
        }
        return new Layout(lead, all);
    }

    private static int tile(int remaining, Share.Repeat repeat, String name, List<Cut> into) throws EvalException {
        int unit = repeat.unit();
        Share gap = repeat.gap();
        if (gap == null) {
            if (repeat.floating()) {
                int count = Math.max(1, (int) Math.round((double) remaining / unit));
                for (int size : spread(remaining, count)) {
                    into.add(new Cut(size, name, false));
                }
                return 0;
            }
            int count = remaining / unit;
            if (count == 0) {
                throw Starlark.errorf("a repeated share of %d cells does not fit in the %d left over", unit,
                    remaining);
            }
            for (int copy = 0; copy < count; copy++) {
                into.add(new Cut(unit, name, false));
            }
            return remaining - count * unit;
        }

        boolean gapFloats = gap instanceof Share.Floating;
        int gapCells = gap instanceof Share.Fixed fixed ? fixed.cells()
            : Math.max(1, (int) Math.round(((Share.Floating) gap).weight()));
        int pitch = unit + gapCells;
        double fits = repeat.edges() ? (double) (remaining - gapCells) / pitch : (double) (remaining + gapCells) / pitch;
        int count = repeat.floating() && !gapFloats ? Math.max(1, (int) Math.round(fits)) : (int) Math.floor(fits);
        if (count < 1) {
            throw Starlark.errorf("not one repeated unit of %d cells, with its gaps of %d, fits in %d", unit,
                gapCells, remaining);
        }
        int gaps = repeat.edges() ? count + 1 : count - 1;
        int[] unitSizes;
        int[] gapSizes;
        int leftover = 0;
        if (gapFloats) {
            unitSizes = filled(count, unit);
            gapSizes = gaps == 0 ? new int[0] : spread(remaining - count * unit, gaps);
            if (gaps == 0) {
                leftover = remaining - unit;
            }
        } else if (repeat.floating()) {
            gapSizes = filled(gaps, gapCells);
            unitSizes = spread(remaining - gaps * gapCells, count);
        } else {
            unitSizes = filled(count, unit);
            gapSizes = filled(gaps, gapCells);
            leftover = remaining - count * unit - gaps * gapCells;
        }
        int gapAt = 0;
        if (repeat.edges()) {
            into.add(new Cut(gapSizes[gapAt++], "gap", true));
        }
        for (int copy = 0; copy < count; copy++) {
            into.add(new Cut(unitSizes[copy], name, false));
            if (gapAt < gapSizes.length && (repeat.edges() || copy < count - 1)) {
                into.add(new Cut(gapSizes[gapAt++], "gap", true));
            }
        }
        return leftover;
    }

    private static int[] weigh(int remaining, List<Share> shares, List<Integer> floats, boolean symmetric)
            throws EvalException {
        int count = floats.size();
        double total = 0.0D;
        for (int index : floats) {
            total += ((Share.Floating) shares.get(index)).weight();
        }
        int[] sizes = new int[count];
        double[] fraction = new double[count];
        int given = 0;
        for (int at = 0; at < count; at++) {
            double exact = ((Share.Floating) shares.get(floats.get(at))).weight() / total * remaining;
            sizes[at] = (int) Math.floor(exact);
            fraction[at] = exact - sizes[at];
            given += sizes[at];
        }
        int left = remaining - given;
        if (!symmetric) {
            while (left-- > 0) {
                int best = 0;
                for (int at = 1; at < count; at++) {
                    if (fraction[at] > fraction[best]) {
                        best = at;
                    }
                }
                sizes[best]++;
                fraction[best] = -1.0D;
            }
            return sizes;
        }
        int middle = count % 2 == 1 ? count / 2 : -1;
        if (left % 2 == 1) {
            if (middle < 0) {
                throw Starlark.errorf("a symmetric split has one cell it cannot share evenly: no share stands in"
                    + " the middle to take it");
            }
            sizes[middle]++;
            left--;
        }
        while (left > 0) {
            int best = -1;
            for (int at = 0; at <= (count - 1) / 2; at++) {
                if (best < 0 || fraction[at] > fraction[best]) {
                    best = at;
                }
            }
            if (best == middle) {
                sizes[middle] += 2;
            } else {
                sizes[best]++;
                sizes[count - 1 - best]++;
            }
            fraction[best] = -1.0D;
            left -= 2;
        }
        return sizes;
    }

    static int[] spread(int total, int count) {
        int[] sizes = filled(count, total / count);
        int extra = total % count;
        int low = (count - 1) / 2;
        int high = count / 2;
        while (extra > 0) {
            if (low == high) {
                sizes[low]++;
                extra--;
            } else if (extra >= 2) {
                sizes[low]++;
                sizes[high]++;
                extra -= 2;
            } else {
                sizes[low]++;
                extra--;
            }
            low--;
            high++;
            if (low < 0) {
                low = (count - 1) / 2;
                high = count / 2;
            }
        }
        return sizes;
    }

    private static int[] filled(int count, int value) {
        int[] sizes = new int[count];
        java.util.Arrays.fill(sizes, value);
        return sizes;
    }

    private static boolean palindrome(List<Share> shares) {
        for (int at = 0, back = shares.size() - 1; at < back; at++, back--) {
            if (!shares.get(at).equals(shares.get(back))) {
                return false;
            }
        }
        return true;
    }
}
