package io.github.izakyl.folkways.vanilla.cookery;

import io.github.izakyl.folkways.vanilla.cookery.KitchenPrefab.Appliance;
import com.github.ysbbbbbb.kaleidoscopecookery.api.blockentity.IStockpot;
import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.ChoppingBoardBlockEntity;
import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.StockpotBlockEntity;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModBlocks;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModRecipes;
import io.github.izakyl.folkways.core.colony.api.Closing;
import io.github.izakyl.folkways.core.plan.api.Grown;
import io.github.izakyl.folkways.core.plan.api.Workshop;
import io.github.izakyl.folkways.core.world.api.Stances;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.vanilla.cookery.board.ChoppingBoardWorkshops;
import io.github.izakyl.folkways.vanilla.cookery.pot.PotStation;
import io.github.izakyl.folkways.vanilla.cookery.stockpot.StockpotWorkshops;
import io.github.izakyl.folkways.vanilla.cookery.teapot.TeapotStation;
import io.github.izakyl.folkways.vanilla.cookery.tray.TrayStation;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.crafting.RecipeType;
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
        KitchenStations.register(new io.github.izakyl.folkways.vanilla.cookery.stove.StoveStation());
        KitchenStations.register(new io.github.izakyl.folkways.vanilla.cookery.spit.SpitStation());
        KitchenStations.register(new io.github.izakyl.folkways.vanilla.cookery.steamer.SteamerStation());
        KitchenStations.register(new io.github.izakyl.folkways.vanilla.cookery.millstone.MillstoneStation());
    }

    private static final class Board implements Appliance {
        @Override
        public List<RecipeType<?>> recipeTypes() {
            return List.of(ModRecipes.CHOPPING_BOARD_RECIPE);
        }

        @Override
        public List<Component> conditions() {
            return List.of(Component.translatable("folkways.mechanics.knife"));
        }

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

    private static final class Stockpot implements Appliance {
        @Override
        public List<RecipeType<?>> recipeTypes() {
            return List.of(ModRecipes.STOCKPOT_RECIPE, ModRecipes.FLEX_STOCKPOT_RECIPE);
        }

        @Override
        public List<Component> conditions() {
            return List.of(Component.translatable("folkways.mechanics.heat"),
                    Component.translatable("folkways.mechanics.lid"));
        }

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
