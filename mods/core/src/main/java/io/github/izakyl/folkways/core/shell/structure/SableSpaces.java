package io.github.izakyl.folkways.core.shell.structure;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.ClientSubLevelAccess;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

public final class SableSpaces implements WorldSpaces.Source {

    private static final double FEET_DEPTH = 0.05D;

    @Override
    public Optional<Space> frame(Level level, UUID id) {
        SubLevelContainer container = SubLevelContainer.getContainer(level);
        return wrap(container == null ? null : container.getSubLevel(id));
    }

    @Override
    public java.util.List<Space> frames(Level level) {
        SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return java.util.List.of();
        }
        return container.getAllSubLevels().stream().flatMap(ship -> wrap(ship).stream()).toList();
    }

    @Override
    public Optional<Space> containing(Level level, BlockPos storage) {
        return wrap(Sable.HELPER.getContaining(level, storage));
    }

    @Override
    public Optional<Space> aboard(Entity entity) {
        Optional<Space> tracked = wrap(Sable.HELPER.getTrackingOrVehicleSubLevel(entity));
        return tracked.isPresent() ? tracked : standingOn(entity);
    }

    // Sable follows what moves about on a ship; a mob standing still on one, asleep or held in place, it does not.
    // Such a body is aboard the ship whose solid block lies under its feet. Each ship is asked in turn, as the
    // bounds Sable keeps of them are only drawn up as physics steps them.
    private static Optional<Space> standingOn(Entity entity) {
        Level level = entity.level();
        SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return Optional.empty();
        }
        Vec3 feet = entity.position().subtract(0.0D, FEET_DEPTH, 0.0D);
        for (SubLevel ship : container.getAllSubLevels()) {
            if (ship.isRemoved()) {
                continue;
            }
            BlockPos cell = BlockPos.containing(ship.logicalPose().transformPositionInverse(feet));
            if (ship.getPlot().getBoundingBox().contains(cell.getX(), cell.getY(), cell.getZ())
                    && level.isLoaded(cell) && !level.getBlockState(cell).getCollisionShape(level, cell).isEmpty()) {
                return wrap(ship);
            }
        }
        return Optional.empty();
    }

    private static Optional<Space> wrap(SubLevel subLevel) {
        return subLevel == null || subLevel.isRemoved() ? Optional.empty() : Optional.of(new Space(subLevel));
    }

    public record Space(SubLevel subLevel) implements WorldSpaces.Frame {
        @Override
        public UUID id() {
            return subLevel.getUniqueId();
        }

        @Override
        public Level level() {
            return subLevel.getLevel();
        }

        @Override
        public Optional<net.minecraft.world.level.levelgen.structure.BoundingBox> bounds() {
            var box = subLevel.getPlot().getBoundingBox();
            return Optional.of(net.minecraft.world.level.levelgen.structure.BoundingBox.fromCorners(
                local(new BlockPos(box.minX(), box.minY(), box.minZ())),
                local(new BlockPos(box.maxX(), box.maxY(), box.maxZ()))));
        }

        @Override
        public BlockPos storageOrigin() {
            return subLevel.getPlot().getCenterBlock();
        }

        @Override
        public Vec3 toWorld(Vec3 local) {
            return subLevel.logicalPose().transformPosition(local.add(Vec3.atLowerCornerOf(storageOrigin())));
        }

        @Override
        public Vec3 toWorld(Vec3 local, float partialTick) {
            return pose(partialTick).transformPosition(local.add(Vec3.atLowerCornerOf(storageOrigin())));
        }

        @Override
        public Vec3 toLocal(Vec3 world, float partialTick) {
            return pose(partialTick).transformPositionInverse(world).subtract(Vec3.atLowerCornerOf(storageOrigin()));
        }

        private Pose3dc pose(float partialTick) {
            return subLevel instanceof ClientSubLevelAccess drawn ? drawn.renderPose(partialTick) : subLevel.logicalPose();
        }

        @Override
        public Vec3 angularVelocity() {
            return subLevel instanceof ServerSubLevel server
                ? new Vec3(server.latestAngularVelocity.x, server.latestAngularVelocity.y,
                    server.latestAngularVelocity.z).scale(1.0 / 20) : Vec3.ZERO;
        }

        @Override
        public Vec3 velocity(Vec3 local) {
            if (!(subLevel instanceof ServerSubLevel server)) {
                return Vec3.ZERO;
            }
            Vec3 arm = toWorld(local).subtract(new Vec3(server.logicalPose().position().x(),
                server.logicalPose().position().y(), server.logicalPose().position().z()));
            return new Vec3(server.latestLinearVelocity.x, server.latestLinearVelocity.y, server.latestLinearVelocity.z)
                .scale(1.0 / 20).add(angularVelocity().cross(arm));
        }

        @Override
        public Vec3 toLocal(Vec3 world) {
            return subLevel.logicalPose().transformPositionInverse(world).subtract(Vec3.atLowerCornerOf(storageOrigin()));
        }
    }
}
