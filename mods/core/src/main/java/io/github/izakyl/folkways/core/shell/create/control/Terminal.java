package io.github.izakyl.folkways.core.shell.create.control;

import com.simibubi.create.content.redstone.link.RedstoneLinkBlock;
import com.simibubi.create.content.redstone.link.RedstoneLinkBlockEntity;
import io.github.izakyl.folkways.core.shell.create.dispatch.PackageNetworks;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public final class Terminal extends BlockEntity {
    static final ResourceLocation RECEIVER = ResourceLocation.fromNamespaceAndPath("create", "redstone_link");
    static final ResourceLocation CHAIN_GEARSHIFT =
        ResourceLocation.fromNamespaceAndPath("create", "adjustable_chain_gearshift");
    private static final ResourceLocation ANALOG_TRANSMISSION =
        ResourceLocation.fromNamespaceAndPath("simulated", "analog_transmission");

    private BlockPos owner;
    private UUID hubId;
    private Mob writer;
    private Integer sandboxOutput;
    private int levelSet;
    private int published = -1;

    public Terminal(BlockPos pos, BlockState state) {
        super(ControlHub.TERMINAL_ENTITY.get(), pos, state);
    }

    boolean unbound() {
        return owner == null;
    }

    boolean belongsTo(Typewriter hub) {
        return hub.getBlockPos().equals(owner) && hub.id().equals(hubId);
    }

    Optional<Typewriter> hub() {
        if (level == null || owner == null || !level.hasChunkAt(owner)
                || !(level.getBlockEntity(owner) instanceof Typewriter hub)
                || !belongsTo(hub) || !hub.connected(worldPosition)) {
            return Optional.empty();
        }
        return Optional.of(hub);
    }

    void attach(Typewriter hub) {
        owner = hub.getBlockPos();
        hubId = hub.id();
        setChanged();
        var state = getBlockState();
        level.sendBlockUpdated(worldPosition, state, state, Block.UPDATE_CLIENTS);
        publish();
    }

    boolean staffedBy(Mob resident) {
        return resident != null && resident.isAlive() && resident.level() == level && resident.isPassenger()
            && level instanceof ServerLevel world && hub().filter(hub ->
                PackageNetworks.get().isSeat(world, hub.seat())
                    && PackageNetworks.get().sitting(world, hub.seat()).filter(resident.getUUID()::equals).isPresent())
                .isPresent();
    }

    int output() {
        if (sandboxOutput != null) {
            return sandboxOutput;
        }
        return levelSet != 0 && staffedBy(writer) ? levelSet : 0;
    }

    public int signal() {
        if (level != null && level.isClientSide() && published >= 0) {
            return published;
        }
        int value = output();
        if (level != null) {
            for (Direction side : Direction.values()) {
                BlockPos cell = worldPosition.relative(side);
                if (!level.hasChunkAt(cell)) {
                    continue;
                }
                var adjacent = BuiltInRegistries.BLOCK.getKey(level.getBlockState(cell).getBlock());
                if (adjacent.equals(CHAIN_GEARSHIFT) || adjacent.equals(ANALOG_TRANSMISSION)) {
                    return 15 - value;
                }
            }
        }
        return value;
    }

    /** Drives a copied terminal during an assistant probe. */
    public boolean probeOutput(ServerLevel sandbox, int value) {
        if (level != sandbox || !sandbox.dimension().location().toString().equals("folkways_assistant:sandbox")
            || value < 0 || value > 15) {
            return false;
        }
        sandboxOutput = value;
        publish();
        return true;
    }

    void output(Mob resident, int value) {
        writer = resident;
        levelSet = value;
        publish();
    }

    void restore() {
        var facing = getBlockState().getValue(TerminalBlock.FACING);
        level.setBlockAndUpdate(worldPosition, BuiltInRegistries.BLOCK.get(RECEIVER).defaultBlockState()
            .setValue(RedstoneLinkBlock.FACING, facing).setValue(RedstoneLinkBlock.RECEIVER, true));
        if (level.getBlockEntity(worldPosition) instanceof RedstoneLinkBlockEntity receiver) {
            // Create constructs the receiver's deferred link behaviour on the first NBT read.
            receiver.readClient(new CompoundTag(), level.registryAccess());
        }
    }

    void reconcile() {
        if (level == null) {
            return;
        }
        if (owner != null && level.hasChunkAt(owner) && hub().isEmpty()) {
            restore();
            return;
        }
        if (writer != null && !staffedBy(writer)) {
            writer = null;
            levelSet = 0;
        }
        publish();
    }

    private void publish() {
        int strength = signal();
        if (strength != published) {
            published = strength;
            var state = getBlockState();
            level.sendBlockUpdated(worldPosition, state, state, Block.UPDATE_CLIENTS);
            level.updateNeighborsAt(worldPosition, state.getBlock());
            var attached = worldPosition.relative(state.getValue(TerminalBlock.FACING).getOpposite());
            level.updateNeighborsAt(attached, state.getBlock());
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider lookup) {
        super.saveAdditional(tag, lookup);
        if (owner != null) {
            tag.putLong("OwnerOffset", owner.subtract(worldPosition).asLong());
            tag.putUUID("Hub", hubId);
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider lookup) {
        super.loadAdditional(tag, lookup);
        owner = tag.hasUUID("Hub") ? worldPosition.offset(BlockPos.of(tag.getLong("OwnerOffset"))) : null;
        hubId = owner == null ? null : tag.getUUID("Hub");
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider lookup) {
        var tag = saveWithoutMetadata(lookup);
        tag.putInt("Signal", signal());
        return tag;
    }

    @Override
    public void handleUpdateTag(CompoundTag tag, HolderLookup.Provider lookup) {
        super.handleUpdateTag(tag, lookup);
        published = tag.getInt("Signal");
    }

    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
