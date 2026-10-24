package io.github.izakyl.folkways.plugins.damagecontrol;

import io.github.izakyl.folkways.base.persist.Reader;
import io.github.izakyl.folkways.base.persist.Writer;
import io.github.izakyl.folkways.core.api.colony.Closing;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.ColonyView;
import io.github.izakyl.folkways.core.api.colony.Sweep;
import io.github.izakyl.folkways.core.api.resident.body.Bodies;
import io.github.izakyl.folkways.core.api.terms.Footing;
import io.github.izakyl.folkways.core.api.terms.Reach;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Stall;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.Urge;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.core.shell.structure.StructureSnapshots;
import io.github.izakyl.folkways.front.api.Facing;
import io.github.izakyl.folkways.front.api.FrontView;
import io.github.izakyl.folkways.front.api.Fronts;
import io.github.izakyl.folkways.front.api.ZoneView;
import io.github.izakyl.folkways.front.api.notice.Attempt;
import io.github.izakyl.folkways.front.api.notice.Line;
import io.github.izakyl.folkways.front.api.panel.Board;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaConfig;
import io.github.izakyl.folkways.plugins.build.MaintenanceOrders;
import io.github.izakyl.folkways.plugins.build.SchematicPlacement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

final class DamageControlPresence implements Facing {
    private final Colony colony;
    private final Sweep sweep;
    private final Map<ResourceKey<Level>, FireSurvey> surveys = new LinkedHashMap<>();
    private final Incidents incidents = new Incidents();
    private final Map<UUID, StructureSnapshots.Snapshot> baselines = new LinkedHashMap<>();
    private final Map<UUID, Long> revisions = new LinkedHashMap<>();
    private final Map<UUID, ZoneView> zones = new LinkedHashMap<>();
    private final Map<UUID, Map<WorldPos, BlockState>> missing = new LinkedHashMap<>();
    private final Map<UUID, Set<WorldPos>> conflicts = new LinkedHashMap<>();
    private final Map<WorldPos, Node> repairs = new LinkedHashMap<>();
    private final Map<UUID, Node> watches = new LinkedHashMap<>();
    private final Map<UUID, Long> duty = new LinkedHashMap<>();
    private final Map<WorldPos, DamageControlRefusal> refusals = new LinkedHashMap<>();
    private boolean closed;

    DamageControlPresence(Colony colony) {
        this.colony = colony;
        sweep = new Sweep(colony, DamageControlContent.ID, 100);
        Reader saved = Reader.of(colony.kept(DamageControlContent.ID));
        for (Reader entry : saved.children("baselines")) {
            entry.uuid("zone").ifPresent(id -> entry.blob("snapshot").flatMap(StructureSnapshots::load)
                .ifPresent(snapshot -> {
                    baselines.put(id, snapshot);
                    revisions.put(id, entry.longValue("revision").orElse(1L));
                }));
        }
    }

    void tick(MinecraftServer server) {
        List<ColonyView> views = colony.views(server);
        zones.clear();
        surveys.keySet().retainAll(views.stream().map(ColonyView::dimension).toList());
        FrontView front = Fronts.of(colony);
        int left = FolkwaysVanillaConfig.damageControlScanBudget();
        for (int i = 0; i < views.size(); i++) {
            ColonyView view = views.get(i);
            List<? extends ZoneView> here = front.zonesIn(view.level()).stream()
                .filter(zone -> zone.delegation().equals(DamageControlContent.ID)
                    && !zone.cells().isEmpty() && zone.cells().size() <= FolkwaysVanillaConfig.maxDamageControlCells()
                    && zone.settings().flag("enabled")).toList();
            here.forEach(zone -> zones.put(zone.id(), zone));
            int budget = left / (views.size() - i);
            left -= surveys.computeIfAbsent(view.dimension(), dimension -> new FireSurvey()).scan(view.level(), here, budget, (zone, pos, state) -> inspect(view.level(), zone, pos, state));
        }
        missing.keySet().retainAll(zones.keySet());
        conflicts.keySet().retainAll(zones.keySet());
        refusals.keySet().retainAll(fires());
        watches.keySet().retainAll(zones.keySet());
        duty.values().removeIf(until -> until < server.overworld().getGameTime());
        sweep.tick(server, this::refresh, this::goals);
    }

