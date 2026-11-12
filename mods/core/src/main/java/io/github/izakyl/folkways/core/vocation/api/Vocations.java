package io.github.izakyl.folkways.core.vocation.api;

import io.github.izakyl.folkways.core.item.api.ItemSpec;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;

public final class Vocations {

    public static final ResourceLocation NETWORK_SERVICE =
        ResourceLocation.fromNamespaceAndPath("folkways_core", "network_service");

    public static final ResourceLocation HAULING =
        ResourceLocation.fromNamespaceAndPath("folkways_core", "hauling");

    public interface Access {

        Vocation register(VocationSpec spec);

        void addKit(ResourceLocation vocation, ItemSpec tool);

        List<ItemSpec> kit(ResourceLocation vocation, List<ItemSpec> declared);

        List<ResourceLocation> ids();

        List<Vocation> all();

        Optional<Vocation> of(ResourceLocation id);

        Vocation required(ResourceLocation id);
    }

    private static Access access;

    private Vocations() {
    }

    public static void install(Access installed) {
        access = Objects.requireNonNull(installed);
    }

    public static Vocation register(VocationSpec spec) {
        return access.register(spec);
    }

    public static void addKit(ResourceLocation vocation, ItemSpec tool) {
        access.addKit(vocation, tool);
    }

    static List<ItemSpec> kit(ResourceLocation vocation, List<ItemSpec> declared) {
        return access.kit(vocation, declared);
    }

    public static List<ResourceLocation> ids() {
        return access.ids();
    }

    public static List<Vocation> all() {
        return access.all();
    }

    public static Optional<Vocation> of(ResourceLocation id) {
        return access.of(id);
    }

    public static Vocation required(ResourceLocation id) {
        return access.required(id);
    }
}
