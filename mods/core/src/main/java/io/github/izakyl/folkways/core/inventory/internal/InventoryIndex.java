package io.github.izakyl.folkways.core.inventory.internal;

import com.mojang.logging.LogUtils;
import io.github.izakyl.folkways.core.inventory.api.ColonyInventory;
import io.github.izakyl.folkways.core.inventory.api.InventoryFeedback;
import io.github.izakyl.folkways.core.inventory.api.InventoryProviders;
import io.github.izakyl.folkways.core.inventory.api.InventorySnapshot.Approach;
import io.github.izakyl.folkways.core.inventory.api.InventorySnapshot.Resident;
import io.github.izakyl.folkways.core.inventory.api.InventorySnapshot.State;
import io.github.izakyl.folkways.core.inventory.api.InventorySnapshot.Store;
import io.github.izakyl.folkways.core.inventory.api.InventorySnapshot.Version;
import io.github.izakyl.folkways.core.inventory.api.InventorySnapshot;
import io.github.izakyl.folkways.core.inventory.api.SlotSnapshot;
import io.github.izakyl.folkways.core.inventory.api.Stash;
import io.github.izakyl.folkways.core.inventory.api.StoreRule;
import io.github.izakyl.folkways.core.inventory.api.StoreReadings;
import io.github.izakyl.folkways.core.inventory.api.port.PortIdentity;
import io.github.izakyl.folkways.core.inventory.api.port.PortReader;
import io.github.izakyl.folkways.core.inventory.api.port.Room;
import io.github.izakyl.folkways.core.inventory.api.port.StorePort;
import io.github.izakyl.folkways.core.inventory.api.port.StorePort.Operation;
import io.github.izakyl.folkways.core.inventory.api.port.StorePorts;
import io.github.izakyl.folkways.core.inventory.api.port.Usability;
import io.github.izakyl.folkways.core.item.api.Containers;
import io.github.izakyl.folkways.core.plan.api.Workshop;
import io.github.izakyl.folkways.core.resident.api.Body;
import io.github.izakyl.folkways.core.resident.api.Pack;
import io.github.izakyl.folkways.core.world.api.BlockChanges;
import io.github.izakyl.folkways.core.world.api.Reach;
import io.github.izakyl.folkways.core.world.api.Stand;
import io.github.izakyl.folkways.core.world.api.StandGeometry;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.slf4j.Logger;

public final class InventoryIndex implements ColonyInventory, InventoryFeedback {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int STEPS = 64;
    private static final int SLOTS_PER_STEP = 64;

    private final UUID colony;
    private UUID epoch = UUID.randomUUID();
    private long contents;
    private long policy;
    private long topology;
    private long approaches;
    private long coverage;
    private long incarnation;
    private long rulesRead = -1;
    private long tick = Long.MIN_VALUE;
    private ObservationBudget budget;
    private final Map<ResourceKey<Level>, Long> membershipRead = new HashMap<>();
    private final Map<ResourceKey<Level>, Membership> membershipDue = new HashMap<>();
    private final Map<ResourceKey<Level>, Set<WorldPos>> members = new LinkedHashMap<>();
    private final Map<ResourceKey<Level>, Set<WorldPos>> excluded = new HashMap<>();

    private record ProviderKey(ResourceLocation owner, ResourceKey<Level> dimension) {
    }

    private final Set<ProviderKey> providerFailures = new LinkedHashSet<>();
    private final Set<ProviderKey> providerIncomplete = new LinkedHashSet<>();
    private final Set<ProviderKey> providersPending = new LinkedHashSet<>();
    private final Map<ProviderKey, Long> providerRead = new HashMap<>();
    private final Map<ProviderKey, ProviderMembership> providerDue = new LinkedHashMap<>();
    private final Map<ProviderKey, Set<WorldPos>> providerMembers = new LinkedHashMap<>();
    private final Map<Stash, StoreEntry> stores = new LinkedHashMap<>();
    private final Map<WorldPos, Stash> aliases = new HashMap<>();
    private final Map<ResourceKey<Level>, Map<WorldPos, Set<WorldPos>>> proxyAccess = new HashMap<>();
    private record PhysicalStore(ResourceKey<Level> dimension, Object key) {
    }

    private final Map<PhysicalStore, StoreEntry> identities = new HashMap<>();
    private final Map<UUID, ResidentEntry> residents = new LinkedHashMap<>();
    private final Deque<Entry> due = new ArrayDeque<>();
    private final Map<ProviderKey, Set<WorldPos>> endpoints = new LinkedHashMap<>();
    private boolean notifying;
    private Iterator<StoreEntry> portsToCheck = java.util.Collections.emptyIterator();
    private volatile InventorySnapshot published;
    private boolean observationMissing;

    public InventoryIndex(UUID colony) {
        this.colony = colony;
        publish();
    }

    @Override
    public InventorySnapshot snapshot() {
        return published;
    }

    // A diagnostic read through the same physical accessor as observation, without refreshing the index.
    public Optional<List<PortReader.Entry>> debugContents(ServerLevel level, Stash stash) {
        if (!level.getServer().isSameThread()) {
            throw new IllegalStateException("live inventory diagnostics belong to the server thread");
        }
        StoreEntry entry = stores.get(stash);
        if (entry == null || !entry.dimension.equals(level.dimension())) {
            return Optional.empty();
        }
        BlockPos anchor = WorldSpaces.storage(level, stash.pos()).orElse(null);
        if (anchor == null || !level.isLoaded(anchor)) {
            return Optional.empty();
        }
        for (WorldPos alias : entry.aliases) {
            BlockPos cell = WorldSpaces.storage(level, alias).orElse(null);
            if (cell == null || !level.isLoaded(cell)) {
                return Optional.empty();
            }
        }
        Container container = entry.provider == null ? null
            : entry.provider.open(level, new InventoryProviders.Endpoint(entry.endpoint.inventory(), stash.pos()));
        StorePort port = entry.provider == null ? StorePorts.at(level, anchor).orElse(null)
            : container == null ? null : new SlotPort(PortIdentity.block(stash.pos()), container);
        if (port == null || !port.usability().usable()) {
            return Optional.empty();
        }
        // Diagnostics must not stall the server or publish a partial read as a difference.
        PortReader reader = port.reader(level, entry.rules);
        if (!reader.step(SLOTS_PER_STEP * STEPS)) {
            return Optional.empty();
        }
        return Optional.of(reader.result().entries());
    }

