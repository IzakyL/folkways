package io.github.izakyl.folkways.core.inventory.api.port;

import io.github.izakyl.folkways.core.facility.api.control.PortObservation;
import io.github.izakyl.folkways.core.facility.api.control.TransformHint;
import io.github.izakyl.folkways.core.facility.api.control.SlotWindow;
import io.github.izakyl.folkways.core.facility.api.control.TransferResult;
import io.github.izakyl.folkways.core.facility.api.control.Pull;
import io.github.izakyl.folkways.core.facility.api.control.PortReader;
import io.github.izakyl.folkways.core.facility.api.control.PortIdentity;
import io.github.izakyl.folkways.core.facility.api.control.ItemPort;
import io.github.izakyl.folkways.core.item.api.Containers;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

/** Enrollment and availability policy for inventory use of item ports. */
public final class StoreAccess {

    public static final TagKey<Block> ALLOWED = TagKey.create(Registries.BLOCK,
        ResourceLocation.fromNamespaceAndPath("folkways_core", "store/allowed"));
    public static final TagKey<Block> REFUSED = TagKey.create(Registries.BLOCK,
        ResourceLocation.fromNamespaceAndPath("folkways_core", "store/refused"));

    /**
     * Client-side prediction for native blocks without inventories or block entities.
     */
    public static final TagKey<Block> CANDIDATES = TagKey.create(Registries.BLOCK,
        ResourceLocation.fromNamespaceAndPath("folkways_core", "store/candidates"));

    private static final List<java.util.function.BiPredicate<ServerLevel, BlockPos>> LOCAL_RULES =
        new java.util.concurrent.CopyOnWriteArrayList<>();

    public static void registerLocalRules(java.util.function.BiPredicate<ServerLevel, BlockPos> accessPoint) {
        LOCAL_RULES.add(Objects.requireNonNull(accessPoint));
    }

    public static boolean localRules(ServerLevel level, WorldPos where) {
        return io.github.izakyl.folkways.core.world.api.WorldSpaces.storage(level, where)
            .filter(level::isLoaded).filter(pos -> LOCAL_RULES.stream().anyMatch(test -> test.test(level, pos)))
            .isPresent();
    }

    private static volatile BiFunction<ServerLevel, BlockPos, Optional<ItemPort>> itemAt =
        (level, pos) -> Optional.empty();
    private static volatile Function<WorldPos, Optional<PortObservation>> observations =
        at -> Optional.empty();

    private static volatile BiPredicate<ServerLevel, BlockPos> attachedStore = (level, pos) -> false;
    private static volatile BiFunction<ServerLevel, BlockPos, Optional<UUID>> owningPart =
        (level, pos) -> Optional.empty();

    public static boolean owned(ServerLevel level, BlockPos pos) {
        return owningPart(level, pos).isPresent();
    }

    public static boolean attached(ServerLevel level, BlockPos pos) {
        return attachedStore.test(level, pos);
    }

    public static boolean unavailable(ServerLevel level, BlockPos pos) {
        return owned(level, pos) || attached(level, pos);
    }

    private StoreAccess() {
    }

    public static Optional<ItemPort> at(ServerLevel level, BlockPos pos) {
        return !level.isLoaded(pos) || unavailable(level, pos) ? Optional.empty() : itemAt.apply(level, pos);
    }

    public static Optional<UUID> owningPart(ServerLevel level, BlockPos pos) {
        return owningPart.apply(level, pos);
    }

    /** Applies the existing store exclusions to a default item port. */
    public static ItemPort checked(ServerLevel level, BlockPos pos, int priority, int containersPriority, ItemPort port) {
        var state = level.getBlockState(pos);
        if (state.is(REFUSED)) {
            String namespace = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getNamespace();
            String reason = namespace.equals("lootr") ? "personal" :
                BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString().equals("create:item_vault")
                    ? "vault" : "refused";
            return new Unavailable(port,
                Component.translatable("folkways.store." + reason));
        }
        if (priority <= containersPriority && !state.is(ALLOWED)
            && (priority < containersPriority
                || !BuiltInRegistries.BLOCK.getKey(state.getBlock()).getNamespace().equals("minecraft"))) {
            return new Unavailable(port,
                Component.translatable("folkways.store.unsupported"));
        }
        return port;
    }

