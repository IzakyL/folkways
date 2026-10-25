package io.github.izakyl.folkways.plugins.combat.weapon;

import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.resident.body.Body;
import io.github.izakyl.folkways.core.api.resident.body.Equipment;
import io.github.izakyl.folkways.core.api.resident.body.PersonalItems;
import io.github.izakyl.folkways.core.api.terms.Containers;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.Reach;
import io.github.izakyl.folkways.core.api.terms.StoreRule;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
import io.github.izakyl.folkways.core.api.vocation.KitRules;
import io.github.izakyl.folkways.core.api.work.Amount;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.Urge;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.plugins.combat.command.ResidentCommands;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import io.github.izakyl.folkways.plugins.person.equipment.ResidentEquipment;
import io.github.izakyl.folkways.plugins.person.equipment.EquipmentPlans;
import io.github.izakyl.folkways.plugins.patrol.PatrolContent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.common.Tags;

/** Finite personal stock, collected locally through the resident's existing work runner. */
public final class PatrolSupplies {
    private static final ResourceLocation OWNER = ResourceLocation.fromNamespaceAndPath("folkways", "patrol");
    private static final ResourceLocation SAVED = ResourceLocation.fromNamespaceAndPath("folkways", "patrol_loadouts");
    public static final int ARROWS = 8;
    static final int RETRY_TICKS = 100;
    static final int TRIP_TICKS = 200;
    private static final int STORE_RANGE = 32;
    private static final ItemSpec AMMUNITION = ItemSpec.of(ItemTags.ARROWS);

    public enum Loadout { MELEE, BOW, CROSSBOW }
    public enum State { INACTIVE, READY, FETCHING, NO_STOCK, NO_ROOM, FAILED, CANCELLED, TIMED_OUT }
    public record Status(State state, String reason, long missing, Optional<WorldPos> source, long retryAt) { }
    record Demand(ItemSpec goods, int target, boolean weapon, Optional<WeaponAdapter.Ammunition> ammunition) {
        Demand(ItemSpec goods, int target, boolean weapon) { this(goods, target, weapon, Optional.empty()); }
        boolean accepts(ItemStack actual) { return Goods.matches(actual, goods) && ammunition.map(ammo -> ammo.accepts(actual)).orElse(true); }
    }
    record Authority(ResidentEntity body, ResidentCommands.Order order, long duty, long until, long revision) { }

    private final Colony colony;
    private final Map<UUID, Loadout> loadouts = new HashMap<>();
    private final Map<UUID, Long> revisions = new HashMap<>();
    private final Map<UUID, Long> duties = new HashMap<>();
    private final Map<UUID, Status> statuses = new HashMap<>();
    private final Map<UUID, PatrolSupplyNode> pending = new HashMap<>();
    private final Set<UUID> ammunitionCycle = new HashSet<>();

    public PatrolSupplies(Colony colony) {
        this.colony = colony;
        ListTag saved = colony.kept(SAVED).getList("loadouts", Tag.TAG_COMPOUND);
        for (int n = 0; n < saved.size(); n++) {
            CompoundTag row = saved.getCompound(n);
            if (row.hasUUID("resident")) {
                try { loadouts.put(row.getUUID("resident"), Loadout.valueOf(row.getString("loadout"))); }
                catch (IllegalArgumentException invalid) { /* Unknown future loadouts are left inactive. */ }
            }
        }
    }

    public static PatrolSupplies of(Colony colony) {
        return PatrolContent.suppliesOf(colony).orElseThrow();
    }

    public Optional<Loadout> preference(UUID resident) { return Optional.ofNullable(loadouts.get(resident)); }

    /** Select replenishment stock; this never removes or replaces a player-given weapon. */
    public void loadout(Body body, Loadout loadout) {
        validate(body);
        java.util.Objects.requireNonNull(loadout);
        if (loadouts.get(body.id()) == loadout) { return; }
        ResidentEntity person = (ResidentEntity) body.mob();
        if (ResidentEquipment.assigned(person).isEmpty() && !ResidentEquipment.mainLocked(person)) person.stopWeaponSession();
        loadouts.put(body.id(), loadout);
        invalidate(body.id());
        save();
    }