    private void inspect(ServerLevel level, ZoneView zone, BlockPos pos, BlockState standing) {
        SuppressionMethods.all().forEach(method -> method.inspect(colony, zone, level, pos, standing));
        StructureSnapshots.Snapshot baseline = baselines.get(zone.id());
        if (baseline == null || !baseline.origin().in(level)) {
            return;
        }
        WorldPos at = zone.at(pos);
        BlockState wanted = baseline.cells().get(pos.subtract(baseline.origin().block(level)));
        Map<WorldPos, BlockState> absent = missing.computeIfAbsent(zone.id(), id -> new LinkedHashMap<>());
        Set<WorldPos> changed = conflicts.computeIfAbsent(zone.id(), id -> new LinkedHashSet<>());
        absent.remove(at);
        changed.remove(at);
        if (wanted == null || !MaintenanceBaseline.accepts(wanted)) {
            return;
        }
        if (standing.isAir()) {
            absent.put(at, wanted);
        } else if (!standing.is(wanted.getBlock())) {
            changed.add(at);
        }
    }

    private void refresh(MinecraftServer server) {
        if (baselines.keySet().removeIf(id -> colony.holding(id).isEmpty())) {
            revisions.keySet().retainAll(baselines.keySet());
            remember();
        }
        repairs.entrySet().removeIf(entry -> zones.values().stream().noneMatch(zone ->
            zone.settings().flag("repair") && missing.getOrDefault(zone.id(), Map.of()).containsKey(entry.getKey())));
        for (ZoneView zone : zones.values()) {
            Optional<ServerLevel> world = zone.at(zone.cells().iterator().next()).level(server);
            if (world.isEmpty()) {
                continue;
            }
            ServerLevel level = world.get();
            watches.computeIfAbsent(zone.id(), id -> watch(level, zone));
            if (!zone.settings().flag("repair") || !fires().isEmpty()) {
                continue;
            }
            Map<WorldPos, BlockState> absent = missing.getOrDefault(zone.id(), Map.of());
            Map<WorldPos, BlockState> targets = new LinkedHashMap<>();
            StructureSnapshots.Snapshot baseline = baselines.get(zone.id());
            if (baseline == null) {
                continue;
            }
            Map<BlockPos, BlockState> recorded = baseline.in(level);
            absent.keySet().stream().limit(128).forEach(at ->
                SchematicPlacement.adapter().launch(pos -> recorded.getOrDefault(pos, Blocks.AIR.defaultBlockState()),
                    at.block(level)).ifPresent(launch -> {
                        if (launch.produces().stream().allMatch(pos -> absent.containsKey(zone.at(pos)))) {
                            launch.produces().forEach(pos -> targets.put(zone.at(pos), recorded.get(pos)));
                        }
                    }));
            long revision = revisions.getOrDefault(zone.id(), 0L);
            for (Node node : MaintenanceOrders.missing(level, targets, DamageControlContent.ID,
                    DamageControlContent.trade(), DamageControlContent.REPAIRING,
                    () -> active(zone.id(), "repair") && revisions.getOrDefault(zone.id(), 0L) == revision
                        && fires().isEmpty(), at -> !MaintenanceOrders.buildingAt(colony, level, at), DamageControlRefusal.CONFLICT,
                    (done, how) -> {
                        repairs.values().removeIf(old -> old.id().equals(done.id()));
                        sweep.nudge();
                    })) {
                WorldPos at = ((WorkSite.AtBlock) node.spec().site()).pos();
                repairs.putIfAbsent(at, node);
            }
        }
    }

    private Node watch(ServerLevel level, ZoneView zone) {
        Optional<Stances> stances = Stances.of(level, zone.cells().stream()
            .filter(pos -> Footing.withFeetAt(level, pos)).limit(32).toList());
        if (stances.isEmpty()) {
            return null;
        }
        WorldPos at = stances.get().cells().iterator().next();
        NodeSpec spec = NodeSpec.of(UUID.randomUUID(), DamageControlContent.ID, new WorkSite.AtBlock(at),
                stances.get(), Workload.Once.of(100))
            .vocation(DamageControlContent.trade()).doing(DamageControlContent.ID).done();
        return new Node() {
            public NodeSpec spec() { return spec; }
            public boolean ready(ServerLevel world) { return active(zone.id(), "enabled"); }
            public Outcome commit(ServerLevel world, Worker who) {
                duty.put(who.resident().id(), world.getGameTime() + 2400);
                return Outcome.done();
            }
            public void ended(ServerLevel world, Ending how) {
                watches.remove(zone.id(), this);
                sweep.nudge();
            }
        };
    }

    private boolean active(UUID zone, String setting) {
        return !closed && zones.containsKey(zone) && zones.get(zone).settings().flag(setting);
    }

