package io.github.izakyl.folkways.core.shell.create;

import net.neoforged.neoforge.common.ModConfigSpec;

// Timing for Create's seat controls.
public final class FolkwaysCreateConfig {

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    private static final ModConfigSpec.IntValue SEAT_TICKS;

    public static final ModConfigSpec SPEC;

    static {

        BUILDER.push("labor");
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

    public static int seatTicks() {
        return read(SEAT_TICKS);
    }

    private static <T> T read(ModConfigSpec.ConfigValue<T> value) {
        return SPEC.isLoaded() ? value.get() : value.getDefault();
    }
}