    /** Revert to ammunition-only inference from a bow or crossbow already carried. */
    public void clear(Body body) {
        validate(body);
        if (!loadouts.containsKey(body.id())) { return; }
        ResidentEntity person = (ResidentEntity) body.mob();
        if (ResidentEquipment.assigned(person).isEmpty() && !ResidentEquipment.mainLocked(person)) person.stopWeaponSession();
        loadouts.remove(body.id());
        invalidate(body.id());
        save();
    }

    public Status status(UUID resident) {
        return statuses.getOrDefault(resident, new Status(State.INACTIVE, "inactive", 0, Optional.empty(), 0));
    }

    private void validate(Body body) {
        if (!(body.mob().level() instanceof ServerLevel level) || !level.getServer().isSameThread()
                || !(body.mob() instanceof ResidentEntity) || !body.mob().isAlive() || body.mob().isRemoved()
                || body.colonyId().filter(colony.id()::equals).isEmpty() || colony.holdingOf(body.id()).isEmpty()) {
            throw new IllegalArgumentException("patrol loadouts require a loaded person held by this colony on its server thread");
        }
    }

    private void invalidate(UUID resident) {
        revisions.merge(resident, 1L, Long::sum);
        pending.remove(resident);
        statuses.remove(resident);
    }

    private void save() {
        CompoundTag saved = new CompoundTag();
        ListTag rows = new ListTag();
        loadouts.forEach((id, loadout) -> {
            CompoundTag row = new CompoundTag(); row.putUUID("resident", id); row.putString("loadout", loadout.name()); rows.add(row);
        });
        saved.put("loadouts", rows);
        colony.keep(SAVED, saved);
    }

    public void tick(MinecraftServer server) {
        Set<UUID> roster = new HashSet<>();
        colony.residents().forEach(resident -> roster.add(resident.id()));
        boolean changed = loadouts.keySet().removeIf(id -> !roster.contains(id));
        for (var entry : List.copyOf(pending.entrySet())) {
            Authority authority = entry.getValue().authority();
            if (!valid(authority) || !(authority.body().level() instanceof ServerLevel level)
                    || level.getEntity(entry.getKey()) != authority.body()) {
                pending.remove(entry.getKey());
                report(entry.getKey(), State.CANCELLED, "resident or order left", 0, null, 0);
            }
        }
        // Pending cancellation can report one final status. Remove departed residents afterwards,
        // while retaining unloaded residents whose holdings remain in the colony's roster.
        statuses.keySet().retainAll(roster);
        ammunitionCycle.retainAll(roster);
        revisions.keySet().retainAll(roster);
        duties.keySet().retainAll(roster);
        if (changed) { save(); }
    }

    public void close() { pending.clear(); statuses.clear(); duties.clear(); ammunitionCycle.clear(); }

    public List<Amount> keeps(Body body) {
        if (!(body.mob() instanceof ResidentEntity person)) { return List.of(); }
        Loadout explicit = loadouts.get(body.id());
        Loadout selected = selected(person);
        if (selected == null) { return List.of(); }
        List<Amount> kept = new ArrayList<>();
        if (explicit != null || selected != Loadout.MELEE) {
            kept.add(new Amount(weapon(selected), 1));
        }
        if (selected != Loadout.MELEE) {
            kept.add(new Amount(AMMUNITION, ResidentEquipment.ammoTarget(person)));
        }
        return List.copyOf(kept);
    }

