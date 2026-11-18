package io.github.izakyl.folkways.core.work.api;

public record ToolUse(ToolNeed need, int baseWear, boolean required) {

    public ToolUse(ToolNeed need, int baseWear) {
        this(need, baseWear, true);
    }

    // Useful for work that can still be done by hand; absence must not block planning or execution.
    public static ToolUse preferred(ToolNeed need) {
        return new ToolUse(need, 1, false);
    }

    public double wearFor(Worker who) {
        return baseWear;
    }

    public static ToolUse of(ToolNeed need) {
        return new ToolUse(need, 1);
    }
}
