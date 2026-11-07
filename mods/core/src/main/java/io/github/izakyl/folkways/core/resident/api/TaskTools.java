package io.github.izakyl.folkways.core.resident.api;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.world.item.ItemStack;

// A borrowed tool is kept only while a task owns it. Once released it is ordinary cargo again, so the
// resident can return the same physical stack to a store rather than keeping a tool for every trade.
public final class TaskTools {

    public interface Access {

        void keep(UUID task, UUID resident, Predicate<ItemStack> matches);

        void release(UUID task);

        boolean holds(UUID resident, ItemStack stack);
    }

    private static Access access;

    private TaskTools() {
    }

    public static void install(Access installed) {
        access = Objects.requireNonNull(installed);
    }

    public static void keep(UUID task, UUID resident, Predicate<ItemStack> matches) {
        access.keep(task, resident, matches);
    }

    public static void release(UUID task) {
        access.release(task);
    }

    public static boolean holds(UUID resident, ItemStack stack) {
        return access.holds(resident, stack);
    }
}
