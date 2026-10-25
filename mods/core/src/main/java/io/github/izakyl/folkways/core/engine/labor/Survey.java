package io.github.izakyl.folkways.core.engine.labor;

import io.github.izakyl.folkways.core.api.resident.body.Body;
import io.github.izakyl.folkways.core.api.resident.body.Equipment;
import io.github.izakyl.folkways.core.api.resident.body.Licence;
import io.github.izakyl.folkways.core.api.resident.body.PersonalItems;
import io.github.izakyl.folkways.core.api.terms.Containers;
import io.github.izakyl.folkways.core.api.terms.Stand;
import io.github.izakyl.folkways.core.api.terms.Stash;
import io.github.izakyl.folkways.core.api.terms.StoreRule;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
import io.github.izakyl.folkways.core.api.vocation.KitRules;
import io.github.izakyl.folkways.core.api.vocation.Vocation;
import io.github.izakyl.folkways.core.api.vocation.KitRules;
import io.github.izakyl.folkways.core.api.vocation.Vocations;
import io.github.izakyl.folkways.core.api.work.Workshop;
import io.github.izakyl.folkways.core.engine.EngineMode;
import io.github.izakyl.folkways.core.engine.colony.ColonyData;
import io.github.izakyl.folkways.core.engine.plan.Asks;
import io.github.izakyl.folkways.core.engine.plan.Crew;
import io.github.izakyl.folkways.core.engine.plan.Known;
import io.github.izakyl.folkways.core.engine.plan.Situation;
import io.github.izakyl.folkways.core.engine.plan.Stands;
import io.github.izakyl.folkways.core.engine.plan.Stock;
import io.github.izakyl.folkways.core.engine.plan.Stores;
import io.github.izakyl.folkways.core.engine.travel.Feet;
import io.github.izakyl.folkways.core.engine.travel.TravelBudget;
import io.github.izakyl.folkways.core.engine.travel.Ways;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.BiConsumer;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

final class Survey {

    private static final int STEPS = 64;

    private record Reading(List<ItemStack> stacks, int free, Set<Stand> stands, List<StoreRule> rules,
                           Set<Item> refused) {

        boolean same(Reading other) {
            return free == other.free && stands.equals(other.stands) && rules.equals(other.rules)
                && refused.equals(other.refused) && sameStacks(stacks, other.stacks);
        }
    }

