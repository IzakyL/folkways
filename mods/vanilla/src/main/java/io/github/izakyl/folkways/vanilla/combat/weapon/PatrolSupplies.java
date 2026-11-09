package io.github.izakyl.folkways.vanilla.combat.weapon;

import io.github.izakyl.folkways.core.colony.api.Closing;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.colony.api.ColonyView;
import io.github.izakyl.folkways.core.colony.api.Equipment;
import io.github.izakyl.folkways.core.inventory.api.InventorySnapshot;
import io.github.izakyl.folkways.core.inventory.api.SlotSnapshot;
import io.github.izakyl.folkways.core.item.api.Containers;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.plan.api.Delivery;
import io.github.izakyl.folkways.core.plan.api.Grown;
import io.github.izakyl.folkways.core.resident.api.Body;
import io.github.izakyl.folkways.core.work.api.Amount;
import io.github.izakyl.folkways.core.work.api.Need;
import io.github.izakyl.folkways.core.work.api.NodeSpec;
import io.github.izakyl.folkways.core.work.api.Stances;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.api.Workload;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import io.github.izakyl.folkways.core.front.api.Fronts;
import io.github.izakyl.folkways.core.combat.api.DefenseControl;
import io.github.izakyl.folkways.vanilla.patrol.PatrolContent;
import io.github.izakyl.folkways.vanilla.person.ResidentEntity;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * Finite personal stock. Each loadout entry a resident is short of, while it may resupply, is asked of the colony's
 * planner as a delivery into that resident's pack; the planner finds the goods and the way they come.
 */
