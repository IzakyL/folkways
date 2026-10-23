package io.github.izakyl.folkways.cookery.tavern;

import com.github.ysbbbbbb.kaleidoscopetavern.block.plant.GrapeCropBlock;
import com.github.ysbbbbbb.kaleidoscopetavern.init.ModBlocks;
import com.github.ysbbbbbb.kaleidoscopetavern.init.ModItems;
import com.github.ysbbbbbb.kaleidoscopetavern.init.tag.TagMod;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.work.Need;
import io.github.izakyl.folkways.core.api.work.WorkNoise;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.plugins.farming.CropBehavior;
import io.github.izakyl.folkways.plugins.farming.CropBehaviors;
import io.github.izakyl.folkways.plugins.farming.Felling;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/** Real Tavern vines grow up the post, over its arm, then hang fruit over the aisle. */
public final class TavernGrapes implements CropBehavior {
    static void register() {
        CropBehaviors.register(new TavernGrapes());
        Felling.registerAttachment(state -> state.is(ModBlocks.WILD_GRAPEVINE.get())
            || state.is(ModBlocks.WILD_GRAPEVINE_PLANT.get()));
    }

    public Block block() { return ModBlocks.GRAPEVINE_TRELLIS.get(); }
    public List<Item> seeds() { return List.of(ModItems.GRAPEVINE.get()); }
    public boolean needsFarmland() { return false; }
    public int reach() { return 3; }
    public List<Need> plantingMaterials(int plants) {
        return List.of(new Need(ItemSpec.of(BuiltInRegistries.ITEM.getKey(ModBlocks.TRELLIS.get().asItem())), plants * 4));
    }
    public boolean sowsAt(BlockPos ground, int minX, int minZ, Set<BlockPos> plot) {
        return (ground.getX() - minX) % 3 == 0 && (ground.getZ() - minZ) % 3 == 0
            && plot.contains(ground.east());
    }
    public List<BlockPos> placementCells(BlockPos base) {
        return List.of(base, base.above(), base.above(2), base.above(2).east());
    }
    public boolean canPlant(LevelReader level, BlockPos base) {
        return base.getY() + 2 < level.getMaxBuildHeight()
            && level.getBlockState(base.below()).is(TagMod.CAN_GROW_GRAPE)
            && placementCells(base).stream().allMatch(p -> level.hasChunkAt(p) && level.getBlockState(p).isAir())
            && level.getBlockState(base.above().east()).isAir();
    }
    public boolean plant(ServerLevel level, Worker who, BlockPos base, ItemStack seed, List<ItemStack> supplied) {
        if (!seed.is(ModItems.GRAPEVINE.get()) || seed.isEmpty() || !canPlant(level, base)) return false;
        Item frame = ModBlocks.TRELLIS.get().asItem();
        if (supplied.stream().filter(s -> s.is(frame)).mapToInt(ItemStack::getCount).sum() < 4) return false;
        List<BlockPos> cells = placementCells(base);
        List<BlockState> before = cells.stream().map(level::getBlockState).toList();
        for (int i = 0; i < cells.size(); i++) {
            BlockState state = i == 0 ? planted() : ModBlocks.TRELLIS.get().defaultBlockState();
            if (!level.setBlock(cells.get(i), state, Block.UPDATE_ALL)) {
                for (int j = cells.size() - 1; j >= 0; j--) level.setBlock(cells.get(j), before.get(j), Block.UPDATE_ALL);
                return false;
            }
        }
        int remaining = 4;
        for (ItemStack stack : supplied) {
            if (!stack.is(frame)) continue;
            int used = Math.min(remaining, stack.getCount());
            stack.shrink(used);
            remaining -= used;
        }
        seed.shrink(1);
        return true;
    }
    public boolean plant(ServerLevel level, Worker who, BlockPos base, ItemStack seed) {
        return false; // Building requires the separately reserved trellises as well as the vine.
    }
    public boolean ripe(LevelReader level, BlockPos base) {
        BlockState state = level.getBlockState(base);
        return state.is(ModBlocks.GRAPE_CROP.get()) && ((GrapeCropBlock) state.getBlock()).isMaxAge(state);
    }
    public List<BlockPos> harvest(LevelReader level, BlockPos base) {
        BlockPos fruit = base.above().east();
        return level.getBlockState(base).is(block()) && ripe(level, fruit) ? List.of(fruit) : List.of();
    }
    public Optional<Harvest> gather(ServerLevel level, Worker who, List<BlockPos> roots) {
        List<ItemStack> items = new ArrayList<>();
        List<WorkNoise> noises = new ArrayList<>();
        for (BlockPos fruit : roots) {
            if (!level.isLoaded(fruit) || !ripe(level, fruit)) continue;
            BlockState state = level.getBlockState(fruit);
            List<ItemStack> drops = Block.getDrops(state, level, fruit, null, who.body(), ItemStack.EMPTY);
            if (!level.removeBlock(fruit, false)) continue;
            items.addAll(drops);
            noises.add(WorkNoise.broke(fruit, state));
        }
        return Optional.of(new Harvest(items, noises, noises.size()));
    }
}