    @Override
    public void ensureObserved() {
        if (!observationMissing) {
            return;
        }
        requestProviders(null, true);
        for (Membership membership : membershipDue.values()) {
            enqueue(membership);
        }
        for (ProviderMembership provider : providerDue.values()) {
            enqueue(provider);
        }
        for (StoreEntry entry : stores.values()) {
            if (entry.state != State.OBSERVED) {
                enqueue(entry);
            }
        }
        for (ResidentEntry entry : residents.values()) {
            if (entry.state != State.OBSERVED) {
                enqueue(entry);
            }
        }
    }

    @Override
    public void ensureObserved(Stash store) {
        StoreEntry entry = stores.get(aliases.getOrDefault(store.pos(), store));
        if (entry != null && entry.state != State.OBSERVED) {
            enqueue(entry);
        }
    }

    @Override
    public void ensureObservedResident(UUID resident) {
        ResidentEntry entry = residents.get(resident);
        if (entry != null && entry.state != State.OBSERVED) {
            enqueue(entry);
        }
    }

    @Override
    public void ensureObservedProvider(ResourceLocation owner) {
        for (StoreEntry entry : stores.values()) {
            if (entry.endpoint != null && entry.endpoint.inventory().equals(owner) && entry.state != State.OBSERVED) {
                enqueue(entry);
            }
        }
        requestProviders(owner, false);
    }

    private void requestProviders(ResourceLocation owner, boolean all) {
        for (ProviderKey key : Stream.concat(providerIncomplete.stream(), providerFailures.stream()).toList()) {
            if ((all || key.owner().equals(owner))
                && !providerDue.containsKey(key) && providersPending.add(key)) {
                providerRead.remove(key);
            }
        }
    }

    @Override
    public void observe(ResourceLocation owner, ServerLevel level, Collection<WorldPos> positions) {
        ProviderKey observer = new ProviderKey(owner, level.dimension());
        Set<WorldPos> next = Set.copyOf(positions);
        if (next.equals(endpoints.getOrDefault(observer, Set.of()))) {
            return;
        }
        endpoints.put(observer, next);
        int immediate = STEPS;
        ObservationBudget immediateBudget = new ObservationBudget(STEPS);
        for (WorldPos at : next) {
            Stash where = aliases.getOrDefault(at, Stash.at(at));
            if (!stores.containsKey(where)) {
                StoreEntry entry = new StoreEntry(where, level.dimension());
                stores.put(where, entry);
                topology++;
                dirty(entry);
                if (immediate-- > 0) {
                    immediateBudget.resume();
                    try {
                        if (!immediateBudget.spent()) {
                            capture(level.getServer(), entry);
                        }
                    } finally {
                        immediateBudget.pause();
                    }
                }
            }
        }
        for (StoreEntry entry : List.copyOf(stores.values())) {
            if (entry.dimension.equals(level.dimension()) && !membershipDue.containsKey(entry.dimension)
                && !retained(entry, null)) {
                remove(entry);
            } else {
                entry.eligibility();
            }
        }
        publish();
    }

    private boolean retained(StoreEntry entry, ProviderKey completed) {
        return entry.provider != null || offered(entry.aliases)
            || providerDue.keySet().stream().anyMatch(key -> !key.equals(completed)
                && key.dimension().equals(entry.dimension))
            || entry.aliases.stream().anyMatch(at -> endpoints.values().stream().anyMatch(set -> set.contains(at)));
    }

    private void remove(StoreEntry entry) {
        due.remove(entry);
        entry.close();
        stores.remove(entry.where, entry);
        identities.remove(new PhysicalStore(entry.dimension, entry.physicalKey), entry);
        aliases.values().removeIf(entry.where::equals);
        topology++;
    }

    // Known mutations publish small inventories immediately. Larger entries retain the sliced reader.
    private void capture(MinecraftServer server, Entry entry) {
        due.remove(entry);
        entry.queued = false;
        boolean wasNotifying = notifying;
        notifying = true;
        try {
            if (!read(entry, server)) {
                enqueue(entry);
            } else if (entry instanceof StoreEntry store && !store.sampled && store.state == State.OBSERVED) {
                enqueue(entry);
            }
        } finally {
            notifying = wasNotifying;
        }
    }

    private boolean read(Entry entry, MinecraftServer server) {
        if (entry.readingNow) {
            return false;
        }
        entry.readingNow = true;
        long generation = entry.generation;
        try {
            boolean done = entry.read(server);
            if (entry.generation != generation) {
                entry.state(State.DIRTY);
                return false;
            }
            return done;
        } catch (RuntimeException failure) {
            if (entry instanceof ProviderMembership discovery) {
                providerFailures.add(discovery.key);
                providerDue.remove(discovery.key);
                providersPending.remove(discovery.key);
                coverage++;
            } else if (entry instanceof StoreEntry store && store.provider != null) {
                store.state(State.UNSUPPORTED);
                store.reading = null;
            } else {
                throw failure;
            }
            LOGGER.warn("Inventory provider observation failed for colony {}", colony, failure);
            return true;
        } finally {
            entry.readingNow = false;
        }
    }

    public void published(MinecraftServer server, ResourceKey<Level> dimension) {
        Membership membership = membershipDue.get(dimension);
        if (membership != null && membership.read(server)) {
            due.remove(membership);
            membership.queued = false;
        }
        int immediate = STEPS;
        for (StoreEntry entry : List.copyOf(stores.values())) {
            if (stores.get(entry.where) == entry && entry.dimension.equals(dimension)
                && entry.state == State.UNOBSERVED && immediate-- > 0) {
                capture(server, entry);
            }
        }
        publish();
    }

    public void mutated(ServerLevel level, Stash store) {
        StoreEntry entry = stores.get(aliases.getOrDefault(store.pos(), store));
        if (entry != null) {
            dirty(entry);
            capture(level.getServer(), entry);
            publish();
        }
    }

    private void mutatedResident(UUID resident) {
        ResidentEntry entry = residents.get(resident);
        if (entry != null && entry.body != null) {
            dirty(entry);
            capture(entry.body.mob().getServer(), entry);
            publish();
        }
    }

