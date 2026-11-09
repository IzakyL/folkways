package io.github.izakyl.folkways.create.firefighting;

import com.mikoalopex.createfirefightingadd.CreateFireFightingAdd;
import com.mikoalopex.createfirefightingadd.content.equipment.extinguisher.FireExtinguisherItem;
import io.github.izakyl.folkways.core.colony.api.Closing;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.colony.api.ColonyView;
import io.github.izakyl.folkways.core.colony.api.Sweep;
import io.github.izakyl.folkways.core.inventory.api.InventorySnapshot;
import io.github.izakyl.folkways.core.inventory.api.Stash;
import io.github.izakyl.folkways.core.inventory.api.StoreRule;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.plan.api.Grown;
import io.github.izakyl.folkways.core.resident.api.Bodies;
import io.github.izakyl.folkways.core.resident.api.Body;
import io.github.izakyl.folkways.core.resident.api.TaskTools;
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
import io.github.izakyl.folkways.core.world.api.Stand;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.front.api.Fronts;
import io.github.izakyl.folkways.core.front.api.ZoneView;
import io.github.izakyl.folkways.vanilla.damagecontrol.DamageControlContent;
import io.github.izakyl.folkways.vanilla.damagecontrol.DamageControlRefusal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;

final class FirefightingPresence {
    private final Colony colony;
    private final Sweep sweep;
    private final ExtinguisherAdapter method;
    private final FireStock stock;
    private int tankTurn;
    private MinecraftServer server;
    private final Map<UUID, UUID> loans = new LinkedHashMap<>();
    private final Map<UUID, Node> chores = new LinkedHashMap<>();
    private boolean closed;

    FirefightingPresence(Colony colony, ExtinguisherAdapter method) {
        this.colony = colony;
        this.method = method;
        stock = new FireStock(colony);
        method.bind(colony, stock);
        sweep = new Sweep(colony, FirefightingIntegration.OWNER, 100);
    }

    void tick(MinecraftServer server) {
        this.server = server;
        sweep.tick(server, this::refresh, this::goals);
        for (UUID resident : loans.keySet()) {
            for (ServerLevel level : server.getAllLevels()) {
                var entity = level.getEntity(resident);
                if (entity == null) {
                    continue;
                }
                Bodies.of(entity).filter(body -> body.doing().filter(doing ->
                    doing.what().equals(DamageControlContent.EXTINGUISHING)
                        || doing.toward().filter(DamageControlContent.EXTINGUISHING::equals).isPresent())
                    .isPresent()).ifPresent(body -> {
                        ItemStack tool = stock.resident(resident).orElse(ItemStack.EMPTY);
                        var water = FireExtinguisherItem.getFluid(tool);
                        if (!HoseSessions.owns(resident)
                            && !body.mob().getMainHandItem().is(
                                CreateFireFightingAdd.HANDHELD_NOZZLE_CONTROLLER_ITEM.get())
                            && water.is(Fluids.WATER) && !water.isEmpty()) {
                            body.presentHeldItem(tool.copy());
                        }
                    });
            }
        }
    }

