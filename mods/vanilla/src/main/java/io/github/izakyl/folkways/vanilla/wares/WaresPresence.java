package io.github.izakyl.folkways.vanilla.wares;

import io.github.izakyl.folkways.core.facility.api.Contribution;
import io.github.izakyl.folkways.core.facility.api.DefinedFacilities;
import io.github.izakyl.folkways.core.front.api.HoldingKind;
import io.github.izakyl.folkways.core.inventory.api.StoreWorkstations;
import io.github.izakyl.folkways.core.resident.api.Bodies;
import io.github.izakyl.folkways.core.resident.api.KitProviders;

import io.github.izakyl.folkways.core.colony.api.Closing;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.colony.api.ColonyView;
import io.github.izakyl.folkways.core.colony.api.Sweep;
import io.github.izakyl.folkways.core.inventory.api.InventorySnapshot;
import io.github.izakyl.folkways.core.inventory.api.port.Usability;
import io.github.izakyl.folkways.core.facility.api.Facilities;
import io.github.izakyl.folkways.core.inventory.api.port.StoreAccess;
import net.minecraft.ChatFormatting;
import io.github.izakyl.folkways.core.inventory.api.Stash;
import io.github.izakyl.folkways.core.item.api.BlockNames;
import io.github.izakyl.folkways.core.item.api.Containers;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.plan.api.Grown;
import io.github.izakyl.folkways.core.plan.api.Workshop;
import io.github.izakyl.folkways.core.world.api.Stances;
import io.github.izakyl.folkways.core.world.api.Reach;
import io.github.izakyl.folkways.core.world.api.Stand;
import io.github.izakyl.folkways.core.world.api.StandGeometry;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.front.api.GhostSource;
import io.github.izakyl.folkways.core.front.api.Fronts;
import io.github.izakyl.folkways.core.front.api.Placard;
import io.github.izakyl.folkways.core.card.api.notice.Line;
import io.github.izakyl.folkways.core.card.api.notice.Sentence;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

final class WaresPresence implements GhostSource, HoldingKind {

    private final Colony colony;

    private final Sweep sweep;

    private final CraftRecipes tenure;

    private final Map<WorldPos, StationHost> stations = new LinkedHashMap<>();

    private record StoreStation(WorldPos at, StoreWorkstations.Capability capability, Block block) {
    }

    private final Map<StoreStation, PackCraftHost> storeStations = new LinkedHashMap<>();

    private final Bookings spokenFor = new Bookings();

    private volatile Map<Block, Set<WorldPos>> cookers = Map.of();

    private final Function<Block, Set<WorldPos>> kin = block -> cookers.getOrDefault(block, Set.of());

    private final Upkeep upkeep;

    WaresPresence(Colony colony, CraftRecipes tenure) {
        this.colony = colony;
        this.sweep = new Sweep(colony, WaresContent.ID, 40);
        this.upkeep = new Upkeep(spokenFor, sweep::nudge);
        this.tenure = tenure;
    }

    public void tick(MinecraftServer server) {
        sweep.tick(server, this::refresh, this::goals);
    }

