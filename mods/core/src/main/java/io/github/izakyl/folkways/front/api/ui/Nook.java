package io.github.izakyl.folkways.front.api.ui;

import io.github.izakyl.folkways.core.api.resident.body.Body;
import java.util.Optional;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;

public interface Nook {

    int width();

    Optional<Body> subject();

    default Optional<Player> viewer() {
        return Optional.empty();
    }

    CompoundTag kept();

    void keep(CompoundTag kept);
}