    private void refresh(MinecraftServer server) {
        Set<UUID> present = new LinkedHashSet<>();
        Set<WorldPos> sources = new LinkedHashSet<>();
        for (ColonyView view : colony.views(server)) {
            ServerLevel level = view.level();
            List<? extends ZoneView> zones = Fronts.of(colony).zonesIn(level).stream()
                .filter(zone -> zone.delegation().equals(DamageControlContent.ID)
                    && zone.settings().flag("refill"))
                .toList();
            if (zones.isEmpty()) {
                colony.inventory().observe(FirefightingIntegration.OWNER, level, List.of());
                continue;
            }
            method.sources(colony.id()).stream()
                .filter(at -> at.in(level) && level.isLoaded(at.block(level))
                    && zones.stream().anyMatch(zone -> zone.cells().contains(at.block(level))
                        && zone.at(at.block(level)).equals(at)))
                .forEach(sources::add);
            colony.inventory().observe(FirefightingIntegration.OWNER, level, method.sources(colony.id()).stream()
                .filter(at -> at.in(level)).toList());
            InventorySnapshot snapshot = colony.inventory().snapshot();
            List<WorldPos> offered = colony.storesIn(view.dimension());
            Set<WorldPos> tanks = new LinkedHashSet<>();
            int examined = Math.min(64, offered.size());
            for (int index = 0; index < examined; index++) {
                WorldPos source = offered.get(Math.floorMod(tankTurn + index, offered.size()));
                if (source.in(level) && level.isLoaded(source.block(level))
                    && RefillNode.canSupply(level, source)) {
                    tanks.add(source);
                }
            }
            tankTurn += examined;
            for (var resident : view.residents()) {
                var entity = level.getEntity(resident.id());
                if (!(entity instanceof LivingEntity living) || !living.isAlive()) {
                    continue;
                }
                Optional<Body> body = Bodies.of(living);
                if (body.isEmpty() || body.get().licences().of(DamageControlContent.trade()).isEmpty()) {
                    continue;
                }
                present.add(resident.id());
                Optional<ItemStack> observed = stock.resident(resident.id());
                if (observed.isEmpty()) {
                    continue;
                }
                ItemStack extinguisher = observed.get();
                if (!extinguisher.isEmpty()) {
                    UUID loan = loans.computeIfAbsent(resident.id(), id -> UUID.randomUUID());
                    TaskTools.keep(loan, resident.id(),
                        item -> item.is(CreateFireFightingAdd.FIRE_EXTINGUISHER_ITEM.get()));
                }
                if (chores.containsKey(resident.id()) || !extinguisher.isEmpty()
                    && FireExtinguisherItem.getFluid(extinguisher).getAmount() > 250) {
                    continue;
                }
                for (WorldPos source : colony.storesIn(view.dimension())) {
                    if (!source.in(level) || !level.isLoaded(source.block(level))
                        || !StoreRule.takes(colony.storeRules(source))) {
                        continue;
                    }
                    InventorySnapshot.Store store = snapshot.store(source).orElse(null);
                    if (store == null || store.state() != InventorySnapshot.State.OBSERVED
                        && !(tanks.contains(source) && !extinguisher.isEmpty())) {
                        colony.inventory().ensureObserved(Stash.at(source));
                        continue;
                    }
                    Optional<Stances> stances = approach(store);
                    if (stances.isEmpty()) {
                        continue;
                    }
                    if (extinguisher.isEmpty()) {
                        if (!stock.extinguisher(snapshot, store).orElse(false)) {
                            continue;
                        }
                        chores.put(resident.id(), borrow(resident.id(), source, stances.get()));
                    } else {
                        if (!tanks.contains(source) && !stock.water(snapshot, store).orElse(false)) {
                            continue;
                        }
                        chores.put(resident.id(), new RefillNode(resident.id(), source, stances.get(),
                            () -> !closed && present(resident.id(), level)
                            && StoreRule.takes(colony.storeRules(source)), false, () -> {
                                colony.inventoryFeedback().stale(store.stash(), store.version());
                                var pack = snapshot.residents().get(resident.id());
                                if (pack != null) {
                                    colony.inventoryFeedback().staleResident(resident.id(), pack.version());
                                }
                            }));
                    }
                    break;
                }
                if (!extinguisher.isEmpty() && !chores.containsKey(resident.id())
                    && FireExtinguisherItem.CAPACITY
                    - FireExtinguisherItem.getFluid(extinguisher).getAmount() >= 1000) {
                    for (WorldPos source : method.sources(colony.id())) {
                        if (!source.in(level) || !level.isLoaded(source.block(level))
                            || !level.getBlockState(source.block(level)).is(Blocks.WATER)
                            || zones.stream().noneMatch(zone -> zone.cells().contains(source.block(level))
                                && zone.at(source.block(level)).equals(source))) {
                            continue;
                        }
                        Optional<Stances> stances = snapshot.store(source).flatMap(FirefightingPresence::approach);
                        if (stances.isPresent()) {
                            chores.put(resident.id(), new RefillNode(resident.id(), source, stances.get(),
                                () -> !closed && present(resident.id(), level)
                                && Fronts.of(colony).zonesIn(level).stream()
                                .anyMatch(zone -> zone.delegation().equals(DamageControlContent.ID)
                                    && zone.settings().flag("refill")
                                    && zone.cells().contains(source.block(level))
                                    && zone.at(source.block(level)).equals(source)), true));
                            break;
                        }
                    }
                }
            }
        }
        method.retain(colony.id(), sources);
        loans.entrySet().removeIf(entry -> {
            if (present.contains(entry.getKey())) {
                return false;
            }
            TaskTools.release(entry.getValue());
            return true;
        });
        chores.keySet().retainAll(present);
    }

