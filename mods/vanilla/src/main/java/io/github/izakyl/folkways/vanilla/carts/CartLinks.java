package io.github.izakyl.folkways.vanilla.carts;

import com.jusipat.astikorcartsredux.entity.AbstractCargoCart;
import com.jusipat.astikorcartsredux.entity.HandCartEntity;
import com.jusipat.astikorcartsredux.entity.SupplyCartEntity;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.resident.api.Bodies;
import io.github.izakyl.folkways.core.resident.api.Locomotions;
import io.github.izakyl.folkways.core.shell.carts.api.CartHitches;
import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;

public final class CartLinks {
    private static final Map<Mob, WeakReference<AbstractCargoCart>> LINKS = new WeakHashMap<>();
    private static final Map<AbstractCargoCart, WeakReference<Mob>> PULLERS = new WeakHashMap<>();

    private CartLinks() {
    }

    static boolean admissible(Entity cart) {
        return cart instanceof HandCartEntity || cart instanceof SupplyCartEntity;
    }

    public static boolean residentDrawn(Entity cart) {
        return cart instanceof HandCartEntity;
    }

    public static boolean residentPuller(Entity entity) {
        return entity != null && entity.isAlive() && !entity.isPassenger()
            && Bodies.of(entity).filter(body -> body.colonyId().isPresent()).isPresent();
    }

    public static Optional<AbstractCargoCart> pulling(Mob body) {
        WeakReference<AbstractCargoCart> link = LINKS.get(body);
        AbstractCargoCart cart = link == null ? null : link.get();
        return cart != null && cart.isAlive() && cart.level() == body.level() && cart.getPulling() == body
            && residentPuller(body) ? Optional.of(cart) : Optional.empty();
    }

    // Called by the cart after native reattachment, including after the cart or puller reloads.
    public static void sync(AbstractCargoCart cart, UUID savedPuller) {
        WeakReference<Mob> previous = PULLERS.putIfAbsent(cart, new WeakReference<>(null));
        Mob before = previous == null ? null : previous.get();
        Entity puller = CartHitches.puller(cart, savedPuller);
        if (puller instanceof Mob mob && Bodies.of(mob).isPresent()) {
            boolean admitted = cart.level() instanceof ServerLevel level && Bodies.of(mob)
                .flatMap(body -> body.colonyId()).flatMap(id -> Colonies.of(level.getServer(), id))
                .flatMap(colony -> colony.claimOf(cart.getUUID()))
                .filter(holding -> holding.owner().equals(CartsPlugin.OWNER)).isPresent();
            if (!admitted || !residentDrawn(cart) || !residentPuller(mob)
                    || pulling(mob).filter(other -> other != cart).isPresent()) {
                cart.setPulling(null);
                puller = null;
            } else if (cart.getPulling() == mob) {
                LINKS.put(mob, new WeakReference<>(cart));
            }
        }
        Mob now = cart.isAlive() && puller instanceof Mob mob && cart.getPulling() == mob ? mob : null;
        if (before != null && before != now) {
            WeakReference<AbstractCargoCart> linked = LINKS.get(before);
            if (linked != null && linked.get() == cart) {
                LINKS.remove(before);
                Bodies.of(before).ifPresent(body -> Locomotions.override(body, null));
            }
        }
        if (now != null && now != before) {
            Bodies.of(now).ifPresent(body -> Locomotions.override(body, Carting.INSTANCE));
        }
        PULLERS.put(cart, new WeakReference<>(now));
        if (cart.isRemoved()) {
            PULLERS.remove(cart);
        }
    }
}
