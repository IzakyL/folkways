package io.github.izakyl.folkways.plugins.cookery;

import com.github.ysbbbbbb.kaleidoscopecookery.api.blockentity.IStockpot;
import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.ChoppingBoardBlockEntity;
import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.StockpotBlockEntity;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModBlocks;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.Workshop;
import io.github.izakyl.folkways.plugins.cookery.board.ChoppingBoardWorkshops;
import io.github.izakyl.folkways.plugins.cookery.stockpot.StockpotWorkshops;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

final class BuiltinKitchenStations {
    private BuiltinKitchenStations() {
    }

    static void register() {
        KitchenStations.register(new Board());
        KitchenStations.register(new Stockpot());
        KitchenStations.register(new io.github.izakyl.folkways.plugins.cookery.stove.StoveStation());
        KitchenStations.register(new io.github.izakyl.folkways.plugins.cookery.millstone.MillstoneStation());
    }

    private static final class Board implements KitchenStation {
        public boolean matches(BlockState state) { return state.is(ModBlocks.CHOPPING_BOARD.get()); }
        public Optional<Workshop> freeze(UUID key, UUID colony, ServerLevel level, WorldPos at,
                                         Stances stances, KitchenBookings bookings, Runnable changed) {
            return ChoppingBoardWorkshops.freeze(key, colony, level, at, stances, bookings, changed);
        }
        public List<Grown> recovery(UUID colony, ServerLevel level, WorldPos at, Stances stances,
                                    KitchenBookings bookings, Runnable changed) {
            return ChoppingBoardWorkshops.recovery(colony, level, at, stances, bookings, changed);
        }
        public void release(UUID colony, ServerLevel level, WorldPos at) {
            ChoppingBoardWorkshops.release(colony, level, at);
        }
        public Component status(ServerLevel level, WorldPos at) {
            return Component.translatable("folkways.page.cookery." +
                (level.getBlockEntity(at.block(level)) instanceof ChoppingBoardBlockEntity board
                    && !board.getCurrentCutStack().isEmpty() ? "chopping" : "idle"));
        }
    }

    private static final class Stockpot implements KitchenStation {
        public boolean matches(BlockState state) { return state.is(ModBlocks.STOCKPOT.get()); }
        public Optional<Workshop> freeze(UUID key, UUID colony, ServerLevel level, WorldPos at,
                                         Stances stances, KitchenBookings bookings, Runnable changed) {
            return StockpotWorkshops.freeze(key, colony, level, at, stances, bookings, changed);
        }
        public List<Grown> recovery(UUID colony, ServerLevel level, WorldPos at, Stances stances,
                                    KitchenBookings bookings, Runnable changed) {
            return StockpotWorkshops.recovery(colony, level, at, stances, bookings, changed);
        }
        public void release(UUID colony, ServerLevel level, WorldPos at) {
            StockpotWorkshops.release(colony, level, at);
        }
        public Component status(ServerLevel level, WorldPos at) {
            String status = "idle";
            if (level.getBlockEntity(at.block(level)) instanceof StockpotBlockEntity pot) {
                status = switch (pot.getStatus()) {
                    case IStockpot.COOKING -> "cooking";
                    case IStockpot.FINISHED -> "finished";
                    case IStockpot.PUT_INGREDIENT -> "preparing";
                    default -> "idle";
                };
                if (pot.getStatus() != IStockpot.FINISHED && !pot.hasHeatSource(level)) status = "cold";
            }
            return Component.translatable("folkways.page.cookery." + status);
        }
    }
}
