package io.github.izakyl.folkways.core.shell.crafting;

import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.facility.api.control.ItemPort;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.minecraft.server.MinecraftServer;

/** Startup hooks for extending a native crafting network. */
public final class NetworkExtensions<P extends ItemPort, G> {

    public record Access<P extends ItemPort, G>(P port, G grid, ItemPort delivery, Consumer<UUID> cancel) {
    }

    public interface Attachment<P extends ItemPort, G> {
        void tick(MinecraftServer server, List<Access<P, G>> devices);

        void close();
    }

    private static final List<Predicate<Object>> PATTERNS = new ArrayList<>();
    private static final List<Class<?>> PROVIDERS = new ArrayList<>();
    private BiFunction<Colony, NetworkDeviceSettings, Attachment<P, G>> factory;

    public void register(BiFunction<Colony, NetworkDeviceSettings, Attachment<P, G>> factory) {
        if (this.factory != null) {
            throw new IllegalStateException("A network attachment is already registered");
        }
        this.factory = factory;
    }

    public Optional<Attachment<P, G>> attach(Colony colony, NetworkDeviceSettings settings) {
        return factory == null ? Optional.empty() : Optional.of(factory.apply(colony, settings));
    }

    public static <T> void externalPattern(Class<T> type, Predicate<T> predicate) {
        PATTERNS.add(pattern -> type.isInstance(pattern) && predicate.test(type.cast(pattern)));
    }

    public static boolean isExternalPattern(Object pattern) {
        return PATTERNS.stream().anyMatch(test -> test.test(pattern));
    }

    public static void externalProvider(Class<?> type) {
        PROVIDERS.add(type);
    }

    public static boolean isExternalProvider(Object provider) {
        return PROVIDERS.stream().anyMatch(type -> type.isInstance(provider));
    }
}
