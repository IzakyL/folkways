package io.github.izakyl.folkways.core.shell.structure;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.ClientSubLevelAccess;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.SubLevel;
import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

public final class SableSpaces implements WorldSpaces.Source {

    @Override
    public Optional<Space> frame(Level level, UUID id) {
        SubLevelContainer container = SubLevelContainer.getContainer(level);
        return wrap(container == null ? null : container.getSubLevel(id));
    }

    @Override
    public Optional<Space> containing(Level level, BlockPos storage) {
        return wrap(Sable.HELPER.getContaining(level, storage));
    }

    @Override
    public Optional<Space> aboard(Entity entity) {
        return wrap(Sable.HELPER.getTrackingOrVehicleSubLevel(entity));
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
        public BlockPos storageOrigin() {
            return subLevel.getPlot().getCenterBlock();
        }

        @Override
        public Vec3 toWorld(Vec3 local) {
            return subLevel.logicalPose().transformPosition(local.add(Vec3.atLowerCornerOf(storageOrigin())));
        }

        @Override
        public Vec3 toWorld(Vec3 local, float partialTick) {
            Pose3dc pose = subLevel instanceof ClientSubLevelAccess drawn
                ? drawn.renderPose(partialTick)
                : subLevel.logicalPose();
            return pose.transformPosition(local.add(Vec3.atLowerCornerOf(storageOrigin())));
        }

        @Override
        public Vec3 toLocal(Vec3 world) {
            return subLevel.logicalPose().transformPositionInverse(world).subtract(Vec3.atLowerCornerOf(storageOrigin()));
        }
    }
}
