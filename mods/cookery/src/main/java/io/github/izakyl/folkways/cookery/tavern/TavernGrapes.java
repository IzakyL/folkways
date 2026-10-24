package io.github.izakyl.folkways.cookery.tavern;

import com.github.ysbbbbbb.kaleidoscopetavern.block.plant.GrapeCropBlock;
import com.github.ysbbbbbb.kaleidoscopetavern.block.plant.GrapevineTrellisBlock;
import com.github.ysbbbbbb.kaleidoscopetavern.block.plant.ITrellis;
import com.github.ysbbbbbb.kaleidoscopetavern.block.plant.TrellisBlock;
import com.github.ysbbbbbb.kaleidoscopetavern.block.properties.TrellisType;
import com.github.ysbbbbbb.kaleidoscopetavern.init.ModBlocks;
import com.github.ysbbbbbb.kaleidoscopetavern.init.ModItems;
import com.github.ysbbbbbb.kaleidoscopetavern.init.tag.TagMod;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
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
 * Real Tavern vines climb a trellis post at each corner of the plot, then spread over a trellis roof covering the whole
 * plot five blocks up and hang fruit under it. Residents lay the frame a trellis at a time, then plant a vine at the
 * foot of each finished post.
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
    public boolean sowsAt(BlockPos ground, int minX, int minZ, Set<BlockPos> plot) {
        return corner(ground, plot);
    }
    public Optional<ItemSpec> frame() {
        return Optional.of(ItemSpec.of(BuiltInRegistries.ITEM.getKey(ModBlocks.TRELLIS.get().asItem())));
    }
    // Posts go up from the ground, the roof from the top of a post outward, each cell against one already laid.
    public List<BlockPos> frameCells(LevelReader level, Set<BlockPos> plot) {
        List<BlockPos> corners = plot.stream().filter(cell -> corner(cell, plot))
            .sorted(Comparator.comparingInt((BlockPos cell) -> cell.getX()).thenComparingInt(cell -> cell.getZ())).toList();
        List<BlockPos> ready = new ArrayList<>();
        for (BlockPos corner : corners) {
            for (int up = 1; up <= POST; up++) {
                BlockPos at = corner.above(up);
                if (open(level, at) && (up == 1 || framed(level, at.below()))) ready.add(at);
            }
        }
        for (BlockPos cell : plot) {
            BlockPos at = new BlockPos(cell.getX(), nearest(cell, corners).getY() + 1 + ROOF, cell.getZ());
            if (!open(level, at)) continue;
            boolean against = framed(level, at.below()) && corners.contains(cell);
            for (Direction side : Direction.Plane.HORIZONTAL) against |= framed(level, at.relative(side));
            if (against) ready.add(at);
        }
        return ready;
    }
    public boolean lay(ServerLevel level, Worker who, BlockPos at, List<ItemStack> supplied) {
        Item trellis = ModBlocks.TRELLIS.get().asItem();
        ItemStack stock = supplied.stream().filter(s -> s.is(trellis) && !s.isEmpty()).findFirst().orElse(ItemStack.EMPTY);
        if (stock.isEmpty() || !level.getBlockState(at).isAir()) return false;
        BlockState state = ModBlocks.TRELLIS.get().defaultBlockState().setValue(ITrellis.TYPE, laid(level, at));
        if (!level.setBlock(at, state, Block.UPDATE_ALL)) return false;
        stock.shrink(1);
        return true;
    }
    // A vine is planted into the foot of a finished post, as a player plants one on a lone trellis.
    public boolean canPlant(LevelReader level, BlockPos base) {
        if (!level.getBlockState(base.below()).is(TagMod.CAN_GROW_GRAPE)) return false;
        for (int up = 0; up < POST; up++) {
            BlockState post = level.getBlockState(base.above(up));
            if (!post.is(ModBlocks.TRELLIS.get()) || post.getValue(TrellisBlock.WAXED)) return false;
        }
        return level.getBlockState(base).getValue(ITrellis.TYPE) == TrellisType.SINGLE;
    }
    public boolean plant(ServerLevel level, Worker who, BlockPos base, ItemStack seed, List<ItemStack> supplied) {
        if (!seed.is(ModItems.GRAPEVINE.get()) || seed.isEmpty() || !canPlant(level, base)) return false;
        BlockState post = level.getBlockState(base);
        BlockState vine = planted().setValue(ITrellis.TYPE, post.getValue(ITrellis.TYPE))
            .setValue(GrapevineTrellisBlock.WATERLOGGED, post.getValue(TrellisBlock.WATERLOGGED));
        if (!level.setBlock(base, vine, Block.UPDATE_ALL)) return false;
        seed.shrink(1);
        return true;
    }
    public boolean plant(ServerLevel level, Worker who, BlockPos base, ItemStack seed) {
        return plant(level, who, base, seed, List.of());
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

    private static boolean open(LevelReader level, BlockPos at) {
        return at.getY() < level.getMaxBuildHeight() && level.hasChunkAt(at) && level.getBlockState(at).isAir();
    }

    private static boolean framed(LevelReader level, BlockPos at) {
        BlockState state = level.getBlockState(at);
        return state.getBlock() instanceof ITrellis trellis && trellis.sameType(state);
    }

    // The shape a trellis takes placed by hand among the ones already standing: a post stands up, a roof lies flat.
    private static TrellisType laid(ServerLevel level, BlockPos at) {
        boolean x = ITrellis.axisHasTrellis(level, at, Direction.Axis.X);
        boolean y = ITrellis.axisHasTrellis(level, at, Direction.Axis.Y);
        boolean z = ITrellis.axisHasTrellis(level, at, Direction.Axis.Z);
        return ITrellis.getTypeForPlacement(x, y, z, x ? Direction.Axis.X : z ? Direction.Axis.Z : Direction.Axis.Y);
    }
}
