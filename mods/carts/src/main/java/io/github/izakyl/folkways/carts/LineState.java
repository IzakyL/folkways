package io.github.izakyl.folkways.carts;

import com.jusipat.astikorcartsredux.entity.AbstractCargoCart;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.work.Grown;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.item.ItemStack;

// What one line has and is doing: its cart and horse, the lap the cart is being drawn round, and the goods aboard
// that belong to some transfer. Anything else aboard is stray, and is set down at the next station.
final class LineState {

    // Within how far of a station, standing still, the cart is at it.
    static final double AT_STATION = 3.0;

    // A batch the line carries from one station to another, from when it is booked until it is set down.
    static final class Consignment {
        final UUID id;
        final ItemSpec goods;
        final long count;
        final int from;
        final int to;
        boolean loaded;

        Consignment(UUID id, ItemSpec goods, long count, int from, int to) {
            this.id = id;
            this.goods = goods;
            this.count = count;
            this.from = from;
            this.to = to;
        }
    }

    final UUID line;
    Optional<UUID> cart = Optional.empty();
    Optional<UUID> horse = Optional.empty();
    int lapNumber = -1;
    Grown lap;
    final Set<UUID> open = new LinkedHashSet<>();
    // Bookings change on the weave thread while the server reads and handles the goods.
    final Map<UUID, Consignment> aboard = new ConcurrentHashMap<>();
    HorseLine team;
    int nextMark;
    int direction = 1;

    LineState(UUID line) {
        this.line = line;
    }

    boolean lapUnderWay() {
        return lap != null && !open.isEmpty();
    }

    Optional<AbstractCargoCart> cart(ServerLevel level) {
        return cart.map(level::getEntity).filter(AbstractCargoCart.class::isInstance).map(AbstractCargoCart.class::cast)
            .filter(Entity::isAlive);
    }

    Optional<AbstractHorse> horse(ServerLevel level) {
        return horse.map(level::getEntity).filter(AbstractHorse.class::isInstance).map(AbstractHorse.class::cast)
            .filter(Entity::isAlive);
    }

    // Whether the cart stands at the station, so goods can be put aboard or taken off.
    boolean parkedAt(ServerLevel level, Line line, int station) {
        var mark = line.station(station);
        return mark.isPresent() && near(level, mark.get(), AT_STATION) && stopped(level);
    }

    boolean near(ServerLevel level, Line.Mark mark, double reach) {
        var found = cart(level);
        return mark.at().in(level) && found.filter(cart -> cart.position()
            .distanceToSqr(mark.at().cell().getBottomCenter()) <= reach * reach).isPresent();
    }

    boolean stopped(ServerLevel level) {
        return cart(level).filter(cart -> !moving(cart) && !walking(cart.getPulling())).isPresent();
    }

    private static boolean walking(Entity entity) {
        // Leash updates can leave a path on a horse whose AI is paused for the stop.
        return entity instanceof Mob mob && !mob.isNoAi() && !mob.getNavigation().isDone();
    }

    // Redux moves a pulled cart directly, keeping horizontal deltaMovement zero even while it rolls.
    static boolean moving(AbstractCargoCart cart) {
        double dx = cart.getX() - cart.xo;
        double dz = cart.getZ() - cart.zo;
        return dx * dx + dz * dz >= 1.0E-4;
    }

    // The station the cart stands at, if any.
    Optional<Integer> parked(ServerLevel level, Line line) {
        for (Line.Mark mark : line.course()) {
            if (mark.isStation() && parkedAt(level, line, mark.station())) {
                return Optional.of(mark.station());
            }
        }
        return Optional.empty();
    }

    // How many cells of the cart no booked batch has taken yet.
    int freeCells(AbstractCargoCart cart) {
        int empty = 0;
        for (int slot = 0; slot < cart.getContainerSize(); slot++) {
            if (cart.getItem(slot).isEmpty()) {
                empty++;
            }
        }
        long booked = 0;
        for (Consignment one : aboard.values()) {
            if (!one.loaded) {
                booked += cells(one.goods, one.count);
            }
        }
        return (int) Math.max(0, empty - booked);
    }

    static long cells(ItemSpec goods, long count) {
        int stack = Math.max(1, Goods.stackSize(goods));
        return (count + stack - 1) / stack;
    }

    // The goods aboard that no batch loaded on the cart accounts for.
    List<ItemStack> stray(AbstractCargoCart cart) {
        Map<UUID, Long> owed = new LinkedHashMap<>();
        aboard.values().stream().filter(one -> one.loaded).forEach(one -> owed.put(one.id, one.count));
        List<ItemStack> found = new ArrayList<>();
        for (int slot = 0; slot < cart.getContainerSize(); slot++) {
            ItemStack stack = cart.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            int left = stack.getCount();
            for (Consignment one : aboard.values()) {
                long due = owed.getOrDefault(one.id, 0L);
                if (left > 0 && due > 0 && Goods.matches(stack, one.goods)) {
                    int take = (int) Math.min(left, due);
                    owed.put(one.id, due - take);
                    left -= take;
                }
            }
            if (left > 0) {
                found.add(stack.copyWithCount(left));
            }
        }
        return found;
    }
}
