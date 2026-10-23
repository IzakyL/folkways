package io.github.izakyl.folkways.create;

import io.github.izakyl.folkways.FolkwaysConfig;
import java.util.List;
import java.util.function.Predicate;
import net.neoforged.neoforge.common.ModConfigSpec;

// The settings of what Create brings: the conductor's and courier's quick hands and their wind-ups.
public final class FolkwaysCreateConfig {

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    private static final Predicate<Object> HASTE_STEP = FolkwaysConfig.HASTE_STEP;

    private static final ModConfigSpec.ConfigValue<List<? extends Number>> CONDUCTING_HASTE;
    private static final ModConfigSpec.ConfigValue<List<? extends Number>> DISPATCH_HASTE;
    private static final ModConfigSpec.IntValue DISPATCH_REACH_TICKS;
    private static final ModConfigSpec.IntValue SEAT_TICKS;

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
        SEAT_TICKS = BUILDER
            .comment("Ticks a resident spends settling into a seat, whether that is a rail carriage or a",
                "courier's post. The same value is both the wind-up planned for and the count the sitting",
                "body waits out, so the plan and the body agree on what sitting down costs.")
            .defineInRange("seatTicks", 8, 0, 200);
        BUILDER.pop();

        SPEC = BUILDER.build();
    }

    private FolkwaysCreateConfig() {
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

    public static int seatTicks() {
        return read(SEAT_TICKS);
    }

    private static <T> T read(ModConfigSpec.ConfigValue<T> value) {
        return SPEC.isLoaded() ? value.get() : value.getDefault();
    }
}
