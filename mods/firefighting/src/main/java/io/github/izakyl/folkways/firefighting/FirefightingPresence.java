package io.github.izakyl.folkways.firefighting;

import com.mikoalopex.createfirefightingadd.CreateFireFightingAdd;
import com.mikoalopex.createfirefightingadd.content.equipment.extinguisher.FireExtinguisherItem;
import io.github.izakyl.folkways.core.api.colony.Closing;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.ColonyView;
import io.github.izakyl.folkways.core.api.colony.Sweep;
import io.github.izakyl.folkways.core.api.resident.body.Bodies;
import io.github.izakyl.folkways.core.api.resident.body.Body;
import io.github.izakyl.folkways.core.api.resident.body.TaskTools;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.Reach;
import io.github.izakyl.folkways.core.api.terms.StoreRule;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Amount;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Need;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.front.api.Fronts;
import io.github.izakyl.folkways.front.api.ZoneView;
import io.github.izakyl.folkways.plugins.damagecontrol.DamageControlContent;
import io.github.izakyl.folkways.plugins.damagecontrol.DamageControlRefusal;
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

final class FirefightingPresence {
    private final Colony colony;
    private final Sweep sweep;
    private final ExtinguisherAdapter method;
    private final Map<UUID, UUID> loans = new LinkedHashMap<>();
    private final Map<UUID, Node> chores = new LinkedHashMap<>();
    private boolean closed;

    FirefightingPresence(Colony colony, ExtinguisherAdapter method) {
        this.colony = colony;
        this.method = method;
        sweep = new Sweep(colony, FolkwaysFirefightingMod.OWNER, 100);
    }

    void tick(MinecraftServer server) {
        sweep.tick(server, this::refresh, this::goals);
    }

    private void refresh(MinecraftServer server) {
        Set<UUID> present = new LinkedHashSet<>();
        Set<WorldPos> sources = new LinkedHashSet<>();
        for (ColonyView view : colony.views(server)) {
            ServerLevel level = view.level();
            List<? extends ZoneView> zones = Fronts.of(colony).zonesIn(level).stream().filter(zone ->
                zone.delegation().equals(DamageControlContent.ID) && zone.settings().flag("enabled")
                    && zone.settings().flag("refill")).toList();
            if (zones.isEmpty()) {
                continue;
            }
            method.sources(colony.id()).stream().filter(at -> at.in(level) && level.isLoaded(at.block(level))
                && zones.stream().anyMatch(zone -> zone.cells().contains(at.block(level))
                    && zone.at(at.block(level)).equals(at))).forEach(sources::add);
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
                ItemStack extinguisher = ExtinguisherAdapter.find(body.get().pack());
                if (!extinguisher.isEmpty()) {
                    UUID loan = loans.computeIfAbsent(resident.id(), id -> UUID.randomUUID());
                    TaskTools.keep(loan, resident.id(), item -> item.is(CreateFireFightingAdd.FIRE_EXTINGUISHER_ITEM.get()));
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
                    Optional<Stances> stances = Stances.of(level, Reach.workableCells(level, source.block(level)));
                    if (stances.isEmpty()) {
                        continue;
                    }
                    if (extinguisher.isEmpty()) {
                        var store = colony.store(level, source);
                        if (store.isEmpty() || ExtinguisherAdapter.find(store.get()).isEmpty()) {
                            continue;
                        }
                        chores.put(resident.id(), borrow(resident.id(), source, stances.get()));
                    } else {
                        if (!RefillNode.canSupply(level, source, colony.store(level, source).orElse(null))) {
                            continue;
                        }
                        chores.put(resident.id(), new RefillNode(resident.id(), source, stances.get(),
                            () -> !closed && present(resident.id(), level) && StoreRule.takes(colony.storeRules(source))));
                    }
                    break;
                }
                if (!extinguisher.isEmpty() && !chores.containsKey(resident.id())
                        && FireExtinguisherItem.CAPACITY - FireExtinguisherItem.getFluid(extinguisher).getAmount() >= 1000) {
                    for (WorldPos source : method.sources(colony.id())) {
                        if (!source.in(level) || !level.isLoaded(source.block(level))
                                || !level.getBlockState(source.block(level)).is(Blocks.WATER)
                                || zones.stream().noneMatch(zone -> zone.cells().contains(source.block(level))
                                    && zone.at(source.block(level)).equals(source))) {
                            continue;
                        }
                        Optional<Stances> stances = Stances.of(level, Reach.workableCells(level, source.block(level)));
                        if (stances.isPresent()) {
                            chores.put(resident.id(), new RefillNode(resident.id(), source, stances.get(),
                                () -> !closed && present(resident.id(), level)
                                    && Fronts.of(colony).zonesIn(level).stream().anyMatch(zone ->
                                        zone.delegation().equals(DamageControlContent.ID) && zone.settings().flag("enabled")
                                            && zone.settings().flag("refill") && zone.cells().contains(source.block(level))
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

    private boolean present(UUID resident, ServerLevel level) {
        return level.getEntity(resident) instanceof LivingEntity living
            && living.isAlive() && Bodies.of(living).flatMap(body -> body.licences().of(DamageControlContent.trade())).isPresent()
            && Fronts.of(colony).zonesIn(level).stream().anyMatch(zone -> zone.delegation().equals(DamageControlContent.ID)
                && zone.settings().flag("enabled") && zone.settings().flag("refill"));
    }

    private Node borrow(UUID resident, WorldPos source, Stances stances) {
        var item = ItemSpec.of(BuiltInRegistries.ITEM
            .getKey(CreateFireFightingAdd.FIRE_EXTINGUISHER_ITEM.get()));
        NodeSpec spec = NodeSpec.of(UUID.randomUUID(), FolkwaysFirefightingMod.OWNER,
                new WorkSite.AtBlock(source), stances, Workload.Once.of(10))
            .needs(new Need(item, 1)).leaves(List.of(new Amount(item, 1)))
            .vocation(DamageControlContent.trade()).doing(DamageControlContent.REFILLING).done();
        return new Node() {
            public NodeSpec spec() { return spec; }
            public Optional<UUID> worker() { return Optional.of(resident); }
            public boolean permitsProduction(ItemSpec input) { return false; }
            public boolean ready(ServerLevel level) { return !closed && present(resident, level); }
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
                public NodeSpec spec() { return node.spec(); }
                public Optional<UUID> worker() { return node.worker(); }
                public boolean permitsProduction(ItemSpec input) { return false; }
                public boolean ready(ServerLevel level) { return node.ready(level); }
                public Outcome commit(ServerLevel level, Worker who) { return node.commit(level, who); }
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
        method.forget(colony.id());
        loans.values().forEach(TaskTools::release);
        loans.clear();
        chores.clear();
    }
}