    public List<Urge> urges(Worker who, Optional<ResidentCommands.Order> order, long duty, long until) {
        if (!(who.body() instanceof ResidentEntity body) || !(body.level() instanceof ServerLevel level)) { return List.of(); }
        if (EquipmentPlans.manages(colony, body.id()) || !ResidentEquipment.assigned(body).isEmpty() && Weapons.ammunition(body).isPresent()) {
            pending.remove(body.id()); ammunitionCycle.remove(body.id()); return List.of();
        }
        boolean authorized = order.map(current -> current.mode() == ResidentCommands.Mode.GUARD).orElse(level.getGameTime() <= until);
        if (!authorized) {
            duties.remove(body.id());
            if (pending.remove(body.id()) != null) { report(body.id(), State.CANCELLED, "order changed", 0, null, 0); }
            return List.of();
        }
        if (order.isEmpty()) { duties.put(body.id(), duty); } else { duties.remove(body.id()); }
        Authority authority = new Authority(body, order.orElse(null), duty, until, revisions.getOrDefault(body.id(), 0L));
        PatrolSupplyNode current = pending.get(body.id());
        if (current != null) {
            if (!current.authority().equals(authority) || !valid(current.authority())
                    || !sameDemand(current.demand(), wanted(body))) {
                pending.remove(body.id());
            } else if (level.getGameTime() >= current.deadline()) {
                pending.remove(body.id());
                report(body.id(), State.TIMED_OUT, "supply trip timed out", 0, current.source(), level.getGameTime() + RETRY_TICKS);
                return List.of();
            } else if (missing(body, current.demand()) > 0) {
                return List.of(current.urge());
            } else {
                pending.remove(body.id());
            }
        }
        Demand demand = wanted(body);
        if (demand == null) {
            report(body.id(), State.READY, "stock ready", 0, null, 0);
            return List.of();
        }
        if (level.getGameTime() < status(body.id()).retryAt()) { return List.of(); }
        int missing = missing(body, demand);
        WorldPos noRoom = null;
        int nearby = 0;
        int readable = 0;
        int stocked = 0;
        for (WorldPos source : colony.storesIn(level.dimension()).stream()
                .filter(at -> at.sameRealm(WorldSpaces.at(body)))
                .filter(at -> WorldSpaces.world(level, at).filter(point -> point.distanceToSqr(body.position()) <= STORE_RANGE * STORE_RANGE).isPresent())
                .sorted(Comparator.comparingDouble(at -> WorldSpaces.world(level, at).orElseThrow().distanceToSqr(body.position())))
                .limit(32).toList()) {
            nearby++;
            Optional<Container> store = source(level, source);
            if (store.isEmpty()) { continue; }
            readable++;
            List<ItemStack> preview = preview(level, source, store.get(), demand, missing);
            if (preview.isEmpty()) { continue; }
            stocked++;
            if (!fits(body, preview)) {
                noRoom = source;
                continue;
            }
            BlockPos target = source.block(level);
            // The runner may arrive within 0.9 blocks of a stance, rather than its exact centre.
            // Leave room for that tolerance; draw still checks the body's actual block reach and sight.
            Optional<Stances> stances = Stances.of(level, Reach.workableCells(level, target).stream()
                .filter(feet -> feet.distSqr(target) <= 2 * 2).toList());
            if (stances.isEmpty()) { continue; }
            int batch = preview.stream().mapToInt(ItemStack::getCount).sum();
            PatrolSupplyNode request = new PatrolSupplyNode(this, authority, source, demand, batch, stances.get(), level.getGameTime() + TRIP_TICKS);
            pending.put(body.id(), request);
            report(body.id(), State.FETCHING, "collecting stock", missing, source, 0);
            return List.of(request.urge());
        }
        report(body.id(), noRoom == null ? State.NO_STOCK : State.NO_ROOM,
            noRoom == null ? "no unclaimed stock within 32 blocks (registered=" + colony.storesIn(level.dimension()).size()
                + ", nearby=" + nearby + ", readable=" + readable + ", stocked=" + stocked + ")" : "pack full",
            missing, noRoom, level.getGameTime() + RETRY_TICKS);
        return List.of();
    }

    boolean valid(Authority authority) {
        ResidentEntity body = authority.body();
        if (!(body.level() instanceof ServerLevel level) || !body.isAlive() || body.isRemoved()
                || body.colonyId().filter(colony.id()::equals).isEmpty() || colony.holdingOf(body.id()).isEmpty()
                || revisions.getOrDefault(body.id(), 0L) != authority.revision()) { return false; }
        var current = ResidentCommands.of(colony).current(body.id());
        return authority.order() != null ? current.orElse(null) == authority.order()
            && authority.order().mode() == ResidentCommands.Mode.GUARD
            : current.isEmpty() && duties.getOrDefault(body.id(), Long.MIN_VALUE) == authority.duty()
                && level.getGameTime() <= authority.until();
    }

