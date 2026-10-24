package io.github.izakyl.folkways;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.neoforged.neoforge.common.ModConfigSpec;

public final class FolkwaysClientConfig {

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    private static final ModConfigSpec.BooleanValue INVERT_WHEEL;
    private static final ModConfigSpec.BooleanValue REMEMBER_DRAWING_SETTINGS;
    private static final ModConfigSpec.ConfigValue<List<? extends String>> GLOBAL_TACTICAL_CONTROL_USERS;

    public static final ModConfigSpec SPEC;

    static {

        BUILDER.push("book");
        INVERT_WHEEL = BUILDER
            .translation("folkways.settings.invert_wheel")
            .comment("Turn the book's wheel around. Scrolling up pushes what the wheel has hold of away from",
                "you - the reach, the face of a box, a point of a line, a blueprint being set down - as",
                "Create's schematics do. On, scrolling up draws it in.")
            .define("invertWheel", false);
        REMEMBER_DRAWING_SETTINGS = BUILDER
            .translation("folkways.settings.remember_drawing_settings")
            .comment("Remember the last options used for each kind of zone, path or building pattern configuration.")
            .define("rememberDrawingSettings", true);
        GLOBAL_TACTICAL_CONTROL_USERS = BUILDER
            .translation("folkways.settings.global_tactical_controls")
            .comment("Account UUIDs using tactical camera controls for every overhead tool. An absent account uses",
                "tactical controls only for tools that request them. This preference belongs to the local account, not a colony.")
            .defineListAllowEmpty("globalTacticalControlUsers", List.<String>of(), null, FolkwaysClientConfig::accountUUID);
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

    public static boolean rememberDrawingSettings() {
        return REMEMBER_DRAWING_SETTINGS.get();
    }

    public static void rememberDrawingSettings(boolean on) {
        REMEMBER_DRAWING_SETTINGS.set(on);
        SPEC.save();
    }

    /** Account-specific local camera preference; accounts not listed retain the tool's own controls. */
    public static boolean globalTacticalControls(UUID account) {
        return account != null && SPEC.isLoaded() && GLOBAL_TACTICAL_CONTROL_USERS.get().stream()
            .anyMatch(account.toString()::equalsIgnoreCase);
    }

    public static void globalTacticalControls(UUID account, boolean on) {
        if (stageGlobalTacticalControls(account, on)) SPEC.save();
    }

    /** The native config screen owns saving and undoing its in-memory edits. */
    public static boolean stageGlobalTacticalControls(UUID account, boolean on) {
        if (account == null || !SPEC.isLoaded() || globalTacticalControls(account) == on) return false;
        List<String> accounts = new ArrayList<>(GLOBAL_TACTICAL_CONTROL_USERS.get());
        accounts.removeIf(account.toString()::equalsIgnoreCase);
        if (on) accounts.add(account.toString());
        GLOBAL_TACTICAL_CONTROL_USERS.set(List.copyOf(accounts));
        return true;
    }

    private static boolean accountUUID(Object value) {
        if (!(value instanceof String text)) return false;
        try { return UUID.fromString(text).toString().equalsIgnoreCase(text); }
        catch (IllegalArgumentException invalid) { return false; }
    }

    /** A turn of the wheel as a step away from the player: up pushes, unless the player turned the wheel around. */
    public static int away(int step) {
        return invertWheel() ? -step : step;
    }
}
