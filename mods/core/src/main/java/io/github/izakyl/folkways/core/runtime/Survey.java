package io.github.izakyl.folkways.core.runtime;

import io.github.izakyl.folkways.core.colony.api.Equipment;
import io.github.izakyl.folkways.core.colony.api.KitRules;
import io.github.izakyl.folkways.core.colony.internal.ColonyData;
import io.github.izakyl.folkways.core.inventory.api.InventorySnapshot;
import io.github.izakyl.folkways.core.inventory.api.SlotSnapshot;
import io.github.izakyl.folkways.core.inventory.api.Stash;
import io.github.izakyl.folkways.core.inventory.api.StoreRule;
import io.github.izakyl.folkways.core.inventory.internal.ObservationBudget;
import io.github.izakyl.folkways.core.inventory.internal.Stands;
import io.github.izakyl.folkways.core.plan.api.Workshop;
import io.github.izakyl.folkways.core.plan.internal.Asks;
import io.github.izakyl.folkways.core.plan.internal.Crew;
import io.github.izakyl.folkways.core.plan.internal.Known;
import io.github.izakyl.folkways.core.plan.internal.Situation;
import io.github.izakyl.folkways.core.plan.internal.Stock;
import io.github.izakyl.folkways.core.resident.api.Body;
import io.github.izakyl.folkways.core.resident.api.Licence;
import io.github.izakyl.folkways.core.transport.api.TransportPublication;
import io.github.izakyl.folkways.core.travel.internal.Feet;
import io.github.izakyl.folkways.core.travel.internal.Ways;
import io.github.izakyl.folkways.core.vocation.api.Vocation;
import io.github.izakyl.folkways.core.vocation.api.Vocations;
import io.github.izakyl.folkways.core.work.internal.Kit;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

final class Survey {

