package io.github.izakyl.folkways.core.facility.api;

import io.github.izakyl.folkways.core.facility.api.control.PortObservation;
import io.github.izakyl.folkways.core.facility.api.control.ItemPort;
import io.github.izakyl.folkways.core.facility.api.control.Port;
import io.github.izakyl.folkways.core.facility.api.control.ResourceKind;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

/**
 * Finds the ports at a block. A block may have several ports of one kind, each named by its {@link Port#part part}
 * (such as separate input and output ports). Every resolver of a kind is asked and the answers merged; only answers for the same
 * part compete, and the resolver of higher priority wins it, as {@link Controls} does for signals.
 *
 * <p>Each kind has a default part, its id, which is the block's plain container of that kind. For items the default
 * part is the block's plain item port.
 *
 * <p>Priorities by convention: integrations for specific blocks use {@link #SPECIFIC}; Core's capability adapters
 * use {@link #CAPABILITIES}.
 */
public final class Ports {

    public static final int SPECIFIC = 1000;
    public static final int CONTAINERS = 0;
    public static final int CAPABILITIES = -1000;

    @FunctionalInterface
    public interface Resolver<R> {

        /**
         * The ports of this kind the resolver knows at `pos`, each with its own part; empty when it knows none. The
         * cell is told in its realm, which may be a moving structure: a port keeps it as its anchor.
         */
        List<Port<R>> at(ServerLevel level, WorldPos pos);
    }

    /** Resolves a default item part without probing sided or named controls. */
    @FunctionalInterface
    public interface ItemResolver {

        Optional<ItemPort> at(ServerLevel level, BlockPos pos);
    }

    @FunctionalInterface
    public interface ItemCheck {

        ItemPort check(ServerLevel level, BlockPos pos, int priority, ItemPort port);
    }

    private static volatile ItemCheck itemCheck = (level, pos, priority, port) -> port;
    private static volatile Hooks hooks;

    /** Receives native change events and retains transfer observations. */
    public interface Hooks {

        void changed(ServerLevel level, BlockPos pos);

        void observe(ServerLevel level, PortObservation observation);

        Optional<PortObservation> observation(WorldPos at);
    }

    /** Core only. */
    public static void installItems(ItemCheck check, Hooks installed) {
        itemCheck = Objects.requireNonNull(check);
        hooks = Objects.requireNonNull(installed);
    }

    public static void registerItem(ResourceLocation owner, int priority, ItemResolver resolver) {
        Objects.requireNonNull(resolver, "resolver");
        register(ResourceKind.ITEM, owner, priority, true, (level, at) -> {
            BlockPos pos = at.block(level);
            return resolver.at(level, pos)
                .<Port<ItemStack>>map(port -> itemCheck.check(level, pos, priority, port))
                .stream().toList();
        });
    }

    public static Optional<ItemPort> itemAt(ServerLevel level, WorldPos pos) {
        return at(level, pos, ResourceKind.ITEM).filter(ItemPort.class::isInstance).map(ItemPort.class::cast);
    }

    public static void changed(ServerLevel level, BlockPos pos) {
        Hooks current = hooks;
        if (current != null) {
            current.changed(level, pos);
        }
    }

    public static void observe(ServerLevel level, WorldPos at, net.minecraft.network.chat.Component reason) {
        Hooks current = hooks;
        if (current != null) {
            current.observe(level, new PortObservation(at, reason));
        }
    }

    public static Optional<PortObservation> observation(WorldPos at) {
        Hooks current = hooks;
        return current == null ? Optional.empty() : current.observation(at);
    }

    private record Entry(ResourceLocation owner, int priority, boolean defaultItem, Resolver<?> resolver) {
    }

    private static final Map<ResourceKind<?>, ResourceKind<?>> KINDS = new ConcurrentHashMap<>(Map.of(
        ResourceKind.ITEM, ResourceKind.ITEM, ResourceKind.FLUID, ResourceKind.FLUID,
        ResourceKind.ENERGY, ResourceKind.ENERGY));
    private static volatile Map<ResourceKind<?>, List<Entry>> resolvers = Map.of();

    private Ports() {
    }

