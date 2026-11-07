package io.github.izakyl.folkways.carts;

import com.jusipat.astikorcartsredux.entity.AbstractCargoCart;
import io.github.izakyl.folkways.core.colony.api.Claim;
import io.github.izakyl.folkways.core.colony.api.ClaimRelease;
import io.github.izakyl.folkways.core.colony.api.Claimed;
import io.github.izakyl.folkways.core.colony.api.Closing;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.resident.api.Bodies;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;

final class CartPresence implements Claim.Watch {
    private final Colony colony;
    private final MinecraftServer server;

    CartPresence(Colony colony, MinecraftServer server) {
        this.colony = colony;
        this.server = server;
    }

    private List<AbstractCargoCart> carts(ServerLevel level) {
        List<AbstractCargoCart> carts = new ArrayList<>();
        for (Claim holding : colony.claims(FolkwaysCartsMod.OWNER)) {
            if (holding.what() instanceof Claimed.Entity held
                    && level.getEntity(held.entity()) instanceof AbstractCargoCart cart && cart.isAlive()) {
                carts.add(cart);
            }
        }
        carts.sort(java.util.Comparator.comparing(AbstractCargoCart::getUUID));
        return carts;
    }

    @Override
    public void released(Claim holding, ClaimRelease why) {
        if (!(holding.what() instanceof Claimed.Entity held) || server == null) {
            return;
        }
        for (var level : server.getAllLevels()) {
            if (level.getEntity(held.entity()) instanceof AbstractCargoCart cart) {
                if (cart.getPulling() instanceof Mob mob && Bodies.of(mob).isPresent()) {
                    cart.setPulling(null);
                }
            }
        }
    }

    void closed(Closing why) {
        if (why != Closing.SHUTDOWN && server != null) {
            for (var level : server.getAllLevels()) {
                for (AbstractCargoCart cart : carts(level)) {
                    if (cart.getPulling() != null && Bodies.of(cart.getPulling())
                            .flatMap(body -> body.colonyId()).filter(colony.id()::equals).isPresent()) {
                        cart.setPulling(null);
                    }
                }
            }
        }
    }
}
