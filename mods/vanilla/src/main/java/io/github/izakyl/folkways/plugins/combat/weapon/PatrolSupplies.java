package io.github.izakyl.folkways.plugins.combat.weapon;

import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.inventory.InventorySnapshot;
import io.github.izakyl.folkways.core.api.inventory.SlotSnapshot;
import io.github.izakyl.folkways.core.api.resident.body.Body;
import io.github.izakyl.folkways.core.api.resident.body.Equipment;
import io.github.izakyl.folkways.core.api.terms.Containers;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.Stash;
import io.github.izakyl.folkways.core.api.terms.StoreRule;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
import io.github.izakyl.folkways.core.api.work.Amount;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.Urge;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.front.api.Fronts;
import io.github.izakyl.folkways.plugins.combat.DefenseControl;
import io.github.izakyl.folkways.plugins.patrol.PatrolContent;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/** Finite personal stock, collected locally through the resident's existing work runner. */
public final class PatrolSupplies {
    static final int RETRY_TICKS = 100;
    static final int TRIP_TICKS = 200;
    private static final int STORE_RANGE = 32;

    public enum State {
        INACTIVE,
        READY,
        FETCHING,
        NO_STOCK,
        NO_ROOM,
        FAILED,
        CANCELLED,
        TIMED_OUT,
        UNAVAILABLE,
        BLOCKED
    }

    public record Status(State state, String reason, long missing, Optional<WorldPos> source, long retryAt) {
    }

    record Demand(
        ItemSpec goods,
        int target,
        boolean weapon,
        Optional<UUID> entry,
        Optional<LoadoutProfile.Entry> equipment) {
        Demand(ItemSpec goods, int target, boolean weapon) {
            this(goods, target, weapon, Optional.empty(), Optional.empty());
        }

        boolean matches(ItemStack stack) {
            return equipment.map(value -> value.matches(stack)).orElseGet(() -> Goods.matches(stack, goods));
        }
    }

    record Authority(ResidentEntity body, DefenseControl.Order order, long duty, long until, long revision) {
    }

    private final Colony colony;
    private final LoadoutProfiles profiles;
    private final Map<UUID, Set<UUID>> refilling = new HashMap<>();
    private final Map<UUID, Long> revisions = new HashMap<>();
    private final Map<UUID, Long> duties = new HashMap<>();
    private final Map<UUID, Status> statuses = new HashMap<>();
    private final Map<UUID, PatrolSupplyNode> pending = new HashMap<>();
    private final Set<UUID> awaitingWeapon = new HashSet<>();

    public PatrolSupplies(Colony colony) {
        this.colony = colony;
        profiles = new LoadoutProfiles(colony, this::invalidate);
    }

    public static PatrolSupplies of(Colony colony) {
        return PatrolContent.suppliesOf(colony).orElseThrow();
    }

    public LoadoutProfiles profiles() {
        return profiles;
    }

    public void clear(Body body) {
        validate(body);
        profiles.assign(List.of(body.id()), Optional.empty());
    }

    public boolean mayEngage(UUID resident) {
        return profiles.assigned(resident).isPresent()
            || Fronts.of(colony).settings(PatrolContent.ID).choice("no_profile").getPath().equals("engage");
    }

    void weaponFailed(UUID resident) {
        if (profiles.assigned(resident).isPresent()) {
            awaitingWeapon.add(resident);
        }
    }

    boolean awaitingWeapon(UUID resident) {
        return awaitingWeapon.contains(resident);
    }

    public Status status(UUID resident) {
        return statuses.getOrDefault(resident, new Status(State.INACTIVE, "inactive", 0, Optional.empty(), 0));
    }

    private void validate(Body body) {
        if (!(body.mob().level() instanceof ServerLevel level)
            || !level.getServer().isSameThread()
            || !(body.mob() instanceof ResidentEntity)
            || !body.mob().isAlive()
            || body.mob().isRemoved()
            || body.colonyId().filter(colony.id()::equals).isEmpty()
            || colony.holdingOf(body.id()).isEmpty()) {
            throw new IllegalArgumentException(
                "patrol loadouts require a loaded person held by this colony on its server"
                + " thread");
        }
    }

