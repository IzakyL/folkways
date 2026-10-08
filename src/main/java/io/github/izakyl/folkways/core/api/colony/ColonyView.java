package io.github.izakyl.folkways.core.api.colony;

import io.github.izakyl.folkways.core.api.resident.Resident;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

public interface ColonyView {

    ServerLevel level();

    ResourceKey<Level> dimension();

    // What the colony holds with a cell in this level: blocks, areas and lines.
    List<Holding> holdings();

    // The loaded blocks held for `owner` in this level, where the level stores them.
    Set<BlockPos> blocks(ResourceLocation owner);

    List<Resident> residents();

    int rankOf(Resident resident, String perk);
}