    private static final int STEPS = 64;

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
        return one.who().routeKind().equals(other.who().routeKind())
            && one.at().realm().equals(other.at().realm()) && one.packCells() == other.packCells()
            && one.packCapacity() == other.packCapacity() && one.pace() == other.pace()
            && one.licences().equals(other.licences()) && sameStacks(one.tools(), other.tools())
            && sameStacks(one.cargo(), other.cargo()) && one.toolRules().equals(other.toolRules());
    }

    private ResourceKey<Level> dimension;
    private InventorySnapshot inventory;
    private InventorySnapshot.Version inventoryRead;
    private Supplier<TransportPublication> transports =
        () -> new TransportPublication(0, List.of());
    private LongSupplier transportVersion = () -> 0;
    private long at;
    private boolean waitingForInventory;
    private final Map<UUID, Crew.Hand> hands = new LinkedHashMap<>();
    private Set<UUID> reserved = Set.of();
    private final Map<UUID, Long> licencesRead = new HashMap<>();
    private final Map<UUID, List<Equipment.Demand>> equipmentRead = new HashMap<>();
    private final Map<UUID, Boolean> suppliesRead = new HashMap<>();

    private final Set<Stash> looks = new LinkedHashSet<>();
    private final Set<UUID> handsOf = new LinkedHashSet<>();
    private final Set<UUID> residentsToRead = new LinkedHashSet<>();
    private boolean overHands;

    private Stock stock;
    private Stands stands;

    void asked(Asks asks) {
        overHands = asks.hands();
        looks.addAll(asks.looks());
        handsOf.addAll(asks.handsOf());
        residentsToRead.addAll(asks.handsOf());
    }

    void read(UUID resident) {
        handsOf.add(resident);
        residentsToRead.add(resident);
    }

    void reserved(Set<UUID> next) {
        for (UUID id : reserved) {
            if (!next.contains(id)) {
                handsOf.add(id);
            }
        }
        for (UUID id : next) {
            if (!reserved.contains(id)) {
                handsOf.add(id);
            }
        }
        reserved = Set.copyOf(next);
    }

    void forget(UUID resident) {
        hands.remove(resident);
        licencesRead.remove(resident);
        equipmentRead.remove(resident);
        suppliesRead.remove(resident);
        handsOf.remove(resident);
    }

    boolean answer(ServerLevel level, ColonyData colony, Map<UUID, Body> present, Consumer<Body> seen) {
        return answer(level, colony, present, (body, supply) -> seen.accept(body));
    }

    boolean answer(ServerLevel level, ColonyData colony, Map<UUID, Body> present, BiConsumer<Body, Boolean> seen) {
        dimension = level.dimension();
        transports = () -> colony.works().transports().in(level.dimension());
        transportVersion = colony.works()::transportVersion;
        at = level.getGameTime();
        var toolRules = KitRules.snapshot(colony.works());
        suppliesRead.keySet().retainAll(present.keySet());
        for (Body body : present.values()) {
            Crew.Hand prior = hands.get(body.id());
            if (prior != null && !prior.who().routeKind().equals(body.routeKind())) {
                handsOf.add(body.id());
            }
            if (prior != null && !prior.toolRules().equals(toolRules)) {
                handsOf.add(body.id());
            }
            boolean supply = KitRules.supplies(colony.works(), body);
            Boolean supplied = suppliesRead.put(body.id(), supply);
            if (supplied == null || supplied != supply) {
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
        for (Stash stash : looks) {
            colony.inventory().changed(stash);
        }
        looks.clear();
        residentsToRead.forEach(colony.inventory()::changedResident);
        residentsToRead.clear();
        colony.inventory().advance(level, colony.works(), present);
        InventorySnapshot next = colony.inventory().snapshot();
        waitingForInventory = next.coverage().membershipPending();
        if (waitingForInventory) {
            colony.inventory().ensureObserved();
        }
        for (var provider : next.coverage().providers().entrySet()) {
            if (!provider.getValue()) {
                colony.inventory().ensureObservedProvider(provider.getKey());
                waitingForInventory = true;
            }
        }
        for (InventorySnapshot.Store store : next.stores().values()) {
            if (store.labor() && store.dimension().equals(level.dimension())
                && store.state() != InventorySnapshot.State.OBSERVED) {
                colony.inventory().ensureObserved(store.stash());
                waitingForInventory = true;
            }
        }
        for (UUID id : present.keySet()) {
            InventorySnapshot.Resident resident = next.residents().get(id);
            if (resident == null || resident.state() != InventorySnapshot.State.OBSERVED) {
                colony.inventory().ensureObservedResident(id);
                waitingForInventory = true;
            }
        }
        boolean answered = inventoryRead == null
            ? !next.stores().isEmpty() || !next.residents().isEmpty() : !inventoryRead.equals(next.version());
        inventoryRead = next.version();
        if (answered) {
            changed();
            for (var entry : next.residents().entrySet()) {
                InventorySnapshot.Resident prior = inventory == null ? null : inventory.residents().get(entry.getKey());
                if (prior == null || !prior.version().equals(entry.getValue().version())) {
                    handsOf.add(entry.getKey());
                }
            }
        }
        inventory = next;
        if (overHands) {
            handsOf.addAll(present.keySet());
            hands.keySet().retainAll(present.keySet());
        }
        ObservationBudget budget = new ObservationBudget(STEPS);
        try {
            for (UUID resident : List.copyOf(handsOf)) {
                if (budget.spent()) {
                    break;
                }
                InventorySnapshot.Resident observed = inventory.residents().get(resident);
                if (present.containsKey(resident) && (observed == null
                    || observed.state() != InventorySnapshot.State.OBSERVED)) {
                    continue;
                }
                handsOf.remove(resident);
                answered |= readHand(colony, present.get(resident), resident, seen);
            }
        } finally {
            budget.pause();
        }
        return answered;
    }

    boolean waitingForInventory() {
        return waitingForInventory;
    }

    Known known(Ways ways, List<Workshop> workshops) {
        gather();
        return new Known(new Situation(ways, stock, workshops, stands, transports, transportVersion, at),
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
        if (inventory != null) {
            for (InventorySnapshot.Store reading : inventory.stores().values()) {
                if (!reading.dimension().equals(dimension) || !reading.labor()
                    || reading.state() != InventorySnapshot.State.OBSERVED
                    || reading.slots().stream().noneMatch(slot -> slot.accessibleSlot().isPresent())) {
                    continue;
                }
                Stash where = reading.stash();
                int free = 0;
                for (SlotSnapshot slot : reading.slots()) {
                    if (slot.accessibleSlot().isEmpty()) {
                        continue;
                    }
                    ItemStack stack = slot.stackCopy();
                    if (stack.isEmpty()) {
                        free++;
                    } else {
                        holdings.add(new Stock.Holding(where, stack));
                    }
                }
                empty.put(where, free);
                rules.put(where, reading.rules());
                refused.put(where, reading.refused());
                around.container(where, reading.approach().stands());
            }
        }
        stock = new Stock(holdings, empty, rules, refused);
        stands = around.done();
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
        licencesRead.put(resident, body.licences().revision());
        equipmentRead.put(resident, Equipment.wanted(colony.works(), body));
        Kit kit = Kit.of(colony.works(), body);
        InventorySnapshot.Resident observed = inventory.residents().get(resident);
        List<ItemStack> contents = observed.pack().stream().map(SlotSnapshot::stackCopy)
            .filter(stack -> !stack.isEmpty()).toList();
        int tools = (int) contents.stream().filter(stack -> kit.holds(stack.getItem().getDefaultInstance())).count();
        List<Licence> licences = new ArrayList<>();
        for (Vocation vocation : Vocations.all()) {
            body.licences().of(vocation).ifPresent(licences::add);
        }
        Crew.Hand had = hands.put(resident, new Crew.Hand(
            body.resident(),
            Feet.of(body.mob()),
            Math.max(0, observed.pack().size() - contents.size()),
            Math.max(0, observed.pack().size() - tools) - kit.keptCells(contents),
            pace(body),
            List.copyOf(licences),
            kit.inPack(contents),
            Optional.empty(),
            kit.cargoIn(contents), KitRules.snapshot(colony.works())));
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

}
