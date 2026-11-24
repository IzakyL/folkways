package io.github.izakyl.folkways.core.shell.cookery.api;

import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.ChoppingBoardBlockEntity;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModBlocks;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModRecipes;
import io.github.izakyl.folkways.core.facility.api.Ports;
import io.github.izakyl.folkways.core.facility.api.control.Control;
import io.github.izakyl.folkways.core.facility.api.control.Port;
import io.github.izakyl.folkways.core.facility.api.control.ResourceKind;
import io.github.izakyl.folkways.core.world.api.Footing;
import io.github.izakyl.folkways.core.world.api.Stances;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * Kaleidoscope Cookery's appliances as controls: where each is worked from. A counter-top appliance is worked from
 * its handle side only: the handle faces the model's facing, except on the stockpot and steamer, whose handles lie
 * across it; the teapot has no rotation and is served from its south side. Other appliances are worked from any
 * footing within reach.
 *
 * <p>Each appliance has an item port, part {@link #INPUT}, at its anchor. The appliance cooks what goes in.
 * The chopping board accepts input through its native interaction. Other transfers use the native item capability
 * where available. Colony batches can still call native interactions directly to settle returns and failures.
 */
public final class Appliances {

    static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("folkways_core", "cookery/appliances");
    /** The part of an appliance's item port. */
    public static final String INPUT = "input";

    private static boolean registered;

    private Appliances() {
    }

    public static synchronized void register() {
        if (registered) {
            return;
        }
        registered = true;
        Ports.register(ResourceKind.ITEM, ID, Ports.SPECIFIC, (level, pos) ->
            appliance(level.getBlockState(pos.block(level))) ? List.<Port<ItemStack>>of(new Input(pos)) : List.of());
    }

    /** The blocks the colony cooks with. */
    public static boolean appliance(BlockState state) {
        return state.is(ModBlocks.CHOPPING_BOARD.get()) || state.is(ModBlocks.STOCKPOT.get())
            || state.is(ModBlocks.POT.get()) || state.is(ModBlocks.BAMBOO_TRAY.get())
            || state.is(ModBlocks.TEAPOT.get()) || state.is(ModBlocks.STOVE.get())
            || state.is(ModBlocks.SHAWARMA_SPIT.get()) || state.is(ModBlocks.STEAMER.get())
            || state.is(ModBlocks.MILLSTONE.get());
    }

    /** The side an appliance is worked from, when it has one; empty when any side will do. */
    public static Optional<Direction> handle(BlockState state) {
        if (state.is(ModBlocks.TEAPOT.get())) {
            // The native tea block has no rotation property. Keep a fixed serving side.
            return Optional.of(Direction.SOUTH);
        }
        if (!state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            return Optional.empty();
        }
        Direction side = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
        // The stockpot and steamer have their handles across the model's facing axis.
        return Optional.of(handlesAcross(state.getBlock()) ? side.getClockWise() : side);
    }

    private static boolean handlesAcross(Block block) {
        return block == ModBlocks.STOCKPOT.get() || block == ModBlocks.STEAMER.get();
    }

    /**
     * The footings a counter-top appliance is worked from on `side`: the column beside it, standing level with it or
     * one below, which also serves an appliance placed directly on the floor. Never another side. Told in the
     * appliance's realm.
     */
    public static Set<WorldPos> footings(ServerLevel level, WorldPos at, Direction side) {
        if (!at.in(level) || !level.isLoaded(at.block(level))) {
            return Set.of();
        }
        BlockPos target = at.block(level);
        BlockPos adjacent = target.relative(side);
        Set<WorldPos> found = new LinkedHashSet<>();
        for (BlockPos cell : List.of(adjacent.below(), adjacent)) {
            if (level.isLoaded(cell) && Footing.withFeetAt(level, cell)) {
                found.add(at.at(at.cell().offset(cell.subtract(target))));
            }
        }
        return found;
    }

    /**
     * Where the appliance at `at` is worked from, told in `at`'s realm: its handle-side footings, or any footing in
     * reach when it has no handle side. Empty when the appliance has nowhere to stand on that side.
     */
    static Optional<Stances> stance(ServerLevel level, WorldPos at) {
        if (!at.in(level) || !level.isLoaded(at.block(level))) {
            return Optional.empty();
        }
        Optional<Direction> side = handle(level.getBlockState(at.block(level)));
        if (side.isEmpty()) {
            return Control.reach(level, at);
        }
        return Stances.of(footings(level, at, side.get()));
    }

    public interface BoardInput {
        boolean folkways$inputFree();
    }

    private record Input(WorldPos anchor) implements Port<ItemStack> {

        @Override
        public ResourceKind<ItemStack> resource() {
            return ResourceKind.ITEM;
        }

        @Override
        public String part() {
            return INPUT;
        }

        @Override
        public boolean valid(ServerLevel level) {
            return anchor.in(level) && level.isLoaded(anchor.block(level))
                && appliance(level.getBlockState(anchor.block(level))) && Appliances.stance(level, anchor).isPresent();
        }

        @Override
        public Optional<Stances> stance(ServerLevel level) {
            return Appliances.stance(level, anchor);
        }

        @Override
        public ItemStack deposit(ServerLevel level, Mob writer, ItemStack offered, boolean simulate) {
            if (offered.isEmpty()) {
                return ItemStack.EMPTY;
            }
            if (anchor.in(level) && level.isLoaded(anchor.block(level))
                    && level.getBlockEntity(anchor.block(level)) instanceof ChoppingBoardBlockEntity board) {
                if (simulate) {
                    // Match the native results check, including a board with uncollected output.
                    return ((BoardInput) board).folkways$inputFree()
                        && level.getRecipeManager().getRecipeFor(ModRecipes.CHOPPING_BOARD_RECIPE,
                            new SingleRecipeInput(offered.copy()), level).isPresent()
                        ? offered.copyWithCount(1) : ItemStack.EMPTY;
                }
                ItemStack input = offered.copy();
                return board.onPutItem(level, writer, input)
                    ? offered.copyWithCount(offered.getCount() - input.getCount()) : ItemStack.EMPTY;
            }
            IItemHandler handler = handler(level);
            if (handler == null) {
                return ItemStack.EMPTY;
            }
            ItemStack rest = offered.copy();
            for (int slot = 0; slot < handler.getSlots() && !rest.isEmpty(); slot++) {
                rest = handler.insertItem(slot, rest, simulate);
            }
            return offered.copyWithCount(offered.getCount() - rest.getCount());
        }

        @Override
        public ItemStack draw(ServerLevel level, Mob writer, ItemStack wanted, boolean simulate) {
            IItemHandler handler = handler(level);
            if (handler == null || wanted.isEmpty()) {
                return ItemStack.EMPTY;
            }
            for (int slot = 0; slot < handler.getSlots(); slot++) {
                if (ItemStack.isSameItemSameComponents(handler.getStackInSlot(slot), wanted)) {
                    return handler.extractItem(slot, wanted.getCount(), simulate);
                }
            }
            return ItemStack.EMPTY;
        }

        private IItemHandler handler(ServerLevel level) {
            return anchor.in(level) && level.isLoaded(anchor.block(level))
                ? level.getCapability(Capabilities.ItemHandler.BLOCK, anchor.block(level), null) : null;
        }
    }
}
