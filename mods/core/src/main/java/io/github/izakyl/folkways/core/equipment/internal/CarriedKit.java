package io.github.izakyl.folkways.core.equipment.internal;

import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.colony.api.ColonyView;
import io.github.izakyl.folkways.core.colony.api.Equipment;
import io.github.izakyl.folkways.core.colony.api.Sweep;
import io.github.izakyl.folkways.core.combat.api.Combatant;
import io.github.izakyl.folkways.core.combat.api.Loadouts;
import io.github.izakyl.folkways.core.equipment.api.EquipmentProfile;
import io.github.izakyl.folkways.core.equipment.api.EquipmentKits;
import io.github.izakyl.folkways.core.equipment.api.EquipmentProfiles;
import io.github.izakyl.folkways.core.equipment.api.GearSlots;
import io.github.izakyl.folkways.core.front.api.Facing;
import io.github.izakyl.folkways.core.inventory.api.InventorySnapshot;
import io.github.izakyl.folkways.core.inventory.api.SlotSnapshot;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.plan.api.Delivery;
import io.github.izakyl.folkways.core.plan.api.Grown;
import io.github.izakyl.folkways.core.resident.api.Bodies;
import io.github.izakyl.folkways.core.resident.api.Body;
import io.github.izakyl.folkways.core.resident.api.KitCapabilities;
import io.github.izakyl.folkways.core.resident.api.KitProviders;
import io.github.izakyl.folkways.core.work.api.Amount;
import io.github.izakyl.folkways.core.work.api.Ending;
import io.github.izakyl.folkways.core.work.api.Need;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.NodeSpec;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.Stances;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.api.Workload;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

/**
 * Personal equipment supplied by the ordinary fetch planner, independently of patrol duty. An entry starts once
 * fewer than its threshold are held and keeps asking until it is full; the ordinary fetch planner supplies it. Units
 * removed from native child slots return to the pack, from where ordinary hauling returns them.
 */
public final class CarriedKit implements Facing {
    record EntryKey(UUID resident, UUID entry) {
    }

    private final Colony colony;
    private final ProfileLedger profiles;
    private final Sweep sweep;
    private final Map<EntryKey, Node> asked = new LinkedHashMap<>();
    private final Set<EntryKey> refilling = new HashSet<>();

    CarriedKit(Colony colony, ProfileLedger profiles) {
        this.colony = colony;
        this.profiles = profiles;
        this.sweep = new Sweep(colony, EquipmentProfiles.ID, 20, (node, how) ->
            asked.values().removeIf(ask -> ask.id().equals(node)));
    }

    void invalidate(UUID resident) {
        asked.keySet().removeIf(key -> key.resident().equals(resident));
        refilling.removeIf(key -> key.resident().equals(resident));
        sweep.nudge();
    }

    void tick(MinecraftServer server) {
        sweep.tick(server, this::refresh, this::goals);
    }

    private void refresh(MinecraftServer server) {
        List<UUID> roster = colony.residents().stream().map(resident -> resident.id()).toList();
        profiles.retain(roster);
        asked.keySet().removeIf(key -> !roster.contains(key.resident()));
        refilling.removeIf(key -> !roster.contains(key.resident()));
        for (ColonyView view : colony.views(server)) {
            for (var resident : view.residents()) {
                Optional<Body> loaded = Optional.ofNullable(view.level().getEntity(resident.id())).flatMap(Bodies::of);
                if (loaded.isEmpty() || !loaded.get().mob().isAlive()) {
                    continue;
                }
                Body body = loaded.get();
                reconcile(body);
                pickup(body);
            }
        }
    }

    static boolean supplies(EquipmentProfile.Entry entry) {
        return entry.slot().isEmpty() || entry.gear().filter(gear -> gear.personal()).isPresent();
    }

    static List<EquipmentProfile.Entry> entries(Optional<EquipmentProfile> profile) {
        return profile.stream().flatMap(value -> value.entries().stream()
            .filter(entry -> active(value, entry))).filter(CarriedKit::supplies).toList();
    }

    static boolean active(EquipmentProfile profile, EquipmentProfile.Entry entry) {
        var root = entry;
        Set<UUID> seen = new HashSet<>();
        while (root.parent().isPresent()) {
            if (!seen.add(root.id())) {
                return false;
            }
            var parent = profile.entry(root.parent().orElseThrow());
            if (parent.isEmpty()) {
                return false;
            }
            root = parent.get();
        }
        return !root.slotted() || root.gear().isPresent();
    }

    static List<Equipment.Demand> armor(Colony colony, Body body) {
        return entries(colony.service(EquipmentProfiles.ID, EquipmentProfiles.class)
            .flatMap(profiles -> profiles.assigned(body.id()))).stream()
            .filter(entry -> entry.slot().flatMap(GearSlots::equipment).filter(Equipment::wearable).isPresent())
            .map(entry -> new Equipment.Demand(GearSlots.equipment(entry.slot().orElseThrow()).orElseThrow(),
                entry.items(), false)).toList();
    }

