package io.github.izakyl.folkways.plugins.combat.weapon;

import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.resident.body.Body;
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
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;

/** Finite personal stock, collected locally through the resident's existing work runner. */
public final class PatrolSupplies {
    private static final ResourceLocation SAVED = ResourceLocation.fromNamespaceAndPath("folkways", "patrol_loadouts");
    public static final int ARROWS = 8;
    static final int RETRY_TICKS = 100;
    static final int TRIP_TICKS = 200;
    private static final int STORE_RANGE = 32;

    private record Supply(ResourceLocation id, java.util.function.BiFunction<Colony, Body, List<Amount>> needs) { }
    private static volatile List<Supply> extraSupplies = List.of();

    /** Extra duty equipment uses the same physical, reservation-aware collection trips as personal loadouts. */
    public static synchronized void registerSupply(ResourceLocation id,
            java.util.function.BiFunction<Colony, Body, List<Amount>> needs) {
        java.util.Objects.requireNonNull(id);
        java.util.Objects.requireNonNull(needs);
        if (extraSupplies.stream().anyMatch(entry -> entry.id().equals(id))) {
            throw new IllegalArgumentException("patrol supply already registered: " + id);
        }
        List<Supply> next = new ArrayList<>(extraSupplies);
        next.add(new Supply(id, needs));
        extraSupplies = List.copyOf(next);
    }

    public enum State { INACTIVE, READY, FETCHING, NO_STOCK, NO_ROOM, FAILED, CANCELLED, TIMED_OUT }
    public record Status(State state, String reason, long missing, Optional<WorldPos> source, long retryAt) { }
    record Demand(ItemSpec goods, int target, boolean weapon, Optional<LoadoutRegistry.Selection> loadout) {
        Demand(ItemSpec goods, int target, boolean weapon) { this(goods, target, weapon, Optional.empty()); }
        boolean matches(ItemStack stack) {
            return Goods.matches(stack, goods) && (!weapon || loadout.map(selection -> selection.provider()
                .map(provider -> provider.acceptsWeapon(stack, selection.options())).orElse(false)).orElse(true));
        }
    }
    record Authority(ResidentEntity body, ResidentCommands.Order order, long duty, long until, long revision) { }

    private final Colony colony;
    private final Map<UUID, LoadoutRegistry.Selection> loadouts = new HashMap<>();
    private final Map<UUID, Long> revisions = new HashMap<>();
    private final Map<UUID, Long> duties = new HashMap<>();
    private final Map<UUID, Status> statuses = new HashMap<>();
    private final Map<UUID, PatrolSupplyNode> pending = new HashMap<>();

    public PatrolSupplies(Colony colony) {
        this.colony = colony;
        ListTag saved = colony.kept(SAVED).getList("loadouts", Tag.TAG_COMPOUND);
        for (int n = 0; n < saved.size(); n++) {
            CompoundTag row = saved.getCompound(n);
            if (row.hasUUID("resident")) {
                LoadoutRegistry.Selection selection = LoadoutRegistry.read(row);
                if (selection != null) { loadouts.put(row.getUUID("resident"), selection); }
            }
        }
        if (!saved.isEmpty() && colony.kept(SAVED).getInt("version") < 2) { save(); }
    }

    public static PatrolSupplies of(Colony colony) {
        return PatrolContent.suppliesOf(colony).orElseThrow();
    }

    public Optional<LoadoutRegistry.Selection> preference(UUID resident) { return Optional.ofNullable(loadouts.get(resident)); }

    /** Select replenishment stock; this never removes or replaces a player-given weapon. */
    public void loadout(Body body, LoadoutRegistry.Selection loadout) {
        validate(body);
        java.util.Objects.requireNonNull(loadout);
        if (loadout.provider().isEmpty()) { throw new IllegalArgumentException("unavailable loadout"); }
        if (loadout.equals(loadouts.get(body.id()))) { return; }
        ((ResidentEntity) body.mob()).stopWeaponSession();
        loadouts.put(body.id(), loadout);
        invalidate(body.id());
        save();
    }