    boolean active(PatrolSupplyNode request) {
        return !EquipmentPlans.manages(colony, request.authority().body().id())
            && (request.demand().weapon() || ResidentEquipment.assigned(request.authority().body()).isEmpty())
            && pending.get(request.authority().body().id()) == request && valid(request.authority())
            && sameDemand(request.demand(), wanted(request.authority().body()));
    }

    Optional<Container> source(ServerLevel level, WorldPos source) {
        return source.in(level) && level.isLoaded(source.block(level)) && StoreRule.takes(colony.storeRules(source))
            ? colony.store(level, source) : Optional.empty();
    }

    List<ItemStack> preview(ServerLevel level, WorldPos source, Container actual, Demand demand, int count) {
        SimpleContainer free = copy(actual);
        WorldPos canonical = WorldPos.of(level, Containers.anchor(level, source.block(level)));
        for (var claim : colony.reservedInputs(level)) {
            if (claim.store().filter(at -> at.equals(source) || at.equals(canonical)
                    || at.in(level) && level.isLoaded(at.block(level))
                        && WorldPos.of(level, Containers.anchor(level, at.block(level))).equals(canonical)).isPresent()) {
                take(free, claim.goods(), (int) Math.min(Integer.MAX_VALUE, claim.count()), false);
            }
        }
        return take(free, demand.goods(), count, demand.weapon(), demand::accepts);
    }

    private List<ItemStack> take(Container source, ItemSpec goods, int count, boolean weapon) {
        return take(source, goods, count, weapon, stack -> true);
    }

    private List<ItemStack> take(Container source, ItemSpec goods, int count, boolean weapon, java.util.function.Predicate<ItemStack> compatible) {
        List<ItemStack> taken = new ArrayList<>();
        int left = count;
        while (left > 0) {
            ItemStack stack = Containers.extract(source, item -> !PersonalItems.marked(item) && compatible.test(item) && Goods.matches(item, goods)
                && (!weapon || KitRules.allows(colony, io.github.izakyl.folkways.core.api.vocation.Vocations.required(PatrolContent.VOCATION), item.getItem())), left);
            if (stack.isEmpty()) { break; }
            taken.add(stack); left -= stack.getCount();
        }
        return List.copyOf(taken);
    }

    static boolean fits(Container pack, List<ItemStack> goods) {
        SimpleContainer copy = copy(pack);
        return goods.stream().allMatch(stack -> Containers.insert(copy, stack).isEmpty());
    }

    static boolean fits(ResidentEntity body, List<ItemStack> goods) {
        SimpleContainer copy = copy(body.pack());
        return goods.stream().allMatch(stack -> Containers.insert(copy, stack).isEmpty())
            && (!body.hasWeaponSession() || Containers.insert(copy, body.getMainHandItem().copy()).isEmpty());
    }

    private static SimpleContainer copy(Container source) {
        SimpleContainer copy = new SimpleContainer(source.getContainerSize());
        for (int slot = 0; slot < source.getContainerSize(); slot++) { copy.setItem(slot, source.getItem(slot).copy()); }
        return copy;
    }