    private void invalidate(UUID resident) {
        revisions.merge(resident, 1L, Long::sum);
        pending.remove(resident);
        refilling.remove(resident);
        awaitingWeapon.remove(resident);
        statuses.put(resident, new Status(State.CANCELLED, "profile"
            + " changed", 0, Optional.empty(), 0));
        var server = ServerLifecycleHooks.getCurrentServer();
        for (ServerLevel level : server.getAllLevels()) {
            if (level.getEntity(resident) instanceof ResidentEntity body) {
                body.stopWeaponSession();
            }
        }
    }

    public void tick(MinecraftServer server) {
        Set<UUID> roster = new HashSet<>();
        colony.residents().forEach(resident -> roster.add(resident.id()));
        profiles.retain(roster);
        refilling.keySet().retainAll(roster);
        awaitingWeapon.retainAll(roster);
        for (var entry : List.copyOf(pending.entrySet())) {
            Authority authority = entry.getValue().authority();
            if (!valid(authority)
                || !(authority.body().level() instanceof ServerLevel level)
                || level.getEntity(entry.getKey()) != authority.body()) {
                pending.remove(entry.getKey());
                report(entry.getKey(), State.CANCELLED, "resident or order left", 0, null, 0);
            }
        }
        // Pending cancellation can report one final status. Remove departed residents afterwards,
        // while retaining unloaded residents whose holdings remain in the colony's roster.
        statuses.keySet().retainAll(roster);
        revisions.keySet().retainAll(roster);
        duties.keySet().retainAll(roster);
    }

    public void close() {
        pending.clear();
        statuses.clear();
        duties.clear();
        refilling.clear();
        awaitingWeapon.clear();
    }

    public List<Amount> keeps(Body body) {
        if (!(body.mob() instanceof ResidentEntity)) {
            return List.of();
        }
        InventorySnapshot.Resident resident = colony.inventory().snapshot().residents().get(body.id());
        if (resident == null || resident.state() != InventorySnapshot.State.OBSERVED) {
            colony.inventory().ensureObservedResident(body.id());
            return profiles.assigned(body.id()).stream().flatMap(profile -> profile.entries().stream())
                .map(entry -> new Amount(entry.items(), entry.target())).toList();
        }
        List<Amount> kept = new ArrayList<>();
        List<ItemStack> available = new ArrayList<>(resident.pack().stream().map(SlotSnapshot::stackCopy).toList());
        profiles.assigned(body.id())
            .ifPresent(
            profile -> {
                for (LoadoutProfile.Entry entry : profile.entries()) {
                    int left = entry.target();
                    for (ItemStack stack : available) {
                        if (left <= 0 || !entry.matches(stack)) {
                            continue;
                        }
                        int count = Math.min(left, stack.getCount());
                        kept.add(new Amount(ItemSpec.exact(stack), count));
                        left -= count;
                        stack.shrink(count);
                    }
                }
            });
        return List.copyOf(kept);
    }

    public List<Equipment.Demand> armor(Body body) {
        return profiles.assigned(body.id()).stream()
            .flatMap(profile -> profile.entries().stream())
            .filter(entry -> entry.kind() == LoadoutProfile.Kind.ARMOR)
            .map(entry -> new Equipment.Demand(entry.slot().orElseThrow(), entry.items(), false))
            .toList();
    }

