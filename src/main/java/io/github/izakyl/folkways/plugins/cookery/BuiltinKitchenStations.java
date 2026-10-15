package io.github.izakyl.folkways.plugins.cookery;

import com.github.ysbbbbbb.kaleidoscopecookery.api.blockentity.IStockpot;
import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.ChoppingBoardBlockEntity;
import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.StockpotBlockEntity;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModBlocks;
import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.Workshop;
import io.github.izakyl.folkways.plugins.cookery.board.ChoppingBoardWorkshops;
import io.github.izakyl.folkways.plugins.cookery.compost.CompostStation;
import io.github.izakyl.folkways.plugins.cookery.pot.PotStation;
import io.github.izakyl.folkways.plugins.cookery.seeds.SeedPresence;
import io.github.izakyl.folkways.plugins.cookery.stockpot.StockpotWorkshops;
import io.github.izakyl.folkways.plugins.cookery.teapot.TeapotStation;
import io.github.izakyl.folkways.plugins.cookery.tray.TrayStation;
import io.github.izakyl.folkways.plugins.farming.CropSeeds;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

final class BuiltinKitchenStations {
    private BuiltinKitchenStations() {
    }

    static void register() {
        KitchenStations.register(new Board());
        KitchenStations.register(new Stockpot());
        KitchenStations.register(new PotStation());
        KitchenStations.register(new TrayStation());
        KitchenStations.register(new TeapotStation());
        KitchenStations.register(new CompostStation(colonyId -> {
            var server = ServerLifecycleHooks.getCurrentServer();
            return server == null ? Optional.empty() : Colonies.of(server, colonyId)
                .flatMap(colony -> colony.service(SeedPresence.ID,
                    SeedPresence.class))
                .filter(SeedPresence::enabled)
                .map(support -> support.budget(server.overworld()));
        }, () -> {
            var server = ServerLifecycleHooks.getCurrentServer();
            return server == null ? Set.of()
                : CropSeeds.catalogue(server.overworld(), BlockPos.ZERO);
        }));
        KitchenStations.register(new io.github.izakyl.folkways.plugins.cookery.stove.StoveStation());
        KitchenStations.register(new io.github.izakyl.folkways.plugins.cookery.spit.SpitStation());
        KitchenStations.register(new io.github.izakyl.folkways.plugins.cookery.steamer.SteamerStation());
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