    List<Grown> goals(ColonyView view) {
        List<Grown> goals = new ArrayList<>();
        watches.values().stream().filter(Objects::nonNull)
            .filter(node -> ((WorkSite.AtBlock) node.spec().site()).pos().in(view.level()))
            .forEach(node -> goals.add(Grown.of(node)));
        repairs.forEach((at, node) -> {
            if (at.in(view.level())) {
                goals.add(Grown.of(node));
            }
        });
        return goals;
    }

    List<Urge> urges(Worker who, ColonyView view) {
        if (closed || duty.getOrDefault(who.resident().id(), Long.MIN_VALUE) < view.level().getGameTime()
                || Bodies.of(who.body()).flatMap(body -> body.licences().of(DamageControlContent.trade())).isEmpty()) {
            return List.of();
        }
        Optional<WorldPos> fire = fires().stream().filter(at -> at.in(view.level())
                && view.level().isLoaded(at.block(view.level())) && FireSurvey.fire(view.level().getBlockState(at.block(view.level())))
                && incidents.free(at, who.resident().id()) && protectedFire(at, view.level()))
            .min(Comparator.comparingDouble(at -> who.body().distanceToSqr(
                Vec3.atCenterOf(at.block(view.level())))));
        if (fire.isEmpty()) {
            return List.of();
        }
        WorldPos target = fire.get();
        double range = SuppressionMethods.all().stream().filter(method -> method.available(who))
            .mapToDouble(SuppressionMethod::range).max().orElse(2);
        Set<BlockPos> feet = new LinkedHashSet<>();
        for (BlockPos pos : Reach.workableCells(view.level(), target.block(view.level()))) {
            if (Vec3.atBottomCenterOf(pos).add(0, Reach.EYE_HEIGHT, 0)
                    .distanceToSqr(Vec3.atCenterOf(target.block(view.level()))) <= range * range
                    && !FireSurvey.fire(view.level().getBlockState(pos))) {
                feet.add(pos);
            }
        }
        Optional<Stances> stances = Stances.of(view.level(), feet);
        if (stances.isEmpty()) {
            refusals.put(target, DamageControlRefusal.NO_REACH);
            return List.of();
        }
        refusals.remove(target);
        return List.of(new Urge(DamageControlContent.EXTINGUISHING, Urge.NOW + .5, true,
            () -> new ExtinguishNode(target, who.resident().id(), stances.get(), incidents,
                () -> protectedFire(target, view.level()))));
    }

    private boolean protectedFire(WorldPos at, ServerLevel level) {
        return !closed && zones.values().stream().anyMatch(zone -> zone.settings().flag("extinguish")
            && zone.at(zone.cells().iterator().next()).sameRealm(at) && zone.cells().contains(at.block(level))
                && zone.at(at.block(level)).equals(at));
    }

    private Set<WorldPos> fires() {
        Set<WorldPos> found = new LinkedHashSet<>();
        surveys.values().forEach(survey -> found.addAll(survey.fires()));
        return found;
    }

    void closed(Closing why) {
        closed = true;
        incidents.clear();
        repairs.clear();
        watches.clear();
    }

    private void remember() {
        colony.keep(DamageControlContent.ID, Writer.of().integer("schemaVersion", 1)
            .children("baselines", baselines.keySet(), id -> Writer.of().uuid("zone", id)
                .longValue("revision", revisions.getOrDefault(id, 1L)).blob("snapshot", baselines.get(id).save()).tag())
            .tag());
    }

    @Override
    public Attempt act(ResourceLocation page, String action, Optional<BlockPos> at, ColonyView view) {
        if (!page.equals(DamageControlContent.ID) || !action.startsWith("record:")) {
            return Attempt.went();
        }
        UUID id;
        try {
            id = UUID.fromString(action.substring(7));
        } catch (IllegalArgumentException invalid) {
            return Attempt.refused(DamageControlRefusal.UNSUPPORTED);
        }
        Optional<? extends ZoneView> found = Fronts.of(colony).zonesIn(view.level()).stream()
            .filter(zone -> zone.id().equals(id) && zone.delegation().equals(DamageControlContent.ID)).findFirst();
        if (found.isEmpty() || found.get().cells().isEmpty()
                || found.get().cells().size() > FolkwaysVanillaConfig.maxDamageControlCells()) {
            return Attempt.refused(DamageControlRefusal.UNSUPPORTED);
        }
        ZoneView zone = found.get();
        if (zone.cells().stream().anyMatch(pos -> !view.level().isLoaded(pos))) {
            return Attempt.refused(DamageControlRefusal.NOT_LOADED);
        }
        WorldPos origin = zone.at(zone.cells().iterator().next());
        if (zone.cells().stream().anyMatch(pos -> !zone.at(pos).sameRealm(origin))) {
            return Attempt.refused(DamageControlRefusal.UNSUPPORTED);
        }
        try {
            baselines.put(id, MaintenanceBaseline.record(view.level(), zone));
        } catch (IllegalArgumentException unsupported) {
            return Attempt.refused(DamageControlRefusal.UNSUPPORTED);
        }
        revisions.merge(id, 1L, Long::sum);
        missing.remove(id);
        conflicts.remove(id);
        repairs.clear();
        remember();
        sweep.nudge();
        return Attempt.went();
    }

