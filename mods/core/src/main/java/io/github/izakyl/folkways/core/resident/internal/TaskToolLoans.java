package io.github.izakyl.folkways.core.resident.internal;

import io.github.izakyl.folkways.core.resident.api.TaskTools;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.world.item.ItemStack;

// A borrowed tool is kept only while a task owns it. Once released it is ordinary cargo again, so the
// resident can return the same physical stack to a store rather than keeping a tool for every trade.
public final class TaskToolLoans implements TaskTools.Access {

    public static final TaskToolLoans INSTANCE = new TaskToolLoans();

    private record Loan(UUID resident, Predicate<ItemStack> matches) {
    }

    private static volatile Map<UUID, Loan> loans = Map.of();

    private TaskToolLoans() {
    }

    @Override
    public synchronized void keep(UUID task, UUID resident, Predicate<ItemStack> matches) {
        Map<UUID, Loan> next = new LinkedHashMap<>(loans);
        next.put(Objects.requireNonNull(task), new Loan(Objects.requireNonNull(resident),
            Objects.requireNonNull(matches)));
        loans = Map.copyOf(next);
    }

    @Override
    public synchronized void release(UUID task) {
        Map<UUID, Loan> next = new LinkedHashMap<>(loans);
        next.remove(task);
        loans = Map.copyOf(next);
    }

    public synchronized void forget(UUID resident) {
        Map<UUID, Loan> next = new LinkedHashMap<>(loans);
        next.values().removeIf(loan -> loan.resident().equals(resident));
        loans = Map.copyOf(next);
    }

    @Override
    public boolean holds(UUID resident, ItemStack stack) {
        return !stack.isEmpty() && loans.values().stream()
            .anyMatch(loan -> loan.resident().equals(resident) && loan.matches().test(stack));
    }

    public synchronized void clear() {
        loans = Map.of();
    }

    public static void install() {
        TaskTools.install(INSTANCE);
    }
}
