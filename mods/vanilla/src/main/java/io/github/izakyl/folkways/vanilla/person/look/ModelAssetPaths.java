package io.github.izakyl.folkways.vanilla.person.look;

import java.util.regex.Pattern;

// Independent of ResourceLocation validation, which other mods can relax.
final class ModelAssetPaths {

    private static final Pattern PACK_PATH = Pattern.compile("assets(/[a-z0-9_.-]+)+");
    private static final Pattern DOTS_ONLY = Pattern.compile("(^|/)\\.+(/|$)");

    private ModelAssetPaths() {
    }

    static boolean accepts(String path) {
        return PACK_PATH.matcher(path).matches() && !DOTS_ONLY.matcher(path).find();
    }
}
