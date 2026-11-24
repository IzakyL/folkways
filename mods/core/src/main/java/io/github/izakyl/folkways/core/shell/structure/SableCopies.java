package io.github.izakyl.folkways.core.shell.structure;

import dev.ryanhcode.sable.api.SubLevelHelper;
import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicket;
import dev.ryanhcode.sable.api.sublevel.ticket.SubLevelTicketInfo;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.storage.HoldingSubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelData;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelSerializer;
import io.github.izakyl.folkways.core.world.api.CopiedIds;
import io.github.izakyl.folkways.core.world.api.FrameCopies;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * Copies Sable sub-levels (ships, hulls, a walker and its legs) through Sable's own serialization: the copy is loaded
 * into the same plot of the other level's container, so every storage cell and every position a block entity keeps
 * stays true, at the same pose and with the same velocities. Its identities are fresh, so the copy and its parts find
 * only each other. Removal goes through the container as a deletion, never an unload, so nothing is held for saving.
 */
public final class SableCopies implements FrameCopies.Copier {

    // A plot larger than this is a world, not a machine: counting its blocks would take too long.
    private static final long MAX_VOLUME = 128L * 128 * 128;

    private record Read(UUID original, UUID copy, SubLevelData data, Vector3d linear, Vector3d angular,
                        FrameCopies.Captured captured) {
    }

