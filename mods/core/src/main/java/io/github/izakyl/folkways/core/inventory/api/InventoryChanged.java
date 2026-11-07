package io.github.izakyl.folkways.core.inventory.api;

import io.github.izakyl.folkways.core.world.api.WorldPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.Event;

// Synchronous notices let the runtime locate observers without exposing colonies to inventory providers.
public abstract class InventoryChanged extends Event {

    private InventoryChanged() {
    }

    public static final class Store extends InventoryChanged {

        private final ServerLevel level;
        private final WorldPos at;

        public Store(ServerLevel level, WorldPos at) {
            this.level = level;
            this.at = at;
        }

        public ServerLevel level() {
            return level;
        }

        public WorldPos at() {
            return at;
        }
    }

    public static final class Resident extends InventoryChanged {

        private final Entity entity;

        public Resident(Entity entity) {
            this.entity = entity;
        }

        public Entity entity() {
            return entity;
        }
    }
}
