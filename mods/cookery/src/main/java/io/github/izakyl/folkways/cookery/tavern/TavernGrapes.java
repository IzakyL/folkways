package io.github.izakyl.folkways.cookery.tavern;

import com.github.ysbbbbbb.kaleidoscopetavern.block.plant.GrapeCropBlock;
import com.github.ysbbbbbb.kaleidoscopetavern.block.plant.ITrellis;
import com.github.ysbbbbbb.kaleidoscopetavern.block.properties.TrellisType;
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
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Real Tavern vines climb a post at each corner of the plot, then spread over a trellis roof covering the whole plot
 * five blocks up and hang fruit under it.
 */
public final class TavernGrapes implements CropBehavior {
    private static final int POST = 4;
    private static final int ROOF = POST;

    static void register() {
        CropBehaviors.register(new TavernGrapes());
        Felling.registerAttachment(state -> state.is(ModBlocks.WILD_GRAPEVINE.get())
            || state.is(ModBlocks.WILD_GRAPEVINE_PLANT.get()));
    }

    public Block block() { return ModBlocks.GRAPEVINE_TRELLIS.get(); }
    public List<Item> seeds() { return List.of(ModItems.GRAPEVINE.get()); }
    public boolean needsFarmland() { return false; }
    public int reach() { return ROOF + 1; }
    public boolean aisles() { return false; }
    public List<Need> plantingMaterials(int plants, int frame) {
        return List.of(new Need(ItemSpec.of(BuiltInRegistries.ITEM.getKey(ModBlocks.TRELLIS.get().asItem())),
            plants * (POST - 1) + frame));
    }
    public boolean sowsAt(BlockPos ground, int minX, int minZ, Set<BlockPos> plot) {
        return corner(ground, plot);
    }
    public List<BlockPos> placementCells(BlockPos base) {
        List<BlockPos> post = new ArrayList<>(POST);
        for (int up = 0; up < POST; up++) post.add(base.above(up));
        return post;
    }
    // This post's share of the roof: every cell of the plot nearer to it than to any other corner.
    public List<BlockPos> frame(LevelReader level, BlockPos base, Set<BlockPos> plot) {
        BlockPos ground = base.below();
        List<BlockPos> corners = plot.stream().filter(cell -> corner(cell, plot))
            .sorted(Comparator.comparingInt((BlockPos cell) -> cell.getX()).thenComparingInt(cell -> cell.getZ())).toList();
        List<BlockPos> roof = new ArrayList<>();
        for (BlockPos cell : plot) {
            if (!ground.equals(nearest(cell, corners))) continue;
            BlockPos at = new BlockPos(cell.getX(), base.getY() + ROOF, cell.getZ());
            if (level.hasChunkAt(at) && level.getBlockState(at).isAir()) roof.add(at);
        }
        roof.sort(Comparator.comparingInt((BlockPos at) -> at.distManhattan(base)));
        return roof;
    }
    public boolean canPlant(LevelReader level, BlockPos base) {
        BlockPos roof = base.above(ROOF);
        return roof.getY() < level.getMaxBuildHeight()
            && level.getBlockState(base.below()).is(TagMod.CAN_GROW_GRAPE)
            && placementCells(base).stream().allMatch(p -> level.hasChunkAt(p) && level.getBlockState(p).isAir())
            && level.hasChunkAt(roof)
            && (level.getBlockState(roof).isAir() || level.getBlockState(roof).is(ModBlocks.TRELLIS.get()));
    }
    public boolean plant(ServerLevel level, Worker who, BlockPos base, ItemStack seed, List<ItemStack> supplied,
            List<BlockPos> frame) {
        if (!seed.is(ModItems.GRAPEVINE.get()) || seed.isEmpty() || !canPlant(level, base)) return false;
        List<BlockPos> cells = new ArrayList<>(placementCells(base));
        frame.stream().filter(p -> level.isLoaded(p) && level.getBlockState(p).isAir()).forEach(cells::add);
        Item trellis = ModBlocks.TRELLIS.get().asItem();
        int needed = cells.size() - 1;
        if (supplied.stream().filter(s -> s.is(trellis)).mapToInt(ItemStack::getCount).sum() < needed) return false;
        List<BlockState> before = cells.stream().map(level::getBlockState).toList();
        for (int i = 0; i < cells.size(); i++) {
            BlockState state = i == 0 ? planted() : ModBlocks.TRELLIS.get().defaultBlockState();
            if (!level.setBlock(cells.get(i), state, Block.UPDATE_ALL)) {
                for (int j = cells.size() - 1; j >= 0; j--) level.setBlock(cells.get(j), before.get(j), Block.UPDATE_ALL);
                return false;
            }
        }
        // Laid one by one, each frame takes the shape it would have had placed by hand among the finished ones.
        for (int i = 1; i < cells.size(); i++) {
            BlockPos at = cells.get(i);
            level.setBlock(at, level.getBlockState(at).setValue(ITrellis.TYPE, laid(level, at, i < POST)),
                Block.UPDATE_ALL);
        }
        int remaining = needed;
        for (ItemStack stack : supplied) {
            if (!stack.is(trellis)) continue;
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
    // Whatever hangs ripe under the roof over this cell.
    public List<BlockPos> harvest(LevelReader level, BlockPos base) {
        List<BlockPos> fruit = new ArrayList<>();
        for (int up = 0; up <= ROOF; up++) if (ripe(level, base.above(up))) fruit.add(base.above(up));
        return fruit;
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

    // A corner of the plot is a cell closed off on one side along each axis.
    private static boolean corner(BlockPos ground, Set<BlockPos> plot) {
        return plot.contains(ground)
            && (!plot.contains(ground.east()) || !plot.contains(ground.west()))
            && (!plot.contains(ground.north()) || !plot.contains(ground.south()));
    }

    private static BlockPos nearest(BlockPos cell, List<BlockPos> corners) {
        BlockPos best = null;
        int distance = Integer.MAX_VALUE;
        for (BlockPos corner : corners) {
            int d = Math.abs(corner.getX() - cell.getX()) + Math.abs(corner.getZ() - cell.getZ());
            if (d < distance) {
                best = corner;
                distance = d;
            }
        }
        return best;
    }

    private static TrellisType laid(ServerLevel level, BlockPos at, boolean post) {
        boolean x = ITrellis.axisHasTrellis(level, at, Direction.Axis.X);
        boolean y = ITrellis.axisHasTrellis(level, at, Direction.Axis.Y);
        boolean z = ITrellis.axisHasTrellis(level, at, Direction.Axis.Z);
        return ITrellis.getTypeForPlacement(x, y, z, post ? Direction.Axis.Y : x ? Direction.Axis.X : Direction.Axis.Z);
    }
}
