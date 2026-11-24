package io.github.izakyl.folkways.core.shell.create.control;

import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public final class Typewriter extends BlockEntity {
    private final Set<BlockPos> points = new LinkedHashSet<>();
    private UUID id = UUID.randomUUID();

    public Typewriter(BlockPos pos, BlockState state) {
        super(ControlHub.TYPEWRITER_ENTITY.get(), pos, state);
    }

    public List<BlockPos> points() {
        return List.copyOf(points);
    }

    UUID id() {
        return id;
    }

    boolean connected(BlockPos pos) {
        return points.contains(pos);
    }

    BlockPos seat() {
        return worldPosition.relative(getBlockState().getValue(TypewriterBlock.FACING));
    }

    void claim(BlockPos pos) {
        if (level == null || !level.hasChunkAt(pos)
                || !WorldSpaces.containing(level, worldPosition).map(WorldSpaces.Frame::id)
                    .equals(WorldSpaces.containing(level, pos).map(WorldSpaces.Frame::id))
                || !(level.getBlockEntity(pos) instanceof Terminal terminal)) {
            return;
        }
        terminal.reconcile();
        if (terminal.unbound() && points.add(pos.immutable())) {
            if (level instanceof ServerLevel world) {
                HubPosts.loaded(world, this);
            }
            terminal.attach(this);
            sync();
        }
    }

    void remove(BlockPos pos) {
        if (points.remove(pos)) {
            sync();
        }
    }

    void reconcile() {
        if (level != null && points.removeIf(pos -> level.hasChunkAt(pos)
                && (!(level.getBlockEntity(pos) instanceof Terminal terminal) || !terminal.belongsTo(this)))) {
            sync();
        }
    }

    void restoreReceivers() {
        for (BlockPos pos : points()) {
            if (level.hasChunkAt(pos) && level.getBlockEntity(pos) instanceof Terminal terminal
                    && terminal.belongsTo(this)) {
                terminal.restore();
            }
        }
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel world) {
            HubPosts.loaded(world, this);
        }
    }

    @Override
    public void setRemoved() {
        if (level instanceof ServerLevel world) {
            HubPosts.unloaded(world, this);
        }
        super.setRemoved();
    }

    private void sync() {
        setChanged();
        var state = getBlockState();
        level.sendBlockUpdated(worldPosition, state, state, Block.UPDATE_CLIENTS);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider lookup) {
        super.saveAdditional(tag, lookup);
        tag.putUUID("Hub", id);
        tag.putLongArray("Points", points.stream().map(pos -> pos.subtract(worldPosition))
            .mapToLong(BlockPos::asLong).toArray());
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider lookup) {
        super.loadAdditional(tag, lookup);
        if (tag.hasUUID("Hub")) {
            id = tag.getUUID("Hub");
        }
        points.clear();
        Arrays.stream(tag.getLongArray("Points")).mapToObj(BlockPos::of).map(worldPosition::offset)
            .forEach(points::add);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider lookup) {
        return saveWithoutMetadata(lookup);
    }

    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
