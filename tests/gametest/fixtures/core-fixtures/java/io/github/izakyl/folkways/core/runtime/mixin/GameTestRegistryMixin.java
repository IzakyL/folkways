package io.github.izakyl.folkways.core.runtime.mixin;

import io.github.izakyl.folkways.core.testing.SelectedTests;
import java.lang.reflect.Method;
import java.util.Collection;
import net.minecraft.gametest.framework.GameTestRegistry;
import net.minecraft.gametest.framework.TestFunction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GameTestRegistry.class)
public abstract class GameTestRegistryMixin {
    @Inject(method = "turnMethodIntoTestFunction", at = @At("RETURN"))
    private static void folkways$recordMethod(Method method, CallbackInfoReturnable<TestFunction> callback) {
        SelectedTests.record(callback.getReturnValue(), method);
    }

    @Inject(method = "useTestGeneratorMethod", at = @At("RETURN"))
    private static void folkways$recordGenerator(Method method,
            CallbackInfoReturnable<Collection<TestFunction>> callback) {
        callback.getReturnValue().forEach(test -> SelectedTests.record(test, method));
    }
}
