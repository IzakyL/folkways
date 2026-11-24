package io.github.izakyl.folkways.core.shell.carts.api;

import com.jusipat.astikorcartsredux.entity.AbstractCargoCart;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

public final class CartHitches {
    public static final String ASTIKOR = "astikorcartsredux";

    private CartHitches() {
    }

    public static Entity puller(AbstractCargoCart cart, UUID savedPuller) {
        Entity puller = cart.getPulling();
        if (puller == null && savedPuller != null && cart.level() instanceof ServerLevel level) {
            puller = level.getEntity(savedPuller);
        }
        if (puller != null && puller.getRemovalReason() == Entity.RemovalReason.UNLOADED_TO_CHUNK) {
            // Release the unloaded entity reference without clearing the native reattachment UUID.
            cart.pulling = null;
            puller = null;
        }
        return puller;
    }
}
