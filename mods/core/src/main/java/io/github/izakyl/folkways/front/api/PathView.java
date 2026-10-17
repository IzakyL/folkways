package io.github.izakyl.folkways.front.api;

import io.github.izakyl.folkways.core.api.terms.WorldPos;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

public interface PathView {

    UUID id();

    ResourceLocation delegation();

    ResourceKey<Level> dimension();

    List<BlockPos> points();

    WorldPos at(BlockPos point);

    Settings settings();
}