    static List<Amount> keeps(Colony colony, Body body) {
        List<EquipmentProfile.Entry> entries = entries(colony.service(EquipmentProfiles.ID, EquipmentProfiles.class)
            .flatMap(profiles -> profiles.assigned(body.id()))).stream()
            .toList();
        InventorySnapshot.Resident resident = colony.inventory().snapshot().residents().get(body.id());
        if (resident == null || resident.state() != InventorySnapshot.State.OBSERVED) {
            colony.inventory().ensureObservedResident(body.id());
            return entries.stream().map(entry -> new Amount(entry.items(), entry.target())).toList();
        }
        List<ItemStack> available = new ArrayList<>(resident.pack().stream().map(SlotSnapshot::stackCopy).toList());
        ItemStack weapon = Combatant.get().engaged(body) ? body.mob().getMainHandItem().copy() : ItemStack.EMPTY;
        List<ItemStack> loaded = new ArrayList<>();
        List<ItemStack> weapons = new ArrayList<>(available);
        weapons.add(weapon);
        for (ItemStack stack : weapons) {
            Loadouts.using(stack).ifPresent(provider -> provider.loadedAmmunition(stack)
                .forEach(rounds -> loaded.add(rounds.copy())));
        }
        List<Amount> kept = new ArrayList<>();
        for (EquipmentProfile.Entry entry : entries) {
            if (entry.parent().isPresent()) {
                continue;
            }
            int left = entry.target();
            if (entry.gear().filter(gear -> entry.matches(gear.worn(body))).isPresent()) {
                left--;
            }
            if (left > 0 && entry.matches(weapon)) {
                int used = Math.min(left, weapon.getCount());
                weapon.shrink(used);
                left -= used;
            }
            if (!entry.slotted()) {
                for (ItemStack rounds : loaded) {
                    if (left > 0 && entry.matches(rounds)) {
                        int used = Math.min(left, rounds.getCount());
                        rounds.shrink(used);
                        left -= used;
                    }
                }
            }
            for (ItemStack stack : available) {
                if (left > 0 && entry.matches(stack)) {
                    int used = Math.min(left, stack.getCount());
                    kept.add(new Amount(ItemSpec.exact(stack), used));
                    stack.shrink(used);
                    left -= used;
                }
            }
        }
        return List.copyOf(kept);
    }

    static Map<UUID, Integer> deficits(Body body, List<EquipmentProfile.Entry> entries) {
        Map<UUID, Integer> missing = new LinkedHashMap<>();
        var physical = EquipmentKits.profile(body).map(profile -> EquipmentKits.parts(body, profile)).orElse(Map.of());
        List<ItemStack> available = new ArrayList<>(body.pack().contents());
        if (Combatant.get().engaged(body)) {
            available.add(body.mob().getMainHandItem().copy());
        }
        List<ItemStack> loaded = new ArrayList<>();
        for (ItemStack stack : available) {
            Loadouts.using(stack).ifPresent(provider -> provider.loadedAmmunition(stack)
                .forEach(rounds -> loaded.add(rounds.copy())));
        }
        // Loaded rounds satisfy carried supplies, but never a physical equipment slot.
        for (EquipmentProfile.Entry entry : entries) {
            if (entry.parent().isPresent()) {
                int held = physical.getOrDefault(entry.id(), List.of()).stream().mapToInt(part -> part.stack()
                    .getCount()).sum();
                missing.put(entry.id(), Math.max(0, entry.target() - held));
                continue;
            }
            int left = entry.target();
            if (entry.gear().filter(gear -> entry.matches(gear.worn(body)) && gear.fits(body, gear.worn(body)))
                .isPresent()) {
                left--;
            }
            for (ItemStack stack : available) {
                if (left > 0 && entry.matches(stack)) {
                    int used = Math.min(left, stack.getCount());
                    stack.shrink(used);
                    left -= used;
                }
            }
            if (!entry.slotted()) {
                for (ItemStack stack : loaded) {
                    if (left > 0 && entry.matches(stack)) {
                        int used = Math.min(left, stack.getCount());
                        stack.shrink(used);
                        left -= used;
                    }
                }
            }
            missing.put(entry.id(), left);
        }
        return Map.copyOf(missing);
    }

