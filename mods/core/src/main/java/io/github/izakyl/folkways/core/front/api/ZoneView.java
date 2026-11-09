package io.github.izakyl.folkways.core.front.api;

import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

public interface ZoneView {

    UUID id();

    ResourceLocation delegation();

    ResourceKey<Level> dimension();

    WorldPos at(BlockPos cell);

    Set<BlockPos> cells();

    Settings settings();
}
