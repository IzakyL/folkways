package io.github.izakyl.folkways.plugins.damagecontrol;

import io.github.izakyl.folkways.base.persist.Reader;
import io.github.izakyl.folkways.base.persist.Writer;
import io.github.izakyl.folkways.core.api.colony.Closing;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.ColonyView;
import io.github.izakyl.folkways.core.api.colony.Sweep;
import io.github.izakyl.folkways.core.api.resident.body.Bodies;
import io.github.izakyl.folkways.core.api.terms.Footing;
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
import java.util.Iterator;
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
    private record Repair(UUID zone, long revision, Node node) { }

    private final Colony colony;
    private final Sweep sweep;
    private final Map<ResourceKey<Level>, FireSurvey> surveys = new LinkedHashMap<>();
    private final Incidents incidents = new Incidents();
    private final Map<UUID, StructureSnapshots.Snapshot> baselines = new LinkedHashMap<>();
    private final Map<UUID, Long> revisions = new LinkedHashMap<>();
    private final Map<UUID, ZoneView> zones = new LinkedHashMap<>();
    private final Map<UUID, Map<WorldPos, BlockState>> missing = new LinkedHashMap<>();
    private final Map<UUID, Set<WorldPos>> conflicts = new LinkedHashMap<>();
    private final Map<WorldPos, Repair> repairs = new LinkedHashMap<>();
    private final Map<UUID, Iterator<BlockPos>> repairCursors = new LinkedHashMap<>();
    private final Map<ResourceKey<Level>, List<WorldPos>> knownFires = new LinkedHashMap<>();
    private final Map<ResourceKey<Level>, Integer> fireTurns = new LinkedHashMap<>();
    private Set<WorldPos> fires = Set.of();
    private final Map<UUID, Long> waiting = new LinkedHashMap<>();
    private final Map<UUID, Long> retryAfter = new LinkedHashMap<>();
    private final Map<UUID, Map<WorldPos, Long>> unreachable = new LinkedHashMap<>();
    private long responseTick = Long.MIN_VALUE;
    private int responsesLeft;
    private int surveyTurn;
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
        List<ColonyView> views = new ArrayList<>();
        server.getAllLevels().forEach(level -> views.add(colony.view(level)));
        survey(views, FolkwaysVanillaConfig.damageControlScanBudget());
        missing.keySet().retainAll(zones.keySet());
        conflicts.keySet().retainAll(zones.keySet());
        refusals.keySet().retainAll(fires());
        watches.keySet().retainAll(zones.keySet());
        duty.values().removeIf(until -> until < server.overworld().getGameTime());
        sweep.tick(server, this::refresh, this::goals);
    }

    void survey(List<ColonyView> views, int allowance) {
        zones.clear();
        FrontView front = Fronts.of(colony);
        Map<ResourceKey<Level>, List<? extends ZoneView>> byDimension = new LinkedHashMap<>();
        List<ColonyView> covered = new ArrayList<>();
        for (ColonyView view : views) {
            List<? extends ZoneView> here = front.zonesIn(view.level()).stream()
                .filter(zone -> zone.delegation().equals(DamageControlContent.ID)
                    && !zone.cells().isEmpty() && zone.cells().size() <= FolkwaysVanillaConfig.maxDamageControlCells()
                    && zone.settings().flag("enabled")).toList();
            here.forEach(zone -> zones.put(zone.id(), zone));
            if (!here.isEmpty()) {
                covered.add(view);
                byDimension.put(view.dimension(), here);
            }
        }
        surveys.keySet().retainAll(byDimension.keySet());
        int left = allowance;
        int first = covered.isEmpty() ? 0 : Math.floorMod(surveyTurn++, covered.size());
        for (int i = 0; i < covered.size(); i++) {
            ColonyView view = covered.get((first + i) % covered.size());
            int budget = (left + covered.size() - i - 1) / (covered.size() - i);
            left -= surveys.computeIfAbsent(view.dimension(), dimension -> new FireSurvey())
                .scan(view.level(), byDimension.get(view.dimension()), budget,
                    (zone, pos, state) -> inspect(view.level(), zone, pos, state));
        }
        knownFires.clear();
        Set<WorldPos> found = new LinkedHashSet<>();
        surveys.forEach((dimension, survey) -> {
            knownFires.put(dimension, List.copyOf(survey.fires()));
            found.addAll(survey.fires());
        });
        fires = Set.copyOf(found);
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
        changed.remove(at);
        if (wanted == null || !MaintenanceBaseline.accepts(wanted)) {
            absent.remove(at);
            return;
        }
        if (standing.isAir()) {
            absent.put(at, wanted);
        } else {
            absent.remove(at);
            if (!standing.is(wanted.getBlock())) {
                changed.add(at);
            }
        }
    }

    void refresh(MinecraftServer server) {
        if (baselines.keySet().removeIf(id -> colony.holding(id).isEmpty())) {
            revisions.keySet().retainAll(baselines.keySet());
            remember();
        }
        repairCursors.keySet().retainAll(baselines.keySet());
        repairs.entrySet().removeIf(entry -> {
            Repair repair = entry.getValue();
            return !active(repair.zone(), "repair")
                || revisions.getOrDefault(repair.zone(), 0L) != repair.revision()
                || !missing.getOrDefault(repair.zone(), Map.of()).containsKey(entry.getKey());
        });
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
            Iterator<BlockPos> cursor = repairCursors.computeIfAbsent(zone.id(), id -> baseline.cells().keySet().iterator());
            for (int checked = 0; checked < Math.min(128, baseline.cells().size()); checked++) {
                if (!cursor.hasNext()) {
                    cursor = baseline.cells().keySet().iterator();
                    repairCursors.put(zone.id(), cursor);
                }
                BlockPos pos = cursor.next().offset(baseline.origin().block(level));
                if (!absent.containsKey(zone.at(pos))) {
                    continue;
                }
                SchematicPlacement.adapter().launch(cell -> recorded.getOrDefault(cell, Blocks.AIR.defaultBlockState()), pos)
                    .filter(launch -> launch.produces().stream().allMatch(cell -> absent.containsKey(zone.at(cell))))
                    .ifPresent(launch -> launch.produces().forEach(cell -> targets.put(zone.at(cell), recorded.get(cell))));
            }
            long revision = revisions.getOrDefault(zone.id(), 0L);
            for (Node node : MaintenanceOrders.missing(level, targets, DamageControlContent.ID,
                    DamageControlContent.trade(), DamageControlContent.REPAIRING,
                    () -> active(zone.id(), "repair") && revisions.getOrDefault(zone.id(), 0L) == revision
                        && fires().isEmpty(), at -> !MaintenanceOrders.buildingAt(colony, level, at), DamageControlRefusal.CONFLICT,
                    (done, how) -> {
                        repairs.values().removeIf(old -> old.node().id().equals(done.id()));
                        sweep.nudge();
                    })) {
                WorldPos at = ((WorkSite.AtBlock) node.spec().site()).pos();
                repairs.putIfAbsent(at, new Repair(zone.id(), revision, node));
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
        repairs.forEach((at, repair) -> {
            if (at.in(view.level())) {
                goals.add(Grown.of(repair.node()));
            }
        });
        return goals;
    }

    List<Urge> urges(Worker who, ColonyView view) {
        if (closed || duty.getOrDefault(who.resident().id(), Long.MIN_VALUE) < view.level().getGameTime()
                || Bodies.of(who.body()).flatMap(body -> body.licences().of(DamageControlContent.trade())).isEmpty()) {
            return List.of();
        }
        ServerLevel level = view.level();
        long now = level.getServer().overworld().getGameTime();
        UUID worker = who.resident().id();
        if (retryAfter.getOrDefault(worker, Long.MIN_VALUE) > now || fires.isEmpty()
                || !incidents.available(worker, FolkwaysVanillaConfig.damageControlConcurrency())) {
            return List.of();
        }
        if (responseTick != now) {
            responseTick = now;
            responsesLeft = FolkwaysVanillaConfig.damageControlConcurrency();
            waiting.values().removeIf(seen -> seen < now - 1);
            retryAfter.values().removeIf(until -> until <= now);
            unreachable.values().forEach(positions -> positions.values().removeIf(until -> until <= now));
            unreachable.values().removeIf(Map::isEmpty);
        }
        waiting.put(worker, now);
        if (responsesLeft == 0 || !waiting.keySet().iterator().next().equals(worker)) {
            return List.of();
        }
        waiting.remove(worker);
        responsesLeft--;
        retryAfter.put(worker, now + 20);
        Map<WorldPos, Long> blocked = unreachable.computeIfAbsent(worker, id -> new LinkedHashMap<>());
        List<WorldPos> known = knownFires.getOrDefault(view.dimension(), List.of());
        if (known.isEmpty()) {
            return List.of();
        }
        // Selection reads only the shared survey. Bound both addresses considered and expensive footing searches.
        int first = fireTurns.getOrDefault(view.dimension(), 0);
        List<WorldPos> candidates = new ArrayList<>();
        int count = Math.min(128, known.size());
        for (int i = 0; i < count; i++) {
            WorldPos at = known.get(Math.floorMod(first + i, known.size()));
            if (incidents.free(at, worker) && !blocked.containsKey(at)) {
                candidates.add(at);
            }
        }
        fireTurns.put(view.dimension(), Math.floorMod(first + count, known.size()));
        candidates.sort(Comparator.comparingDouble(at -> who.body().distanceToSqr(Vec3.atCenterOf(at.block(level)))));
        boolean firstTarget = true;
        for (WorldPos target : candidates) {
            if (!firstTarget) {
                if (responsesLeft == 0) {
                    break;
                }
                responsesLeft--;
            }
            firstTarget = false;
            Set<BlockPos> feet = Set.of();
            for (SuppressionMethod method : SuppressionMethods.all()) {
                feet = method.footings(level, who, target);
                if (!feet.isEmpty()) {
                    break;
                }
            }
            Optional<Stances> stances = Stances.of(level, feet);
            if (stances.isEmpty()) {
                refusals.put(target, DamageControlRefusal.NO_REACH);
                blocked.put(target, now + 20);
                continue;
            }
            refusals.remove(target);
            return List.of(new Urge(DamageControlContent.EXTINGUISHING, Urge.NOW + .5, true,
                () -> new ExtinguishNode(target, worker, stances.get(), incidents, () -> protectedFire(target, level))));
        }
        return List.of();
    }

    private boolean protectedFire(WorldPos at, ServerLevel level) {
        return !closed && at.in(level) && zones.values().stream().anyMatch(zone -> zone.settings().flag("extinguish")
            && zone.cells().contains(at.block(level)) && zone.at(at.block(level)).equals(at));
    }

    private Set<WorldPos> fires() { return fires; }

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
        repairCursors.remove(id);
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
        return zone.cells().stream().map(zone::at).filter(fires::contains).count();
    }

    @Override
    public List<Line> zoneLines(ZoneView zone, ColonyView view) {
        return zone.delegation().equals(DamageControlContent.ID)
            ? List.of(Line.text(Component.translatable("folkways.page.damage_control.status", fires(zone),
                missing.getOrDefault(zone.id(), Map.of()).size(), conflicts.getOrDefault(zone.id(), Set.of()).size(),
                baselines.containsKey(zone.id()) ? baselines.get(zone.id()).cells().size() : 0))) : List.of();
    }
}
