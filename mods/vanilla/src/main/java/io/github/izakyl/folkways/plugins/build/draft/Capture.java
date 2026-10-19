package io.github.izakyl.folkways.plugins.build.draft;

import java.util.Map;
import net.starlark.java.eval.Dict;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Starlark;

/**
 * How much of the world around the mark a pattern reads: chunks across, and sub-chunks (16 cells) up and down from
 * those the mark stands in. What lies beyond is never taken, and reading it is refused.
 *
 * <pre>
 * capture = 2                                    # two chunks around; up and down as by default
 * capture = {"around": 1, "up": 2, "down": "all"}  # down to the bottom of the world
 * </pre>
 */
public record Capture(int around, int up, int down) {

    /** Up or down as far as the world goes. */
    public static final int ALL = Integer.MAX_VALUE;

    public static final Capture DEFAULT = new Capture(1, 6, 6);

    private static final String AROUND = "around";
    private static final String UP = "up";
    private static final String DOWN = "down";

    public Capture {
        if (around < 0 || up < 0 || down < 0) {
            throw new IllegalArgumentException("a pattern captures no less than nothing");
        }
    }

    static Capture parse(Object declared) throws EvalException {
        if (declared == null) {
            return DEFAULT;
        }
        if (!(declared instanceof Dict<?, ?> dict)) {
            return new Capture(count(declared, AROUND), DEFAULT.up(), DEFAULT.down());
        }
        int around = DEFAULT.around();
        int up = DEFAULT.up();
        int down = DEFAULT.down();
        for (Map.Entry<?, ?> entry : dict.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw Starlark.errorf("capture's keys are 'around', 'up' and 'down'");
            }
            switch (key) {
                case AROUND -> around = count(entry.getValue(), AROUND);
                case UP -> up = reach(entry.getValue(), UP);
                case DOWN -> down = reach(entry.getValue(), DOWN);
                default -> throw Starlark.errorf("capture's keys are 'around', 'up' and 'down', not '%s'", key);
            }
        }
        return new Capture(around, up, down);
    }

    private static int reach(Object written, String what) throws EvalException {
        return "all".equals(written) ? ALL : count(written, what);
    }

    private static int count(Object written, String what) throws EvalException {
        int count = Starlark.toInt(written, "capture " + what);
        if (count < 0) {
            throw Starlark.errorf("capture %s is a count of chunks, got %d", what, count);
        }
        return count;
    }
}