    @Override
    public Optional<Board> board(ResourceLocation page, ColonyView view) {
        if (!page.equals(DamageControlContent.ID)) {
            return Optional.empty();
        }
        List<Board.Row> rows = new ArrayList<>();
        for (ZoneView zone : Fronts.of(colony).zonesIn(view.level())) {
            if (!zone.delegation().equals(DamageControlContent.ID) || zone.cells().isEmpty()) {
                continue;
            }
            BlockPos at = zone.cells().iterator().next();
            if (baselines.containsKey(zone.id()) && baselines.get(zone.id()).unsupported() > 0) {
                rows.add(Board.Row.of(ItemStack.EMPTY, Component.translatable("folkways.page.damage_control.unsupported",
                    baselines.get(zone.id()).unsupported()), Component.empty()).note());
            }
            rows.add(new Board.Row(new ItemStack(Items.WATER_BUCKET),
                Component.translatable("folkways.page.damage_control.zone", at.toShortString()),
                Component.translatable("folkways.page.damage_control.status", fires(zone),
                    missing.getOrDefault(zone.id(), Map.of()).size(), conflicts.getOrDefault(zone.id(), Set.of()).size(),
                    baselines.containsKey(zone.id()) ? baselines.get(zone.id()).cells().size() : 0),
                List.of(new Board.Act.Ping(at), new Board.Act.Do("record:" + zone.id(),
                    baselines.containsKey(zone.id()) ? "folkways.page.damage_control.rerecord"
                        : "folkways.page.damage_control.record"))));
        }
        refusals.forEach((where, why) -> {
            if (where.in(view.level())) {
                rows.add(new Board.Row(ItemStack.EMPTY, Component.translatable(why.translationKey()),
                    Component.empty(), List.of(new Board.Act.Ping(where.block(view.level())))));
            }
        });
        for (Stall stall : colony.stallsIn(view.dimension())) {
            if (stall.doing().equals(DamageControlContent.REPAIRING)
                    || stall.doing().equals(DamageControlContent.REFILLING)
                    || stall.doing().equals(DamageControlContent.EXTINGUISHING)) {
                rows.add(Board.Row.of(ItemStack.EMPTY, Component.translatable(stall.why().translationKey()),
                    Component.empty()));
            }
        }
        return Optional.of(new Board(List.of(
            new Board.Figure("folkways.page.damage_control.fires", Component.literal("" + fires().size())),
            new Board.Figure("folkways.page.damage_control.workers", Component.literal("" + incidents.workers())),
            new Board.Figure("folkways.page.damage_control.fluid", Component.literal("" + view.residents().stream()
                .map(resident -> view.level().getEntity(resident.id())).filter(Objects::nonNull)
                .map(Bodies::of).flatMap(Optional::stream)
                .mapToInt(body -> SuppressionMethods.all().stream().mapToInt(method -> method.fluid(body.pack())).max().orElse(0)).sum()))), rows,
            Optional.of(Component.translatable("folkways.page.damage_control.empty"))));
    }

    private long fires(ZoneView zone) {
        return fires().stream().filter(at -> zone.cells().stream().anyMatch(pos -> zone.at(pos).equals(at))).count();
    }

    @Override
    public List<Line> zoneLines(ZoneView zone, ColonyView view) {
        return zone.delegation().equals(DamageControlContent.ID)
            ? List.of(Line.text(Component.translatable("folkways.page.damage_control.status", fires(zone),
                missing.getOrDefault(zone.id(), Map.of()).size(), conflicts.getOrDefault(zone.id(), Set.of()).size(),
                baselines.containsKey(zone.id()) ? baselines.get(zone.id()).cells().size() : 0))) : List.of();
    }
}
