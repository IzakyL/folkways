package io.github.izakyl.folkways.core.runtime.mixin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.gametest.framework.GameTestBatch;
import net.minecraft.gametest.framework.GameTestBatchFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GameTestBatchFactory.class)
public abstract class GameTestMethodsMixin {
    @Inject(method = "fromTestFunction", at = @At("RETURN"), cancellable = true)
    private static void folkways$selectMethods(CallbackInfoReturnable<Collection<GameTestBatch>> callback) {
        if (!Boolean.getBoolean("folkways.filterGameTestMethods")) {
            return;
        }
        Set<String> methods = Arrays.stream(System.getProperty("folkways.testMethods", "").split(","))
            .map(String::trim).filter(name -> !name.isEmpty()).map(name -> name.toLowerCase(Locale.ROOT))
            .collect(Collectors.toSet());
        if (methods.isEmpty()) {
            return;
        }
        var selected = new ArrayList<GameTestBatch>();
        for (GameTestBatch batch : callback.getReturnValue()) {
            var tests = batch.gameTestInfos().stream().filter(test -> {
                String name = test.getTestName();
                return methods.contains(name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT));
            }).toList();
            if (!tests.isEmpty()) {
                selected.add(new GameTestBatch(batch.name(), tests, batch.beforeBatchFunction(),
                    batch.afterBatchFunction()));
            }
        }
        if (selected.isEmpty()) {
            throw new IllegalArgumentException("No GameTests matched " + methods);
        }
        callback.setReturnValue(selected);
    }
}
