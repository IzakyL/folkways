package io.github.izakyl.folkways.cookery.stove;

import io.github.izakyl.folkways.core.api.colony.Closing;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.cookery.KitchenBookings;
import io.github.izakyl.folkways.cookery.KitchenStation;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

public final class StoveStation implements KitchenStation {
    public boolean matches(BlockState state) { return StoveWorkshops.matches(state); }
    public List<Grown> recovery(UUID colony, ServerLevel level, WorldPos at, Stances stances,
                                KitchenBookings bookings, Runnable changed) {
        return StoveWorkshops.recovery(colony, level, at, stances, bookings, changed);
    }
    public List<Grown> activeRecovery(UUID colony, ServerLevel level, WorldPos at, KitchenBookings bookings) {
        return StoveWorkshops.active(colony, level, at, bookings);
    }
    public void release(UUID colony, ServerLevel level, WorldPos at) {
        StoveWorkshops.release(colony, level, at);
    }
    public void stop(UUID colony, Closing why) { StoveWorkshops.stop(colony, why); }
    public Component status(ServerLevel level, WorldPos at) {
        return Component.translatable("folkways.page.cookery." +
            (level.getBlockState(at.block(level)).getValue(com.github.ysbbbbbb.kaleidoscopecookery.block.kitchen.StoveBlock.LIT)
                ? "idle" : "cold"));
    }
}
