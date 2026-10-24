package io.github.izakyl.folkways.war;

import java.util.List;
import net.neoforged.neoforge.common.ModConfigSpec;

public final class WarConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();
    static final ModConfigSpec.BooleanValue ENABLED = BUILDER.define("enabled", true);
    static final ModConfigSpec.IntValue MIN_RESIDENTS = BUILDER.defineInRange("minimumResidents", 4, 1, 1024);
    static final ModConfigSpec.IntValue GRACE = BUILDER.defineInRange("graceTicks", 7 * 24000, 0, Integer.MAX_VALUE);
    static final ModConfigSpec.IntValue INTERVAL_MIN = BUILDER.defineInRange("intervalMinTicks", 5 * 24000, 1200, Integer.MAX_VALUE);
    static final ModConfigSpec.IntValue INTERVAL_MAX = BUILDER.defineInRange("intervalMaxTicks", 8 * 24000, 1200, Integer.MAX_VALUE);
    static final ModConfigSpec.IntValue WARNING = BUILDER.defineInRange("warningTicks", 2400, 0, 24000);
    static final ModConfigSpec.IntValue TIMEOUT = BUILDER.defineInRange("timeoutTicks", 24000, 200, 240000);
    static final ModConfigSpec.IntValue MAX_RAIDS = BUILDER.defineInRange("maximumConcurrentRaids", 2, 1, 16);
    static final ModConfigSpec.IntValue MAX_MEMBERS = BUILDER.defineInRange("maximumRaiders", 64, 1, 256);
    static final ModConfigSpec.ConfigValue<List<? extends String>> DISABLED_DIMENSIONS = BUILDER.defineListAllowEmpty(
        "disabledDimensions", List.of("minecraft:the_end"), () -> "minecraft:the_end", value -> value instanceof String);
    static final ModConfigSpec.IntValue BREAKS = BUILDER.defineInRange("maximumBrokenBlocks", 96, 0, 4096);
    static final ModConfigSpec.DoubleValue HARDNESS = BUILDER.defineInRange("maximumBreachHardness", 5D, 0D, 49D);
    public static final ModConfigSpec SPEC = BUILDER.build();

    private WarConfig() { }
}