    private static final List<TagKey<Block>> NETWORK_ACCESS = List.of("take", "put", "craft").stream()
        .map(name -> TagKey.create(Registries.BLOCK,
            ResourceLocation.fromNamespaceAndPath("folkways_core", "network_access/" + name)))
        .toList();

    /**
     * Whether the block may be enrolled as a store, on either side. On the server this resolves the port. A client
     * cannot resolve ports, so it predicts from what it can see: a container, a store candidate or a network
     * access tag. The prediction may be wider than the answer; the server resolves again before it takes anything in.
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

    private record Unavailable(ItemPort inner, Component reason) implements ItemPort {

        @Override
        public WorldPos anchor() {
            return identity().home();
        }

        @Override
        public PortIdentity identity() {
            return inner.identity();
        }

        @Override
        public Optional<Component> unavailable(ServerLevel level) {
            return Optional.of(reason);
        }

        @Override
        public java.util.Set<Operation> operations() {
            return inner.operations();
        }

        @Override
        public PortReader reader(ServerLevel level,
                Optional<SlotWindow> window) {
            return PortReader.EMPTY;
        }

        @Override
        public Optional<List<WorldPos>> proxyMembers() {
            return inner.proxyMembers();
        }

        @Override
        public TransferResult take(ServerLevel level, List<Pull> requested,
                Optional<SlotWindow> window, boolean simulate) {
            return new TransferResult.Refused(TransferResult.Why.UNUSABLE);
        }

        @Override
        public TransferResult put(ServerLevel level, List<net.minecraft.world.item.ItemStack> goods,
                Optional<SlotWindow> window, boolean simulate) {
            return new TransferResult.Refused(TransferResult.Why.UNUSABLE);
        }

        @Override
        public java.util.OptionalLong revision() {
            return inner.revision();
        }
    }

    public static Optional<PortObservation> observation(WorldPos at) {
        return observations.apply(at);
    }

    /** Store eligibility combines observed behavior with native reach and write effects. */
    public static Usability usability(ServerLevel level, ItemPort port) {
        Optional<PortObservation> observation = observation(port.identity().home());
        if (observation.isPresent()) {
            return new Usability.Unusable(observation.get().reason(), true);
        }
        Optional<Component> unavailable = port.unavailable(level);
        if (unavailable.isPresent()) {
            return Usability.because(unavailable.get());
        }
        return port.transforms().stream().findFirst()
            .<Usability>map(hint -> Usability.because(reason(hint))).orElse(Usability.USABLE);
    }

    private static Component reason(TransformHint hint) {
        return switch (hint) {
            case TransformHint.Discard discard -> Component.translatable(switch (discard.mode()) {
                case UPGRADE -> "folkways.storage.unusable.void";
                case NETWORK_EXCESS -> "folkways.rs2.voiding";
            });
            case TransformHint.Convert ignored -> Component.translatable("folkways.storage.unusable.compacting");
            case TransformHint.Compacting ignored -> Component.translatable("folkways.storage.unusable.compacting");
            case TransformHint.Conversion ignored -> Component.translatable("folkways.storage.unusable.conversion");
            case TransformHint.Source ignored -> Component.translatable("folkways.storage.unusable.infinite");
            case TransformHint.UnknownUpgrade unknown -> Component.translatable("folkways.storage.unusable.upgrade",
                unknown.upgrade().getHoverName());
        };
    }

    /** Read visible stock for the panel, including readable controls with unavailable writes. */
    public static PortReader reader(ItemPort port, ServerLevel level, Optional<SlotWindow> window) {
        return observation(port.identity().home()).isPresent() || !port.transforms().isEmpty()
            ? PortReader.EMPTY : port.reader(level, window);
    }

    /** Core only. */
    public static void install(BiFunction<ServerLevel, BlockPos, Optional<ItemPort>> resolver,
            Function<WorldPos, Optional<PortObservation>> recorded, BiPredicate<ServerLevel, BlockPos> attached,
            BiFunction<ServerLevel, BlockPos, Optional<UUID>> owner) {
        itemAt = Objects.requireNonNull(resolver);
        observations = Objects.requireNonNull(recorded);
        attachedStore = Objects.requireNonNull(attached);
        owningPart = Objects.requireNonNull(owner);
    }
}
