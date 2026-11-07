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
    private final Map<UUID, ResidentEntry> residents = new LinkedHashMap<>();
    private final Deque<Entry> due = new ArrayDeque<>();
    private final Map<ProviderKey, Set<WorldPos>> endpoints = new LinkedHashMap<>();
    private boolean notifying;
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
            if (entry.dimension.equals(dimension) && entry.state == State.UNOBSERVED && immediate-- > 0) {
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
                entry.slots = entry.slots.stream().map(slot -> new SlotSnapshot(slot.physicalSlot(),
                    Stores.accessibleSlot(next, entry.slots.size(), slot.physicalSlot()),
                    slot.stackCopy(), slot.stackLimit())).toList();
                entry.policy++;
                policy++;
                entry.generation++;
                entry.reading = null;
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
                entry.block));
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
                    barred.add(workshops.next().site().where());
                } else if (storesToAdd.hasNext()) {
                    WorldPos at = storesToAdd.next();
                    offered.add(at);
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
        BlockChanges.Heard ground;

        StoreEntry(Stash where, ResourceKey<Level> dimension) {
            this.where = where;
            this.dimension = dimension;
            aliases.add(where.pos());
        }

        void close() {
            if (ground != null) {
                ground.close();
                ground = null;
            }
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
            boolean next = provider == null && aliases.stream().anyMatch(alias ->
                members.values().stream().anyMatch(offered -> offered.contains(alias)))
                && aliases.stream().noneMatch(excluded.getOrDefault(dimension, Set.of())::contains);
            if (labor != next) {
                labor = next;
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
            BlockPos anchor = Containers.anchor(level, block.cell());
            Stash canonical = Stash.at(WorldPos.of(level, anchor));
            if (!canonical.equals(where)) {
                StoreEntry had = stores.get(canonical);
                stores.remove(where);
                close();
                if (had != null) {
                    had.aliases.addAll(aliases);
                    if (provider != null) {
                        had.provider = provider;
                        had.endpoint = endpoint;
                    }
                    aliases.forEach(alias -> InventoryIndex.this.aliases.put(alias, canonical));
                    dirty(had);
                    if (notifying) {
                        InventoryIndex.this.capture(server, had);
                    }
                    had.topology++;
                    InventoryIndex.this.topology++;
                    return true;
                }
                where = canonical;
                stores.put(where, this);
            }
            if (!level.isLoaded(anchor)) {
                other = block.cell();
                unloaded();
                return true;
            }
            BlockState stateAt = level.getBlockState(anchor);
            other = stateAt.getBlock() instanceof ChestBlock
                && stateAt.getValue(ChestBlock.TYPE) != ChestType.SINGLE
                ? anchor.relative(ChestBlock.getConnectedDirection(stateAt)) : null;
            Set<WorldPos> oldAliases = Set.copyOf(aliases);
            aliases.clear();
            aliases.add(where.pos());
            if (other != null) {
                aliases.add(WorldPos.of(level, other));
            }
            for (WorldPos alias : oldAliases) {
                if (!aliases.contains(alias)) {
                    InventoryIndex.this.aliases.remove(alias, where);
                    if (members.values().stream().anyMatch(offered -> offered.contains(alias))
                        || endpoints.values().stream().anyMatch(observed -> observed.contains(alias))) {
                        Stash split = Stash.at(alias);
                        StoreEntry entry = stores.computeIfAbsent(split, key -> new StoreEntry(key, dimension));
                        enqueue(entry);
                    }
                }
            }
            aliases.forEach(alias -> InventoryIndex.this.aliases.put(alias, where));
            if (!aliases.equals(oldAliases)) {
                topology++;
                InventoryIndex.this.topology++;
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

            if (ground == null) {
                int span = (int) Math.ceil(Reach.PLAYER_BLOCK_REACH);
                BoundingBox box = BoundingBox.fromCorners(anchor.offset(-span, -span - 1, -span),
                    anchor.offset(span, span + 2, span));
                ground = BlockChanges.listen(level, box, cell -> dirtyApproach());
            }
            Container container = provider == null ? Containers.at(level, anchor).orElse(null)
                : provider.open(level, new InventoryProviders.Endpoint(endpoint.inventory(), where.pos()));
            if (container == null) {
                observeApproach(level);
                state(State.UNSUPPORTED);
                reading = null;
                return true;
            }
            Object current = level.getBlockEntity(anchor);
            Object second = other == null ? null : level.getBlockEntity(other);
            if (identity != current || otherIdentity != second || size != container.getContainerSize()
                || kind != container.getClass()
                || !Objects.equals(inventory, endpoint == null ? null : endpoint.inventory())) {
                identity = current;
                otherIdentity = second;
                size = container.getContainerSize();
                kind = container.getClass();
                inventory = endpoint == null ? null : endpoint.inventory();
                incarnation = ++InventoryIndex.this.incarnation;
                topology++;
                InventoryIndex.this.topology++;
                reading = null;
                refused = Set.of();
                sampled = false;
                approach = new Approach(approach.revision(), State.DIRTY, approach.stands());
            }
            if (notifying && container.getContainerSize() > SLOTS_PER_STEP) {
                return false;
            }
            if (reading == null || reading.generation != generation) {
                Set<Item> cached = provider != null ? Set.of() : sampled || notifying ? refused : null;
                reading = new Reading(this, generation, Stores.rulesAt(aliases), container, cached);
            }
            if (!reading.step(container)) {
                return false;
            }
            if (!SlotObservation.sameSlots(slots, reading.slots)) {
                contents++;
                InventoryIndex.this.contents++;
            }
            if (!rules.equals(reading.rules) || !refused.equals(reading.refused())) {
                policy++;
                InventoryIndex.this.policy++;
            }
            if (!SlotObservation.sameIdentities(slots, reading.slots)) {
                topology++;
                InventoryIndex.this.topology++;
            }
            var observedBlock = Optional.of(BuiltInRegistries.BLOCK.getKey(stateAt.getBlock()));
            if (!this.block.equals(observedBlock)) {
                this.block = observedBlock;
                topology++;
                InventoryIndex.this.topology++;
            }
            slots = List.copyOf(reading.slots);
            rules = reading.rules;
            refused = reading.refused();
            sampled = sampled || !notifying;
            observeApproach(level);
            if (!sampled) {
                reading = new Reading(this, generation, rules, container, provider != null ? Set.of() : null);
                reading.slots.addAll(slots);
            } else {
                reading = null;
            }
            state(State.OBSERVED);
            return true;
        }

        private void observeApproach(ServerLevel level) {
            if (approach.state() != State.OBSERVED) {
                Set<Stand> stands = StandGeometry.footingsOf(level, where.pos());
                if (!approach.stands().equals(stands) || approaches == 0) {
                    approaches++;
                    InventoryIndex.this.approaches++;
                }
                approach = new Approach(approaches, State.OBSERVED, stands);
            }
        }
    }

    private static final class Reading {

        final Entry entry;
        final long generation;
        final List<StoreRule> rules;
        final List<SlotSnapshot> slots = new ArrayList<>();
        final Containers.Refusals refusals;
        final Set<Item> cached;

        Reading(Entry entry, long generation, List<StoreRule> rules, Container container, Set<Item> cached) {
            this.entry = entry;
            this.generation = generation;
            this.rules = rules;
            refusals = new Containers.Refusals(container);
            this.cached = cached;
        }

        Set<Item> refused() {
            return cached == null ? refusals.items() : cached;
        }

        boolean step(Container container) {
            int limit = SLOTS_PER_STEP;
            int size = container.getContainerSize();
            while (slots.size() < size && limit-- > 0) {
                int slot = slots.size();
                slots.add(entry.slotObservation.capture(slot, Stores.accessibleSlot(rules, size, slot),
                    container.getItem(slot), container.getMaxStackSize()));
            }
            return slots.size() == size && (cached != null || refusals.step(container, SLOTS_PER_STEP));
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
                reading.add(slotObservation.capture(slot, slot, pack.getItem(slot), pack.getMaxStackSize()));
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