    public List<Urge> urges(Worker who, Optional<? extends DefenseControl.Order> order, long duty, long until) {
        if (!(who.body() instanceof ResidentEntity body) || !(body.level() instanceof ServerLevel level)) {
            return List.of();
        }
        if (profiles.assigned(body.id()).isEmpty()) {
            return List.of();
        }
        DefenseControl control = DefenseControl.of(colony);
        if (order.isEmpty()
            && duty == Long.MIN_VALUE
            && !control.failedWaiting(body.id())
            && !control.reserved(body.id())) {
            duty = 0;
            until = Long.MAX_VALUE;
        }
        boolean authorized = order.map(DefenseControl.Order::guarding).orElse(level.getGameTime() <= until);
        if (!authorized) {
            duties.remove(body.id());
            if (pending.remove(body.id()) != null) {
                report(body.id(), State.CANCELLED, "order changed", 0, null, 0);
            }
            return List.of();
        }
        if (order.isEmpty()) {
            duties.put(body.id(), duty);
        } else {
            duties.remove(body.id());
        }
        Authority authority =
            new Authority(body, order.orElse(null), duty, until, revisions.getOrDefault(body.id(), 0L));
        PatrolSupplyNode current = pending.get(body.id());
        if (current != null && (!current.authority().equals(authority) || !valid(current.authority()))) {
            pending.remove(body.id());
            current = null;
        }
        if (current != null && level.getGameTime() >= current.deadline()) {
            pending.remove(body.id());
            report(body.id(), State.TIMED_OUT, "supply trip timed out", 0, current.source(),
                level.getGameTime() + RETRY_TICKS);
            return List.of();
        }
        InventorySnapshot snapshot = colony.inventory().snapshot();
        InventorySnapshot.Resident observed = snapshot.residents().get(body.id());
        if (observed == null || observed.state() != InventorySnapshot.State.OBSERVED) {
            colony.inventory().ensureObservedResident(body.id());
            report(body.id(), State.UNAVAILABLE, "inventory observation pending", 0, null, 0);
            return List.of();
        }
        LoadoutProfile profile = profiles.assigned(body.id()).orElseThrow();
        Map<UUID, Integer> deficits = observedDeficits(body, profile, observed);
        if (current != null) {
            if (deficits.getOrDefault(current.demand().entry().orElse(null), 0) > 0) {
                return List.of(current.urge());
            }
            pending.remove(body.id());
        }
        for (LoadoutProfile.Entry entry : profile.entries()) {
            if (!entry.available()) {
                report(body.id(), State.UNAVAILABLE, "entry has no usable equipment for its kind or"
                    + " slot", 1, null, 0);
                return List.of();
            }
            if (entry.kind() == LoadoutProfile.Kind.ARMOR
                && observed.equipment().containsKey(entry.slot().orElseThrow())
                && !observed.equipment().get(entry.slot().orElseThrow()).stackCopy().isEmpty()
                && !Goods.matches(observed.equipment().get(entry.slot().orElseThrow()).stackCopy(), entry.items())) {
                report(body.id(), State.BLOCKED, "armor slot occupied", 1, null, 0);
                return List.of();
            }
        }
        Demand demand = wanted(body, deficits);
        if (demand == null) {
            awaitingWeapon.remove(body.id());
            report(body.id(), State.READY, "stock ready", 0, null, 0);
            return List.of();
        }
        if (level.getGameTime() < status(body.id()).retryAt()) {
            return List.of();
        }
        int missing = deficits.getOrDefault(demand.entry().orElse(null), 0);
        WorldPos noRoom = null;
        boolean uncertain = snapshot.coverage().membershipPending() || unresolvedReservations(level, snapshot);
        if (uncertain) {
            colony.inventory().ensureObserved();
            report(body.id(), State.UNAVAILABLE, "inventory observation pending", missing, null,
                level.getGameTime() + RETRY_TICKS);
            return List.of();
        }
        int nearby = 0;
        int readable = 0;
        int stocked = 0;
        for (WorldPos source :
            colony.storesIn(level.dimension()).stream()
            .filter(at -> at.sameRealm(WorldSpaces.at(body)))
            .filter(
            at ->
                WorldSpaces.world(level, at)
                    .filter(
                    point ->
                        point.distanceToSqr(body.position())
                            <= STORE_RANGE * STORE_RANGE)
                    .isPresent())
            .sorted(
            Comparator.comparingDouble(
            at ->
                WorldSpaces.world(level, at)
                    .orElseThrow()
                    .distanceToSqr(body.position())))
            .limit(32)
            .toList()) {
            nearby++;
            Optional<InventorySnapshot.Store> store = snapshot.store(source);
            if (store.isEmpty() || store.get().state() != InventorySnapshot.State.OBSERVED
                || store.get().approach().state() != InventorySnapshot.State.OBSERVED) {
                colony.inventory().ensureObserved(Stash.at(source));
                uncertain = true;
                continue;
            }
            if (!StoreRule.takes(store.get().rules())) {
                continue;
            }
            readable++;
            List<ItemStack> preview = take(observedUnreserved(level, snapshot, store.get()), demand, missing);
            if (preview.isEmpty()) {
                continue;
            }
            stocked++;
            if (!fits(observedPack(observed), preview)) {
                noRoom = source;
                continue;
            }
            BlockPos target = source.block(level);
            // The runner may arrive within 0.9 blocks of a stance, rather than its exact centre.
            // Leave room for that tolerance; draw still checks the body's actual block reach and
            // sight.
            Optional<Stances> stances =
                Stances.of(
                store.get().approach().stands().stream()
                .map(stand -> stand.cell())
                .filter(feet -> feet.in(level) && feet.block(level).distSqr(target) <= 2 * 2)
                .toList());
            if (stances.isEmpty()) {
                continue;
            }
            int batch = preview.stream().mapToInt(ItemStack::getCount).sum();
            PatrolSupplyNode request =
                new PatrolSupplyNode(
                this, authority, source, demand, batch, stances.get(), level.getGameTime() + TRIP_TICKS,
                store.get().stash(), store.get().version());
            pending.put(body.id(), request);
            report(body.id(), State.FETCHING, "collecting stock", missing, source, 0);
            return List.of(request.urge());
        }
        if (uncertain) {
            report(body.id(), State.UNAVAILABLE, "inventory observation pending", missing, null,
                level.getGameTime() + RETRY_TICKS);
            return List.of();
        }
        report(
            body.id(),
            noRoom == null ? State.NO_STOCK : State.NO_ROOM,
            noRoom == null
            ? "no unclaimed stock within 32 blocks (registered="
            + colony.storesIn(level.dimension()).size()
            + ", nearby="
            + nearby
            + ", readable="
            + readable
            + ", stocked="
            + stocked
            + ")"
            : "pack full",
            missing,
            noRoom,
            level.getGameTime() + RETRY_TICKS);
        return List.of();
    }

