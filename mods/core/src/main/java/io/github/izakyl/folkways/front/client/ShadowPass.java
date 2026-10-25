package io.github.izakyl.folkways.front.client;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import net.neoforged.fml.ModList;

/**
 * Whether a shader pack is drawing its shadow map. Iris draws the translucent layer once more from the sun, and the
 * game raises {@code AFTER_TRANSLUCENT_BLOCKS} for that draw too: an overlay drawn there lands in the shadow map
 * and casts its outline on the ground around the player. Read through Iris's public API, so Iris stays optional.
 */
public final class ShadowPass {
    private static final MethodHandle RENDERING_SHADOWS = renderingShadows();

    private ShadowPass() {
    }

    public static boolean active() {
        if (RENDERING_SHADOWS == null) {
            return false;
        }
        try {
            return (boolean) RENDERING_SHADOWS.invoke();
        } catch (Throwable failure) {
            return false;
        }
    }

    private static MethodHandle renderingShadows() {
        if (!ModList.get().isLoaded("iris")) {
            return null;
        }
        try {
            Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            MethodHandle instance = lookup.findStatic(api, "getInstance", MethodType.methodType(api));
            MethodHandle shadows = lookup.findVirtual(api, "isRenderingShadowPass",
                MethodType.methodType(boolean.class));
            return MethodHandles.foldArguments(shadows, instance);
        } catch (ReflectiveOperationException | LinkageError failure) {
            return null;
        }
    }
}
