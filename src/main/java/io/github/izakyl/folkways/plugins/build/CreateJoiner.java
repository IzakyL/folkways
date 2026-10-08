package io.github.izakyl.folkways.plugins.build;

import com.mojang.authlib.GameProfile;
import com.simibubi.create.content.trains.track.BezierConnection;
import com.simibubi.create.content.trains.track.TrackBlock;
import com.simibubi.create.content.trains.track.TrackBlockEntity;
import com.simibubi.create.content.trains.track.TrackBlockItem;
import com.simibubi.create.content.trains.track.TrackPlacement;
import com.simibubi.create.content.trains.track.TrackPropagator;
import io.github.izakyl.folkways.plugins.CreateMod;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;

final class CreateJoiner implements Joints.Joiner {

    private static final GameProfile HANDS =
        new GameProfile(UUID.nameUUIDFromBytes("folkways:track-layer".getBytes()), "[Folkways track layer]");

    private static final Supplier<Item> GIRDER = () ->
        BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath(CreateMod.ID, "metal_girder"));

    private record Tried(TrackPlacement.PlacementInfo info, List<ItemStack> left) {
    }

    @Override
    public Optional<List<ItemStack>> cost(ServerLevel level, BlockPos from, BlockPos to) {
        if (joined(level, from, to)) {
            return Optional.of(List.of());
        }
        Optional<Tried> tried = attempt(level, from, to, List.of());
        if (tried.isEmpty() || tried.get().info().requiredTracks <= 0 || tried.get().info().hasRequiredTracks) {
            return Optional.empty();
        }
        Item track = level.getBlockState(from).getBlock().asItem();
        return Optional.of(stacks(track, tried.get().info().requiredTracks));
    }

    @Override
    public boolean joined(Level level, BlockPos from, BlockPos to) {
        return level.getBlockEntity(from) instanceof TrackBlockEntity start && start.getConnections().containsKey(to)
            && level.getBlockEntity(to) instanceof TrackBlockEntity end && end.getConnections().containsKey(from);
    }

    @Override
    public Joints.Joining join(ServerLevel level, BlockPos from, BlockPos to, List<ItemStack> paid) {
        Optional<Tried> tried = attempt(level, from, to, paid);
        if (tried.isEmpty()) {
            return new Joints.Joining(false, paid);
        }
        return new Joints.Joining(joined(level, from, to), tried.get().left());
    }

    @Override
    public List<ItemStack> loosen(ServerLevel level, BlockPos cell) {
        if (!(level.getBlockEntity(cell) instanceof TrackBlockEntity track)) {
            return List.of();
        }
        List<ItemStack> refund = new ArrayList<>();
        for (Map.Entry<BlockPos, BezierConnection> held : new LinkedHashMap<>(track.getConnections()).entrySet()) {
            BezierConnection curve = held.getValue();
            refund.addAll(stacks(curve.getMaterial().asStack().getItem(), curve.getTrackItemCost()));
            refund.addAll(stacks(GIRDER.get(), curve.getGirderItemCost()));
            if (level.getBlockEntity(held.getKey()) instanceof TrackBlockEntity other) {
                other.removeConnection(cell);
            }
            track.removeConnection(held.getKey());
        }
        if (!refund.isEmpty()) {
            TrackPropagator.onRailRemoved(level, cell, level.getBlockState(cell));
        }
        return List.copyOf(refund);
    }

    private static Optional<Tried> attempt(ServerLevel level, BlockPos from, BlockPos to, List<ItemStack> paid) {
        BlockState start = level.getBlockState(from);
        BlockState end = level.getBlockState(to);
        if (!(start.getBlock() instanceof TrackBlock) || !(end.getBlock() instanceof TrackBlock)
            || from.equals(to)) {
            return Optional.empty();
        }
        FakePlayer hands = FakePlayerFactory.get(level, HANDS);
        Vec3 look = Vec3.atCenterOf(to).subtract(Vec3.atCenterOf(from)).normalize();
        hands.moveTo(from.getX() + 0.5D, from.getY() + 1.0D, from.getZ() + 0.5D,
            (float) Math.toDegrees(Math.atan2(-look.x, look.z)), (float) Math.toDegrees(-Math.asin(look.y)));
        Inventory pack = hands.getInventory();
        pack.clearContent();
        for (int slot = 0; slot < paid.size() && slot < pack.items.size(); slot++) {
            pack.items.set(slot, paid.get(slot).copy());
        }
        pack.selected = 0;
        ItemStack held = new ItemStack(start.getBlock().asItem());
        List<ItemStack> left = new ArrayList<>();
        try {
            TrackBlockItem.select(level, from, look, held);
            TrackPlacement.PlacementInfo info = TrackPlacement.tryConnect(level, hands, to, end, held, false, true);
            for (ItemStack stack : pack.items) {
                if (!stack.isEmpty()) {
                    left.add(stack.copy());
                }
            }
            return Optional.of(new Tried(info, left));
        } finally {
            pack.clearContent();
        }
    }

    private static List<ItemStack> stacks(Item item, int count) {
        List<ItemStack> made = new ArrayList<>();
        int max = new ItemStack(item).getMaxStackSize();
        for (int left = count; left > 0; left -= max) {
            made.add(new ItemStack(item, Math.min(max, left)));
        }
        return made;
    }
}