    void staleStore(Stash store, InventorySnapshot.Version version) {
        colony.inventoryFeedback().stale(store, version);
    }

    void staleApproach(Stash store, InventorySnapshot.Version version) {
        colony.inventoryFeedback().staleApproach(store, version);
    }

    boolean valid(Authority authority) {
        ResidentEntity body = authority.body();
        if (!(body.level() instanceof ServerLevel level)
            || !body.isAlive()
            || body.isRemoved()
            || body.colonyId().filter(colony.id()::equals).isEmpty()
            || colony.holdingOf(body.id()).isEmpty()
            || revisions.getOrDefault(body.id(), 0L) != authority.revision()) {
            return false;
        }
        var current = DefenseControl.of(colony).current(body.id());
        return authority.order() != null
            ? current.orElse(null) == authority.order() && authority.order().guarding()
            : current.isEmpty()
            && !DefenseControl.of(colony).failedWaiting(body.id())
            && (authority.duty() != 0 || !DefenseControl.of(colony).reserved(body.id()))
            && duties.getOrDefault(body.id(), Long.MIN_VALUE) == authority.duty()
            && level.getGameTime() <= authority.until();
    }

    boolean active(PatrolSupplyNode request) {
        return pending.get(request.authority().body().id()) == request && valid(request.authority());
    }

    Optional<Container> source(ServerLevel level, WorldPos source) {
        return source.in(level) && level.isLoaded(source.block(level)) && StoreRule.takes(colony.storeRules(source))
            ? colony.store(level, source)
            : Optional.empty();
    }

    boolean unresolvedReservations(ServerLevel level, InventorySnapshot snapshot) {
        return colony.reservedInputs(level).stream().flatMap(claim -> claim.store().stream())
            .anyMatch(at -> snapshot.store(at).isEmpty());
    }

