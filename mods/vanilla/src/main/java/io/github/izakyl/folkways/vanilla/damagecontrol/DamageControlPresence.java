package io.github.izakyl.folkways.vanilla.damagecontrol;

import io.github.izakyl.folkways.core.base.persist.Reader;
import io.github.izakyl.folkways.core.base.persist.Writer;
import io.github.izakyl.folkways.core.colony.api.Closing;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.colony.api.ColonyView;
import io.github.izakyl.folkways.core.colony.api.Sweep;
import io.github.izakyl.folkways.core.inventory.api.InventorySnapshot;
import io.github.izakyl.folkways.core.plan.api.Grown;
import io.github.izakyl.folkways.core.resident.api.Bodies;
import io.github.izakyl.folkways.core.resident.api.Body;
import io.github.izakyl.folkways.core.resident.api.Resident;
import io.github.izakyl.folkways.core.work.api.Ending;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.Placement;
import io.github.izakyl.folkways.core.work.api.Stall;
import io.github.izakyl.folkways.core.work.api.ToolNeed;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.world.api.StructureSnapshots;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.effect.Effects;
import io.github.izakyl.folkways.core.front.api.Facing;
import io.github.izakyl.folkways.core.front.api.FrontView;
import io.github.izakyl.folkways.core.front.api.Fronts;
import io.github.izakyl.folkways.core.front.api.ZoneView;
import io.github.izakyl.folkways.core.front.api.notice.Attempt;
import io.github.izakyl.folkways.core.front.api.notice.Line;
import io.github.izakyl.folkways.core.front.api.notice.Notice;
import io.github.izakyl.folkways.core.front.api.panel.Board;
import io.github.izakyl.folkways.core.front.engine.colony.ColonyStalls;
import io.github.izakyl.folkways.vanilla.FolkwaysVanillaConfig;
import io.github.izakyl.folkways.vanilla.build.MaintenanceOrders;
import io.github.izakyl.folkways.vanilla.build.SchematicPlacement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

final class DamageControlPresence implements Facing {
    private record Repair(UUID zone, long revision, Node node) {
    }

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
    private final Map<UUID, ExtinguishNode> responding = new LinkedHashMap<>();
    private final Map<UUID, Long> retryAfter = new LinkedHashMap<>();
    private final Map<UUID, Map<WorldPos, Long>> unreachable = new LinkedHashMap<>();
    private long responseTick = Long.MIN_VALUE;
    private int responsesLeft;
    private int surveyTurn;
    private final Map<WorldPos, DamageControlRefusal> refusals = new LinkedHashMap<>();
    private InventorySnapshot.Version fluidVersion;
    private final Map<UUID, Integer> fluids = new LinkedHashMap<>();
    private boolean closed;

