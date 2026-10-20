package io.github.izakyl.folkways.cookery.farming;

import com.github.ysbbbbbb.kaleidoscopecookery.block.crop.RiceCropBlock;
import com.github.ysbbbbbb.kaleidoscopecookery.block.crop.TeaTreeBlock;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModBlocks;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModItems;
import io.github.izakyl.folkways.core.api.work.WorkNoise;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.plugins.farming.CropBehavior;
import io.github.izakyl.folkways.plugins.farming.CropBehaviors;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.neoforged.neoforge.common.ItemAbilities;

public final class CookeryCrops {
    private static boolean registered;

    private CookeryCrops() {
    }

    public static synchronized void register() {
        if (registered) return;
        CropBehaviors.register(new Rice());
        CropBehaviors.register(new Picking(ModBlocks.TEA_TREE.get(), ModItems.TEA_SEED.get(), false, 3));
        CropBehaviors.register(new Picking(ModBlocks.TOMATO_CROP.get(), ModItems.TOMATO_SEED.get(), true, 5));
        CropBehaviors.register(new Picking(ModBlocks.CHILI_CROP.get(), ModItems.CHILI_SEED.get(), true, 5));
        CropBehaviors.register(new Lettuce());
        registered = true;
    }

    private record Rice() implements CropBehavior {
        public Block block() { return ModBlocks.RICE_CROP.get(); }
        public List<Item> seeds() { return List.of(ModItems.WILD_RICE_SEED.get(), ModItems.RICE_SEED.get()); }
        public boolean needsFarmland() { return false; }
        public BlockState planted() { return block().defaultBlockState().setValue(RiceCropBlock.WATERLOGGED, true); }
        public int reach() { return 2; }
        public List<BlockPos> placementCells(BlockPos base) { return List.of(base, base.above(), base.above(2)); }

        public Optional<BlockState> tilled(Level level, BlockPos ground, ItemStack hoe) {
            // Cookery's HoeUseEvent handles this in a Player event, outside getToolModifiedState.
            // Keep its three native soils and real hoe requirement, without impersonating a player.
            BlockState soil = level.getBlockState(ground);
            return hoe.getItem() instanceof HoeItem && hoe.canPerformAction(ItemAbilities.HOE_TILL)
                && (soil.is(Blocks.DIRT) || soil.is(Blocks.GRASS_BLOCK) || soil.is(Blocks.DIRT_PATH))
                && level.getBlockState(ground.above()).is(Blocks.WATER)
                ? Optional.of(Blocks.FARMLAND.defaultBlockState()) : Optional.empty();
        }

        public boolean canPlant(LevelReader level, BlockPos base) {
            return base.getY() < level.getMaxBuildHeight() - 2 && level.getFluidState(base).is(FluidTags.WATER)
                && level.getBlockState(base.above()).isAir() && level.getBlockState(base.above(2)).isAir()
                && planted().canSurvive(level, base);
        }

        public boolean ripe(LevelReader level, BlockPos base) {
            BlockState state = level.getBlockState(base);
            return state.is(block()) && state.getValue(RiceCropBlock.LOCATION) == RiceCropBlock.DOWN
                && ((CropBlock) block()).isMaxAge(state);
        }

        public List<BlockPos> attached(LevelReader level, List<BlockPos> roots) {
            List<BlockPos> parts = new ArrayList<>();
            for (BlockPos root : roots) {
                for (int up = 1; up <= 2; up++) {
                    if (level.getBlockState(root.above(up)).is(block())) parts.add(root.above(up));
                }
            }
            return List.copyOf(parts);
        }
    }

    private record Lettuce() implements CropBehavior {
        public Block block() { return ModBlocks.LETTUCE_CROP.get(); }
        public List<Item> seeds() { return List.of(ModItems.LETTUCE_SEED.get()); }
        public boolean needsFarmland() { return true; }
        public boolean ripe(LevelReader level, BlockPos base) {
            BlockState state = level.getBlockState(base);
            return state.is(block()) && ((CropBlock) block()).isMaxAge(state);
        }
    }

    // These native crop interactions require a Player despite only reading the crop, RNG and world. Keep their
    // exact harvest/reset rules here with the real resident as actor, rather than impersonating a player.
    private record Picking(Block block, Item seed, boolean needsFarmland, int afterAge) implements CropBehavior {
        public List<Item> seeds() { return List.of(seed); }
        public boolean ripe(LevelReader level, BlockPos base) {
            BlockState state = level.getBlockState(base);
            return state.is(block) && (block instanceof CropBlock crop ? crop.isMaxAge(state)
                : ((TeaTreeBlock) block).isMaxAge(state));
        }

        public Optional<Harvest> gather(ServerLevel level, Worker who, List<BlockPos> roots) {
            List<ItemStack> items = new ArrayList<>();
            List<WorkNoise> noises = new ArrayList<>();
            for (BlockPos root : roots) {
                if (!level.isLoaded(root) || !ripe(level, root)) continue;
                Item result = block == ModBlocks.TEA_TREE.get() ? ModItems.FRESH_TEA_LEAVES.get()
                    : block == ModBlocks.TOMATO_CROP.get() ? ModItems.TOMATO.get()
                    : level.getRandom().nextInt(5) == 0 ? ModItems.GREEN_CHILI.get() : ModItems.RED_CHILI.get();
                BlockState next = block.defaultBlockState().setValue(block instanceof CropBlock ? CropBlock.AGE : TeaTreeBlock.AGE, afterAge);
                if (!level.setBlock(root, next, Block.UPDATE_CLIENTS)) continue;
                items.add(new ItemStack(result));
                noises.add(WorkNoise.placed(root, next));
                try {
                    level.playSound(null, root, SoundEvents.CROP_BREAK, SoundSource.BLOCKS, 1.0F, 1.0F);
                    level.gameEvent(GameEvent.BLOCK_CHANGE, root, GameEvent.Context.of(who.body(), next));
                } catch (RuntimeException | LinkageError broken) {
                    // The crop already regrew: settle its fruit rather than losing it or rolling the harvest again.
                    org.slf4j.LoggerFactory.getLogger("folkways-cookery").warn("crop {} changed before notification failed", root, broken);
                }
            }
            return Optional.of(new Harvest(items, noises, noises.size()));
        }
    }
}