    public void advance(ServerLevel level, InventorySources sources, Map<UUID, Body> present) {
        Map<WorldPos, Set<WorldPos>> access = new LinkedHashMap<>();
        for (WorldPos at : sources.storesIn(level.dimension())) {
            WorldSpaces.resolve(level.getServer(), at).flatMap(block -> StorePorts.at(block.level(), block.cell()))
                .filter(port -> port.usability().usable()).flatMap(StorePort::proxyMembers).ifPresent(represented -> {
                    for (WorldPos member : represented) {
                        access.computeIfAbsent(member, ignored -> new LinkedHashSet<>()).add(at);
                    }
                });
        }
        if (!access.equals(proxyAccess.put(level.dimension(), access))) {
            for (StoreEntry entry : List.copyOf(stores.values())) {
                if (entry.dimension.equals(level.dimension())) {
                    entry.dirtyApproach();
                }
            }
        }
        long revision = sources.inventoryMembershipRevision();
        if (membershipRead.getOrDefault(level.dimension(), -1L) != revision) {
            offered(level.dimension(), sources, revision);
        }
        InventoryProviders.all().forEach((owner, provider) -> {
            ProviderKey key = new ProviderKey(owner, level.dimension());
            long next = provider.revision(sources, level);
            if (providerRead.getOrDefault(key, Long.MIN_VALUE) != next) {
                providerRead.put(key, next);
                providerFailures.remove(key);
                ProviderMembership old = providerDue.remove(key);
                if (old != null) {
                    due.remove(old);
                }
                ProviderMembership discovery = new ProviderMembership(key, provider, sources);
                providerDue.put(key, discovery);
                enqueue(discovery);
                coverage++;
            }
        });
        Set<UUID> roster = new LinkedHashSet<>();
        sources.residents().forEach(resident -> roster.add(resident.id()));
        roster.addAll(present.keySet());
        for (UUID id : roster) {
            residents.computeIfAbsent(id, key -> {
                ResidentEntry entry = new ResidentEntry(key);
                enqueue(entry);
                topology++;
                return entry;
            });
        }
        for (var iterator = residents.entrySet().iterator(); iterator.hasNext();) {
            ResidentEntry entry = iterator.next().getValue();
            if (!roster.contains(entry.id) && (entry.body == null || entry.body.mob().level() == level)) {
                entry.detach();
                due.remove(entry);
                iterator.remove();
                topology++;
            }
        }
        for (ResidentEntry entry : residents.values()) {
            Body body = present.get(entry.id);
            if (body != null && (entry.body == null || entry.body.mob() != body.mob() || entry.pack != body.pack())) {
                attach(entry, body);
            } else if (body != null && entry.at != null
                && !entry.at.realm().equals(WorldSpaces.at(body.mob()).realm())) {
                dirty(entry);
            } else if (body == null && entry.body != null && entry.body.mob().level() == level) {
                entry.detach();
                entry.state(State.UNLOADED);
                entry.generation++;
            }
        }
        if (rulesRead != Stores.revision()) {
            rulesChanged();
        }
        if (!portsToCheck.hasNext()) {
            portsToCheck = List.copyOf(stores.values()).iterator();
        }
        int checked = SLOTS_PER_STEP;
        while (portsToCheck.hasNext() && checked-- > 0) {
            StoreEntry entry = portsToCheck.next();
            if (stores.get(entry.where) != entry || entry.provider != null || !entry.dimension.equals(level.dimension())
                || entry.queued || entry.state != State.OBSERVED) {
                continue;
            }
            StorePort port = StorePorts.at(level, entry.where.pos().block(level)).orElse(null);
            if (port == null || !Objects.equals(entry.physicalKey, port.identity().key())
                || !Objects.equals(entry.revision, port.revision())
                || !entry.usability.equals(entry.usability(port)) || level.getGameTime() - entry.checked >= 100) {
                dirty(entry);
            }
        }
        budget(level);
        budget.resume();
        try {
            while (!due.isEmpty() && !budget.spent()) {
                step(level.getServer());
            }
        } finally {
            budget.pause();
        }
        publish();
    }

    private void budget(ServerLevel level) {
        long now = level.getServer().overworld().getGameTime();
        if (now != tick) {
            tick = now;
            budget = new ObservationBudget(STEPS);
        }
    }

    private void attach(ResidentEntry entry, Body body) {
        entry.detach();
        entry.body = body;
        entry.pack = body.pack();
        entry.pack.onChanged(() -> {
            if (entry.body != null) {
                if (entry.body.colonyId().filter(colony::equals).isPresent()) {
                    mutatedResident(entry.id);
                } else if (entry.body.colonyId().isEmpty()) {
                    changedResident(entry.id);
                }
            }
        });
        entry.incarnation = ++incarnation;
        entry.topology++;
        topology++;
        dirty(entry);
    }

    // Enrollment is a known lifecycle mutation. A small pack can publish within this tick's remaining budget.
    public void entered(Body body) {
        joined(body.id());
        ResidentEntry entry = residents.get(body.id());
        if (entry.body == null || entry.body.mob() != body.mob() || entry.pack != body.pack()) {
            attach(entry, body);
        }
        observeResident(entry);
        publish();
    }

    private void observeResident(ResidentEntry entry) {
        if (entry.body != null && entry.body.mob().level() instanceof ServerLevel level && entry.queued) {
            budget(level);
            budget.resume();
            try {
                if (!budget.spent()) {
                    capture(level.getServer(), entry);
                }
            } finally {
                budget.pause();
            }
        }
    }

    public void offered(ResourceKey<Level> dimension, InventorySources sources, long revision) {
        membershipRead.put(dimension, revision);
        Membership old = membershipDue.remove(dimension);
        if (old != null) {
            due.remove(old);
        }
        Membership next = new Membership(dimension, sources);
        membershipDue.put(dimension, next);
        members.put(dimension, next.offered);
        excluded.put(dimension, next.barred);
        enqueue(next);
        topology++;
        coverage++;
        publish();
    }

