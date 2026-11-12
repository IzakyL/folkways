package io.github.izakyl.folkways.core.inventory.api.port;

import io.github.izakyl.folkways.core.item.api.Containers;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

/**
 * Finds the port for a block. Resolvers are asked in descending priority; the first answer wins.
 *
 * <p>Priorities by convention: integrations for specific blocks use {@link #SPECIFIC}; Core's own resolver for
 * vanilla containers and {@code Containers.register} adapters uses {@link #CONTAINERS}.
 */
public final class StorePorts {

    public static final TagKey<Block> ALLOWED = TagKey.create(Registries.BLOCK,
        ResourceLocation.fromNamespaceAndPath("folkways_core", "store/allowed"));
    public static final TagKey<Block> REFUSED = TagKey.create(Registries.BLOCK,
        ResourceLocation.fromNamespaceAndPath("folkways_core", "store/refused"));

    /** Client-side prediction for native blocks without inventories or block entities, including refused devices. */
    public static final TagKey<Block> CANDIDATES = TagKey.create(Registries.BLOCK,
        ResourceLocation.fromNamespaceAndPath("folkways_core", "store/candidates"));

    public static final int SPECIFIC = 1000;
    public static final int CONTAINERS = 0;

    @FunctionalInterface
    public interface Resolver {
        Optional<StorePort> at(ServerLevel level, BlockPos pos);
    }

    /** Where the colony listens for news about its stores. Core installs the real one. */
    public interface Hooks {

        /** The goods at `pos` may have changed outside block-entity change events. */
        void changed(ServerLevel level, BlockPos pos);

        /** A lasting verdict found by watching the store at `at`; it holds until the block is registered again. */
        void condemn(ServerLevel level, WorldPos at, Component reason);

        /** The lasting verdict on `at`, if any. */
        Optional<Component> condemned(WorldPos at);
    }

    private record Entry(ResourceLocation owner, int priority, Resolver resolver) {
    }

    private static volatile List<Entry> resolvers = List.of();
    private static volatile Hooks hooks = new Hooks() {
        private final java.util.Map<WorldPos, Component> verdicts = new java.util.concurrent.ConcurrentHashMap<>();

        @Override
        public void changed(ServerLevel level, BlockPos pos) {
        }

        @Override
        public void condemn(ServerLevel level, WorldPos at, Component reason) {
            verdicts.put(at, reason);
        }

        @Override
        public Optional<Component> condemned(WorldPos at) {
            return Optional.ofNullable(verdicts.get(at));
        }
    };

    private StorePorts() {
    }

    public static synchronized void register(ResourceLocation owner, int priority, Resolver resolver) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(resolver, "resolver");
        if (resolvers.stream().anyMatch(entry -> entry.owner().equals(owner))) {
            throw new IllegalStateException(owner + " already resolves store ports");
        }
        List<Entry> next = new ArrayList<>(resolvers);
        next.add(new Entry(owner, priority, resolver));
        next.sort(Comparator.comparingInt(Entry::priority).reversed());
        resolvers = List.copyOf(next);
    }

    public static Optional<StorePort> at(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos)) {
            return Optional.empty();
        }
        for (Entry entry : resolvers) {
            Optional<StorePort> found = entry.resolver().at(level, pos);
            if (found.isPresent()) {
                StorePort port = found.get();
                var state = level.getBlockState(pos);
                if (state.is(REFUSED)) {
                    String namespace = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getNamespace();
                    String reason = namespace.equals("lootr") ? "personal" :
                        BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString().equals("create:item_vault")
                            ? "vault" : "refused";
                    return Optional.of(new Condemned(port,
                        Component.translatable("folkways.store." + reason), false));
                }
                if (entry.priority() <= CONTAINERS && !state.is(ALLOWED)
                    && (entry.priority() < CONTAINERS
                        || !BuiltInRegistries.BLOCK.getKey(state.getBlock()).getNamespace().equals("minecraft"))) {
                    return Optional.of(new Condemned(port,
                        Component.translatable("folkways.store.unsupported"), false));
                }
                Optional<Component> reason = condemned(port.identity().home());
                return Optional.of(reason.<StorePort>map(verdict -> new Condemned(port, verdict, true)).orElse(port));
            }
        }
        return Optional.empty();
    }

    private static final List<TagKey<Block>> NETWORK_ACCESS = List.of("take", "put", "craft").stream()
        .map(name -> TagKey.create(Registries.BLOCK,
            ResourceLocation.fromNamespaceAndPath("folkways_core", "network_access/" + name)))
        .toList();

    /**
     * Whether the block may be enrolled as a store, on either side. On the server this resolves the port. A client
     * cannot resolve ports, so it predicts from what it can see: a container or a network device
     * tag. The prediction may be wider than the answer; the server resolves again before it takes anything in.
     */
    public static boolean candidate(Level level, BlockPos pos) {
        if (level instanceof ServerLevel server) {
            return at(server, pos).isPresent();
        }
        if (!level.isLoaded(pos)) {
            return false;
        }
        if (level.getBlockState(pos).is(CANDIDATES)) {
            return true;
        }
        if (level.getBlockEntity(pos) == null) {
            return false;
        }
        if (Containers.at(level, pos).isPresent()) {
            return true;
        }
        if (NETWORK_ACCESS.stream().anyMatch(level.getBlockState(pos)::is)) {
            return true;
        }
        return false;
    }

    /** Native stores without a block entity use the existing point-claim ledger. */
    public static boolean ledger(Level level, BlockPos pos) {
        return level.isLoaded(pos) && level.getBlockEntity(pos) == null
            && level.getBlockState(pos).is(CANDIDATES) && candidate(level, pos);
    }

    private record Condemned(StorePort inner, Component reason, boolean lasting) implements StorePort {

        @Override
        public PortIdentity identity() {
            return inner.identity();
        }

        @Override
        public Usability usability() {
            return new Usability.Unusable(reason, lasting);
        }

        @Override
        public java.util.Set<Operation> operations() {
            return inner.operations();
        }

        @Override
        public PortReader reader(ServerLevel level,
                List<io.github.izakyl.folkways.core.inventory.api.StoreRule> rules) {
            return new PortReader() {
                @Override
                public boolean step(int budget) {
                    return true;
                }

                @Override
                public PortReading result() {
                    return new PortReading(List.of(), Optional.empty(), Room.NONE, java.util.Set.of(), false);
                }
            };
        }

        @Override
        public Optional<List<WorldPos>> proxyMembers() {
            return inner.proxyMembers();
        }

        @Override
        public TransferResult take(ServerLevel level, List<Pull> requested,
                List<io.github.izakyl.folkways.core.inventory.api.StoreRule> rules, boolean simulate) {
            return new TransferResult.Refused(TransferResult.Why.UNUSABLE);
        }

        @Override
        public TransferResult put(ServerLevel level, List<net.minecraft.world.item.ItemStack> goods,
                List<io.github.izakyl.folkways.core.inventory.api.StoreRule> rules,
                boolean directed, boolean simulate) {
            return new TransferResult.Refused(TransferResult.Why.UNUSABLE);
        }

        @Override
        public java.util.OptionalLong revision() {
            return inner.revision();
        }
    }

    public static void changed(ServerLevel level, BlockPos pos) {
        hooks.changed(level, pos);
    }

    public static void condemn(ServerLevel level, WorldPos at, Component reason) {
        hooks.condemn(level, at, reason);
    }

    public static Optional<Component> condemned(WorldPos at) {
        return hooks.condemned(at);
    }

    /** Core only. */
    public static void install(Hooks installed) {
        hooks = Objects.requireNonNull(installed, "hooks");
    }
}
