package io.github.izakyl.folkways.firearms.warfare.mixin;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "com.github.mcmodderanchor.simplebedrockmodel.v2.client.compat.sodium.SodiumCompat", remap = false)
public abstract class BedrockTriangleWriterMixin {

    @Unique
    private static final MethodHandle folkways$shaderPackInUse = folkways$findShaderPackInUse();

    // Iris 1.8's bulk entity serializer copies count / 4 quads, leaving a triangle batch's last 1–3 vertices
    // unwritten. Those uninitialized positions stretch AC-130H meshes across the sky. Returning false uses
    // SimpleBedrockModel's existing per-vertex path, where Iris handles TRIANGLES correctly; quads stay batched.
    @Inject(method = "writeVertices", at = @At("HEAD"), cancellable = true)
    private static void folkways$scalarShaderTriangles(CallbackInfoReturnable<Boolean> callback) throws Throwable {
        if ((boolean) folkways$shaderPackInUse.invokeExact()) {
            callback.setReturnValue(false);
        }
    }

    @Unique
    private static MethodHandle folkways$findShaderPackInUse() {
        try {
            Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            MethodHandle instance = lookup.findStatic(api, "getInstance", MethodType.methodType(api));
            MethodHandle enabled = lookup.findVirtual(api, "isShaderPackInUse", MethodType.methodType(boolean.class));
            return MethodHandles.foldArguments(enabled, instance);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot read Iris shader state for Bedrock triangle rendering", failure);
        }
    }
}