    SimpleContainer observedUnreserved(ServerLevel level, InventorySnapshot snapshot, InventorySnapshot.Store store) {
        List<SlotSnapshot> slots = store.slots().stream().filter(slot -> slot.accessibleSlot().isPresent()).toList();
        SimpleContainer free = new SimpleContainer(slots.size());
        for (int slot = 0; slot < slots.size(); slot++) {
            free.setItem(slot, slots.get(slot).stackCopy());
        }
        for (var claim : colony.reservedInputs(level)) {
            if (claim.store().flatMap(snapshot::store)
                .filter(found -> found.stash().equals(store.stash())).isPresent()) {
                take(free, claim.goods(), (int) Math.min(Integer.MAX_VALUE, claim.count()), false);
            }
        }
        return free;
    }

    List<ItemStack> preview(ServerLevel level, WorldPos source, Container actual, Demand demand, int count) {
        return take(unreserved(level, source, actual), demand, count);
    }

    SimpleContainer unreserved(ServerLevel level, WorldPos source, Container actual) {
        SimpleContainer free = copy(actual);
        WorldPos canonical = WorldPos.of(level, Containers.anchor(level, source.block(level)));
        for (var claim : colony.reservedInputs(level)) {
            if (claim.store()
                .filter(
                at ->
                    at.equals(source)
                        || at.equals(canonical)
                        || at.in(level)
                        && level.isLoaded(at.block(level))
                        && WorldPos.of(level, Containers.anchor(level, at.block(level)))
                        .equals(canonical))
                .isPresent()) {
                take(free, claim.goods(), (int) Math.min(Integer.MAX_VALUE, claim.count()), false);
            }
        }
        return free;
    }

    private List<ItemStack> take(Container source, ItemSpec goods, int count, boolean weapon) {
        return take(source, new Demand(goods, count, weapon), count);
    }

    private List<ItemStack> take(Container source, Demand demand, int count) {
        List<ItemStack> taken = new ArrayList<>();
        int left = count;
        while (left > 0) {
            ItemStack stack = Containers.extract(source, demand::matches, left);
            if (stack.isEmpty()) {
                break;
            }
            taken.add(stack);
            left -= stack.getCount();
        }
        return List.copyOf(taken);
    }

    static boolean fits(Container pack, List<ItemStack> goods) {
        SimpleContainer copy = copy(pack);
        return goods.stream().allMatch(stack -> Containers.insert(copy, stack).isEmpty());
    }

    private static SimpleContainer copy(Container source) {
        SimpleContainer copy = new SimpleContainer(source.getContainerSize());
        for (int slot = 0; slot < source.getContainerSize(); slot++) {
            copy.setItem(slot, source.getItem(slot).copy());
        }
        return copy;
    }

    private Demand wanted(ResidentEntity body, Map<UUID, Integer> deficits) {
        Optional<LoadoutProfile> profile = profiles.assigned(body.id());
        if (profile.isEmpty()) {
            return null;
        }
        Set<UUID> active = refilling.computeIfAbsent(body.id(), ignored -> new HashSet<>());
        for (LoadoutProfile.Entry entry : profile.get().entries()) {
            Demand demand =
                new Demand(
                entry.items(),
                entry.target(),
                entry.kind() == LoadoutProfile.Kind.WEAPON,
                Optional.of(entry.id()),
                Optional.of(entry));
            int missing = deficits.getOrDefault(entry.id(), 0);
            if (missing == 0) {
                active.remove(entry.id());
                continue;
            }
            if (entry.target() - missing < entry.threshold()) {
                active.add(entry.id());
            }
            if (active.contains(entry.id())) {
                return demand;
            }
        }
        return null;
    }

    private static SimpleContainer observedPack(InventorySnapshot.Resident resident) {
        SimpleContainer pack = new SimpleContainer(resident.pack().size());
        for (SlotSnapshot slot : resident.pack()) {
            pack.setItem(slot.physicalSlot(), slot.stackCopy());
        }
        return pack;
    }