    public void rulesChanged() {
        rulesRead = Stores.revision();
        for (StoreEntry entry : stores.values()) {
            List<StoreRule> next = Stores.rulesAt(entry.aliases);
            if (!entry.rules.equals(next)) {
                entry.rules = next;
                if (entry.slotted) {
                    entry.slots = entry.slots.stream().map(slot -> new SlotSnapshot(slot.physicalSlot(),
                        Stores.accessibleSlot(next, entry.slots.size(), slot.physicalSlot()),
                        slot.stackCopy(), slot.stackLimit(), slot.incarnation())).toList();
                    entry.entries = StoreReadings.entries(entry.slots);
                    if (entry.room instanceof Room.Slots shared && entry.usability.usable()) {
                        List<Room.Cell> partial = entry.slots.stream()
                            .filter(slot -> slot.accessibleSlot().isPresent() && !slot.stackCopy().isEmpty()
                                && slot.stackLimit() > slot.stackCopy().getCount())
                            .map(slot -> new Room.Cell(slot.stackCopy(),
                                slot.stackLimit() - slot.stackCopy().getCount())).toList();
                        long free = entry.slots.stream().filter(slot -> slot.accessibleSlot().isPresent()
                            && slot.stackCopy().isEmpty()).count();
                        entry.room = new Room.Slots(partial, free, shared.cap());
                    }
                }
                entry.policy++;
                policy++;
                entry.generation++;
                entry.reading = null;
                enqueue(entry);
            }
        }
        publish();
    }

    public void joined(UUID resident) {
        if (!residents.containsKey(resident)) {
            ResidentEntry entry = new ResidentEntry(resident);
            residents.put(resident, entry);
            enqueue(entry);
            topology++;
            publish();
        }
    }

    public void left(UUID resident) {
        ResidentEntry entry = residents.remove(resident);
        if (entry != null) {
            entry.detach();
            due.remove(entry);
            topology++;
            publish();
        }
    }

    private void step(MinecraftServer server) {
        Entry entry = due.removeFirst();
        entry.queued = false;
        if (entry.state == State.UNOBSERVED) {
            entry.state(State.QUEUED);
        }
        if (!read(entry, server)) {
            enqueue(entry);
        }
    }

    // Package access lets budget tests stop at an exact work boundary without changing the production budget.
    void work(MinecraftServer server, int steps) {
        while (steps-- > 0 && !due.isEmpty()) {
            step(server);
        }
        publish();
    }

    private void enqueue(Entry entry) {
        if (!entry.queued) {
            entry.queued = true;
            due.addLast(entry);
        }
    }

    private void dirty(Entry entry) {
        entry.state(State.DIRTY);
        entry.generation++;
        enqueue(entry);
    }

    @Override
    public void changed(Stash store) {
        changed(stores.get(aliases.getOrDefault(store.pos(), store)));
    }

    private void changed(Entry entry) {
        if (entry != null) {
            State was = entry.state;
            dirty(entry);
            if (was != entry.state) {
                publish();
            }
        }
    }

    @Override
    public void providersChanged(ResourceLocation owner) {
        for (var dimension : membershipRead.keySet()) {
            ProviderKey key = new ProviderKey(owner, dimension);
            providerRead.remove(key);
            providersPending.add(key);
        }
        coverage++;
        publish();
    }

    @Override
    public void changedResident(UUID resident) {
        changed(residents.get(resident));
    }

    @Override
    public long changedResidentSlot(UUID resident, int physicalSlot, ItemStack stack) {
        ResidentEntry entry = residents.get(resident);
        if (entry == null) {
            return 0;
        }
        long identity = entry.slotObservation.identify(physicalSlot, stack);
        changedResident(resident);
        return identity;
    }

    @Override
    public long changedEquipment(UUID resident, EquipmentSlot slot, ItemStack stack) {
        return changedResidentSlot(resident, -1 - slot.ordinal(), stack);
    }

    @Override
    public void stale(StoreSlot slot, Version observed) {
        stale(slot.store(), observed);
    }

    @Override
    public void stale(Stash store, Version observed) {
        StoreEntry entry = stores.get(aliases.getOrDefault(store.pos(), store));
        if (entry != null && entry.version().equals(observed)) {
            changed(entry.where);
        }
    }

    @Override
    public void staleResident(UUID resident, Version observed) {
        ResidentEntry entry = residents.get(resident);
        if (entry != null && entry.version().equals(observed)) {
            changedResident(resident);
        }
    }

    @Override
    public void staleApproach(Stash store, Version observed) {
        StoreEntry entry = stores.get(aliases.getOrDefault(store.pos(), store));
        if (entry != null && entry.version().equals(observed)) {
            entry.dirtyApproach();
        }
    }

    public void unloaded(UUID resident) {
        ResidentEntry entry = residents.get(resident);
        if (entry != null) {
            entry.detach();
            entry.state(State.UNLOADED);
            due.remove(entry);
            entry.queued = false;
            publish();
        }
    }

    public void chunk(ServerLevel level, int x, int z, boolean loaded) {
        int span = (int) Math.ceil(Reach.PLAYER_BLOCK_REACH);
        for (StoreEntry entry : stores.values()) {
            WorldSpaces.storage(level, entry.where.pos()).ifPresent(pos -> {
                boolean physical = pos.getX() >> 4 == x && pos.getZ() >> 4 == z
                    || entry.other != null && entry.other.getX() >> 4 == x && entry.other.getZ() >> 4 == z;
                if (physical) {
                    if (loaded) {
                        dirty(entry);
                    } else {
                        entry.unloaded();
                        due.remove(entry);
                        entry.queued = false;
                    }
                } else if (x >= (pos.getX() - span) >> 4 && x <= (pos.getX() + span) >> 4
                    && z >= (pos.getZ() - span) >> 4 && z <= (pos.getZ() + span) >> 4) {
                    entry.dirtyApproach();
                }
            });
        }
        publish();
    }

    public void clear() {
        residents.values().forEach(ResidentEntry::detach);
        stores.values().forEach(StoreEntry::close);
        stores.clear();
        endpoints.clear();
        aliases.clear();
        identities.clear();
        residents.clear();
        members.clear();
        excluded.clear();
        membershipRead.clear();
        membershipDue.clear();
        providerRead.clear();
        providersPending.clear();
        providerFailures.clear();
        providerIncomplete.clear();
        providerDue.clear();
        providerMembers.clear();
        due.clear();
        portsToCheck = java.util.Collections.emptyIterator();
        tick = Long.MIN_VALUE;
        budget = null;
        epoch = UUID.randomUUID();
        topology++;
        coverage++;
        publish();
    }

