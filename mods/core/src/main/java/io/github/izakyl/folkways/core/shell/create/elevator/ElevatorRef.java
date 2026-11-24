package io.github.izakyl.folkways.core.shell.create.elevator;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

// The fixed pulley identifies a lift across unloads and reassembly; its moving entity does not.
public record ElevatorRef(ResourceKey<Level> dimension, BlockPos pulley) {

    public ElevatorRef {
        pulley = pulley.immutable();
    }

    public UUID id() {
        String identity = dimension.location() + "/" + pulley.asLong();
        return UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8));
    }
}