    private static boolean sameStacks(List<ItemStack> one, List<ItemStack> other) {
        if (one.size() != other.size()) {
            return false;
        }
        for (int at = 0; at < one.size(); at++) {
            if (!ItemStack.matches(one.get(at), other.get(at))) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameHand(Crew.Hand one, Crew.Hand other) {
        return one.at().realm().equals(other.at().realm()) && one.packCells() == other.packCells()
            && one.packCapacity() == other.packCapacity() && one.pace() == other.pace()
            && one.licences().equals(other.licences()) && sameStacks(one.tools(), other.tools())
            && sameStacks(one.cargo(), other.cargo()) && one.toolRules().equals(other.toolRules());
    }

    private final Map<Stash, Reading> stores = new LinkedHashMap<>();
    // Where a worker can stand to reach each store, read again only this often: the ground around a store seldom
    // changes, and finding it reads every cell in reach, which is the most of what reading a store costs.
    private static final long STANDS_TICKS = 200L;
    private record Footed(long at, Set<Stand> stands) {
    }
    private final Map<Stash, Footed> footed = new LinkedHashMap<>();

    // What a store's container refuses is read once per kind of container there, not on every change inside it.
    private record Refusal(Class<?> kind, Set<Item> items) {
    }

    private final Map<Stash, Refusal> refusals = new HashMap<>();
    private final Map<UUID, Crew.Hand> hands = new LinkedHashMap<>();
    private Set<UUID> reserved = Set.of();
    private final Map<UUID, Long> licencesRead = new HashMap<>();
    private final Map<UUID, List<Equipment.Demand>> equipmentRead = new HashMap<>();
    private final Map<UUID, Boolean> suppliesRead = new HashMap<>();

    private final Deque<BlockPos> storesDue = new ArrayDeque<>();
    private final Deque<UUID> handsDue = new ArrayDeque<>();
    private final Set<Stash> looks = new LinkedHashSet<>();
    private final Set<UUID> handsOf = new LinkedHashSet<>();
    private boolean surveying;
    private boolean overHands;
    private boolean storesMoved;
    private boolean handsMoved;

    private Stock stock;
    private Stands stands;
    private long rulesRead = Stores.revision();

    void asked(Asks asks) {
        surveying = asks.survey();
        overHands = asks.hands();
        looks.addAll(asks.looks());
        handsOf.addAll(asks.handsOf());
        if (!surveying) {
            storesDue.clear();
        }
        if (!overHands) {
            handsDue.clear();
        }
    }

    void read(UUID resident) {
        handsOf.add(resident);
    }

    void reserved(Set<UUID> next) {
        for (UUID id : reserved) { if (!next.contains(id)) { handsOf.add(id); } }
        for (UUID id : next) { if (!reserved.contains(id)) { handsOf.add(id); } }
        reserved = Set.copyOf(next);
    }

    void forget(UUID resident) {
        hands.remove(resident);
        licencesRead.remove(resident);
        equipmentRead.remove(resident);
        suppliesRead.remove(resident);
        handsOf.remove(resident);
        handsDue.remove(resident);
    }

    boolean answer(ServerLevel level, ColonyData colony, Map<UUID, Body> present, Consumer<Body> seen) {
        return answer(level, colony, present, (body, supply) -> seen.accept(body));
    }

    boolean answer(ServerLevel level, ColonyData colony, Map<UUID, Body> present, BiConsumer<Body, Boolean> seen) {
        var toolRules = KitRules.snapshot(colony.works());
        suppliesRead.keySet().retainAll(present.keySet());
        for (Body body : present.values()) {
            boolean supply = KitRules.supplies(colony.works(), body);
            Boolean supplied = suppliesRead.put(body.id(), supply);
            Crew.Hand prior = hands.get(body.id());
            if (supplied == null || supplied != supply || prior != null && !prior.toolRules().equals(toolRules)) {
                handsOf.add(body.id());
            }
            if (Equipment.reconcile(colony.works(), body)) {
                handsOf.add(body.id());
            }
            Crew.Hand cached = hands.get(body.id());
            if (cached != null && !cached.at().realm().equals(WorldSpaces.at(body.mob()).realm())) {
                handsOf.add(body.id());
            }
            // A setting can request missing clothing without changing the pack or native armor slots yet.
            // Re-read that hand so Kits can publish or withdraw its real clothing delivery.
            if (cached != null && !Equipment.wanted(colony.works(), body).equals(equipmentRead.get(body.id()))) {
                handsOf.add(body.id());
            }
            Long read = licencesRead.get(body.id());
            if (cached != null && (read == null || read != body.licences().revision())) {
                handsOf.add(body.id());
            }
        }
        if (rulesRead != Stores.revision()) {
            rulesRead = Stores.revision();
            looks.addAll(stores.keySet());
        }
        if (looks.isEmpty() && handsOf.isEmpty() && !surveying && !overHands) {
            return false;
        }
        long began = Util.getNanos();
        Budget budget = EngineMode.singleThread() ? new Budget(-1, STEPS)
            : new Budget(began + TravelBudget.wallClockNanos(), Integer.MAX_VALUE);
        boolean answered = false;
        Set<BlockPos> offered = offered(level, colony);
        for (Stash stash : List.copyOf(looks)) {
            if (budget.spent()) {
                break;
            }
            looks.remove(stash);
            if (stash.pos().in(level)) {
                answered |= WorldSpaces.storage(level, stash.pos())
                    .map(pos -> readStore(level, offered, pos)).orElse(false);
            }
        }
        for (UUID resident : List.copyOf(handsOf)) {
            if (budget.spent()) {
                break;
            }
            handsOf.remove(resident);
            answered |= readHand(colony, present.get(resident), resident, seen);
        }
        if (surveying) {
            if (storesDue.isEmpty()) {
                List<BlockPos> members = List.copyOf(offered);
                for (BlockPos pos : members) {
                    if (!stores.containsKey(Stash.at(WorldPos.of(level, pos)))) {
                        storesDue.addLast(pos);
                    }
                }
                for (BlockPos pos : members) {
                    if (stores.containsKey(Stash.at(WorldPos.of(level, pos)))) {
                        storesDue.addLast(pos);
                    }
                }
                Set<Stash> still = new LinkedHashSet<>();
                members.forEach(pos -> still.add(Stash.at(WorldPos.of(level, pos))));
                refusals.keySet().retainAll(still);
                footed.keySet().retainAll(still);
                if (stores.keySet().retainAll(still)) {
                    changed();
                    answered = true;
                }
            }
            while (!storesDue.isEmpty() && !budget.spent()) {
                storesMoved |= readStore(level, offered, storesDue.pollFirst());
            }
            if (storesDue.isEmpty()) {
                answered |= storesMoved;
                storesMoved = false;
            }
        }
        if (overHands) {
            if (handsDue.isEmpty()) {
                handsDue.addAll(present.keySet());
                hands.keySet().retainAll(present.keySet());
            }
            while (!handsDue.isEmpty() && !budget.spent()) {
                UUID resident = handsDue.pollFirst();
                handsMoved |= readHand(colony, present.get(resident), resident, seen);
            }
            if (handsDue.isEmpty()) {
                answered |= handsMoved;
                handsMoved = false;
            }
        }
        TravelBudget.spent(Util.getNanos() - began);
        return answered;
    }

    Known known(Ways ways, List<Workshop> workshops) {
        gather();
        return new Known(new Situation(ways, stock, workshops, stands),
            List.copyOf(hands.values()));
    }

    List<Crew.Hand> hands() {
        return List.copyOf(hands.values());
    }

    Stands stands() {
        gather();
        return stands;
    }

    private void gather() {
        if (stock != null) {
            return;
        }
        List<Stock.Holding> holdings = new ArrayList<>();
        Map<Stash, Integer> empty = new LinkedHashMap<>();
        Map<Stash, List<StoreRule>> rules = new LinkedHashMap<>();
        Map<Stash, Set<Item>> refused = new LinkedHashMap<>();
        Stands.Builder around = Stands.building();
        stores.forEach((where, reading) -> {
            for (ItemStack stack : reading.stacks()) {
                holdings.add(new Stock.Holding(where, stack));
            }
            empty.put(where, reading.free());
            if (!reading.rules().isEmpty()) {
                rules.put(where, reading.rules());
            }
            if (!reading.refused().isEmpty()) {
                refused.put(where, reading.refused());
            }
            around.container(where, reading.stands());
        });
        stock = new Stock(holdings, empty, rules, refused);
        stands = around.done();
    }

    // The loaded stores the colony's owners offer here, short of any block a workshop stands on and any store an owner
    // answers for itself.
    private static Set<BlockPos> offered(ServerLevel level, ColonyData colony) {
        Set<BlockPos> sites = colony.works().sitesIn(level.dimension());
        Set<BlockPos> withheld = new LinkedHashSet<>();
        for (WorldPos store : colony.works().withheldIn(level.dimension())) {
            WorldSpaces.storage(level, store).filter(level::isLoaded)
                .ifPresent(pos -> withheld.add(Containers.anchor(level, pos)));
        }
        Set<BlockPos> found = new LinkedHashSet<>();
        for (WorldPos store : colony.works().storesIn(level.dimension())) {
            WorldSpaces.storage(level, store)
                .filter(pos -> level.isLoaded(pos) && !sites.contains(pos)
                    && !withheld.contains(Containers.anchor(level, pos)))
                .ifPresent(found::add);
        }
        return found;
    }

    private boolean readStore(ServerLevel level, Set<BlockPos> offered, BlockPos pos) {
        Stash where = Stash.at(WorldPos.of(level, pos));
        Container container = offered.contains(pos)
            ? Stores.at(level, where.pos()).orElse(null) : null;
        if (container == null) {
            refusals.remove(where);
            footed.remove(where);
            if (stores.remove(where) != null) {
                changed();
                return true;
            }
            return false;
        }
        List<ItemStack> stacks = new ArrayList<>();
        int free = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.isEmpty()) {
                free++;
            } else if (!PersonalItems.marked(stack)) {
                stacks.add(stack.copy());
            }
        }
        long now = level.getGameTime();
        Footed feet = footed.get(where);
        if (feet == null || now - feet.at() >= STANDS_TICKS) {
            feet = new Footed(now, Stands.footingsOf(level, where.pos()));
            footed.put(where, feet);
        }
        Reading reading = new Reading(List.copyOf(stacks), free, feet.stands(),
            Stores.rulesAt(where.pos()), refusedBy(where, container));
        Reading had = stores.put(where, reading);
        if (had != null && had.same(reading)) {
            return false;
        }
        changed();
        return true;
    }