    void reconcile(Body body) {
        List<EquipmentProfile.Entry> entries = entries(profiles.assigned(body.id()));
        InstalledItems.reconcile(body, profiles.assigned(body.id()).orElseGet(() ->
            new EquipmentProfile(body.id(), "Unassigned", List.of())));
        Equipment.reconcile(colony, body);
        NamedEquipment.reconcile(body, entries);
        Map<UUID, Integer> deficits = deficits(body, entries);
        body.refreshEffects();
        for (EquipmentProfile.Entry entry : entries) {
            EntryKey key = new EntryKey(body.id(), entry.id());
            int missing = deficits.get(entry.id());
            if (missing == 0) {
                refilling.remove(key);
            } else if (entry.target() - missing < entry.threshold()) {
                refilling.add(key);
            }
        }
        asked.keySet().removeIf(key -> key.resident().equals(body.id()) && !refilling.contains(key));
        List<ItemSpec> awaitingAmmunition = entries.stream().filter(entry -> entry.parent().isEmpty()
                && deficits.get(entry.id()) > 0).flatMap(entry -> EquipmentProfile.samples(entry.items()).stream())
            .flatMap(stack -> Loadouts.using(stack).flatMap(provider -> provider.ammunition(stack)).stream()).toList();
        for (EquipmentProfile.Entry entry : entries) {
            EntryKey key = new EntryKey(body.id(), entry.id());
            int missing = deficits.get(entry.id());
            if (!entry.slotted() && entry.parent().isEmpty() && EquipmentProfile.samples(entry.items()).stream()
                .anyMatch(stack -> awaitingAmmunition.stream().anyMatch(ammo -> Goods.matches(stack, ammo)))) {
                continue;
            }
            boolean blocked = entry.slot().flatMap(GearSlots::equipment).filter(Equipment::wearable)
                .filter(slot -> !body.mob().getItemBySlot(slot).isEmpty()
                    && !Equipment.managed(body, slot) && !entry.matches(body.mob().getItemBySlot(slot))).isPresent();
            boolean parentReady = entry.parent().isEmpty() || profiles.assigned(body.id())
                .map(profile -> EquipmentKits.parts(body, profile).containsKey(entry.parent().orElseThrow()))
                    .orElse(false);
            boolean fitsResident = entry.gear().map(gear -> EquipmentProfile.samples(entry.items()).stream()
                .anyMatch(stack -> gear.fits(body, stack))).orElse(true);
            if (refilling.contains(key) && missing > 0 && entry.available() && !blocked && parentReady
                && fitsResident) {
                asked.computeIfAbsent(key, ignored -> receipt(body, entry, missing, key));
            }
        }
    }

    static Map<UUID, Integer> allocate(List<EquipmentProfile.Entry> entries, List<ItemStack> available) {
        Map<UUID, Integer> missing = new LinkedHashMap<>();
        for (EquipmentProfile.Entry entry : entries) {
            int left = entry.target();
            for (ItemStack stack : available) {
                if (left > 0 && entry.matches(stack)) {
                    int used = Math.min(left, stack.getCount());
                    stack.shrink(used);
                    left -= used;
                }
            }
            missing.put(entry.id(), left);
        }
        return Map.copyOf(missing);
    }

    boolean supplying(UUID resident, Collection<UUID> route) {
        return asked.entrySet().stream().anyMatch(entry -> entry.getKey().resident().equals(resident)
            && route.contains(entry.getValue().id()));
    }

    /** Residents with a delivery under way, per entry. */
    Map<UUID, Integer> inTransit(Set<UUID> residents) {
        Map<UUID, Integer> counts = new LinkedHashMap<>();
        for (EntryKey key : asked.keySet()) {
            if (residents.contains(key.resident())) {
                counts.merge(key.entry(), 1, Integer::sum);
            }
        }
        return counts;
    }

    private ItemSpec planned(Body body, EquipmentProfile.Entry entry) {
        List<ItemStack> seen = colony.inventory().snapshot().stores().values().stream()
            .filter(store -> store.dimension().equals(body.mob().level().dimension()) && store.usability().usable())
            .flatMap(store -> store.entries().stream()).map(held -> held.prototype())
            .filter(stack -> Goods.matches(stack, entry.items())).toList();
        java.util.function.Predicate<ItemStack> fits = stack -> entry.matches(stack)
            && entry.gear().map(gear -> gear.fits(body, stack)).orElse(true);
        if (seen.stream().allMatch(fits)) {
            return entry.items();
        }
        List<ItemSpec> usable = seen.stream().filter(fits).map(ItemSpec::exact).distinct().toList();
        return usable.isEmpty() ? entry.items() : usable.size() == 1 ? usable.getFirst() : ItemSpec.anyOf(usable);
    }