    Map<UUID, Integer> observedDeficits(ResidentEntity body, LoadoutProfile profile,
                                       InventorySnapshot.Resident resident) {
        List<ItemStack> carried = new ArrayList<>(resident.pack().stream().map(SlotSnapshot::stackCopy).toList());
        if (body.hasWeaponSession()) {
            carried.add(body.getMainHandItem().copy());
        }
        return allocate(profile, carried, slot -> resident.equipment().containsKey(slot)
            ? resident.equipment().get(slot).stackCopy() : ItemStack.EMPTY);
    }

    /** Allocate each physical unit once, in profile order, including matching loaded ammunition. */
    public Map<UUID, Integer> deficits(ResidentEntity body, LoadoutProfile profile) {
        return allocate(profile, LoadoutRegistry.carried(body), body::getItemBySlot);
    }

    static Map<UUID, Integer> allocate(LoadoutProfile profile, List<ItemStack> carried,
                                        Function<EquipmentSlot, ItemStack> equipment) {
        List<ItemStack> available = new ArrayList<>(carried.stream().map(ItemStack::copy).toList());
        List<ItemStack> loaded = new ArrayList<>();
        for (ItemStack weapon : carried) {
            LoadoutRegistry.using(weapon)
                .ifPresent(
                provider -> provider.loadedAmmunition(weapon).forEach(stack -> loaded.add(stack.copy())));
        }
        Map<UUID, Integer> missing = new LinkedHashMap<>();
        for (LoadoutProfile.Entry entry : profile.entries()) {
            int left = entry.target();
            if (entry.kind() == LoadoutProfile.Kind.ARMOR) {
                ItemStack worn = equipment.apply(entry.slot().orElseThrow());
                if (entry.matches(worn)) {
                    left--;
                }
            }
            for (ItemStack stack : available) {
                if (left <= 0) {
                    break;
                }
                if (entry.matches(stack)) {
                    int count = Math.min(left, stack.getCount());
                    left -= count;
                    stack.shrink(count);
                }
            }
            if (entry.kind() == LoadoutProfile.Kind.SUPPLY) {
                for (ItemStack stack : loaded) {
                    if (left <= 0) {
                        break;
                    }
                    if (entry.matches(stack)) {
                        int count = Math.min(left, stack.getCount());
                        left -= count;
                        stack.shrink(count);
                    }
                }
            }
            missing.put(entry.id(), Math.max(0, left));
        }
        return Collections.unmodifiableMap(missing);
    }

    int missing(ResidentEntity body, Demand demand) {
        if (demand.entry().isPresent()) {
            return profiles.assigned(body.id())
                .map(profile -> deficits(body, profile).getOrDefault(demand.entry().get(), 0))
                .orElse(0);
        }
        long carried =
            LoadoutRegistry.carried(body).stream().filter(demand::matches).mapToLong(ItemStack::getCount).sum();
        return (int) Math.max(0, demand.target() - carried);
    }

    public Component detail(ResidentEntity body) {
        return profiles.assigned(body.id())
            .map(profile -> Component.literal(profile.name()))
            .orElseGet(() -> Component.translatable("folkways.loadouts.none"));
    }

    void finished(PatrolSupplyNode request, Ending how) {
        UUID resident = request.authority().body().id();
        if (!pending.remove(resident, request)) {
            return;
        }
        long now = request.authority().body().level().getGameTime();
        if (how instanceof Ending.Done) {
            report(resident, State.READY, "stock collected", 0, request.source(), 0);
        } else if (how instanceof Ending.Failed failed) {
            State state = request.failure() == null ? State.FAILED : request.failure();
            report(
                resident,
                state,
                request.failureReason().orElse(failed.why().translationKey()),
                missing(request.authority().body(), request.demand()),
                request.source(),
                now + RETRY_TICKS);
        } else {
            report(resident, State.CANCELLED, "supply interrupted", 0, request.source(), now + 20);
        }
    }

    private void report(UUID resident, State state, String reason, long missing, WorldPos source, long retry) {
        statuses.put(resident, new Status(state, reason, missing, Optional.ofNullable(source), retry));
    }
}