    DamageControlPresence(Colony colony) {
        this.colony = colony;
        sweep = new Sweep(colony, DamageControlContent.ID, 100);
        Reader saved = Reader.of(colony.kept(DamageControlContent.ID));
        if (saved.keys().isEmpty()) {
            return;
        }
        for (Reader entry : saved.requiredChildren("baselines")) {
            entry.uuid("zone").ifPresent(id -> entry.blob("snapshot").flatMap(StructureSnapshots::load)
                .ifPresent(snapshot -> {
                    baselines.put(id, snapshot);
                    revisions.put(id, entry.longValue("revision").orElseThrow());
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
        for (ExtinguishNode node : List.copyOf(responding.values())) {
            node.target().level(server).ifPresent(level -> {
                if (!node.continues(level)) {
                    if (level.isLoaded(node.target().block(level))
                        && !FireSurvey.fire(level.getBlockState(node.target().block(level)))) {
                        node.resolved(level);
                    } else {
                        node.cancel();
                    }
                }
            });
        }
        for (ColonyView view : views) {
            for (Resident resident : view.residents()) {
                Bodies.of(view.level().getEntity(resident.id())).ifPresent(body ->
                    plan(new Responder(body), view));
            }
        }
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
                    && !zone.cells().isEmpty()
                    && zone.cells().size() <= FolkwaysVanillaConfig.maxDamageControlCells())
                .toList();
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
        if (baselines.keySet().removeIf(id -> colony.claim(id).isEmpty())) {
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
            Iterator<BlockPos> cursor = repairCursors.computeIfAbsent(zone.id(), id -> baseline.cells().keySet()
                .iterator());
            for (int checked = 0; checked < Math.min(128, baseline.cells().size()); checked++) {
                if (!cursor.hasNext()) {
                    cursor = baseline.cells().keySet().iterator();
                    repairCursors.put(zone.id(), cursor);
                }
                BlockPos pos = cursor.next().offset(baseline.origin().block(level));
                if (!absent.containsKey(zone.at(pos))) {
                    continue;
                }
                SchematicPlacement.adapter()
                    .launch(cell -> recorded.getOrDefault(cell, Blocks.AIR.defaultBlockState()), pos)
                    .filter(launch -> launch.produces().stream()
                        .allMatch(cell -> absent.containsKey(zone.at(cell))))
                    .ifPresent(launch -> launch.produces()
                        .forEach(cell -> targets.put(zone.at(cell), recorded.get(cell))));
            }
            long revision = revisions.getOrDefault(zone.id(), 0L);
            for (Node node : MaintenanceOrders.missing(level, targets, DamageControlContent.ID,
                DamageControlContent.trade(), DamageControlContent.REPAIRING,
                () -> active(zone.id(), "repair") && revisions.getOrDefault(zone.id(), 0L) == revision
                && fires().isEmpty(), at -> !MaintenanceOrders.buildingAt(colony, level, at),
                DamageControlRefusal.CONFLICT,
                (done, how) -> {
                    repairs.values().removeIf(old -> old.node().id().equals(done.id()));
                    sweep.nudge();
                })) {
                WorldPos at = ((WorkSite.AtBlock) node.spec().site()).pos();
                repairs.putIfAbsent(at, new Repair(zone.id(), revision, node));
            }
        }
    }

    private boolean active(UUID zone, String setting) {
        return !closed && zones.containsKey(zone) && zones.get(zone).settings().flag(setting);
    }

    List<Grown> goals(ColonyView view) {
        List<Grown> goals = new ArrayList<>();
        responding.values().stream().filter(node -> node.target().in(view.level()))
            .forEach(node -> goals.add(Grown.of(node)));
        repairs.forEach((at, repair) -> {
            if (at.in(view.level())) {
                goals.add(Grown.of(repair.node()));
            }
        });
        return goals;
    }

    Optional<ExtinguishNode> plan(Worker who, ColonyView view) {
        if (closed || !who.body().isAlive() || who.body().isRemoved()
            || Bodies.of(who.body()).flatMap(body -> body.licences().of(DamageControlContent.trade())).isEmpty()) {
            return Optional.empty();
        }
        ServerLevel level = view.level();
        long now = level.getServer().overworld().getGameTime();
        UUID worker = who.resident().id();
        ExtinguishNode retained = responding.get(worker);
        if (retained != null) {
            if (retained.continues(level)) {
                return Optional.of(retained);
            }
            if (retained.target().in(level) && level.isLoaded(retained.target().block(level))
                && !FireSurvey.fire(level.getBlockState(retained.target().block(level)))) {
                retained.resolved(level);
            } else {
                retained.ended(level, Ending.DROPPED);
            }
            responding.remove(worker, retained);
            return Optional.empty();
        }
        if (retryAfter.getOrDefault(worker, Long.MIN_VALUE) > now || fires.isEmpty()
            || !incidents.available(worker, FolkwaysVanillaConfig.damageControlConcurrency())) {
            return Optional.empty();
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
            return Optional.empty();
        }
        waiting.remove(worker);
        responsesLeft--;
        retryAfter.put(worker, now + 20);
        Map<WorldPos, Long> blocked = unreachable.computeIfAbsent(worker, id -> new LinkedHashMap<>());
        List<WorldPos> known = knownFires.getOrDefault(view.dimension(), List.of());
        if (known.isEmpty()) {
            return Optional.empty();
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
            Optional<SuppressionMethod.Plan> selected = Optional.empty();
            for (SuppressionMethod method : SuppressionMethods.all()) {
                selected = method.plan(level, who, target);
                if (selected.isPresent()) {
                    break;
                }
            }
            if (selected.isEmpty()) {
                refusals.put(target, DamageControlRefusal.NO_REACH);
                blocked.put(target, now + 20);
                continue;
            }
            refusals.remove(target);
            ExtinguishNode node = new ExtinguishNode(target, worker, selected.get(), incidents,
                () -> protectedFire(target, level) && who.body().level() == level
                    && who.body().isAlive() && !who.body().isRemoved()
                    && Bodies.of(who.body()).flatMap(body -> body.colonyId()).filter(colony.id()::equals).isPresent()
                    && Bodies.of(who.body()).flatMap(body -> body.licences()
                        .of(DamageControlContent.trade())).isPresent(),
                ended -> {
                    if (responding.remove(worker, ended)) {
                        retryAfter.put(worker, level.getServer().overworld().getGameTime() + 20);
                        ended.failure().filter(DamageControlRefusal.class::isInstance)
                            .map(DamageControlRefusal.class::cast).ifPresent(why -> refusals.put(target, why));
                        if (ended.failure().filter(DamageControlRefusal.NO_REACH::equals).isPresent()) {
                            blocked.put(target, level.getServer().overworld().getGameTime() + 20);
                        }
                        sweep.nudge();
                    }
                }, this::reconcile);
            responding.put(worker, node);
            sweep.nudge();
            return Optional.of(node);
        }
        return Optional.empty();
    }

    private void reconcile(ServerLevel level) {
        FireSurvey survey = surveys.get(level.dimension());
        if (survey == null) {
            return;
        }
        // Only known incidents are revisited after a paid area pulse; discovery still uses the bounded survey.
        survey.reconcile(level);
        knownFires.put(level.dimension(), List.copyOf(survey.fires()));
        Set<WorldPos> found = new LinkedHashSet<>();
        surveys.values().forEach(known -> found.addAll(known.fires()));
        fires = Set.copyOf(found);
        for (ExtinguishNode node : List.copyOf(responding.values())) {
            if (node.target().in(level) && level.isLoaded(node.target().block(level))
                && !FireSurvey.fire(level.getBlockState(node.target().block(level)))) {
                node.resolved(level);
            }
        }
        sweep.nudge();
    }

    private boolean protectedFire(WorldPos at, ServerLevel level) {
        return !closed && at.in(level) && zones.values().stream().anyMatch(zone -> zone.settings().flag("extinguish")
            && zone.cells().contains(at.block(level)) && zone.at(at.block(level)).equals(at));
    }

    private Set<WorldPos> fires() {
        return fires;
    }

    void closed(Closing why) {
        closed = true;
        List.copyOf(responding.values()).forEach(ExtinguishNode::cancel);
        incidents.clear();
        responding.clear();
        repairs.clear();
    }

    private record Responder(Body person) implements Worker {
        public Resident resident() {
            return person.resident();
        }

        public Mob body() {
            return person.mob();
        }

        public Container pack() {
            return person.pack();
        }

        public List<Store> within() {
            return List.of();
        }

        public ItemStack held(ToolNeed need) {
            return ItemStack.EMPTY;
        }

        public void spill(ItemStack stack) {
            throw new UnsupportedOperationException("planning cannot spill items");
        }

        public Effects effects() {
            return person.effects();
        }

        public List<Node> route() {
            return List.of();
        }

        public Placement placement() {
            return Placement.NOWHERE;
        }
    }

    private void remember() {
        colony.keep(DamageControlContent.ID, Writer.of()
            .children("baselines", baselines.keySet(), id -> Writer.of().uuid("zone", id)
                .longValue("revision", revisions.getOrDefault(id, 1L))
                .blob("snapshot", baselines.get(id).save())
                .tag())
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
            .filter(zone -> zone.id().equals(id) && zone.delegation().equals(DamageControlContent.ID))
            .findFirst();
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
                rows.add(Board.Row.of(ItemStack.EMPTY,
                    Component.translatable("folkways.page.damage_control.unsupported",
                        baselines.get(zone.id()).unsupported()), Component.empty()).note());
            }
            rows.add(new Board.Row(new ItemStack(Items.WATER_BUCKET),
                Component.translatable("folkways.page.damage_control.zone", at.toShortString()),
                Component.translatable("folkways.page.damage_control.status", fires(zone),
                    missing.getOrDefault(zone.id(), Map.of()).size(),
                    conflicts.getOrDefault(zone.id(), Set.of()).size(),
                    baselines.containsKey(zone.id()) ? baselines.get(zone.id()).cells().size() : 0),
                List.of(new Board.Act.Ping(at))));
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
            new Board.Figure("folkways.page.damage_control.fluid", fluid(view))), rows,
            Optional.of(Component.translatable("folkways.page.damage_control.empty"))));
    }