    void refresh(MinecraftServer server) {
        RecipeIndex recipes = tenure.recipes();
        Optional<ItemSpec> fuel = fuel();
        List<WorldPos> standing = new ArrayList<>();
        Set<StoreStation> provided = new LinkedHashSet<>();
        List<Upkeep.Cold> cold = new ArrayList<>();
        List<Upkeep.Stranded> stranded = new ArrayList<>();
        for (ColonyView view : colony.views(server)) {
            ServerLevel level = view.level();
            List<Workshop> here = new ArrayList<>();
            for (BlockPos pos : view.blocks(WaresContent.STATION)) {
                Optional<StationPrefab> nativeStation = StationPrefab.at(level, pos);
                if (nativeStation.isEmpty()) {
                    continue;
                }
                StationPrefab prefab = nativeStation.get();
                WorldPos at = prefab.site();
                BlockState state = level.getBlockState(pos);
                StationHost host = stations.get(at);
                if (host == null) {
                    Optional<Stances> footings = Stances.of(level, Reach.workableCells(level, pos));
                    if (footings.isEmpty()) {
                        continue;
                    }
                    host = prefab.host(footings.get(), spokenFor, kin, sweep::nudge);
                    stations.put(at, host);
                }
                for (Contribution projection : prefab.offering(host, recipes, fuel).projections(level, Map.of())) {
                    if (projection instanceof Contribution.Workshop work) {
                        here.add(work.workshop());
                    }
                }
                standing.add(at);
                upkeep(level, host, at, state, fuel, cold, stranded);
            }
            for (BlockPos pos : view.blocks(WaresContent.STORE)) {
                if (StoreAccess.unavailable(level, pos)) {
                    continue;
                }
                Set<StoreWorkstations.Capability> capabilities = StoreWorkstations.at(level, pos);
                if (capabilities.isEmpty() || DefinedFacilities.covers(level, WorldPos.of(level, pos))) {
                    continue;
                }
                WorldPos at = WorldPos.of(level, pos);
                Optional<Stances> footings = Stances.of(StandGeometry.footingsOf(level, at).stream()
                    .map(Stand::cell).toList());
                if (footings.isEmpty()) {
                    continue;
                }
                var container = Facilities.container(level, at).filter(store -> !store.network());
                if (container.isEmpty()) {
                    continue;
                }
                List<Workshop> offers = new ArrayList<>();
                Block block = level.getBlockState(pos).getBlock();
                for (StoreWorkstations.Capability capability : capabilities) {
                    // A separately enrolled vanilla station already offers this same capability.
                    if (stations.containsKey(at) && block == capability.station()) {
                        continue;
                    }
                    StoreStation key = new StoreStation(at, capability, block);
                    PackCraftHost host = storeStations.get(key);
                    if (host == null || !host.stances().equals(footings.get())) {
                        host = new PackCraftHost(at, block, capability, footings.get(), world ->
                            !StoreAccess.unavailable(world, at.block(world))
                                && colony.claims(WaresContent.STORE).stream()
                                .anyMatch(claim -> claim.what().singleCell().filter(at::equals).isPresent())
                                && StoreWorkstations.at(world, at.block(world)).contains(capability));
                        storeStations.put(key, host);
                    }
                    offers.add(host.frozen(recipes, level, fuel));
                    provided.add(key);
                }
                for (Contribution projection : container.get().withWorkshops(offers).projections(level, Map.of())) {
                    if (projection instanceof Contribution.Workshop work) {
                        here.add(work.workshop());
                    }
                }
            }
            for (var resident : view.residents()) {
                var body = Optional.ofNullable(level.getEntity(resident.id()))
                    .flatMap(Bodies::of);
                if (body.isEmpty() || !body.get().mob().isAlive()) {
                    continue;
                }
                var capabilities = KitProviders.capabilities(body.get());
                for (var capability : capabilities.workstations()) {
                    PackCraftHost host = new PackCraftHost(body.get().id(),
                        io.github.izakyl.folkways.core.world.api.WorldSpaces.at(body.get().mob()), capability,
                        world -> Optional.ofNullable(world.getEntity(resident.id()))
                            .flatMap(Bodies::of)
                            .filter(held -> held.colonyId().filter(colony.id()::equals).isPresent()
                                && held.mob().isAlive()
                                && KitProviders.capabilities(held)
                                    .workstations().contains(capability)).isPresent());
                    here.add(host.frozen(recipes, level, fuel));
                }
            }
            colony.publish(WaresContent.ID, level.dimension(), here);
        }
        stations.keySet().retainAll(standing);
        storeStations.keySet().retainAll(provided);
        Map<Block, Set<WorldPos>> byBlock = new LinkedHashMap<>();
        stations.forEach((at, host) -> {
            if (host instanceof CookHost cook) {
                byBlock.computeIfAbsent(cook.block(), key -> new LinkedHashSet<>()).add(at);
            }
        });
        byBlock.replaceAll((block, ats) -> Set.copyOf(ats));
        cookers = Map.copyOf(byBlock);
        upkeep.found(cold, stranded);
    }

    public List<Grown> goals(ColonyView view) {
        return upkeep.goals(view.level());
    }

    private static void upkeep(ServerLevel level, StationHost host, WorldPos at, BlockState state,
                               Optional<ItemSpec> fuel, List<Upkeep.Cold> cold,
                               List<Upkeep.Stranded> stranded) {
        Block block = state.getBlock();
        if (!Stations.COOKERS.containsKey(block)) {
            return;
        }
        if (fuel.isPresent() && Machines.coldWithCharge(level, at.block(level), state)) {
            cold.add(new Upkeep.Cold(at, block, host.stances(), fuel.get()));
        }
        Machines.finished(level, at.block(level)).ifPresent(machine -> stranded.add(new Upkeep.Stranded(at, block,
            host.stances(), machine.getItem(Machines.RESULT_SLOT).copy())));
    }

    public void closed(Closing why) {
        if (why == Closing.SHUTDOWN) {
            return;
        }
        stations.clear();
        storeStations.clear();
        spokenFor.clear();
        upkeep.clear();
    }

    // A cooker looked at tells its load and fuel turning and how far through it is; a store, what it holds most of.
    @Override
    public List<Line> lines(Held held, ColonyView view) {
        return statusLines(held.site().block(view.level()), view);
    }