    /** Declares a resource kind of an integration's own. */
    public static void kind(ResourceKind<?> kind) {
        if (KINDS.putIfAbsent(kind, kind) != null) {
            throw new IllegalStateException(kind + " is already a resource kind");
        }
    }

    public static List<ResourceKind<?>> kinds() {
        return KINDS.keySet().stream().sorted(Comparator.comparing(kind -> kind.id().toString())).toList();
    }

    /** The default part of a kind: the block's plain container of it. */
    public static String part(ResourceKind<?> kind) {
        return kind.id().toString();
    }

    public static <R> void register(ResourceKind<R> kind, ResourceLocation owner, int priority,
            Resolver<R> resolver) {
        register(kind, owner, priority, false, resolver);
    }

    private static synchronized <R> void register(ResourceKind<R> kind, ResourceLocation owner, int priority,
            boolean defaultItem, Resolver<R> resolver) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(resolver, "resolver");
        if (!KINDS.containsKey(kind)) {
            throw new IllegalArgumentException(kind + " is not a declared resource kind");
        }
        List<Entry> next = new ArrayList<>(resolvers.getOrDefault(kind, List.of()));
        if (next.stream().anyMatch(entry -> entry.owner().equals(owner))) {
            throw new IllegalStateException(owner + " already resolves " + kind + " ports");
        }
        next.add(new Entry(owner, priority, defaultItem, resolver));
        next.sort(Comparator.comparingInt(Entry::priority).reversed());
        Map<ResourceKind<?>, List<Entry>> all = new java.util.HashMap<>(resolvers);
        all.put(kind, List.copyOf(next));
        resolvers = Map.copyOf(all);
    }

    /** The block's port of the kind's default part: its store for items. */
    public static <R> Optional<Port<R>> at(ServerLevel level, WorldPos pos, ResourceKind<R> kind) {
        return at(level, pos, kind, part(kind));
    }

    /** The block's port of this kind and part, from the resolver of highest priority that names it. */
    @SuppressWarnings("unchecked")
    public static <R> Optional<Port<R>> at(ServerLevel level, WorldPos pos, ResourceKind<R> kind, String part) {
        if (!Controls.loaded(level, pos)) {
            return Optional.empty();
        }
        boolean item = kind.equals(ResourceKind.ITEM);
        boolean defaultPart = item && part.equals(part(kind));
        for (Entry entry : resolvers.getOrDefault(kind, List.of())) {
            if (item && entry.defaultItem() != defaultPart) {
                continue;
            }
            for (Port<R> port : ((Resolver<R>) entry.resolver()).at(level, pos)) {
                if (port.part().equals(part)) {
                    return Optional.of(port);
                }
            }
        }
        return Optional.empty();
    }

    /** Every port of this kind at the block, one per part. */
    @SuppressWarnings("unchecked")
    public static <R> List<Port<R>> of(ServerLevel level, WorldPos pos, ResourceKind<R> kind) {
        if (!Controls.loaded(level, pos)) {
            return List.of();
        }
        Map<String, Port<R>> found = new LinkedHashMap<>();
        for (Entry entry : resolvers.getOrDefault(kind, List.of())) {
            for (Port<R> port : ((Resolver<R>) entry.resolver()).at(level, pos)) {
                if (!kind.equals(ResourceKind.ITEM) || entry.defaultItem() == port.part().equals(part(kind))) {
                    found.putIfAbsent(port.part(), port);
                }
            }
        }
        return List.copyOf(found.values());
    }

    /** Every port at the block, of every kind, one per part. */
    public static List<Port<?>> all(ServerLevel level, WorldPos pos) {
        List<Port<?>> found = new ArrayList<>();
        for (ResourceKind<?> kind : kinds()) {
            found.addAll(of(level, pos, kind));
        }
        return found;
    }

    /** The port of this part at the block, whatever its kind. */
    public static Optional<Port<?>> part(ServerLevel level, WorldPos pos, String part) {
        for (ResourceKind<?> kind : kinds()) {
            Optional<? extends Port<?>> found = at(level, pos, kind, part);
            if (found.isPresent()) {
                return Optional.of(found.get());
            }
        }
        return Optional.empty();
    }
}