    /** Revert to ammunition-only inference from a weapon already carried. */
    public void clear(Body body) {
        validate(body);
        if (!loadouts.containsKey(body.id())) { return; }
        ((ResidentEntity) body.mob()).stopWeaponSession();
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
        saved.putInt("version", 2);
        ListTag rows = new ListTag();
        loadouts.forEach((id, loadout) -> {
            CompoundTag row = new CompoundTag(); row.putUUID("resident", id); row.putString("loadout", loadout.id().toString());
            row.put("options", loadout.options()); rows.add(row);
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
        revisions.keySet().retainAll(roster);
        duties.keySet().retainAll(roster);
        if (changed) { save(); }
    }

    public void close() { pending.clear(); statuses.clear(); duties.clear(); }

    public List<Amount> keeps(Body body) {
        if (!(body.mob() instanceof ResidentEntity person)) { return List.of(); }
        LoadoutRegistry.Selection selected = selected(person);
        List<Amount> kept = new ArrayList<>();
        if (selected != null && selected.provider().isPresent()) {
            LoadoutProvider provider = selected.provider().orElseThrow();
            ItemSpec retainedWeapon = LoadoutRegistry.carried(person).stream()
                .filter(stack -> provider.acceptsWeapon(stack, selected.options())).findFirst().map(ItemSpec::exact)
                .orElseGet(() -> provider.weapon(selected.options()));
            kept.add(new Amount(retainedWeapon, 1));
            kept.addAll(provider.ammunition(selected.options()));
        }
        for (Supply supply : extraSupplies) { kept.addAll(supply.needs().apply(colony, body)); }
        return List.copyOf(kept);
    }

    public List<Urge> urges(Worker who, Optional<ResidentCommands.Order> order, long duty, long until) {
        if (!(who.body() instanceof ResidentEntity body) || !(body.level() instanceof ServerLevel level)) { return List.of(); }
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
            if (!current.authority().equals(authority) || !valid(current.authority())) {
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
            if (!fits(body.pack(), preview)) {
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
        return pending.get(request.authority().body().id()) == request && valid(request.authority());
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
        return take(free, demand, count);
    }

    private List<ItemStack> take(Container source, ItemSpec goods, int count, boolean weapon) {
        return take(source, new Demand(goods, count, weapon), count);
    }

    private List<ItemStack> take(Container source, Demand demand, int count) {
        List<ItemStack> taken = new ArrayList<>();
        int left = count;
        while (left > 0) {
            ItemStack stack = Containers.extract(source, item -> demand.matches(item)
                && (!demand.weapon() || KitRules.allows(colony, item.getItem())), left);
            if (stack.isEmpty()) { break; }
            taken.add(stack); left -= stack.getCount();
        }
        return List.copyOf(taken);
    }

    static boolean fits(Container pack, List<ItemStack> goods) {
        SimpleContainer copy = copy(pack);
        return goods.stream().allMatch(stack -> Containers.insert(copy, stack).isEmpty());
    }

    private static SimpleContainer copy(Container source) {
        SimpleContainer copy = new SimpleContainer(source.getContainerSize());
        for (int slot = 0; slot < source.getContainerSize(); slot++) { copy.setItem(slot, source.getItem(slot).copy()); }
        return copy;
    }

    private LoadoutRegistry.Selection selected(ResidentEntity body) {
        LoadoutRegistry.Selection explicit = loadouts.get(body.id());
        return explicit != null ? explicit : LoadoutRegistry.infer(LoadoutRegistry.carried(body)).orElse(null);
    }

    private Demand wanted(ResidentEntity body) {
        for (Supply supply : extraSupplies) {
            for (Amount amount : supply.needs().apply(colony, body)) {
                Demand demand = new Demand(amount.spec(), Math.toIntExact(amount.count()), false);
                if (missing(body, demand) > 0) { return demand; }
            }
        }
        LoadoutRegistry.Selection selected = selected(body);
        if (selected == null || selected.provider().isEmpty()) { return null; }
        LoadoutProvider provider = selected.provider().orElseThrow();
        Demand arm = new Demand(provider.weapon(selected.options()), 1, true, Optional.of(selected));
        if (loadouts.containsKey(body.id()) && missing(body, arm) > 0) { return arm; }
        for (Amount ammunition : provider.ammunition(selected.options())) {
            Demand ammo = new Demand(ammunition.spec(), (int) ammunition.count(), false);
            if (ammo.target() - missing(body, ammo) <= provider.lowWater(selected.options(), ammunition)
                    && missing(body, ammo) > 0) { return ammo; }
        }
        return null;
    }

    int missing(ResidentEntity body, Demand demand) {
        long carried = LoadoutRegistry.carried(body).stream().filter(demand::matches).mapToLong(ItemStack::getCount).sum();
        if (!demand.weapon()) {
            for (ItemStack weapon : LoadoutRegistry.carried(body)) {
                carried += LoadoutRegistry.infer(List.of(weapon)).flatMap(LoadoutRegistry.Selection::provider)
                    .map(provider -> provider.loaded(weapon, demand.goods())).orElse(0);
            }
        }
        return (int) Math.max(0, demand.target() - carried);
    }

    static boolean prefers(LoadoutRegistry.Selection loadout, ItemStack stack) {
        return loadout.provider().map(provider -> provider.acceptsWeapon(stack, loadout.options())).orElse(false);
    }

    public net.minecraft.network.chat.Component detail(ResidentEntity body) {
        LoadoutRegistry.Selection selected = selected(body);
        return selected == null ? net.minecraft.network.chat.Component.empty() : LoadoutRegistry.find(selected.id())
            .map(provider -> provider.detail(body, selected.options())).orElseGet(() -> LoadoutRegistry.label(selected));
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