    private static Optional<Stances> approach(InventorySnapshot.Store store) {
        return store.approach().state() == InventorySnapshot.State.OBSERVED
            ? Stances.of(store.approach().stands().stream().map(Stand::cell).toList()) : Optional.empty();
    }

    private boolean present(UUID resident, ServerLevel level) {
        return level.getEntity(resident) instanceof LivingEntity living
            && living.isAlive() && Bodies.of(living)
            .flatMap(body -> body.licences().of(DamageControlContent.trade())).isPresent()
            && Fronts.of(colony).zonesIn(level).stream()
            .anyMatch(zone -> zone.delegation().equals(DamageControlContent.ID)
                && zone.settings().flag("refill"));
    }

    private Node borrow(UUID resident, WorldPos source, Stances stances) {
        var item = ItemSpec.of(BuiltInRegistries.ITEM
            .getKey(CreateFireFightingAdd.FIRE_EXTINGUISHER_ITEM.get()));
        NodeSpec spec = NodeSpec.of(UUID.randomUUID(), FirefightingIntegration.OWNER,
            new WorkSite.AtBlock(source), stances, Workload.Once.of(10))
            .needs(new Need(item, 1))
            .leaves(List.of(new Amount(item, 1)))
            .vocation(DamageControlContent.trade())
            .doing(DamageControlContent.REFILLING)
            .done();
        return new Node() {
            public NodeSpec spec() {
                return spec;
            }

            public Optional<UUID> worker() {
                return Optional.of(resident);
            }

            public boolean permitsProduction(ItemSpec input) {
                return false;
            }

            public boolean ready(ServerLevel level) {
                return !closed && present(resident, level);
            }

            public Outcome commit(ServerLevel level, Worker who) {
                if (!ready(level)) {
                    return Outcome.failed(DamageControlRefusal.NOT_LOADED);
                }
                UUID loan = loans.computeIfAbsent(resident, id -> UUID.randomUUID());
                TaskTools.keep(loan, resident, stack -> stack.is(CreateFireFightingAdd.FIRE_EXTINGUISHER_ITEM.get()));
                return new Outcome.Done(List.of(), who.supplied(), Optional.empty());
            }
        };
    }

    List<Grown> goals(ColonyView view) {
        List<Grown> grown = new ArrayList<>();
        for (var entry : new ArrayList<>(chores.entrySet())) {
            Node node = entry.getValue();
            if (!node.spec().site().where().in(view.level())) {
                continue;
            }
            // One finite supply task per worker; the next sweep rechecks the physical item.
            grown.add(Grown.of(new Node() {
                public NodeSpec spec() {
                    return node.spec();
                }

                public Optional<UUID> worker() {
                    return node.worker();
                }

                public boolean permitsProduction(ItemSpec input) {
                    return false;
                }

                public boolean ready(ServerLevel level) {
                    return node.ready(level);
                }

                public Outcome commit(ServerLevel level, Worker who) {
                    return node.commit(level, who);
                }

                public void ended(ServerLevel level, Ending how) {
                    chores.remove(entry.getKey(), node);
                    sweep.nudge();
                }
            }));
        }
        return grown;
    }

    void closed(Closing why) {
        closed = true;
        if (server != null) {
            for (ServerLevel level : server.getAllLevels()) {
                colony.inventory().observe(FirefightingIntegration.OWNER, level, List.of());
            }
        }
        method.forget(colony.id());
        loans.values().forEach(TaskTools::release);
        loans.clear();
        chores.clear();
    }
}