    private Node receipt(Body body, EquipmentProfile.Entry entry, int count, EntryKey key) {
        if (entry.parent().isEmpty()) {
            // A pack delivery accounts for stock already in the pack, including protected personal goods.
            return new Delivery(NodeSpec.of(UUID.randomUUID(), EquipmentProfiles.ID, WorkSite.on(body.mob()),
                Stances.WHEREVER, Workload.Once.of(1)).doing(EquipmentProfiles.ID, entry.items()).done(),
                new Need(planned(body, entry), count, true), supplied -> { });
        }
        UUID profile = profiles.assigned(body.id()).orElseThrow().id();
        long revision = profiles.revision(profile);
        NodeSpec spec = NodeSpec.of(UUID.randomUUID(), EquipmentProfiles.ID, WorkSite.on(body.mob()),
            Stances.WHEREVER, Workload.Once.of(0)).needs(new Need(planned(body, entry), count, true))
            .doing(EquipmentProfiles.ID, entry.items()).done();
        return new Node() {
            @Override
            public NodeSpec spec() {
                return spec;
            }

            @Override
            public Optional<UUID> worker() {
                return Optional.of(body.id());
            }

            @Override
            public boolean permitsProduction(ItemSpec input) {
                return false;
            }

            @Override
            public Outcome commit(ServerLevel level, Worker who) {
                List<ItemStack> returned = new ArrayList<>();
                boolean valid = who.resident().id().equals(body.id())
                    && body.colonyId().filter(colony.id()::equals).isPresent()
                    && profiles.assigned(body.id()).filter(held -> held.id().equals(profile)).isPresent()
                    && profiles.revision(profile) == revision && entry.available();
                int missing = valid ? deficits(body, entries(profiles.get(profile)))
                    .getOrDefault(entry.id(), 0) : 0;
                for (ItemStack supplied : who.supplied()) {
                    ItemStack rest = supplied.copy();
                    int taken = Math.min(missing, rest.getCount());
                    if (taken > 0 && entry.matches(rest)) {
                        ItemStack refused = InstalledItems.install(body, profiles.assigned(body.id()).orElseThrow(),
                            entry, rest.copyWithCount(taken));
                        int kept = taken - refused.getCount();
                        rest.shrink(kept);
                        missing -= kept;
                    }
                    if (!rest.isEmpty()) {
                        returned.add(rest);
                    }
                }
                body.refreshEffects();
                return new Outcome.Done(List.of(), List.of(), Optional.empty(), returned);
            }

            @Override
            public void ended(ServerLevel level, Ending how) {
                asked.remove(key, this);
                sweep.nudge();
            }
        };
    }

    List<Grown> goals(ColonyView view) {
        return asked.values().stream()
            .filter(node -> view.level().getEntity(((WorkSite.AtEntity) node.spec().site()).entity()) != null)
            .map(Grown::of).toList();
    }

    private static void pickup(Body body) {
        double radius = Math.min(16, KitProviders.capabilities(body).ability(KitCapabilities.PICKUP_RADIUS));
        if (radius <= 0 || !body.mob().isAlive()) {
            return;
        }
        for (ItemEntity entity : body.mob().level().getEntitiesOfClass(ItemEntity.class,
                body.mob().getBoundingBox().inflate(radius), item -> item.isAlive() && !item.hasPickUpDelay())) {
            ItemStack rest = body.pack().insert(entity.getItem());
            if (rest.getCount() == entity.getItem().getCount()) {
                continue;
            }
            if (rest.isEmpty()) {
                entity.discard();
            } else {
                entity.setItem(rest);
            }
        }
    }

    List<Component> details(Body body) {
        List<Component> lines = new ArrayList<>();
        profiles.assigned(body.id()).ifPresent(profile -> lines.add(
            Component.translatable("folkways.equipment.profile", profile.name())));
        for (var group : io.github.izakyl.folkways.core.equipment.api.GearSlots.groups()) {
            List<ItemStack> stacks = GearSlots.in(group.id()).stream().map(gear -> gear.worn(body))
                .filter(stack -> !stack.isEmpty()).toList();
            if (!stacks.isEmpty()) {
                lines.add(group.name());
                for (ItemStack stack : stacks) {
                    lines.add(Component.translatable("folkways.equipment.held", stack.getCount(),
                        stack.getHoverName()));
                }
            }
        }
        lines.add(Component.translatable("folkways.equipment.inventory_cells", body.pack().ownCells(),
            body.pack().getContainerSize() - body.pack().ownCells()));
        for (ItemStack stack : body.pack().contents()) {
            lines.add(Component.translatable("folkways.equipment.held", stack.getCount(), stack.getHoverName()));
        }
        KitCapabilities capabilities = KitProviders.capabilities(body);
        capabilities.abilities().forEach((id, value) -> lines.add(Component.translatable(
            "folkways.equipment.ability", Component.translatable("folkways.kits.ability." + id.getPath()), value)));
        capabilities.workstations().forEach(station -> lines.add(Component.translatable(
            "folkways.equipment.workstation", station.station().getName())));
        return List.copyOf(lines);
    }
}
