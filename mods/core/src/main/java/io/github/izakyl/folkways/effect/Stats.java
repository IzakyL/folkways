package io.github.izakyl.folkways.effect;

import net.minecraft.resources.ResourceLocation;

// What the core reads off every resident. A plugin keeps its own stats and flags beside its content.
public final class Stats {

    // How fast work goes, against its declared ticks: 2 is done in half of them. Asked per trade.
    public static final Stat WORK_SPEED = new Stat(id("work_speed"), 1.0, 0.5, 10.0);

    // Times the body's walking speed.
    public static final Stat MOVE_SPEED = new Stat(id("move_speed"), 1.0, 0.05, 10.0);

    // Blocks of reach past the body's own.
    public static final Stat REACH = new Stat(id("reach"), 0.0, -4.0, 16.0);

    // Times the cells of the pack, which never shrinks below its own.
    public static final Stat PACK_ROOM = new Stat(id("pack_room"), 1.0, 1.0, 10.0);

    // Times the rate food is worn down at.
    public static final Stat APPETITE = new Stat(id("appetite"), 1.0, 0.0, 10.0);

    // Whether an urge that is not yet pressing may wait for the work in hand to finish.
    public static final Flag PATIENT = new Flag(id("patient"));

    public static final Stat TOOL_WEAR = new Stat(id("tool_wear"), 1.0, 0.0, 10.0);

    public static final Stat FATIGUE = new Stat(id("fatigue"), 1.0, 0.1, 10.0);

    // Share of negative food, housing and weather memories retained.
    public static final Stat DISCOMFORT = new Stat(id("discomfort"), 1.0, 0.0, 1.0);

    public static final Stat SOCIAL_RECOVERY = new Stat(id("social_recovery"), 1.0, 1.0, 5.0);

    private Stats() {
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("folkways", path);
    }
}
