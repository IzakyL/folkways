package io.github.izakyl.folkways.core.engine.plan;

import io.github.izakyl.folkways.core.api.work.Need;
import java.util.List;
import net.minecraft.world.item.ItemStack;

public final class Manifest {
    private final List<ItemStack> exact;
    private final List<Need> pending;
    private final List<Manifest> inputs;
    private volatile List<ItemStack> bound;

    public Manifest(List<ItemStack> exact, List<Need> pending) {
        this(exact, pending, List.of());
    }

    public Manifest(List<ItemStack> exact, List<Need> pending, List<Manifest> inputs) {
        this.inputs = List.copyOf(inputs);
        if (exact.stream().anyMatch(ItemStack::isEmpty)
                || pending.stream().anyMatch(need -> need.count() <= 0)) {
            throw new IllegalArgumentException("a manifest needs positive amounts");
        }
        this.exact = exact.stream().map(ItemStack::copy).toList();
        this.pending = List.copyOf(pending);
    }

    public List<ItemStack> exact() {
        List<ItemStack> chosen = bound;
        if (chosen != null) return chosen.stream().map(ItemStack::copy).toList();
        var all = new java.util.ArrayList<>(exact.stream().map(ItemStack::copy).toList());
        inputs.forEach(input -> all.addAll(input.exact()));
        return List.copyOf(all);
    }

    public List<Need> pending() {
        if (bound != null) return List.of();
        var all = new java.util.ArrayList<>(pending);
        inputs.forEach(input -> all.addAll(input.pending()));
        return List.copyOf(all);
    }

    public boolean isEmpty() { return exact.isEmpty() && pending.isEmpty() && inputs.stream().allMatch(Manifest::isEmpty); }

    public void bind(List<ItemStack> moved) {
        if (bound == null) {
            bound = moved.stream().map(ItemStack::copy).toList();
        }
    }
}