    private void publish() {
        Version version = new Version(epoch, contents, policy, topology, approaches, coverage);
        if (published != null && published.version().equals(version)) {
            return;
        }
        Map<Stash, Store> stored = new LinkedHashMap<>();
        Map<Stash, State> storeCoverage = new LinkedHashMap<>();
        boolean missing = false;
        for (StoreEntry entry : stores.values()) {
            stored.put(entry.where, new Store(entry.where, entry.aliases, entry.dimension, entry.incarnation,
                entry.version(), entry.state, entry.labor, entry.rules, entry.slots, entry.refused, entry.approach,
                entry.block, entry.entries, entry.room, entry.usability, entry.operations, entry.container, entry.storage));
            // An approach-only point may have no inventory, but a stock endpoint must be observed.
            if (entry.state != State.UNSUPPORTED || entry.labor || entry.provider != null) {
                storeCoverage.put(entry.where, entry.state);
                missing |= entry.state != State.OBSERVED;
            }
        }
        Map<UUID, Resident> carried = new LinkedHashMap<>();
        Map<UUID, State> residentCoverage = new LinkedHashMap<>();
        for (ResidentEntry entry : residents.values()) {
            carried.put(entry.id, new Resident(entry.id, entry.at, entry.incarnation, entry.version(), entry.state,
                entry.slots, entry.equipment));
            residentCoverage.put(entry.id, entry.state);
            missing |= entry.state != State.OBSERVED;
        }
        boolean pending = !membershipDue.isEmpty() || !providerDue.isEmpty() || !providersPending.isEmpty()
            || !providerFailures.isEmpty() || !providerIncomplete.isEmpty();
        Map<ResourceLocation, Boolean> providerCoverage = new LinkedHashMap<>();
        for (ProviderKey key : providerRead.keySet()) {
            boolean complete = !providerDue.containsKey(key) && !providersPending.contains(key)
                && !providerFailures.contains(key) && !providerIncomplete.contains(key);
            providerCoverage.merge(key.owner(), complete, (left, right) -> left && right);
        }
        for (ProviderKey key : providersPending) {
            providerCoverage.put(key.owner(), false);
        }
        for (StoreEntry entry : stores.values()) {
            if (entry.endpoint != null && entry.state != State.OBSERVED) {
                providerCoverage.put(entry.endpoint.inventory(), false);
            }
        }
        observationMissing = missing || pending;
        published = new InventorySnapshot(colony, version, stored, carried,
            new InventorySnapshot.Coverage(storeCoverage, residentCoverage, pending, providerCoverage));
    }

    private boolean offered(Set<WorldPos> locations) {
        return locations.stream().anyMatch(at -> members.values().stream().anyMatch(set -> set.contains(at))
            || providerMembers.values().stream().anyMatch(set -> set.contains(at))
            || providerDue.values().stream().anyMatch(discovery -> discovery.found.contains(at)));
    }

    private final class ProviderMembership extends Entry {

        private final ProviderKey key;
        private final InventoryProviders.Provider provider;
        private final InventorySources sources;
        private final Set<WorldPos> found = new LinkedHashSet<>();
        private String cursor;
        private Iterator<StoreEntry> cleanup;

        ProviderMembership(ProviderKey key, InventoryProviders.Provider provider, InventorySources sources) {
            this.key = key;
            this.provider = provider;
            this.sources = sources;
        }

        @Override
        boolean read(MinecraftServer server) {
            ServerLevel level = server.getLevel(key.dimension());
            if (level == null) {
                return false;
            }
            if (cleanup == null) {
                var page = provider.discover(sources, level, cursor, SLOTS_PER_STEP);
                if (page.endpoints().size() > SLOTS_PER_STEP) {
                    throw new IllegalStateException(key.owner() + " exceeded its discovery page budget");
                }
                for (var endpoint : page.endpoints()) {
                    WorldPos at = endpoint.anchor();
                    found.add(at);
                    Stash where = aliases.getOrDefault(at, Stash.at(at));
                    StoreEntry entry = stores.computeIfAbsent(where, stash -> new StoreEntry(stash, key.dimension()));
                    boolean changed = entry.provider != provider || !endpoint.equals(entry.endpoint);
                    entry.provider = provider;
                    entry.endpoint = endpoint;
                    entry.aliases.add(at);
                    entry.eligibility();
                    if (changed || entry.state == State.UNOBSERVED) {
                        dirty(entry);
                    }
                }
                cursor = page.next();
                if (cursor != null) {
                    return false;
                }
                if (page.complete()) {
                    providerIncomplete.remove(key);
                } else {
                    providerIncomplete.add(key);
                    found.addAll(providerMembers.getOrDefault(key, Set.of()));
                }
                providerMembers.put(key, Set.copyOf(found));
                cleanup = List.copyOf(stores.values()).iterator();
            }
            int limit = SLOTS_PER_STEP;
            while (cleanup.hasNext() && limit-- > 0) {
                StoreEntry entry = cleanup.next();
                if (!entry.dimension.equals(key.dimension())) {
                    continue;
                }
                if (entry.provider == provider && entry.aliases.stream().noneMatch(found::contains)) {
                    entry.provider = null;
                    entry.endpoint = null;
                    entry.eligibility();
                    dirty(entry);
                }
                if (!retained(entry, key) && !membershipDue.containsKey(key.dimension())) {
                    remove(entry);
                }
            }
            if (cleanup.hasNext()) {
                return false;
            }
            providerDue.remove(key);
            providersPending.remove(key);
            topology++;
            coverage++;
            return true;
        }
    }

    private abstract class Entry {

        State state = State.UNOBSERVED;
        boolean queued;
        boolean readingNow;
        long generation;
        long contents;
        long policy;
        long topology;
        long approaches;
        long incarnation;
        final SlotObservation slotObservation = new SlotObservation(() -> ++InventoryIndex.this.incarnation);
        long covered;
        List<SlotSnapshot> slots = List.of();

        abstract boolean read(MinecraftServer server);

        Version version() {
            return new Version(epoch, contents, policy, topology, approaches, covered);
        }

        void state(State next) {
            if (state != next) {
                state = next;
                covered++;
                coverage++;
            }
        }
    }

    private final class Membership extends Entry {

        private final ResourceKey<Level> dimension;
        private final Set<WorldPos> offered = new LinkedHashSet<>();
        private final Set<WorldPos> barred = new LinkedHashSet<>();
        private final Iterator<WorldPos> storesToAdd;
        private final Iterator<WorldPos> withheld;
        private final Iterator<Workshop> workshops;
        private Iterator<StoreEntry> cleanup;

        Membership(ResourceKey<Level> dimension, InventorySources sources) {
            this.dimension = dimension;
            storesToAdd = sources.storesIn(dimension).iterator();
            withheld = sources.withheldIn(dimension).iterator();
            workshops = sources.workshopsIn(dimension).iterator();
        }

