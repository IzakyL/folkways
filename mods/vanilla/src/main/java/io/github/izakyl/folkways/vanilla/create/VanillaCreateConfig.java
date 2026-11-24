package io.github.izakyl.folkways.vanilla.create;

import io.github.izakyl.folkways.core.FolkwaysConfig;
import io.github.izakyl.folkways.core.base.persist.upgrade.ConfigRenames;
import java.util.List;
import java.util.function.Predicate;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;

// The conductor's and courier's quick hands and their wind-ups.
public final class VanillaCreateConfig {

    private static final String CONFIG = "folkways_vanilla-create-server.toml";

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    private static final Predicate<Object> HASTE_STEP = FolkwaysConfig.HASTE_STEP;

    private static final ModConfigSpec.ConfigValue<List<? extends Number>> CONDUCTING_HASTE;
    private static final ModConfigSpec.ConfigValue<List<? extends Number>> DISPATCH_HASTE;
    private static final ModConfigSpec.IntValue DISPATCH_REACH_TICKS;

    public static final ModConfigSpec SPEC;

    static {

        BUILDER.push("perks");
        CONDUCTING_HASTE = BUILDER
            .comment("The conductor's quick hands, as a multiplier on the wind-up of boarding and the rest",
                "of the rail work. One entry per rank, below 1 for faster.")
            .<Number>defineList("conductingHaste", List.of(0.92D, 0.84D, 0.76D, 0.68D), () -> 1.0D, HASTE_STEP);
        DISPATCH_HASTE = BUILDER
            .comment("The courier's quick hands, as a multiplier on the wind-up of collecting a package.",
                "This ladder is deliberately shallower than the others: a courier's day is mostly walking,",
                "so a steep one would buy almost nothing. One entry per rank, below 1 for faster.")
            .<Number>defineList("dispatchHaste", List.of(0.92D, 0.85D, 0.78D, 0.72D), () -> 1.0D, HASTE_STEP);
        BUILDER.pop();

        BUILDER.push("labor");
        DISPATCH_REACH_TICKS = BUILDER
            .comment("Wind-up, in ticks, for a courier lifting a package off a port. Shorter than a",
                "hauler's reach by default, on the grounds that a port hands its package over ready to",
                "carry. The courier's quick hands scale it.")
            .defineInRange("dispatchReachTicks", 10, 0, 1_200);
        BUILDER.pop();

        SPEC = BUILDER.build();
    }

    private VanillaCreateConfig() {
    }

    public static void register(ModContainer container) {
        ConfigRenames.registerSplit("folkways_core-create-server.toml", CONFIG,
            List.of("perks.conductingHaste", "perks.dispatchHaste", "labor.dispatchReachTicks"));
        container.registerConfig(ModConfig.Type.SERVER, SPEC, CONFIG);
    }

    public static List<? extends Number> conductingHaste() {
        return read(CONDUCTING_HASTE);
    }

    public static List<? extends Number> dispatchHaste() {
        return read(DISPATCH_HASTE);
    }

    public static int dispatchReachTicks() {
        return read(DISPATCH_REACH_TICKS);
    }

    private static <T> T read(ModConfigSpec.ConfigValue<T> value) {
        return SPEC.isLoaded() ? value.get() : value.getDefault();
    }
}