    private Component fluid(ColonyView view) {
        InventorySnapshot snapshot = colony.inventory().snapshot();
        boolean pending = false;
        for (var resident : view.residents()) {
            var observed = snapshot.residents().get(resident.id());
            if (observed == null || observed.state() != InventorySnapshot.State.OBSERVED) {
                colony.inventory().ensureObservedResident(resident.id());
                pending = true;
            }
        }
        if (pending) {
            return Component.translatable("folkways.settlers.checking_stock");
        }
        InventorySnapshot.Version version = snapshot.version();
        if (fluidVersion == null || !fluidVersion.epoch().equals(version.epoch())
            || fluidVersion.contents() != version.contents() || fluidVersion.topology() != version.topology()) {
            fluids.clear();
            for (InventorySnapshot.Resident resident : snapshot.residents().values()) {
                SimpleContainer pack = new SimpleContainer(resident.pack().size());
                resident.pack().forEach(slot -> pack.setItem(slot.physicalSlot(), slot.stackCopy()));
                fluids.put(resident.resident(), SuppressionMethods.all().stream()
                    .mapToInt(method -> method.fluid(pack)).max().orElse(0));
            }
            fluidVersion = version;
        }
        return Component.literal(Integer.toString(
            view.residents().stream().mapToInt(resident -> fluids.getOrDefault(resident.id(), 0)).sum()));
    }

