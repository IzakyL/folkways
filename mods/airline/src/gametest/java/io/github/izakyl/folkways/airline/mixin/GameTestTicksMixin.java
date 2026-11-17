package io.github.izakyl.folkways.airline.mixin;

import io.github.izakyl.folkways.airline.AirlineTestArea;
import io.github.izakyl.folkways.airline.GameTestTicks;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestRunner;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Efficient repeating assertions and the lifecycle of separately allocated GameTest sites. */
@Mixin(GameTestInfo.class)
public abstract class GameTestTicksMixin implements GameTestTicks.Repeating {
    @Shadow
    private BlockPos northWestCorner;
    @Shadow
    private long tickCount;
    @Shadow
    @Final
    private int timeoutTicks;
    @Unique
    private final List<Runnable> airline$repeating = new ArrayList<>();
    @Unique
    private boolean airline$visited;

    @Shadow
    public abstract void fail(Throwable error);

    public long airline$testTick() {
        return tickCount;
    }

    public void airline$onEachTick(Runnable action) {
        if (tickCount < timeoutTicks) {
            airline$repeating.add(action);
            // Registration from a delayed task still includes the current tick, just like onEachTick's range.
            if (airline$visited) {
                airline$run(action);
            }
        }
    }

    @Inject(method = "prepareTestStructure", at = @At("HEAD"))
    private void airline$allocateStructure(CallbackInfoReturnable<GameTestInfo> callback) {
        AirlineTestArea.prepare((GameTestInfo) (Object) this, northWestCorner);
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void airline$finishArea(GameTestRunner runner, CallbackInfo callback) {
        // Run after all normal success/failure listeners, while the batch still owns its loaded structure.
        AirlineTestArea.finish((GameTestInfo) (Object) this);
    }

    @Inject(method = "tickInternal", at = @At("HEAD"))
    private void airline$beginTick(CallbackInfo callback) {
        airline$visited = false;
    }

    @Inject(method = "tickInternal", at = @At(value = "INVOKE",
        target = "Lit/unimi/dsi/fastutil/objects/Object2LongMap;object2LongEntrySet()"
            + "Lit/unimi/dsi/fastutil/objects/ObjectSet;"))
    private void airline$repeatAssertions(CallbackInfo callback) {
        // This point follows startTest and precedes the one-shot task iterator, inside tickCount >= 0.
        // The timeout is exclusive, matching LongStream.range in GameTestHelper.onEachTick.
        if (tickCount < timeoutTicks) {
            for (int index = 0; index < airline$repeating.size(); index++) {
                airline$run(airline$repeating.get(index));
            }
        }
        airline$visited = true;
    }

    @Unique
    private void airline$run(Runnable action) {
        try {
            action.run();
        } catch (Exception error) {
            fail(error);
        }
    }
}