    private List<Line> statusLines(BlockPos at, ColonyView view) {
        ServerLevel level = view.level();
        if (stations.containsKey(WorldPos.of(level, at))) {
            List<Line> lines = new ArrayList<>();
            Sentence told = Hearths.told(level, at, fuel());
            if (!told.isEmpty()) {
                lines.add(Line.told(told));
            }
            Hearths.progress(level, at).ifPresent(lines::add);
            return List.copyOf(lines);
        }
        BlockPos anchor = Containers.anchor(level, at);
        if (!view.blocks(WaresContent.STORE).contains(anchor) || StoreAccess.attached(level, anchor)) {
            return List.of();
        }
        Optional<Component> part = usedBy(level, anchor);
        if (part.isPresent()) {
            return List.of(Line.text(part.get()));
        }
        WorldPos where = WorldPos.of(level, anchor);
        var port = StoreAccess.at(level, anchor);
        InventorySnapshot snapshot = colony.inventory().snapshot();
        List<Line> lines = new ArrayList<>();
        Optional<List<WorldPos>> members = port.flatMap(value -> value.proxyMembers());
        if (members.isPresent()) {
            // An access point holds nothing itself: it tells what its members hold together, each counted once.
            if (StoreAccess.usability(level, port.get()) instanceof Usability.Unusable unusable) {
                lines.add(unusable(unusable));
            } else {
                lines.add(Line.text(Component.translatable("folkways.store.proxy.members", members.get().size())));
            }
            Map<Stash, InventorySnapshot.Store> held = new LinkedHashMap<>();
            boolean pending = false;
            for (WorldPos member : members.get()) {
                var store = snapshot.store(member);
                if (store.isPresent() && StoreContents.known(store.get())) {
                    held.putIfAbsent(store.get().stash(), store.get());
                } else {
                    pending = true;
                }
            }
            if (!held.isEmpty() || !pending && StoreAccess.usability(level, port.get()).usable()) {
                lines.add(StoreContents.of(held.values()));
            } else if (pending && StoreAccess.usability(level, port.get()).usable()) {
                lines.add(Line.text(Component.translatable("folkways.settlers.checking_stock")));
            }
            return List.copyOf(lines);
        }
        var observed = snapshot.store(where);
        if (observed.isEmpty() || observed.get().state() != InventorySnapshot.State.OBSERVED) {
            colony.inventory().ensureObserved(Stash.at(where));
        }
        Usability usability = observed.map(InventorySnapshot.Store::usability)
            .or(() -> port.map(value -> StoreAccess.usability(level, value))).orElse(Usability.USABLE);
        if (usability instanceof Usability.Unusable unusable) {
            lines.add(unusable(unusable));
        }
        boolean known = observed.filter(StoreContents::known).isPresent();
        if (known && (usability.usable() || !observed.get().entries().isEmpty())) {
            lines.add(StoreContents.of(List.of(observed.get())));
        } else if (!known && usability.usable()) {
            lines.add(Line.text(Component.translatable("folkways.settlers.checking_stock")));
        }
        for (StoreWorkstations.Capability capability : StoreWorkstations.at(level, anchor)) {
            lines.add(Line.text(Component.translatable("folkways.wares.store_workstation",
                capability.station().getName())));
        }
        return List.copyOf(lines);
    }

    private Optional<Component> usedBy(ServerLevel level, BlockPos at) {
        return StoreAccess.owningPart(level, at).map(id -> {
            Component name = DefinedFacilities.of(colony).flatMap(facilities -> facilities.facility(id))
                .<Component>map(facility -> Component.literal(facility.name()))
                .orElseGet(() -> Component.translatable("folkways.store.facility"));
            return Component.translatable("folkways.store.used_by", name);
        });
    }

    private static Line unusable(Usability.Unusable unusable) {
        return Line.text(Component.translatable("folkways.store.unusable", unusable.reason())
            .withStyle(ChatFormatting.GRAY));
    }

    // Over every cooker and every store, a card naming it with what its own look card tells, the store's box taking in
    // both halves of a double chest.
    @Override
    public List<Placard> placards(ColonyView view) {
        ServerLevel level = view.level();
        List<Placard> placards = new ArrayList<>();
        for (Map.Entry<WorldPos, StationHost> station : stations.entrySet()) {
            WorldPos cell = station.getKey();
            if (!(station.getValue() instanceof CookHost) || !cell.in(level) || !level.isLoaded(cell.block(level))) {
                continue;
            }
            BlockPos at = cell.block(level);
            placards.add(placard(level, at, at, statusLines(at, view)));
        }
        for (BlockPos anchor : view.blocks(WaresContent.STORE)) {
            if (!level.isLoaded(anchor) || StoreAccess.attached(level, anchor)) {
                continue;
            }
            placards.add(placard(level, anchor, WaresPlugin.otherHalf(level, anchor, level.getBlockState(anchor))
                .orElse(anchor), statusLines(anchor, view)));
        }
        return List.copyOf(placards);
    }

    private static Placard placard(ServerLevel level, BlockPos min, BlockPos max, List<Line> told) {
        List<Line> lines = new ArrayList<>();
        lines.add(Line.text(BlockNames.at(level, min)));
        lines.addAll(told);
        UUID id = UUID.nameUUIDFromBytes(("folkways_vanilla:wares/" + level.dimension().location() + "/" + min.asLong())
            .getBytes(StandardCharsets.UTF_8));
        return new Placard(id, new Placard.Outline.Box(min, max), lines);
    }

    private Optional<ItemSpec> fuel() {
        return Fronts.of(colony).settings(WaresContent.ID).items(WaresContent.FUEL.key());
    }
}