    private Set<Item> refusedBy(Stash where, Container container) {
        Container inner = Stores.inner(container);
        Refusal known = refusals.get(where);
        if (known == null || known.kind() != inner.getClass()) {
            known = new Refusal(inner.getClass(), Containers.refused(inner));
            refusals.put(where, known);
        }
        return known.items();
    }

    private boolean readHand(ColonyData colony, Body body, UUID resident, BiConsumer<Body, Boolean> seen) {
        if (body != null && reserved.contains(resident)) {
            seen.accept(body, false);
            return hands.remove(resident) != null;
        }
        if (body == null) {
            licencesRead.remove(resident);
            equipmentRead.remove(resident);
            suppliesRead.remove(resident);
            return hands.remove(resident) != null;
        }
        Equipment.reconcile(colony.works(), body);
        licencesRead.put(resident, body.licences().revision());
        equipmentRead.put(resident, Equipment.wanted(colony.works(), body));
        Kit kit = Kit.of(colony.works(), body);
        List<Licence> licences = new ArrayList<>();
        for (Vocation vocation : Vocations.all()) {
            body.licences().of(vocation).ifPresent(licences::add);
        }
        Crew.Hand had = hands.put(resident, new Crew.Hand(
            body.resident(),
            Feet.of(body.mob()),
            body.pack().plannableRoom(item -> kit.holds(item.getDefaultInstance())),
            body.pack().plannableCells(item -> kit.holds(item.getDefaultInstance())) - kit.keptCells(body),
            pace(body),
            List.copyOf(licences),
            kit.inPack(body),
            Optional.empty(),
            kit.cargoIn(body), KitRules.snapshot(colony.works())));
        seen.accept(body, suppliesRead.getOrDefault(resident, true));
        return had == null || !sameHand(had, hands.get(resident));
    }

    private void changed() {
        stock = null;
        stands = null;
    }

    private static double pace(Body body) {
        double base = body.mob().getAttributeBaseValue(Attributes.MOVEMENT_SPEED);
        return base <= 0 ? 1.0 : body.mob().getAttributeValue(Attributes.MOVEMENT_SPEED) / base;
    }

    private static final class Budget {

        private final long until;
        private int steps;
        private int taken;

        Budget(long until, int steps) {
            this.until = until;
            this.steps = steps;
        }

        boolean spent() {
            if (taken++ == 0) {
                return false;
            }
            return --steps < 0 || (until >= 0 && Util.getNanos() >= until);
        }
    }
}
