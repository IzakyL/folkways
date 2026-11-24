package io.github.izakyl.folkways.core.shell.cookery.api;

import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.KitchenwareRacksBlockEntity;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModBlocks;
import io.github.izakyl.folkways.core.item.api.Containers;
import java.util.Optional;
import net.minecraft.world.level.block.state.BlockState;

public final class RackPorts {
    private static boolean registered;

    private RackPorts() {
    }

    public static synchronized void register() {
        if (!registered) {
            Containers.register(KitchenwareRacksBlockEntity.class,
                (level, rack) -> Optional.of(new RackContainer(rack)));
            registered = true;
        }
    }

    public static boolean matches(BlockState state) {
        return state.is(ModBlocks.KITCHENWARE_RACKS.get());
    }
}