    @Override
    public Optional<FrameCopies.Capture> capture(ServerLevel level, Collection<UUID> frames, CopiedIds ids,
                                                 FrameCopies.Limits limits) {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return Optional.empty();
        }
        Set<ServerSubLevel> chosen = new LinkedHashSet<>();
        for (UUID id : frames) {
            if (container.getSubLevel(id) instanceof ServerSubLevel ship && !ship.isRemoved()) {
                chosen.add(ship);
                SubLevelHelper.getLoadingDependencyChain(ship).forEach(chosen::add);
                for (SubLevel joined : SubLevelHelper.getConnectedChain(ship)) {
                    if (joined instanceof ServerSubLevel server) {
                        chosen.add(server);
                    }
                }
            }
        }
        chosen.removeIf(SubLevel::isRemoved);
        if (chosen.isEmpty()) {
            return Optional.empty();
        }
        if (chosen.size() > limits.frames()) {
            throw new IllegalArgumentException("copy_too_large");
        }
        List<UUID> chain = chosen.stream().map(SubLevel::getUniqueId).toList();
        chain.forEach(ids::of);
        List<Read> reads = new ArrayList<>();
        int blocks = 0;
        for (ServerSubLevel ship : chosen) {
            BoundingBox3ic plot = ship.getPlot().getBoundingBox();
            if ((long) plot.width() * plot.height() * plot.length() > MAX_VOLUME) {
                throw new IllegalArgumentException("copy_too_large");
            }
            int own = 0;
            for (BlockPos pos : BlockPos.betweenClosed(plot.minX(), plot.minY(), plot.minZ(), plot.maxX(), plot.maxY(),
                    plot.maxZ())) {
                if (!level.getBlockState(pos).isAir()) {
                    own++;
                }
            }
            blocks += own;
            if (blocks > limits.blocks()) {
                throw new IllegalArgumentException("copy_too_large");
            }
            CompoundTag tag = ids.retag(SubLevelSerializer.toData(ship, chain).fullTag());
            SubLevelData data = SubLevelSerializer.fromData(tag);
            if (data == null) {
                continue;
            }
            Vector3d linear = new Vector3d();
            Vector3d angular = new Vector3d();
            RigidBodyHandle handle = RigidBodyHandle.of(ship);
            if (handle != null && handle.isValid()) {
                handle.getLinearVelocity(linear);
                handle.getAngularVelocity(angular);
            }
            BoundingBox3dc world = ship.boundingBox();
            reads.add(new Read(ship.getUniqueId(), data.uuid(), data, linear, angular,
                new FrameCopies.Captured(ship.getUniqueId(), data.uuid(), own,
                    new BoundingBox(plot.minX(), plot.minY(), plot.minZ(), plot.maxX(), plot.maxY(), plot.maxZ()),
                    new AABB(world.minX(), world.minY(), world.minZ(), world.maxX(), world.maxY(), world.maxZ()))));
        }
        List<Runnable> restore = FrameCopies.restorers(level, reads.stream().map(Read::original).toList());
        return Optional.of(new Capture(reads, restore));
    }

    @Override
    public int clear(ServerLevel level) {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return 0;
        }
        List<ServerSubLevel> all = List.copyOf(container.getAllSubLevels());
        all.forEach(ship -> delete(container, ship));
        return all.size();
    }

    private record Capture(List<Read> reads, List<Runnable> restore) implements FrameCopies.Capture {

        @Override
        public List<FrameCopies.Captured> frames() {
            return reads.stream().map(Read::captured).toList();
        }

        @Override
        public FrameCopies.Placed place(ServerLevel into) {
            ServerSubLevelContainer container = SubLevelContainer.getContainer(into);
            List<Copy> copies = new ArrayList<>();
            Placed placed = new Placed(into, copies, restore);
            if (container == null) {
                return placed;
            }
            PhysicsPipeline pipeline = container.physicsSystem().getPipeline();
            for (Read read : reads) {
                ServerSubLevel copy = SubLevelSerializer.fullyLoad(into, read.data());
                if (copy == null) {
                    continue;
                }
                // Loading keeps only part of a saved velocity; a copy carries on exactly as the original moves.
                pipeline.resetVelocity(copy);
                pipeline.addLinearAndAngularVelocity(copy, read.linear(), read.angular());
                copies.add(new Copy(container, read.original(), read.copy()));
            }
            return placed;
        }
    }

    private record Placed(ServerLevel level, List<Copy> made, List<Runnable> restore)
            implements FrameCopies.Placed {

        @Override
        public List<FrameCopies.Copy> copies() {
            return List.copyOf(made);
        }

        @Override
        public void remove() {
            try {
                made.forEach(Copy::remove);
            } finally {
                restore.forEach(Runnable::run);
            }
        }
    }

    private record Copy(ServerSubLevelContainer container, UUID original, UUID id) implements FrameCopies.Copy {

        private Optional<ServerSubLevel> live() {
            return container.getSubLevel(id) instanceof ServerSubLevel ship && !ship.isRemoved()
                ? Optional.of(ship) : Optional.empty();
        }

        @Override
        public boolean present() {
            return live().isPresent();
        }

        @Override
        public Optional<FrameCopies.Pose> pose() {
            return live().map(ship -> new FrameCopies.Pose(new Vec3(ship.logicalPose().position().x(),
                ship.logicalPose().position().y(), ship.logicalPose().position().z()),
                new Quaterniond(ship.logicalPose().orientation())));
        }

        @Override
        public void remove() {
            if (container.getSubLevel(id) instanceof ServerSubLevel ship) {
                delete(container, ship);
                return;
            }
            // A copy that wandered into an unloaded chunk waits in memory to load again: load it, to delete it.
            HoldingSubLevel held = container.getHoldingChunkMap().getHoldingSubLevel(id);
            if (held != null) {
                container.getHoldingChunkMap().loadHoldingSubLevel(held);
                if (container.getSubLevel(id) instanceof ServerSubLevel ship) {
                    delete(container, ship);
                }
            }
        }
    }

    private static void delete(ServerSubLevelContainer container, ServerSubLevel ship) {
        SubLevelTicketInfo tickets = container.getAllTickets().get(ship.getUniqueId());
        if (tickets != null) {
            for (SubLevelLoadingTicket<?> ticket : List.copyOf(tickets.tickets())) {
                release(container, ship, ticket);
            }
        }
        if (container.getSubLevel(ship.getUniqueId()) == ship) {
            container.removeSubLevel(ship, SubLevelRemovalReason.REMOVED);
        }
    }

    private static <T> void release(ServerSubLevelContainer container, ServerSubLevel ship,
                                    SubLevelLoadingTicket<T> ticket) {
        container.removeForceLoadTicket(ship, ticket.type(), ticket.key());
    }
}
