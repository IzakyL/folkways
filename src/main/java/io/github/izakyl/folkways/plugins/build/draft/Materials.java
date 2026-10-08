package io.github.izakyl.folkways.plugins.build.draft;

import io.github.izakyl.folkways.core.api.terms.ItemFilter;
import java.util.List;
import net.starlark.java.annot.StarlarkBuiltin;
import net.starlark.java.eval.StarlarkValue;

@StarlarkBuiltin(name = "materials", doc = "Blocks a part may be built out of, in priority or mixed by weight.")
public record Materials(List<ItemFilter> filters, List<Integer> weights) implements StarlarkValue {

    public Materials {
        filters = List.copyOf(filters);
        weights = List.copyOf(weights);
        if (filters.isEmpty()) {
            throw new IllegalArgumentException("a part with no palette cannot be built out of anything");
        }
        if (!weights.isEmpty() && weights.size() != filters.size()) {
            throw new IllegalArgumentException("a mix weighs every block it names");
        }
        for (int weight : weights) {
            if (weight < 1) {
                throw new IllegalArgumentException("a mix weighs each block at least 1, got " + weight);
            }
        }
    }

    public static Materials inOrder(List<ItemFilter> filters) {
        return new Materials(filters, List.of());
    }

    boolean mixed() {
        return !weights.isEmpty();
    }

    @Override
    public boolean isImmutable() {
        return true;
    }
}
