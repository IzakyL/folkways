package io.github.izakyl.folkways.core.front.api;

import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.colony.api.ColonyView;
import io.github.izakyl.folkways.core.resident.api.Body;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;

public sealed interface Endorsement {

    Colony colony();

    ColonyView view();

    Player by();

    record Pointed(Colony colony, ColonyView view, Player by, Body at, boolean enrolled)
        implements Endorsement {
    }

    record Called(Colony colony, ColonyView view, Player by, BlockPos at, ResourceLocation kind)
        implements Endorsement {
    }
}
