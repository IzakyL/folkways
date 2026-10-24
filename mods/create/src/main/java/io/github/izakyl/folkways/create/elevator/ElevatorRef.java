package io.github.izakyl.folkways.create.elevator;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

// The fixed pulley identifies a lift across unloads and reassembly; its moving entity does not.
public record ElevatorRef(ResourceKey<Level> dimension, BlockPos pulley) {

    public ElevatorRef {
        pulley = pulley.immutable();
    }

    public ResourceLocation service() {
        String identity = dimension.location() + "/" + pulley.asLong();
        return ResourceLocation.fromNamespaceAndPath("folkways", "elevator/"
            + UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)));
    }
}
