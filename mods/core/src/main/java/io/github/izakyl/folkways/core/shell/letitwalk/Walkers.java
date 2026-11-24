package io.github.izakyl.folkways.core.shell.letitwalk;

import dev.letitwalk.content.PilotControlsBlockEntity;
import dev.letitwalk.content.SimpleFacingBlock;
import dev.letitwalk.content.WalkerCoreBlockEntity;
import dev.letitwalk.walker.WalkerTracker;
import dev.ryanhcode.sable.Sable;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/** Finds the walker a pilot station drives, and where that walker stands and faces in the world. */
final class Walkers {

    // Where each walker was last sent, so the position reading can say it has arrived. In memory only: a run that
    // outlives a restart writes its destination again on its next step.
    private static final Map<WalkerCoreBlockEntity, Destination> DESTINATIONS = new WeakHashMap<>();

    private Walkers() {
    }

    /** The pilot station at a control's anchor. */
    static Optional<PilotControlsBlockEntity> station(ServerLevel level, WorldPos anchor) {
        if (!anchor.in(level)) {
            return Optional.empty();
        }
        BlockPos block = anchor.block(level);
        return level.isLoaded(block) && level.getBlockEntity(block) instanceof PilotControlsBlockEntity station
            ? Optional.of(station) : Optional.empty();
    }

    /**
     * The assembled walker a station drives: the one it is linked to, or else the one whose hull carries it, as the
     * native pilot packet finds it.
     */
    static Optional<WalkerCoreBlockEntity> core(ServerLevel level, PilotControlsBlockEntity station) {
        WalkerCoreBlockEntity core = null;
        if (station.linkedWalker != null) {
            core = WalkerCoreBlockEntity.linked(level.getServer(), station.linkedWalker);
        } else {
            var hull = Sable.HELPER.getContaining(level, station.getBlockPos());
            BlockPos corePos = hull == null ? null : WalkerTracker.coreOf(hull);
            if (corePos != null && level.isLoaded(corePos)
                    && level.getBlockEntity(corePos) instanceof WalkerCoreBlockEntity found) {
                core = found;
            }
        }
        return core != null && !core.isRemoved() && core.isAssembled() ? Optional.of(core) : Optional.empty();
    }

    static Optional<WalkerCoreBlockEntity> core(ServerLevel level, WorldPos anchor) {
        return station(level, anchor).flatMap(station -> core(level, station));
    }

    /** The assembled walker whose core is the block at the anchor. */
    static Optional<WalkerCoreBlockEntity> coreAt(ServerLevel level, WorldPos anchor) {
        if (!anchor.in(level)) {
            return Optional.empty();
        }
        BlockPos block = anchor.block(level);
        return level.isLoaded(block) && level.getBlockEntity(block) instanceof WalkerCoreBlockEntity core
            && !core.isRemoved() && core.isAssembled() ? Optional.of(core) : Optional.empty();
    }

    /** The middle of the walker's core block, in the world. */
    static Optional<Vec3> position(WalkerCoreBlockEntity core) {
        if (!(core.getLevel() instanceof ServerLevel level)) {
            return Optional.empty();
        }
        return WorldSpaces.world(level, WorldSpaces.at(level, core.getBlockPos())).map(bottom -> bottom.add(0, .5, 0));
    }

    /** Where the walker's front points in the world, flattened onto the ground; its core faces its front. */
    static Optional<Vec3> heading(WalkerCoreBlockEntity core) {
        if (!(core.getLevel() instanceof ServerLevel level)) {
            return Optional.empty();
        }
        Direction front = core.getBlockState().getValue(SimpleFacingBlock.FACING);
        BlockPos at = core.getBlockPos();
        var here = WorldSpaces.world(level, WorldSpaces.at(level, at));
        var ahead = WorldSpaces.world(level, WorldSpaces.at(level, at.relative(front)));
        if (here.isEmpty() || ahead.isEmpty()) {
            return Optional.empty();
        }
        Vec3 flat = ahead.get().subtract(here.get()).multiply(1, 0, 1);
        return flat.lengthSqr() < 1e-6 ? Optional.empty() : Optional.of(flat.normalize());
    }

    /** Moves the station's levers as a pilot's hands would, for whoever watches. */
    static void showKeys(ServerLevel level, PilotControlsBlockEntity station, int keys) {
        int shown = keys & 15;
        if (station.keys != shown) {
            station.keys = shown;
            station.setChanged();
            level.sendBlockUpdated(station.getBlockPos(), station.getBlockState(), station.getBlockState(), 3);
        }
    }

    private record Destination(Vec3 position, Optional<Vec3> heading) {
    }

    /** Locks the hull heading on the first write in range of this destination. */
    static Optional<Vec3> approachHeading(WalkerCoreBlockEntity core, Vec3 position, Optional<Vec3> heading) {
        synchronized (DESTINATIONS) {
            Destination previous = DESTINATIONS.get(core);
            if (previous != null && previous.position().equals(position) && previous.heading().isPresent()) {
                return previous.heading();
            }
            DESTINATIONS.put(core, new Destination(position, heading));
            return heading;
        }
    }

    static Optional<Vec3> destination(WalkerCoreBlockEntity core) {
        synchronized (DESTINATIONS) {
            return Optional.ofNullable(DESTINATIONS.get(core)).map(Destination::position);
        }
    }
}