        @Override
        boolean read(MinecraftServer server) {
            int limit = SLOTS_PER_STEP;
            while (limit-- > 0) {
                if (withheld.hasNext()) {
                    barred.add(withheld.next());
                } else if (workshops.hasNext()) {
                    Workshop workshop = workshops.next();
                    if (workshop.withholdsStore()) {
                        barred.add(workshop.site().where());
                    }
                } else if (storesToAdd.hasNext()) {
                    WorldPos at = storesToAdd.next();
                    offered.add(at);
                    if (WorldSpaces.resolve(server, at)
                        .flatMap(block -> StorePorts.at(block.level(), block.cell()))
                        .flatMap(StorePort::proxyMembers).isPresent()) {
                        continue;
                    }
                    Stash where = aliases.getOrDefault(at, Stash.at(at));
                    StoreEntry entry = stores.get(where);
                    if (entry == null) {
                        entry = new StoreEntry(where, dimension);
                        stores.put(where, entry);
                        enqueue(entry);
                    }
                    entry.aliases.add(at);
                } else {
                    if (cleanup == null) {
                        cleanup = List.copyOf(stores.values()).iterator();
                    }
                    if (!cleanup.hasNext()) {
                        membershipDue.remove(dimension);
                        coverage++;
                        return true;
                    }
                    StoreEntry entry = cleanup.next();
                    if (!entry.dimension.equals(dimension)) {
                        continue;
                    }
                    if (!retained(entry, null)) {
                        remove(entry);
                    } else {
                        entry.eligibility();
                        if (entry.state != State.UNOBSERVED && entry.state != State.QUEUED) {
                            dirty(entry);
                        }
                    }
                }
            }
            return false;
        }
    }

    private final class StoreEntry extends Entry {

        Stash where;
        final ResourceKey<Level> dimension;
        final Set<WorldPos> aliases = new LinkedHashSet<>();
        boolean labor = true;
        boolean storage;
        InventoryProviders.Provider provider;
        InventoryProviders.Endpoint endpoint;
        List<StoreRule> rules = List.of();
        Set<Item> refused = Set.of();
        Approach approach = new Approach(0, State.UNOBSERVED, Set.of());
        Optional<ResourceLocation> block = Optional.empty();
        Object identity;
        Object otherIdentity;
        BlockPos other;
        Class<?> kind;
        ResourceLocation inventory;
        int size;
        Reading reading;
        boolean sampled;
        Object physicalKey;
        OptionalLong revision = OptionalLong.empty();
        List<PortReader.Entry> entries = List.of();
        Room room = Room.NONE;
        Usability usability = Usability.USABLE;
        Map<WorldPos, Set<Operation>> operations = Map.of();
        boolean slotted = true;
        boolean container;
        long checked;
        final Map<WorldPos, BlockChanges.Heard> ground = new LinkedHashMap<>();

        StoreEntry(Stash where, ResourceKey<Level> dimension) {
            this.where = where;
            this.dimension = dimension;
            aliases.add(where.pos());
        }

        void close() {
            ground.values().forEach(BlockChanges.Heard::close);
            ground.clear();
        }

        void unloaded() {
            state(State.UNLOADED);
            approach = new Approach(approach.revision(), State.UNLOADED, approach.stands());
            close();
            reading = null;
        }

        void dirtyApproach() {
            if (approach.state() != State.DIRTY) {
                approach = new Approach(approach.revision(), State.DIRTY, approach.stands());
                covered++;
                coverage++;
            }
            dirty(this);
            publish();
        }

        void eligibility() {
            boolean nextStorage = provider == null && aliases.stream().anyMatch(alias ->
                members.values().stream().anyMatch(offered -> offered.contains(alias)));
            boolean nextLabor = nextStorage
                && aliases.stream().noneMatch(excluded.getOrDefault(dimension, Set.of())::contains);
            if (labor != nextLabor || storage != nextStorage) {
                labor = nextLabor;
                storage = nextStorage;
                policy++;
                InventoryIndex.this.policy++;
            }
        }

