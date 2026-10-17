package io.github.izakyl.folkways.core.api.work;

public record ToolUse(ToolNeed need, int baseWear) {

    public double wearFor(Worker who) {
        return baseWear;
    }

    public static ToolUse of(ToolNeed need) {
        return new ToolUse(need, 1);
    }
}
