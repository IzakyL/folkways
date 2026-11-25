package io.github.izakyl.folkways.core.colony.api;

import java.util.Objects;
import java.util.UUID;
import net.minecraft.world.entity.item.ItemEntity;

// Goods a plugin leaves lying in the world for a colony, with no resident to carry them: the colony picks them up
// and puts them away as it does goods a resident spilled. Dropped where no one can stand to reach them, or where the
// colony does no work, they simply lie.
public final class LooseGoods {

    @FunctionalInterface
    public interface Access {
        void left(UUID colony, ItemEntity drop);
    }

    private static Access access = (colony, drop) -> { };

    private LooseGoods() {
    }

    public static void install(Access installed) {
        access = Objects.requireNonNull(installed);
    }

    // `drop` must already be in its level.
    public static void left(UUID colony, ItemEntity drop) {
        access.left(colony, drop);
    }
}
