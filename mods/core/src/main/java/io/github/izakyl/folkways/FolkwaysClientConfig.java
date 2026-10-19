package io.github.izakyl.folkways;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class FolkwaysClientConfig {

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    private static final ModConfigSpec.BooleanValue INVERT_WHEEL;

    public static final ModConfigSpec SPEC;

    static {

        BUILDER.push("book");
        INVERT_WHEEL = BUILDER
            .comment("Turn the book's wheel around. Scrolling up pushes what the wheel has hold of away from",
                "you - the reach, the face of a box, a point of a line, a blueprint being set down - as",
                "Create's schematics do. On, scrolling up draws it in.")
            .define("invertWheel", false);
        BUILDER.pop();

        SPEC = BUILDER.build();
    }

    private FolkwaysClientConfig() {
    }

    public static boolean invertWheel() {
        return INVERT_WHEEL.get();
    }

    public static void invertWheel(boolean on) {
        INVERT_WHEEL.set(on);
        SPEC.save();
    }

    /** A turn of the wheel as a step away from the player: up pushes, unless the player turned the wheel around. */
    public static int away(int step) {
        return invertWheel() ? -step : step;
    }
}