        @Override
        boolean read(MinecraftServer server) {
            WorldSpaces.Block block = WorldSpaces.resolve(server, where.pos()).orElse(null);
            if (block == null || !block.level().isLoaded(block.cell())) {
                unloaded();
                return true;
            }
            ServerLevel level = block.level();
            Set<WorldPos> oldAliases = Set.copyOf(aliases);
            if (provider == null && !membershipDue.containsKey(dimension)) {
                Set<WorldPos> registered = aliases.stream().filter(alias -> offered(Set.of(alias))
                    || endpoints.values().stream().anyMatch(points -> points.contains(alias)))
                    .collect(java.util.stream.Collectors.toSet());
                if (!registered.isEmpty()) {
                    aliases.retainAll(registered);
                    WorldPos home = registered.stream().filter(at -> at.sameRealm(where.pos()))
                        .min(java.util.Comparator.comparing(at -> at.block(level), BlockPos::compareTo))
                        .orElse(where.pos());
                    block = WorldSpaces.resolve(server, home).orElse(block);
                }
            }
            StorePort resolved = provider == null ? StorePorts.at(level, block.cell()).orElse(null) : null;
            if (provider == null && resolved != null) {
                Object key = resolved.identity().key();
                PhysicalStore physical = new PhysicalStore(dimension, key);
                StoreEntry joined = identities.get(physical);
                if (joined != null && joined != this) {
                    aliases.addAll(joined.aliases);
                    remove(joined);
                }
                if (!Objects.equals(physicalKey, key)) {
                    identities.remove(new PhysicalStore(dimension, physicalKey), this);
                    physicalKey = key;
                    incarnation = ++InventoryIndex.this.incarnation;
                    topology++;
                    InventoryIndex.this.topology++;
                    reading = null;
                }
                identities.put(physical, this);
                for (WorldPos alias : List.copyOf(aliases)) {
                    StorePort member = WorldSpaces.resolve(server, alias)
                        .flatMap(at -> StorePorts.at(at.level(), at.cell())).orElse(null);
                    if (member != null && !member.identity().key().equals(key)) {
                        aliases.remove(alias);
                        InventoryIndex.this.aliases.remove(alias);
                        if (offered(Set.of(alias))) {
                            Stash split = Stash.at(alias);
                            StoreEntry entry = stores.computeIfAbsent(split, at -> new StoreEntry(at, dimension));
                            dirty(entry);
                        }
                    }
                }
            }
            BlockPos anchor = provider == null && resolved != null
                ? aliases.stream().filter(at -> at.sameRealm(where.pos())).map(at -> at.block(level))
                    .min(BlockPos::compareTo).orElse(block.cell()) : Containers.chestAnchor(level, block.cell());
            // A double chest keeps both physical halves as aliases, including an unregistered half.
            BlockState stateAt = level.getBlockState(anchor);
            other = stateAt.getBlock() instanceof ChestBlock
                && stateAt.getValue(ChestBlock.TYPE) != ChestType.SINGLE
                ? anchor.relative(ChestBlock.getConnectedDirection(stateAt)) : null;
            if (other != null) {
                aliases.add(WorldPos.of(level, other));
                anchor = Containers.chestAnchor(level, anchor);
                stateAt = level.getBlockState(anchor);
                other = anchor.relative(ChestBlock.getConnectedDirection(stateAt));
            }
            Stash canonical = Stash.at(WorldPos.of(level, anchor));
            if (!canonical.equals(where)) {
                stores.remove(where, this);
                StoreEntry had = stores.get(canonical);
                if (had != null && had != this) {
                    aliases.addAll(had.aliases);
                    remove(had);
                }
                close();
                where = canonical;
                stores.put(where, this);
            }
            aliases.add(where.pos());
            oldAliases.stream().filter(alias -> !aliases.contains(alias))
                .forEach(alias -> InventoryIndex.this.aliases.remove(alias, where));
            aliases.forEach(alias -> InventoryIndex.this.aliases.put(alias, where));
            if (!aliases.equals(oldAliases)) {
                topology++;
                InventoryIndex.this.topology++;
                approach = new Approach(approach.revision(), State.DIRTY, approach.stands());
            }
            if (!membershipDue.containsKey(dimension) && !retained(this, null)) {
                stores.remove(where);
                close();
                InventoryIndex.this.aliases.values().removeIf(where::equals);
                InventoryIndex.this.topology++;
                return true;
            }
            eligibility();
            if (!level.isLoaded(anchor) || other != null && !level.isLoaded(other)) {
                unloaded();
                return true;
            }

            for (WorldPos alias : approachesAt()) {
                if (!ground.containsKey(alias)) {
                    BlockPos cell = alias.block(level);
                    int span = (int) Math.ceil(Reach.PLAYER_BLOCK_REACH);
                    BoundingBox box = BoundingBox.fromCorners(cell.offset(-span, -span - 1, -span),
                        cell.offset(span, span + 2, span));
                    ground.put(alias, BlockChanges.listen(level, box, changed -> dirtyApproach()));
                }
            }
            for (WorldPos alias : List.copyOf(ground.keySet())) {
                if (!approachesAt().contains(alias)) {
                    ground.remove(alias).close();
                }
            }
            Container container = provider == null ? null
                : provider.open(level, new InventoryProviders.Endpoint(endpoint.inventory(), where.pos()));
            StorePort port = provider == null ? StorePorts.at(level, anchor).orElse(null)
                : container == null ? null : new SlotPort(PortIdentity.block(where.pos()), container);
            if (port == null) {
                observeApproach(level);
                state(State.UNSUPPORTED);
                reading = null;
                return true;
            }
            if (port instanceof SlotPort slotPort) {
                container = slotPort.container();
            }
            Usability usable = usability(port);
            Map<WorldPos, Set<Operation>> allowed = new LinkedHashMap<>();
            for (WorldPos alias : aliases) {
                StorePort member = provider == null ? WorldSpaces.resolve(server, alias)
                    .flatMap(at -> StorePorts.at(at.level(), at.cell())).orElse(null) : port;
                if (member != null) {
                    allowed.put(alias, member.operations());
                    if (!member.usability().usable()
                        && !(usable instanceof Usability.Unusable verdict && verdict.lasting())) {
                        usable = member.usability();
                    }
                }
            }
            for (WorldPos alias : aliases) {
                for (WorldPos access : proxyAccess.getOrDefault(dimension, Map.of()).getOrDefault(alias, Set.of())) {
                    StorePort proxy = WorldSpaces.resolve(server, access)
                        .flatMap(at -> StorePorts.at(at.level(), at.cell())).orElse(null);
                    if (proxy != null && proxy.usability().usable()) {
                        Set<Operation> shared = new LinkedHashSet<>(proxy.operations());
                        shared.retainAll(port.operations());
                        allowed.put(access, Set.copyOf(shared));
                    }
                }
            }
            if (!usability.equals(usable) || !operations.equals(allowed)) {
                usability = usable;
                operations = Map.copyOf(allowed);
                policy++;
                InventoryIndex.this.policy++;
                approach = new Approach(approach.revision(), State.DIRTY, approach.stands());
            }
            Object current = level.getBlockEntity(anchor);
            Object second = other == null ? null : level.getBlockEntity(other);
            if (identity != current || otherIdentity != second
                || size != (container == null ? -1 : container.getContainerSize())
                || kind != (container == null ? port.getClass() : container.getClass())
                || !Objects.equals(inventory, endpoint == null ? null : endpoint.inventory())) {
                identity = current;
                otherIdentity = second;
                size = container == null ? -1 : container.getContainerSize();
                kind = container == null ? port.getClass() : container.getClass();
                inventory = endpoint == null ? null : endpoint.inventory();
                incarnation = ++InventoryIndex.this.incarnation;
                topology++;
                InventoryIndex.this.topology++;
                reading = null;
                refused = Set.of();
                sampled = false;
                approach = new Approach(approach.revision(), State.DIRTY, approach.stands());
            }
            // Workshop approaches do not depend on completing a possibly sliced inventory read.
            observeApproach(level);
            if (notifying && size > SLOTS_PER_STEP) {
                return false;
            }
            if (reading == null || reading.generation != generation || !reading.revision.equals(port.revision())) {
                Set<Item> cached = provider != null ? Set.of() : sampled || notifying ? refused : null;
                reading = new Reading(this, generation, Stores.rulesAt(aliases), level, port, cached);
            }
            if (!reading.step(level, container)) {
                return false;
            }
            Room nextRoom = usability.usable() ? reading.result.room() : Room.NONE;
            if (!StoreReadings.same(entries, reading.result.entries()) || !room.equals(nextRoom)
                || reading.result.slots().isPresent() && !SlotObservation.sameSlots(slots, reading.slots)) {
                contents++;
                InventoryIndex.this.contents++;
            }
            if (!rules.equals(reading.rules) || !refused.equals(reading.refused())) {
                policy++;
                InventoryIndex.this.policy++;
            }
            if (reading.result.slots().isPresent() && !SlotObservation.sameIdentities(slots, reading.slots)) {
                topology++;
                InventoryIndex.this.topology++;
            }
            var observedBlock = Optional.of(BuiltInRegistries.BLOCK.getKey(stateAt.getBlock()));
            if (!this.block.equals(observedBlock)) {
                this.block = observedBlock;
                topology++;
                InventoryIndex.this.topology++;
            }
            slots = reading.slots;
            entries = reading.result.entries();
            slotted = reading.result.slots().isPresent();
            this.container = container != null;
            room = nextRoom;
            revision = port.revision();
            checked = level.getGameTime();
            rules = reading.rules;
            refused = reading.refused();
            sampled = sampled || !notifying;
            observeApproach(level);
            if (!sampled) {
                reading.cached = provider != null ? Set.of() : null;
            } else {
                reading = null;
            }
            state(State.OBSERVED);
            return true;
        }

