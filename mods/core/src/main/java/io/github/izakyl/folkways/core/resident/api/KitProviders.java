package io.github.izakyl.folkways.core.resident.api;

import io.github.izakyl.folkways.core.inventory.api.StoreWorkstations;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

public final class KitProviders {
    private static volatile Map<ResourceLocation, KitProvider> providers = Map.of();
    private static volatile Function<Body, List<KitProvider.Held>> holding = body -> List.of();

    private KitProviders() {
    }

    public static synchronized void register(ResourceLocation id, KitProvider provider) {
        Map<ResourceLocation, KitProvider> next = new LinkedHashMap<>(providers);
        if (next.putIfAbsent(id, provider) != null) {
            throw new IllegalArgumentException("duplicate kit provider " + id);
        }
        providers = java.util.Collections.unmodifiableMap(next);
    }

    public static boolean accepts(ResourceLocation item) {
        return providers.values().stream().anyMatch(provider -> provider.accepts(item));
    }

    public static Component refusal(ResourceLocation item) {
        return providers.values().stream().map(provider -> provider.refusal(item)).flatMap(Optional::stream)
            .findFirst().orElseGet(() -> Component.translatable("folkways.kits.unsupported"));
    }

    /** The equipment domain supplies the allocation; providers remain independent of profiles and colonies. */
    public static void holding(Function<Body, List<KitProvider.Held>> source) {
        holding = Objects.requireNonNull(source);
    }

    public static List<KitProvider> all() {
        return List.copyOf(providers.values());
    }

    public static List<KitProvider.Held> held(Body body) {
        return holding.apply(body);
    }

    public static Optional<KitProvider> provider(ItemStack stack) {
        ResourceLocation item = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return providers.values().stream().filter(provider -> provider.accepts(item)).findFirst();
    }

    public static List<KitProvider.Inventory> inventories(Body body) {
        return activeRoots(body).stream().flatMap(stack -> provider(stack)
            .flatMap(provider -> provider.inventory(stack)).stream()).toList();
    }

    public static List<ItemStack> activeRoots(Body body) {
        List<ItemStack> equipped = worn.apply(body);
        return providers.values().stream().flatMap(provider -> provider.roots(body, equipped).stream()).toList();
    }

    private static Function<Body, List<ItemStack>> worn = body -> List.of();

    public static void worn(Function<Body, List<ItemStack>> source) {
        worn = Objects.requireNonNull(source);
    }

    public static KitCapabilities capabilities(Body body) {
        return capabilities(held(body));
    }

    public static KitCapabilities capabilities(List<KitProvider.Held> held) {
        Map<ResourceLocation, Double> abilities = new LinkedHashMap<>();
        var stations = EnumSet.noneOf(StoreWorkstations.Capability.class);
        for (KitProvider provider : providers.values()) {
            List<KitProvider.Held> roots = held.stream().filter(item -> provider.accepts(item.item())).toList();
            KitCapabilities found = provider.capabilities(roots);
            found.abilities().forEach((id, value) -> abilities.merge(id, value, Math::max));
            stations.addAll(found.workstations());
        }
        return new KitCapabilities(abilities, stations);
    }
}
