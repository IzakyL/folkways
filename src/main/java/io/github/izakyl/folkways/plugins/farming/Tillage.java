package io.github.izakyl.folkways.plugins.farming;

import io.github.izakyl.folkways.FolkwaysMod;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.ItemAbilities;

final class Tillage {

    static final ItemStack PLAIN_HOE = new ItemStack(Items.WOODEN_HOE);

    // What a resident pulls up before tilling, and the soil a hoe turns once it is gone. The hoe itself refuses soil
    // with anything above it, so a scan cannot ask it without changing the world; these tags stand in for that answer.
    static final TagKey<Block> WEEDS = tag("weeds");
    static final TagKey<Block> FLOWERS = tag("flowers");
    static final TagKey<Block> TILLABLE_UNDER_WEEDS = tag("tillable_under_weeds");

    // What a plot lets its residents pull up: flowers are often planted on purpose, so each is the plot's own choice.
    record Clearing(boolean grass, boolean flowers) {

        static final Clearing ALL = new Clearing(true, true);

        boolean clears(BlockState state) {
            return grass && state.is(WEEDS) || flowers && state.is(FLOWERS);
        }
    }

    private Tillage() {
    }

    static boolean canTill(Level level, BlockPos ground, BlockState state, ItemStack hoe, Clearing clearing) {
        if (tilled(level, ground, state, hoe).isPresent()) {
            return true;
        }
        // The work clears what is in the way and asks the hoe again before turning the soil.
        return clearing.clears(level.getBlockState(ground.above())) && hoe.canPerformAction(ItemAbilities.HOE_TILL)
            && state.is(TILLABLE_UNDER_WEEDS);
    }

    static Optional<BlockState> tilled(Level level, BlockPos ground, BlockState state, ItemStack hoe) {
        if (state.getBlock() instanceof FarmBlock || !level.getBlockState(ground.above()).isAir()) {
            return Optional.empty();
        }
        Vec3 hit = Vec3.atCenterOf(ground);
        UseOnContext context = new UseOnContext(level, null, InteractionHand.MAIN_HAND, hoe,
            new BlockHitResult(hit, Direction.UP, ground, false));
        BlockState turned = state.getToolModifiedState(context, ItemAbilities.HOE_TILL, true);
        return turned != null && turned.is(Blocks.FARMLAND) ? Optional.of(turned) : Optional.empty();
    }

    private static TagKey<Block> tag(String path) {
        return TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath(FolkwaysMod.MOD_ID, path));
    }
}