        Usability usability(StorePort port) {
            for (WorldPos alias : aliases) {
                var reason = StorePorts.condemned(alias);
                if (reason.isPresent()) {
                    return new Usability.Unusable(reason.get(), true);
                }
            }
            return port.usability();
        }

        private Set<WorldPos> approachesAt() {
            Set<WorldPos> access = new LinkedHashSet<>(aliases);
            for (WorldPos alias : aliases) {
                access.addAll(proxyAccess.getOrDefault(dimension, Map.of()).getOrDefault(alias, Set.of()));
            }
            return access;
        }

        private void observeApproach(ServerLevel level) {
            if (approach.state() != State.OBSERVED) {
                Set<Stand> stands = new LinkedHashSet<>();
                Set<Stand> taking = new LinkedHashSet<>();
                Set<Stand> putting = new LinkedHashSet<>();
                for (WorldPos alias : approachesAt()) {
                    Set<Stand> cells = StandGeometry.footingsOf(level, alias);
                    stands.addAll(cells);
                    Set<Operation> allowed = operations.getOrDefault(alias, Set.of());
                    if (allowed.contains(Operation.TAKE)) {
                        taking.addAll(cells);
                    }
                    if (allowed.contains(Operation.PUT)) {
                        putting.addAll(cells);
                    }
                }
                if (!approach.stands().equals(stands) || !approach.taking().equals(taking)
                    || !approach.putting().equals(putting) || approaches == 0) {
                    approaches++;
                    InventoryIndex.this.approaches++;
                }
                approach = new Approach(approaches, State.OBSERVED, stands, taking, putting);
            }
        }
    }

    private static final class Reading {

        final Entry entry;
        final long generation;
        final List<StoreRule> rules;
        final PortReader reader;
        final OptionalLong revision;
        Set<Item> cached;
        PortReader.PortReading result;
        List<SlotSnapshot> slots = List.of();
        Containers.Refusals refusals;

        Reading(Entry entry, long generation, List<StoreRule> rules, ServerLevel level, StorePort port,
                Set<Item> cached) {
            this.entry = entry;
            this.generation = generation;
            this.rules = rules;
            StorePort observed = port instanceof SlotPort slot ? slot.observing(entry.slotObservation) : port;
            reader = observed.reader(level, rules);
            revision = port.revision();
            this.cached = cached;
        }

        Set<Item> refused() {
            return result.refusesBySampling() && cached != null ? cached
                : refusals == null ? result.refused() : refusals.items();
        }

        boolean step(ServerLevel level, Container container) {
            if (result == null) {
                if (!reader.step(SLOTS_PER_STEP)) {
                    return false;
                }
                result = reader.result();
                slots = result.slots().orElseGet(() -> StoreReadings.synthetic(result.entries()));
            }
            if (result.refusesBySampling() && cached == null && container != null) {
                if (refusals == null) {
                    refusals = new Containers.Refusals(container);
                }
                return refusals.step(container, SLOTS_PER_STEP);
            }
            return true;
        }
    }

    private final class ResidentEntry extends Entry {

        final UUID id;
        Body body;
        Pack pack;
        WorldPos at;
        Map<EquipmentSlot, SlotSnapshot> equipment = Map.of();
        List<SlotSnapshot> reading;
        long readingGeneration;

        ResidentEntry(UUID id) {
            this.id = id;
        }

        void detach() {
            if (pack != null) {
                pack.onChanged(() -> { });
            }
            body = null;
            pack = null;
            reading = null;
        }

        @Override
        boolean read(MinecraftServer server) {
            if (body == null || body.mob().isRemoved()) {
                state(State.UNLOADED);
                return true;
            }
            if (reading == null || readingGeneration != generation) {
                reading = new ArrayList<>();
                readingGeneration = generation;
            }
            if (notifying && pack.getContainerSize() > SLOTS_PER_STEP) {
                return false;
            }
            int limit = SLOTS_PER_STEP;
            while (reading.size() < pack.getContainerSize() && limit-- > 0) {
                int slot = reading.size();
                reading.add(slotObservation.capture(slot, slot, pack.getItem(slot), pack.slotLimit(slot,
                    pack.getItem(slot))));
            }
            if (reading.size() < pack.getContainerSize()) {
                return false;
            }
            Map<EquipmentSlot, SlotSnapshot> worn = new EnumMap<>(EquipmentSlot.class);
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                worn.put(slot, slotObservation.capture(-1 - slot.ordinal(), -1, body.mob().getItemBySlot(slot), 1));
            }
            if (!SlotObservation.sameSlots(slots, reading) || !SlotObservation.sameEquipment(equipment, worn)) {
                contents++;
                InventoryIndex.this.contents++;
            }
            if (slots.size() != reading.size() || incarnation == 0) {
                incarnation = ++InventoryIndex.this.incarnation;
                topology++;
                InventoryIndex.this.topology++;
            }
            if (!SlotObservation.sameIdentities(slots, reading)
                    || !SlotObservation.sameIdentities(List.copyOf(equipment.values()), List.copyOf(worn.values()))) {
                topology++;
                InventoryIndex.this.topology++;
            }
            slots = List.copyOf(reading);
            equipment = Map.copyOf(worn);
            at = WorldSpaces.at(body.mob());
            reading = null;
            state(State.OBSERVED);
            return true;
        }
    }
}