    private Demand wanted(ResidentEntity body) {
        Loadout explicit = loadouts.get(body.id());
        Loadout selected = selected(body);
        if (selected == null) { return null; }
        Demand arm = new Demand(weapon(selected), 1, true);
        if (explicit != null && !Equipment.locked(body, net.minecraft.world.entity.EquipmentSlot.MAINHAND)
                && ResidentEquipment.assigned(body).isEmpty() && missing(body, arm) > 0) { return arm; }
        if (!EquipmentPlans.ammoEnabled(colony, body.id())) { ammunitionCycle.remove(body.id()); return null; }
        Optional<Weapons.AmmunitionSupply> supply = Weapons.ammunition(body);
        if (supply.isEmpty()) return null;
        var declared = supply.get().demand();
        Demand ammo = new Demand(declared.stock(), declared.target(), false, Optional.of(declared));
        int missing = missing(body, ammo);
        if (missing == 0) { ammunitionCycle.remove(body.id()); return null; }
        int carriedAndLoaded = declared.target() - missing;
        if (carriedAndLoaded < Math.max(1, declared.target() / 2)) ammunitionCycle.add(body.id());
        return ammunitionCycle.contains(body.id()) ? ammo : null;
    }
    int missing(ResidentEntity body, Demand demand) {
        long carried = body.pack().countMatching(stack -> ResidentEquipment.ammoCandidate(body, stack)
            && demand.accepts(stack));
        if (body.hasWeaponSession() && Goods.matches(body.getMainHandItem(), demand.goods())) { carried += body.getMainHandItem().getCount(); }
        if (!demand.weapon()) {
            carried += demand.ammunition().map(WeaponAdapter.Ammunition::loaded).orElse(0);
        }
        return (int) Math.max(0, demand.target() - carried);
    }

    private static boolean sameDemand(Demand first, Demand second) {
        return second != null && first.weapon() == second.weapon() && first.target() == second.target() && first.goods().equals(second.goods());
    }

    boolean compatible(ResidentEntity body, Demand demand, ItemStack actual) {
        Demand current = wanted(body);
        return sameDemand(demand, current) && current.accepts(actual);
    }

    private Loadout selected(ResidentEntity body) {
        if (!ResidentEquipment.assigned(body).isEmpty() || ResidentEquipment.mainLocked(body)) return inferred(body);
        return loadouts.getOrDefault(body.id(), inferred(body));
    }

    private static Loadout inferred(ResidentEntity body) {
        ItemStack assigned = ResidentEquipment.assigned(body);
        if (!assigned.isEmpty()) {
            if (assigned.is(Items.BOW)) return Loadout.BOW;
            if (assigned.is(Items.CROSSBOW)) return Loadout.CROSSBOW;
            return Loadout.MELEE;
        }
        if (ResidentEquipment.mainLocked(body)) return null;
        List<ItemStack> carried = new ArrayList<>();
        if (body.hasWeaponSession()) { carried.add(body.getMainHandItem()); }
        carried.addAll(body.pack().contents());
        for (ItemStack stack : carried) {
            if (!ResidentEquipment.weaponCandidate(body, stack)) continue;
            if (stack.is(Items.BOW)) { return Loadout.BOW; }
            if (stack.is(Items.CROSSBOW)) { return Loadout.CROSSBOW; }
        }
        return null;
    }

    static boolean prefers(Loadout loadout, ItemStack stack) { return Goods.matches(stack, weapon(loadout)); }

    private static ItemSpec weapon(Loadout loadout) {
        return switch (loadout) {
            case MELEE -> ItemSpec.of(Tags.Items.MELEE_WEAPON_TOOLS);
            case BOW -> ItemSpec.of(BuiltInRegistries.ITEM.getKey(Items.BOW));
            case CROSSBOW -> ItemSpec.of(BuiltInRegistries.ITEM.getKey(Items.CROSSBOW));
        };
    }

    void finished(PatrolSupplyNode request, Ending how) {
        UUID resident = request.authority().body().id();
        if (!pending.remove(resident, request)) { return; }
        long now = request.authority().body().level().getGameTime();
        if (how instanceof Ending.Done) { report(resident, State.READY, "stock collected", 0, request.source(), 0); }
        else if (how instanceof Ending.Failed failed) {
            State state = request.failure() == null ? State.FAILED : request.failure();
            report(resident, state, request.failureReason().orElse(failed.why().translationKey()),
                missing(request.authority().body(), request.demand()), request.source(), now + RETRY_TICKS);
        } else { report(resident, State.CANCELLED, "supply interrupted", 0, request.source(), now + 20); }
    }

    private void report(UUID resident, State state, String reason, long missing, WorldPos source, long retry) {
        statuses.put(resident, new Status(state, reason, missing, Optional.ofNullable(source), retry));
    }
}
