package io.github.izakyl.folkways.cookery;

import com.github.ysbbbbbb.kaleidoscopecookery.api.blockentity.IStockpot;
import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.ChoppingBoardBlockEntity;
import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.StockpotBlockEntity;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModBlocks;
import io.github.izakyl.folkways.cookery.board.ChoppingBoardWorkshops;
import io.github.izakyl.folkways.cookery.pot.PotStation;
import io.github.izakyl.folkways.cookery.stockpot.StockpotWorkshops;
import io.github.izakyl.folkways.cookery.teapot.TeapotStation;
import io.github.izakyl.folkways.cookery.tray.TrayStation;
import io.github.izakyl.folkways.core.colony.api.Closing;
import io.github.izakyl.folkways.core.plan.api.Grown;
import io.github.izakyl.folkways.core.plan.api.Workshop;
import io.github.izakyl.folkways.core.work.api.Stances;
import io.github.izakyl.folkways.core.world.api.WorldPos;
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
        KitchenStations.register(new PotStation());
        KitchenStations.register(new TrayStation());
        KitchenStations.register(new TeapotStation());
        KitchenStations.register(new io.github.izakyl.folkways.cookery.stove.StoveStation());
        KitchenStations.register(new io.github.izakyl.folkways.cookery.spit.SpitStation());
        KitchenStations.register(new io.github.izakyl.folkways.cookery.steamer.SteamerStation());
        KitchenStations.register(new io.github.izakyl.folkways.cookery.millstone.MillstoneStation());
    }

    private static final class Board implements KitchenStation {
        public boolean matches(BlockState state) { return state.is(ModBlocks.CHOPPING_BOARD.get()); }

        public Optional<Object> freezeSignature(ServerLevel level, WorldPos at) {
            if (!(level.getBlockEntity(at.block(level)) instanceof ChoppingBoardBlockEntity board)) {
                return Optional.empty();
            }
            return Optional.of(List.of(board, board.getBlockState(), board.getCurrentCutStack().isEmpty()));
        }

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

        public Optional<Object> freezeSignature(ServerLevel level, WorldPos at) {
            if (!(level.getBlockEntity(at.block(level)) instanceof StockpotBlockEntity pot)) {
                return Optional.empty();
            }
            boolean claimed = pot.getPersistentData().contains("folkways.cookery.stockpot");
            if (claimed && pot.getStatus() == IStockpot.PUT_SOUP_BASE && pot.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(List.of(pot, pot.getBlockState(), pot.getStatus(), pot.isEmpty(), pot.hasLid(),
                claimed));
        }

        public Optional<Workshop> freeze(UUID key, UUID colony, ServerLevel level, WorldPos at,
                                         Stances stances, KitchenBookings bookings, Runnable changed) {
            return StockpotWorkshops.freeze(key, colony, level, at, stances, bookings, changed);
        }
        public List<Grown> recovery(UUID colony, ServerLevel level, WorldPos at, Stances stances,
                                    KitchenBookings bookings, Runnable changed) {
            return StockpotWorkshops.recovery(colony, level, at, stances, bookings, changed);
        }
        public List<Grown> activeRecovery(UUID colony, ServerLevel level, WorldPos at, KitchenBookings bookings) {
            return StockpotWorkshops.activeRecovery(colony, level, at, bookings);
        }
        public void stop(UUID colony, Closing why) { StockpotWorkshops.stop(colony); }
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
