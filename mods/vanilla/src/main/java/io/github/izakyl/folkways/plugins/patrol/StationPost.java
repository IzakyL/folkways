package io.github.izakyl.folkways.plugins.patrol;

import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import io.github.izakyl.folkways.front.api.Settings;
import io.github.izakyl.folkways.front.api.ZoneView;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

/** A loaded entity post adapts to the existing sentry runner without creating a drawn area. */
record StationPost(UUID id, UUID entity, WorldPos anchor, ResourceKey<Level> dimension,
    Set<BlockPos> cells, Settings settings) implements ZoneView {

    static StationPost of(UUID id, Entity entity, Settings settings) {
        AABB bounds = entity.getBoundingBox().inflate(1, 0, 1);
        BlockPos at = entity.blockPosition();
        Set<BlockPos> cells = BlockPos.betweenClosedStream(
            BlockPos.containing(bounds.minX, at.getY() - 1, bounds.minZ),
            BlockPos.containing(bounds.maxX, at.getY() + 1, bounds.maxZ))
            .map(BlockPos::immutable).collect(Collectors.toUnmodifiableSet());
        return new StationPost(id, entity.getUUID(), WorldSpaces.at(entity), entity.level().dimension(), cells,
            settings);
    }

    @Override
    public ResourceLocation delegation() {
        return SentryPosts.STATION;
    }

    @Override
    public WorldPos at(BlockPos cell) {
        return WorldPos.of(dimension, cell);
    }
}
