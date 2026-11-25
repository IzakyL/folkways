package io.github.izakyl.folkways.core.runtime.mixin;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.gametest.framework.GameTestBatch;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameTestServer.class)
public abstract class GameTestListMixin {
    @Shadow private List<GameTestBatch> testBatches;

    @Inject(method = "tickServer", at = @At("HEAD"), cancellable = true)
    private void folkways$listTests(CallbackInfo callback) {
        String destination = System.getProperty("folkways.listTests");
        if (destination == null) {
            return;
        }
        var lines = testBatches.stream().flatMap(batch -> batch.gameTestInfos().stream()).map(test -> {
            TestFunction function = test.getTestFunction();
            return ResourceLocation.parse(function.structureName()).getNamespace() + ":"
                + function.batchName() + "/" + function.testName();
        }).sorted().toList();
        Path output = Path.of(destination);
        try {
            Files.createDirectories(output.toAbsolutePath().getParent());
            Files.write(output, lines);
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not write GameTest list to " + output, exception);
        }
        ((GameTestServer) (Object) this).halt(false);
        callback.cancel();
    }

    @Inject(method = "onServerExit", at = @At("HEAD"))
    private void folkways$exitAfterListing(CallbackInfo callback) {
        if (System.getProperty("folkways.listTests") != null) {
            System.exit(0);
        }
    }
}
