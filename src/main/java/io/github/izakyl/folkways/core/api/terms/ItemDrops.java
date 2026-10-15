package io.github.izakyl.folkways.core.api.terms;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/** The newly dropped goods of one synchronous interaction, for work that delivers them through its outcome. */
@EventBusSubscriber(modid = "folkways")
public final class ItemDrops {

    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();

    private ItemDrops() {
    }

    public record Captured<T>(T result, List<ItemStack> items) {
        public Captured {
            items = List.copyOf(items);
        }
    }

    public static final class CaptureFailure extends RuntimeException {
        private final List<ItemStack> items;

        private CaptureFailure(Throwable cause, List<ItemStack> items) {
            super(cause);
            this.items = List.copyOf(items);
        }

        public List<ItemStack> items() {
            return items;
        }
    }

    public static <T> Captured<T> capture(ServerLevel level, AABB bounds, Supplier<T> action) {
        return capture(level, List.of(bounds), action);
    }

    public static <T> Captured<T> capture(ServerLevel level, List<AABB> bounds, Supplier<T> action) {
        Scope previous = CURRENT.get();
        Scope scope = new Scope(level, bounds);
        CURRENT.set(scope);
        try {
            return new Captured<>(action.get(), scope.items);
        } catch (RuntimeException | LinkageError broken) {
            throw new CaptureFailure(broken, scope.items);
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void joining(EntityJoinLevelEvent event) {
        Scope scope = CURRENT.get();
        if (scope != null && event.getLevel() == scope.level && event.getEntity() instanceof ItemEntity item
                && scope.bounds.stream().anyMatch(bound -> bound.contains(item.position())) && !item.getItem().isEmpty()) {
            scope.items.add(item.getItem().copy());
            event.setCanceled(true);
        }
    }

    private static final class Scope {
        private final ServerLevel level;
        private final List<AABB> bounds;
        private final List<ItemStack> items = new ArrayList<>();

        private Scope(ServerLevel level, List<AABB> bounds) {
            this.level = level;
            this.bounds = List.copyOf(bounds);
        }
    }
}