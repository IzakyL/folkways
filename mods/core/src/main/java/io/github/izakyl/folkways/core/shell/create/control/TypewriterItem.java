package io.github.izakyl.folkways.core.shell.create.control;

import com.simibubi.create.content.redstone.link.RedstoneLinkBlock;
import com.simibubi.create.content.redstone.link.RedstoneLinkBlockEntity;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

public final class TypewriterItem extends BlockItem {
    public TypewriterItem(Block block, Properties properties) {
        super(block, properties);
    }

    static List<BlockPos> points(ItemStack stack) {
        var tag = stack.get(ControlHub.CONNECTIONS.get());
        if (tag == null) {
            return List.of();
        }
        return Arrays.stream(tag.getLongArray("Positions")).mapToObj(BlockPos::of).toList();
    }

    static CompoundTag connections(Level level, BlockPos pos, List<BlockPos> points) {
        var tag = new CompoundTag();
        tag.putString("Dimension", level.dimension().location().toString());
        WorldSpaces.containing(level, pos).ifPresent(frame -> tag.putUUID("Frame", frame.id()));
        tag.putLongArray("Positions", points.stream().mapToLong(BlockPos::asLong).toArray());
        return tag;
    }

    static boolean sameSpace(ItemStack stack, Level level, BlockPos pos) {
        var tag = stack.get(ControlHub.CONNECTIONS.get());
        if (tag == null || points(stack).isEmpty()) {
            return true;
        }
        var frame = WorldSpaces.containing(level, pos).map(WorldSpaces.Frame::id);
        return tag.getString("Dimension").equals(level.dimension().location().toString())
            && (tag.hasUUID("Frame") ? frame.filter(tag.getUUID("Frame")::equals).isPresent() : frame.isEmpty());
    }

    @Override
    public InteractionResult onItemUseFirst(ItemStack stack, UseOnContext context) {
        var level = context.getLevel();
        var pos = context.getClickedPos();
        var target = level.getBlockEntity(pos);
        if (!(target instanceof Terminal) && !(target instanceof RedstoneLinkBlockEntity)) {
            return InteractionResult.PASS;
        }
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        var player = context.getPlayer();
        if (player == null || !player.mayUseItemAt(pos, context.getClickedFace(), stack)) {
            return InteractionResult.FAIL;
        }
        String message = "folkways.core.control_hub.invalid";
        var points = new ArrayList<>(points(stack));
        if (!sameSpace(stack, level, pos)) {
            message = "folkways.core.control_hub.space";
        } else if (target instanceof Terminal terminal) {
            terminal.reconcile();
            if (terminal.unbound()) {
                if (points.remove(pos)) {
                    terminal.restore();
                } else {
                    points.add(pos.immutable());
                }
                message = "folkways.core.control_hub.connections";
            }
        } else if (Links.eligible(target)) {
            var facing = target.getBlockState().getValue(RedstoneLinkBlock.FACING);
            if (!level.setBlockAndUpdate(pos, ControlHub.TERMINAL.get().defaultBlockState()
                    .setValue(TerminalBlock.FACING, facing))) {
                return InteractionResult.FAIL;
            }
            if (level.getBlockEntity(pos) instanceof Terminal terminal) {
                terminal.reconcile();
            }
            if (!points.contains(pos)) {
                points.add(pos.immutable());
            }
            message = "folkways.core.control_hub.connections";
        }
        if (message.equals("folkways.core.control_hub.connections")) {
            if (points.isEmpty()) {
                stack.remove(ControlHub.CONNECTIONS.get());
            } else {
                stack.set(ControlHub.CONNECTIONS.get(), connections(level, pos, points));
            }
        }
        player.displayClientMessage(Component.translatable(message, points.size()), true);
        return InteractionResult.CONSUME;
    }

    @Override
    public InteractionResult place(BlockPlaceContext context) {
        if (!sameSpace(context.getItemInHand(), context.getLevel(), context.getClickedPos())) {
            if (context.getPlayer() != null && !context.getLevel().isClientSide()) {
                context.getPlayer().displayClientMessage(Component.translatable("folkways.core.control_hub.space"), true);
            }
            return InteractionResult.FAIL;
        }
        return super.place(context);
    }

    @Override
    protected boolean placeBlock(BlockPlaceContext context, BlockState state) {
        if (!super.placeBlock(context, state)) {
            return false;
        }
        if (!context.getLevel().isClientSide()
                && context.getLevel().getBlockEntity(context.getClickedPos()) instanceof Typewriter writer) {
            for (var pos : points(context.getItemInHand())) {
                writer.claim(pos);
            }
        }
        return true;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        tooltip.add(Component.translatable("folkways.core.control_hub.connections", points(stack).size()));
    }
}