    DamageControlState state(ColonyView view) {
        List<DamageControlState.Area> areas = new ArrayList<>();
        List<DamageControlState.Mark> marks = new ArrayList<>();
        for (var zone : Fronts.of(colony).zonesIn(view.level())) {
            if (!zone.delegation().equals(DamageControlContent.ID) || areas.size() >= DamageControlState.MAX_AREAS) {
                continue;
            }
            BlockPos min = zone.cells().stream().reduce(BlockPos::min).orElseThrow();
            BlockPos max = zone.cells().stream().reduce(BlockPos::max).orElseThrow();
            int flags = 1;
            List<String> keys = List.of("extinguish", "repair", "refill");
            for (int bit = 0; bit < keys.size(); bit++) {
                if (zone.settings().flag(keys.get(bit))) {
                    flags |= 1 << (bit + 1);
                }
            }
            List<Line> lines = new ArrayList<>(zoneLines(zone, view));
            lines.add(Line.text(Component.translatable(baselines.containsKey(zone.id())
                ? "folkways.damage_control.baseline.recorded" : "folkways.damage_control.baseline.empty")));
            var snapshot = baselines.get(zone.id());
            if (snapshot != null && snapshot.unsupported() > 0) {
                lines.add(Line.text(Component.translatable("folkways.page.damage_control.unsupported",
                    snapshot.unsupported())));
            }
            areas.add(new DamageControlState.Area(zone.id(), min, max, flags, baselines.containsKey(zone.id()), lines));
            for (var assignment : incidents.assigned().entrySet()) {
                if (!assignment.getKey().in(view.level())
                    || !zone.cells().contains(assignment.getKey().block(view.level()))) {
                    continue;
                }
                var entity = view.level().getEntity(assignment.getValue());
                if (entity != null) {
                    mark(marks, zone.id(), entity.blockPosition(), 0x70D6FF,
                        List.of(Line.text(Component.translatable("folkways.damage_control.worker",
                            entity.getDisplayName()))));
                }
            }
            for (Stall stall : colony.stallsIn(view.dimension())) {
                if (damageDoing(stall.doing()) && stall.where().in(view.level())
                    && zone.cells().contains(stall.where().block(view.level()))) {
                    mark(marks, zone.id(), stall.where().block(view.level()), 0xFF70D0,
                        ColonyStalls.lines(colony, view.level(),
                            cell -> cell.equals(stall.where().block(view.level()))));
                }
            }
            for (WorldPos fire : fires) {
                if (fire.in(view.level()) && zone.cells().contains(fire.block(view.level()))) {
                    List<Line> detail = new ArrayList<>();
                    detail.add(Line.text(Component.translatable("folkways.damage_control.fire")));
                    if (refusals.containsKey(fire)) {
                        detail.add(Line.text(Component.translatable(refusals.get(fire).translationKey())));
                    }
                    UUID worker = incidents.assigned().get(fire);
                    var entity = worker == null ? null : view.level().getEntity(worker);
                    if (entity != null) {
                        detail.add(Line.text(Component.translatable("folkways.damage_control.worker",
                            entity.getDisplayName())));
                    }
                    mark(marks, zone.id(), fire.block(view.level()), 0xFF6038, detail);
                }
            }
            for (var cell : missing.getOrDefault(zone.id(), Map.of()).entrySet()) {
                if (cell.getKey().in(view.level())) {
                    mark(marks, zone.id(), cell.getKey().block(view.level()), 0xFFD45C,
                        List.of(Line.text(Component.translatable("folkways.damage_control.missing",
                            Component.translatable(cell.getValue().getBlock().getDescriptionId())))));
                }
            }
            for (WorldPos cell : conflicts.getOrDefault(zone.id(), Set.of())) {
                if (cell.in(view.level())) {
                    mark(marks, zone.id(), cell.block(view.level()), 0xE878E8,
                        List.of(Line.text(Component.translatable("folkways.damage_control.conflict"))));
                }
            }
        }
        // Repairing and refilling workers also show when they are away fetching materials or water.
        for (var resident : view.residents()) {
            var entity = view.level().getEntity(resident.id());
            if (entity == null) {
                continue;
            }
            Bodies.of(entity).ifPresent(body -> body.doing().filter(doing ->
                damageDoing(doing.what()) || doing.toward().filter(DamageControlPresence::damageDoing).isPresent())
                .ifPresent(doing -> {
                    List<Line> lines = new ArrayList<>();
                    lines.add(Line.text(Component.translatable("folkways.damage_control.worker",
                        entity.getDisplayName())));
                    lines.add(Line.busy(doing));
                    body.balk().ifPresent(balk -> lines.add(Line.blocked(Notice.of(
                        balk.why(), balk.doing().about().map(Notice::named)
                        .orElseGet(() -> Notice.text(""))))));
                    mark(marks, DamageControlCommand.NONE, entity.blockPosition(), 0x70D6FF, lines);
                }));
        }
        return new DamageControlState(colony.id(), view.dimension().location(), List.copyOf(areas), List.copyOf(marks));
    }

    private static boolean damageDoing(ResourceLocation doing) {
        return doing.equals(DamageControlContent.ID) || doing.equals(DamageControlContent.EXTINGUISHING)
            || doing.equals(DamageControlContent.REPAIRING) || doing.equals(DamageControlContent.REFILLING);
    }

    private static void mark(List<DamageControlState.Mark> marks, UUID area, BlockPos at, int color, List<Line> lines) {
        if (marks.size() < DamageControlState.MAX_MARKS) {
            marks.add(new DamageControlState.Mark(area, at, color, lines));
        }
    }

    private long fires(ZoneView zone) {
        return zone.cells().stream().map(zone::at).filter(fires::contains).count();
    }

    @Override
    public List<Line> zoneLines(ZoneView zone, ColonyView view) {
        return zone.delegation().equals(DamageControlContent.ID)
            ? List.of(Line.text(Component.translatable("folkways.page.damage_control.status", fires(zone),
                missing.getOrDefault(zone.id(), Map.of()).size(),
                conflicts.getOrDefault(zone.id(), Set.of()).size(),
                baselines.containsKey(zone.id()) ? baselines.get(zone.id()).cells().size() : 0))) : List.of();
    }
}