public final class PatrolSupplies {
    private static final ResourceLocation RESUPPLYING =
        ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "patrol_resupplying");

    public enum State {
        INACTIVE,
        READY,
        FETCHING,
        CANCELLED,
        UNAVAILABLE,
        BLOCKED
    }

    public record Status(State state, String reason, long missing) {
    }

    record Authority(ResidentEntity body, DefenseControl.Order order, long duty, long until, long revision) {
    }

    // One entry asked of the planner: its goal is stated unchanged until it ends, and asked afresh after if still short.
    private record Ask(long count, Grown goal) {
    }

    private record Asked(Authority authority, ResourceKey<Level> dimension, Map<UUID, Ask> asks) {
    }

    private final Colony colony;
    private final LoadoutProfiles profiles;
    private final ResidentMounts mounts;
    private final Runnable changed;
    private final Map<UUID, Set<UUID>> refilling = new HashMap<>();
    private final Map<UUID, Long> revisions = new HashMap<>();
    private final Map<UUID, Long> duties = new HashMap<>();
    private final Map<UUID, Status> statuses = new HashMap<>();
    private final Map<UUID, Asked> asked = new HashMap<>();
    private final Set<UUID> underway = new HashSet<>();
    private final Set<UUID> awaitingWeapon = new HashSet<>();

    public PatrolSupplies(Colony colony) {
        this(colony, () -> { });
    }

    // `changed` hears that the deliveries asked for differ, so they are offered to the planner again.
    public PatrolSupplies(Colony colony, Runnable changed) {
        this.colony = colony;
        this.changed = changed;
        mounts = new ResidentMounts(colony);
        profiles = new LoadoutProfiles(colony, this::invalidate);
    }

    public static PatrolSupplies of(Colony colony) {
        return PatrolContent.suppliesOf(colony).orElseThrow();
    }

    public ResidentMounts mounts() {
        return mounts;
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
        return statuses.getOrDefault(resident, new Status(State.INACTIVE, "inactive", 0));
    }

    // Whether the resident's own route, when last seen, went to fetch one of its deliveries: it is away for them.
    public boolean underway(UUID resident) {
        return underway.contains(resident);
    }

    // The deliveries asked for residents in this dimension, to be offered to the planner.
    public List<Grown> goals(ColonyView view) {
        List<Grown> goals = new ArrayList<>();
        for (Asked each : asked.values()) {
            if (each.dimension().equals(view.dimension())) {
                each.asks().values().forEach(ask -> goals.add(ask.goal()));
            }
        }
        return List.copyOf(goals);
    }

    // A delivery ended, delivered or not: it is stated again, for what is then missing, when next read.
    public void ended(UUID node) {
        asked.forEach((resident, each) -> {
            if (each.asks().values().removeIf(ask -> ask.goal().completions().contains(node))) {
                underway.remove(resident);
            }
        });
    }

    private void validate(Body body) {
        if (!(body.mob().level() instanceof ServerLevel level)
            || !level.getServer().isSameThread()
            || !(body.mob() instanceof ResidentEntity)
            || !body.mob().isAlive()
            || body.mob().isRemoved()
            || body.colonyId().filter(colony.id()::equals).isEmpty()
            || colony.claimOf(body.id()).isEmpty()) {
            throw new IllegalArgumentException(
                "patrol loadouts require a loaded person held by this colony on its server"
                + " thread");
        }
    }

    private void invalidate(UUID resident) {
        revisions.merge(resident, 1L, Long::sum);
        withdraw(resident);
        refilling.remove(resident);
        awaitingWeapon.remove(resident);
        statuses.put(resident, new Status(State.CANCELLED, "profile changed", 0));
        var server = ServerLifecycleHooks.getCurrentServer();
        for (ServerLevel level : server.getAllLevels()) {
            if (level.getEntity(resident) instanceof ResidentEntity body) {
                body.stopWeaponSession();
                if (profiles != null) {
                    mounts.reconcile(level, body, profiles.assigned(resident));
                }
            }
        }
    }

    // Whatever was asked for the resident is no longer wanted; goods already on their way are the planner's to undo.
    private boolean withdraw(UUID resident) {
        underway.remove(resident);
        if (asked.remove(resident) == null) {
            return false;
        }
        changed.run();
        return true;
    }

    public void tick(MinecraftServer server) {
        mounts.tick(server, profiles);
        Set<UUID> roster = new HashSet<>();
        colony.residents().forEach(resident -> roster.add(resident.id()));
        profiles.retain(roster);
        refilling.keySet().retainAll(roster);
        awaitingWeapon.retainAll(roster);
        for (var entry : List.copyOf(asked.entrySet())) {
            Authority authority = entry.getValue().authority();
            if (!valid(authority)
                || !(authority.body().level() instanceof ServerLevel level)
                || level.getEntity(entry.getKey()) != authority.body()) {
                withdraw(entry.getKey());
                report(entry.getKey(), State.CANCELLED, "resident or order left", 0);
            }
        }
        // Withdrawing can report one final status. Remove departed residents afterwards,
        // while retaining unloaded residents whose holdings remain in the colony's roster.
        statuses.keySet().retainAll(roster);
        revisions.keySet().retainAll(roster);
        duties.keySet().retainAll(roster);
    }

    public void close(Closing why) {
        mounts.close(why);
        close();
    }

    public void close() {
        asked.clear();
        underway.clear();
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

    // Reads what the resident is short of while it may resupply, and asks the planner for it; outside its duty or
    // order everything asked is withdrawn.
    public void resupply(Worker who, Optional<? extends DefenseControl.Order> order, long duty, long until) {
        if (!(who.body() instanceof ResidentEntity body) || !(body.level() instanceof ServerLevel level)) {
            return;
        }
        mounts.reconcile(level, body, profiles.assigned(body.id()));
        if (profiles.assigned(body.id()).isEmpty()) {
            withdraw(body.id());
            return;
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
            if (withdraw(body.id())) {
                report(body.id(), State.CANCELLED, "order changed", 0);
            }
            return;
        }
        if (order.isEmpty()) {
            duties.put(body.id(), duty);
        } else {
            duties.remove(body.id());
        }
        Authority authority =
            new Authority(body, order.orElse(null), duty, until, revisions.getOrDefault(body.id(), 0L));
        Asked current = asked.get(body.id());
        if (current != null) {
            current = new Asked(authority, current.dimension(), current.asks());
            asked.put(body.id(), current);
            Set<UUID> mine = new HashSet<>();
            current.asks().values().forEach(ask -> mine.addAll(ask.goal().completions()));
            if (who.route().stream().anyMatch(node -> mine.contains(node.id()))) {
                underway.add(body.id());
            } else {
                underway.remove(body.id());
            }
        }
        InventorySnapshot.Resident observed = colony.inventory().snapshot().residents().get(body.id());
        if (observed == null || observed.state() != InventorySnapshot.State.OBSERVED) {
            colony.inventory().ensureObservedResident(body.id());
            if (current == null || current.asks().isEmpty()) {
                report(body.id(), State.UNAVAILABLE, "inventory observation pending", 0);
            }
            return;
        }
        LoadoutProfile profile = profiles.assigned(body.id()).orElseThrow();
        for (LoadoutProfile.Entry entry : profile.entries()) {
            if (!entry.available()) {
                withdraw(body.id());
                report(body.id(), State.UNAVAILABLE, "entry has no usable equipment for its kind or slot", 1);
                return;
            }
            if (entry.kind() == LoadoutProfile.Kind.HORSE_ARMOR
                    && mounts.horse(level, body.id()).filter(horse ->
                        !horse.getItemBySlot(EquipmentSlot.BODY).isEmpty()
                            && !entry.matches(horse.getItemBySlot(EquipmentSlot.BODY))).isPresent()) {
                withdraw(body.id());
                report(body.id(), State.BLOCKED, "horse armor slot occupied", 1);
                return;
            }
            if (entry.kind() == LoadoutProfile.Kind.ARMOR
                && observed.equipment().containsKey(entry.slot().orElseThrow())
                && !observed.equipment().get(entry.slot().orElseThrow()).stackCopy().isEmpty()
                && !Goods.matches(observed.equipment().get(entry.slot().orElseThrow()).stackCopy(), entry.items())) {
                withdraw(body.id());
                report(body.id(), State.BLOCKED, "armor slot occupied", 1);
                return;
            }
        }
        Map<UUID, Integer> wanted = wanted(body, profile, observedDeficits(body, profile, observed));
        if (wanted.isEmpty()) {
            withdraw(body.id());
            awaitingWeapon.remove(body.id());
            if (profile.entries().stream().anyMatch(entry -> entry.kind() == LoadoutProfile.Kind.MOUNT)
                    && !mounts.assigned(body.id())) {
                report(body.id(), State.UNAVAILABLE, "no free tame pasture horse", 1);
            } else {
                report(body.id(), State.READY, "stock ready", 0);
            }
            return;
        }
        // Rounds loaded in a weapon count toward its ammunition, so supplies wait until no weapon is still to come.
        if (profile.entries().stream().anyMatch(entry -> entry.kind() == LoadoutProfile.Kind.WEAPON
            && wanted.containsKey(entry.id()))) {
            profile.entries().stream().filter(entry -> entry.kind() == LoadoutProfile.Kind.SUPPLY)
                .forEach(entry -> wanted.remove(entry.id()));
        }
        Map<UUID, Ask> asks = current == null ? new LinkedHashMap<>() : current.asks();
        boolean differs = current == null || !asks.keySet().equals(wanted.keySet());
        asks.keySet().retainAll(wanted.keySet());
        WorkSite into = new WorkSite.AtEntity(body.id(), WorldSpaces.at(body));
        for (LoadoutProfile.Entry entry : profile.entries()) {
            Integer missing = wanted.get(entry.id());
            if (missing != null && !asks.containsKey(entry.id())) {
                UUID id = UUID.nameUUIDFromBytes(("folkways_vanilla:patrol_supply/" + body.id() + "/" + entry.id())
                    .getBytes(StandardCharsets.UTF_8));
                Delivery delivery = new Delivery(
                    NodeSpec.of(id, PatrolContent.ID, into, Stances.WHEREVER, Workload.Once.of(1))
                        .doing(RESUPPLYING, entry.items()).done(),
                    new Need(planned(entry, level), missing, true), count -> { });
                asks.put(entry.id(), new Ask(missing, Grown.of(delivery)));
            }
        }
        asked.put(body.id(), new Asked(authority, level.dimension(), asks));
        if (differs) {
            changed.run();
        }
        report(body.id(), State.FETCHING, underway(body.id()) ? "collecting stock" : "stock asked for",
            asks.values().stream().mapToLong(Ask::count).sum());
    }

    // The goods the planner looks for. An entry also asks its weapon to be one a provider fights with and its armor
    // to fit its slot, which goods alone cannot say: only the members that pass are named, or the entry's own goods
    // when every member does. What a member's components decide beyond that is left to the entry's own matching.
    static ItemSpec planned(LoadoutProfile.Entry entry) {
        List<ItemSpec> members = members(entry.items());
        List<ItemSpec> fit = members.stream().filter(member -> entry.matches(Goods.display(member))).toList();
        if (fit.isEmpty() || fit.size() == members.size()) {
            return entry.items();
        }
        return fit.size() == 1 ? fit.getFirst() : ItemSpec.anyOf(fit);
    }

    // Where the colony's stores hold goods of the entry it would refuse, such as a gun whose data no provider fights
    // with, the planner is told the exact stacks it would accept instead, as the stores showed them when asked.
    private ItemSpec planned(LoadoutProfile.Entry entry, ServerLevel level) {
        ItemSpec goods = planned(entry);
        List<ItemStack> seen = colony.inventory().snapshot().stores().values().stream()
            .filter(store -> store.dimension().equals(level.dimension()))
            .flatMap(store -> store.slots().stream()).map(SlotSnapshot::stackCopy)
            .filter(stack -> !stack.isEmpty() && Goods.matches(stack, goods)).toList();
        if (seen.stream().allMatch(entry::matches)) {
            return goods;
        }
        List<ItemSpec> usable = seen.stream().filter(entry::matches).map(Goods::specOf).distinct().toList();
        if (usable.isEmpty()) {
            return goods;
        }
        return usable.size() == 1 ? usable.getFirst() : ItemSpec.anyOf(usable);
    }

    private static List<ItemSpec> members(ItemSpec spec) {
        if (!spec.anyOf().isEmpty()) {
            return spec.anyOf().stream().flatMap(one -> members(one).stream()).toList();
        }
        return Goods.members(spec).stream()
            .map(item -> new ItemSpec(ItemSpec.of(item).item(), Optional.empty(), Set.of(), spec.components()))
            .toList();
    }

    boolean valid(Authority authority) {
        ResidentEntity body = authority.body();
        if (!(body.level() instanceof ServerLevel level)
            || !body.isAlive()
            || body.isRemoved()
            || body.colonyId().filter(colony.id()::equals).isEmpty()
            || colony.claimOf(body.id()).isEmpty()
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
                take(free, claim.goods(), (int) Math.min(Integer.MAX_VALUE, claim.count()));
            }
        }
        return free;
    }

    // Takes up to `count` of the goods out of the copy, as a claim on them would.
    private static void take(Container source, ItemSpec goods, int count) {
        int left = count;
        while (left > 0) {
            ItemStack stack = Containers.extract(source, item -> Goods.matches(item, goods), left);
            if (stack.isEmpty()) {
                return;
            }
            left -= stack.getCount();
        }
    }

    // Entries refilled now and how many each is short: an entry starts below its threshold and keeps on until full.
    private Map<UUID, Integer> wanted(ResidentEntity body, LoadoutProfile profile, Map<UUID, Integer> deficits) {
        Set<UUID> active = refilling.computeIfAbsent(body.id(), ignored -> new HashSet<>());
        Map<UUID, Integer> wanted = new LinkedHashMap<>();
        for (LoadoutProfile.Entry entry : profile.entries()) {
            int missing = deficits.getOrDefault(entry.id(), 0);
            if (missing == 0) {
                active.remove(entry.id());
                continue;
            }
            if (entry.target() - missing < entry.threshold()) {
                active.add(entry.id());
            }
            if (active.contains(entry.id())) {
                wanted.put(entry.id(), missing);
            }
        }
        return wanted;
    }

    Map<UUID, Integer> observedDeficits(ResidentEntity body, LoadoutProfile profile,
                                       InventorySnapshot.Resident resident) {
        List<ItemStack> carried = new ArrayList<>(resident.pack().stream().map(SlotSnapshot::stackCopy).toList());
        if (body.hasWeaponSession()) {
            carried.add(body.getMainHandItem().copy());
        }
        return allocate(profile, carried, slot -> resident.equipment().containsKey(slot)
            ? resident.equipment().get(slot).stackCopy() : ItemStack.EMPTY, kind -> tack(body, kind));
    }

    /** Allocate each physical unit once, in profile order, including matching loaded ammunition. */
    public Map<UUID, Integer> deficits(ResidentEntity body, LoadoutProfile profile) {
        List<ItemStack> carried = new ArrayList<>(LoadoutRegistry.carried(body));
        return allocate(profile, carried, body::getItemBySlot, kind -> tack(body, kind));
    }

    private static ItemStack tack(ResidentEntity body, LoadoutProfile.Kind kind) {
        return ResidentMounts.horse(body).map(horse -> kind == LoadoutProfile.Kind.MOUNT
            ? horse.getSlot(400).get() : horse.getItemBySlot(EquipmentSlot.BODY)).orElse(ItemStack.EMPTY);
    }

    static Map<UUID, Integer> allocate(LoadoutProfile profile, List<ItemStack> carried,
                                        Function<EquipmentSlot, ItemStack> equipment) {
        return allocate(profile, carried, equipment, kind -> ItemStack.EMPTY);
    }

    private static Map<UUID, Integer> allocate(LoadoutProfile profile, List<ItemStack> carried,
            Function<EquipmentSlot, ItemStack> equipment, Function<LoadoutProfile.Kind, ItemStack> tack) {
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
            if (entry.kind() == LoadoutProfile.Kind.MOUNT || entry.kind() == LoadoutProfile.Kind.HORSE_ARMOR) {
                if (entry.matches(tack.apply(entry.kind()))) {
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

    public Component detail(ResidentEntity body) {
        return profiles.assigned(body.id())
            .map(profile -> Component.literal(profile.name()))
            .orElseGet(() -> Component.translatable("folkways.loadouts.none"));
    }

    private void report(UUID resident, State state, String reason, long missing) {
        statuses.put(resident, new Status(state, reason, missing));
    }
}
