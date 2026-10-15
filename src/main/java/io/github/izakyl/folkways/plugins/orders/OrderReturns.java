package io.github.izakyl.folkways.plugins.orders;

import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

// A standing target may include its own real borrowed goods already promised back to it.
// Providers must observe an existing item, never expected manufacturing or unrelated colony inventory.
public final class OrderReturns {
    public record Promise(UUID item, WorldPos into, ItemStack stack) {
        public Promise {
            if (stack.isEmpty()) throw new IllegalArgumentException("a return needs a physical item");
            stack = stack.copy();
        }
        @Override public ItemStack stack() { return stack.copy(); }
    }
    public interface Registration extends AutoCloseable { @Override void close(); }
    private record Key(UUID colony, ResourceLocation owner) { }
    private static final Map<Key, Supplier<List<Promise>>> PROVIDERS = new LinkedHashMap<>();
    private OrderReturns() { }

    public static synchronized Registration register(UUID colony, ResourceLocation owner, Supplier<List<Promise>> provider) {
        Key key = new Key(colony, owner);
        PROVIDERS.put(key, provider);
        // A previous instance closing after reload must not unregister the new instance's observer.
        return () -> { synchronized (OrderReturns.class) { PROVIDERS.remove(key, provider); } };
    }

    public static long count(Colony colony, WorldPos into, ItemSpec goods) {
        List<Supplier<List<Promise>>> providers;
        synchronized (OrderReturns.class) {
            providers = PROVIDERS.entrySet().stream().filter(entry -> entry.getKey().colony().equals(colony.id()))
                .map(Map.Entry::getValue).toList();
        }
        Map<UUID, Promise> physical = new LinkedHashMap<>();
        providers.forEach(provider -> provider.get().forEach(promise -> physical.putIfAbsent(promise.item(), promise)));
        return physical.values().stream().filter(promise -> promise.into().equals(into) && Goods.matches(promise.stack(), goods))
            .mapToLong(promise -> promise.stack().getCount()).sum();
    }
}
