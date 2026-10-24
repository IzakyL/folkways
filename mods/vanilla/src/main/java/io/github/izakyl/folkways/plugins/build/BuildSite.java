package io.github.izakyl.folkways.plugins.build;

import io.github.izakyl.folkways.core.api.ground.BlockChanges;
import io.github.izakyl.folkways.core.api.terms.Closures;
import io.github.izakyl.folkways.core.api.terms.Containers;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.Reach;
import io.github.izakyl.folkways.core.api.terms.Realm;
import io.github.izakyl.folkways.core.api.terms.RefusalKind;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Amount;
import io.github.izakyl.folkways.core.api.work.Before;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Leaves;
import io.github.izakyl.folkways.core.api.work.Need;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Recourse;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.ToolUse;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.core.engine.labor.LaborRefusal;
import io.github.izakyl.folkways.plugins.build.draft.Joint;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BiConsumer;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.pathfinder.Path;

/**
 * One order's work, planned once and kept. The whole of what is left is laid out by {@link Layering} as pieces in the
 * order they go in. The colony is not asked for the pieces: it is asked for hands, each a turn on the site that one
 * worker goes into, bringing what a batch of the pieces needs, and stays in while the site has a piece for it. The
 * site hands the pieces out itself, to whoever is on it and holds what a piece takes, as each comes up and where
 * the ground as it stands then lets it be done from; so a piece never waits on work queued behind something else
 * in another worker's day. From then on it is heard: a piece done, or a block set in the site by anyone, meets its
 * cells, a cell met is met for good, and what that frees comes up. The order is built when every cell has been met
 * and every joint made. While it stands the shared ways are closed over the site; residents walk to the work
 * using the ground as it is.
 *
 * <p>A piece that ends undone comes up again as it was, the pieces after it waiting on it meanwhile, unless its
 * cells were met some other way. Nothing is looked at again on a clock.
 */
final class BuildSite {

    interface Asks {

        void submit(ResourceKey<Level> dimension, Grown work, BiConsumer<UUID, Ending> ended);

        void withdraw(UUID node);
    }

    static final class Piece {

        private final Layering.Work work;
        private final Node node;
        private int tries;
        // The node handed out for it while someone is at it, and when it may next be handed out after a hitch.
        @Nullable
        private UUID doing;
        private long restUntil = Long.MIN_VALUE;
        // Since when it has been up with nobody on the site holding what it takes.
        private long starvedSince = Long.MIN_VALUE;
        // The scaffold being raised to come to it by, while that is still to raise; whether one was looked for; and
        // how often the piece was done from further off than looks near, for the site's telling.
        @Nullable
        private UUID way;
        private boolean looked;
        private int shortOf;

        Piece(Layering.Work work, Node node) {
            this.work = work;
            this.node = node;
        }

        // Every cell the piece changes, which nobody stands in to do it.
        private Set<BlockPos> cells() {
            return work.step().map(step -> step.after().keySet()).orElse(Set.of());
        }

        Layering.Work work() {
            return work;
        }

        Node node() {
            return node;
        }

        int tries() {
            return tries;
        }
    }

    private record Heard(UUID node, Ending how) {
    }

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("folkways-labor");

    private static final int TOLD_TICKS = 600;

    // Before any time the game tells, and not so far before that the ticks since overflow.
    private static final long NEVER = Long.MIN_VALUE / 2;

    static final int MOST_TRIES = 5;

    // How deep the asked work runs: a piece is asked for once fewer than this many pieces still stand before it, one
    // waiting on the next.
    static final int DEPTH = 16;

    // How far past the work the shared ways are closed, including room for its working surfaces.
    private static final int CLOSED_MARGIN = 2;

    private static final int FAR = Integer.MAX_VALUE / 2;

    // How much work one turn is: so many ticks of it at most, its goods and what it leaves in so many cells of a
    // pack; and how the plan reckons the walking between them. A turn kept small is asked for goods the colony can
    // more readily find, and the work is spread over as many hands as come; one hand takes several in a row.
    private static final int TURN_TICKS = 600;
    private static final int MOST_CELLS = 6;
    private static final ItemSpec SCAFFOLDS = ItemSpec.of(BuiltInRegistries.ITEM.getKey(Blocks.SCAFFOLDING.asItem()));
    private static final int WALK_TICKS = 40;

    // How long a worker on the site waits for a piece to come up before it leaves, how long a piece that hit a
    // hitch rests before it is handed out again, and how long the site asks for nobody after a turn that came to
    // nothing.
    private static final int PATIENCE_TICKS = 300;
    // How long a piece up may go with nobody on the site holding what it takes before hands are asked for it anew,
    // and how lately a worker must have asked for a piece to count as on the site.
    private static final int STARVED_TICKS = 200;
    private static final int PRESENT_TICKS = 40;
    private static final int REST_TICKS = 100;
    private static final int IDLE_TICKS = 200;

    // How often the site looks for pieces nobody can come to, how many it raises a way to at once, how tall such
    // a way may be, and how far over the ground cut off it looks for where to raise one.
    private static final int ACCESS_TICKS = 40;
    private static final int MOST_WAYS_AT_ONCE = 1;
    private static final int TALLEST_WAY = 32;
    private static final int MOST_CUT_OFF = 512;

    // Temporary access raised at runtime: its support column first, followed by any horizontal walkway.
    private record Raised(List<BlockPos> column) {

        BlockPos under() {
            return column.getFirst().below();
        }
    }

    private static final int SPARE_SPAN = 2;

    // How long the ground is taken as read before it is read again, once something in it has changed.
    private static final int GROUND_TICKS = 20;

    // How often a worker on its way over the site is stood afresh, its walk giving out each time.
    private static final int MOST_ASKED = 3;

    // When the presentation looks close enough to stop walking. Movement and target changes have bounded
    // retries, but neither adds time to the operation clock.
    private static final int APPROACH_TICKS = 300;
    private static final int REPATH_TICKS = 40;
    private static final int MOST_PATHS = 6;

    // A piece with nowhere to stand this near under it has a scaffold raised beside it, looked for this far round
    // and this far down.
    private static final int WAY_ROUND = 2;
    private static final int WAY_DROP = 6;
    // Scaffolds stand sparsely: no new one is raised this near one standing or going up, whatever its height. They
    // are for the look of the work, and a worker does what one does not reach from wherever it has got to.
    private static final int WAY_APART = 10;
    private static final int MOST_LOOKED_AT = 24;

    private final UUID order;
    private final Asks asks;
    private final BuildTarget target;
    private final TemporaryScaffolds temporary;
    private final Set<BlockPos> latched = new LinkedHashSet<>();
    private final Map<UUID, Piece> live = new LinkedHashMap<>();
    private final Map<UUID, Piece> held = new LinkedHashMap<>();
    private final List<Integer> keyed = new ArrayList<>();
    private final Map<Integer, UUID> nodeOf = new HashMap<>();
    private final Map<Integer, Set<Integer>> waitsOn = new HashMap<>();
    private Runnable reopen = () -> { };
    private final Map<BlockPos, UUID> pieceAt = new HashMap<>();
    private final List<Joint> joints = new ArrayList<>();
    private final Set<Joint> joined = new LinkedHashSet<>();
    private final Map<BlockPos, BuildRefusal> refused = new LinkedHashMap<>();
    private final Set<BlockPos> told = new LinkedHashSet<>();
    private long geometryVersion;
    private final ConcurrentLinkedQueue<Heard> endings = new ConcurrentLinkedQueue<>();
    @Nullable
    private BlockChanges.Heard listening;
    @Nullable
    private ResourceKey<Level> dimension;
    private Optional<BuildOrderView> progress = Optional.empty();
    private boolean closed;
    // Pieces the plan did not lay out, a scaffold left over or a joint, are numbered down from here.
    private int spare;
    // The turns asked for and not ended yet, each a worker's stay on the site; the nodes handed out to the workers
    // on it, by the piece each is for; and when a turn that came to nothing ended.
    private final Map<UUID, Stay> stays = new LinkedHashMap<>();
    private final Map<UUID, UUID> handed = new HashMap<>();
    private long idleUntil = Long.MIN_VALUE;
    private boolean staffingPending;
    private final List<Raised> raised = new ArrayList<>();
    private long accessAt = NEVER;
    private long toldAt = NEVER;
    @Nullable
    private ServerLevel standing;

    BuildSite(UUID order, Asks asks, BuildTarget target, TemporaryScaffolds temporary) {
        this.order = order;
        this.asks = asks;
        this.target = target;
        this.temporary = temporary;
    }

    UUID order() {
        return order;
    }

    BuildTarget target() {
        return target;
    }

    /**
     * Plans what is left and asks for all of it. {@code met} are the cells met before, kept as the realm has them;
     * every cell that holds what the target wants right now is met as well.
     */
    void start(ServerLevel level, Set<BlockPos> met) {
        start(level, met, Set.of());
    }

    void start(ServerLevel level, Set<BlockPos> met, Set<Joint> metJoints) {
        dimension = level.dimension();
        standing = level;
        met.forEach(cell -> latched.add(cell.offset(target.storageOrigin())));
        for (Map.Entry<BlockPos, BlockState> cell : target.cells().entrySet()) {
            if (!latched.contains(cell.getKey()) && level.hasChunkAt(cell.getKey())
                && target.satisfied(level, cell.getKey())) {
                latched.add(cell.getKey());
            }
        }
        latched.retainAll(target.cells().keySet());
        metJoints.forEach(joint -> joined.add(joint.moved(target.storageOrigin())));
        joined.retainAll(target.joints());
        Layering.Plan plan = Layering.of(level, target, latched);
        refused.putAll(plan.refused());
        Map<Integer, Node> nodes = new LinkedHashMap<>();
        for (Layering.Work work : plan.works()) {
            if (work.kind() == Layering.Kind.JOIN) {
                work.joint().filter(joint -> !joined.contains(joint)).ifPresent(joints::add);
                continue;
            }
            Optional<Stances> footings = Stances.of(level, work.stances());
            Step step = work.step().orElseThrow();
            // Where the plan would have a body stand is only where the piece is reckoned from: it is done from
            // wherever near it the worker has come to.
            if (footings.isEmpty()) {
                footings = Optional.of(Stances.at(level, work.focus()));
            }
            if (footings.isEmpty()) {
                step.after().keySet().forEach(cell -> refused.put(cell, BuildRefusal.NO_STANCE));
                continue;
            }
            Node node = node(level, step, footings.get());
            nodes.put(work.id(), node);
            held.put(node.id(), new Piece(work, node));
            keyed.add(work.id());
            nodeOf.put(work.id(), node.id());
            step.after().keySet().stream().filter(target.cells()::containsKey)
                .forEach(cell -> pieceAt.put(cell, node.id()));
        }
        plan.after().forEach((id, befores) -> {
            if (nodes.containsKey(id)) {
                for (int before : befores) {
                    if (nodes.containsKey(before)) {
                        waitsOn.computeIfAbsent(id, key -> new LinkedHashSet<>()).add(before);
                    }
                }
            }
        });
        closeOff(level);
        release();
        lowerSpare(level);
        listen(level);
        joinWhatIsLaid(level);
    }

    // The shared ways are closed over the site and a little past it while it stands. Residents use ordinary
    // navigation to their tasks inside it; the changing work does not become a route through the shared graph.
    private void closeOff(ServerLevel level) {
        if (!(target.realm() instanceof Realm.Dimension) || target.cells().isEmpty()) {
            return;
        }
        BlockPos min = null;
        BlockPos max = null;
        for (BlockPos cell : target.cells().keySet()) {
            min = min == null ? cell : BlockPos.min(min, cell);
            max = max == null ? cell : BlockPos.max(max, cell);
        }
        reopen = Closures.close(level, BoundingBox.fromCorners(min, max).inflatedBy(CLOSED_MARGIN));
    }

    /**
     * Brings up every piece held back that fewer than {@link #DEPTH} pieces still stand before, one waiting on the
     * next: hands are asked for only so far ahead. A failed cell is reported locally and does not hold the rest of the site forever.
     */
    private void release() {
        if (held.isEmpty() || closed) {
            return;
        }
        Map<Integer, Integer> depth = new HashMap<>();
        List<Integer> going = new ArrayList<>();
        for (int id : keyed) {
            if (held.containsKey(nodeOf.get(id)) && depth(id, depth) < DEPTH) {
                going.add(id);
            }
        }
        if (going.isEmpty()) {
            return;
        }
        for (int id : going) {
            UUID node = nodeOf.get(id);
            live.put(node, held.remove(node));
        }
        staff();
    }

    // One worker's stay on the site, as the colony is asked for it: come to where its first piece is done, bringing what a
    // batch of the pieces up takes, and stayed in while the site has a piece for the worker. The goods are for
    // whichever pieces they suit, not for the batch they were counted from.
    private final class Stay implements Node, Workload.Within {

        private final NodeSpec spec;
        private final Set<UUID> batch;
        private final UUID first;
        private long positionsAt;
        private long recheckPositionsAt;
        private boolean approachFailed;
        private long waitingSince = Long.MIN_VALUE;
        private int did;
        // The worker on the site for it, as it last asked for a piece, and when.
        @Nullable
        private Worker who;
        private long asked = NEVER;
        // The piece its worker is making for, and since when.
        @Nullable
        private Piece toward;
        private long towardSince;
        private long repathAt;
        @Nullable
        private BlockPos aim;
        @Nullable
        private BlockPos last;
        @Nullable
        private Path walking;

        Stay(ServerLevel level, List<Piece> batch, Stances positions) {
            Set<UUID> ids = new LinkedHashSet<>();
            Map<ItemSpec, Long> goods = new LinkedHashMap<>();
            List<Amount> left = new ArrayList<>();
            List<ToolUse> tools = new ArrayList<>();
            int ticks = WALK_TICKS;
            for (Piece piece : batch) {
                ids.add(piece.node().id());
                NodeSpec one = piece.node().spec();
                one.needs().forEach(need -> goods.merge(need.spec(), need.count(), Long::sum));
                left.addAll(one.leaves());
                one.tools().stream().filter(use -> !tools.contains(use)).forEach(tools::add);
                ticks += one.estimate();
            }
            this.batch = ids;
            int reserve = batch.stream().anyMatch(piece -> piece.work().id() >= 0) ? accessReserve(level, positions) : 0;
            if (reserve > 0) {
                goods.merge(SCAFFOLDS, (long) reserve, Math::max);
            }
            List<Need> needs = new ArrayList<>();
            // Brought in the pack: the pieces are done all over the site, not beside a store at its edge.
            goods.forEach((item, count) -> needs.add(new Need(item, count, true)));
            Piece first = batch.getFirst();
            this.first = first.node().id();
            positionsAt = geometryVersion;
            recheckPositionsAt = level.getGameTime() + GROUND_TICKS;
            this.spec = NodeSpec.of(UUID.randomUUID(), BuildContent.ID,
                    new WorkSite.AtBlock(target.cell(first.work().focus())), positions, this)
                .needs(needs)
                .leaves(Leaves.merged(left))
                .tools(tools)
                .vocation(BuildContent.trade())
                .estimate(ticks)
                .doing(first.node().spec().doing())
                .done();
        }

        @Override
        public NodeSpec spec() {
            return spec;
        }

        @Override
        public boolean ready(ServerLevel level) {
            return !closed;
        }

        @Override
        public Workload.Within.Turn next(ServerLevel level, Worker who) {
            return hand(this, level, who);
        }

        @Override
        public void ended(ServerLevel level, Node piece, Ending how) {
            if (how instanceof Ending.Done) {
                did++;
                if (toward != null && lowering(toward) && (toward.node().id().equals(piece.id())
                        || toward.node().id().equals(handed.get(piece.id())))) {
                    stopApproach(this);
                }
            }
            endings.add(new Heard(piece.id(), how));
        }

        @Override
        public Outcome commit(ServerLevel level, Worker who) {
            return Outcome.done();
        }

        @Override
        public void tick(ServerLevel level, Worker who) {
            this.who = who;
            this.asked = level.getGameTime();
            animate(this, level, who.body());
        }

        // A turn nobody could come to the site for is asked for again in a while: the site stands where it does.
        @Override
        public Recourse failing(ServerLevel level, RefusalKind why) {
            if (why == LaborRefusal.NO_WAY_THERE) {
                approachFailed = true;
                positionsAt = NEVER;
            }
            return Recourse.waitFor(REST_TICKS);
        }
    }

    private Optional<Stances> positions(ServerLevel level, Piece piece) {
        List<BlockPos> current = SiteApproach.stances(level, piece.work().focus(), piece.cells(),
            piece.work().stances());
        if (current.isEmpty()) {
            // A high piece may still be worked from the ground below while its access scaffold is raised.
            current = piece.work().stances().stream()
                .filter(feet -> SiteApproach.stands(level, feet, piece.cells())).toList();
        }
        return Stances.of(level, current);
    }

    // A hand descending into excavated air must bring its temporary access materials before the descent;
    // fetching them after the surrounding walls close would itself require that same access column.
    private int accessReserve(ServerLevel level, Stances positions) {
        int arrival = positions.cells().stream().filter(feet -> feet.in(level))
            .mapToInt(feet -> feet.block(level).getY()).max().orElse(Integer.MIN_VALUE);
        int bottom = target.cells().entrySet().stream().filter(entry -> entry.getValue().isAir())
            .mapToInt(entry -> entry.getKey().getY()).min().orElse(arrival);
        boolean upperStructure = target.cells().entrySet().stream()
            .anyMatch(entry -> !entry.getValue().isAir() && entry.getKey().getY() >= arrival - 1);
        int depth = arrival - bottom;
        return upperStructure && depth >= 3 ? Math.min(TALLEST_WAY, depth + 1) : 0;
    }

    // A turn not yet entered can outlive the floor it was sent to. Geometry changes or an actual native approach
    // failure prompt reconsideration; material shortages and unchanged failed destinations keep ordinary retries.
    private boolean refreshPositions(ServerLevel level) {
        boolean changed = false;
        for (Stay stay : List.copyOf(stays.values())) {
            if (stay.who != null || level.getGameTime() < stay.recheckPositionsAt) {
                continue;
            }
            Piece first = stay.batch.stream().map(live::get).filter(java.util.Objects::nonNull).findFirst().orElse(null);
            if (first == null || stay.positionsAt == geometryVersion && stay.first.equals(first.node().id())) {
                continue;
            }
            stay.positionsAt = geometryVersion;
            stay.recheckPositionsAt = level.getGameTime() + GROUND_TICKS;
            if (stay.first.equals(first.node().id())) {
                boolean usable = stay.spec().stances().cells().stream()
                    .anyMatch(feet -> feet.in(level) && SiteApproach.stands(level, feet.block(level), first.cells()));
                if (usable && (!stay.approachFailed || positions(level, first)
                        .filter(stay.spec().stances()::equals).isPresent())) {
                    continue;
                }
            }
            stays.remove(stay.id());
            asks.withdraw(stay.id());
            changed = true;
        }
        return changed;
    }

    /**
     * Asks for hands: a turn for each piece up that nobody is bringing the goods for, which brings them for that
     * piece and for the pieces that come up behind it as it is done, so far as one pack and one turn's time hold. So whoever comes
     * finds its work up, or brings it up itself, and nobody stands on the site holding goods for work that waits
     * on someone else. A turn ended, or a piece nobody on the site can do, is asked for again here.
     */
    private void staff() {
        ServerLevel level = standing;
        if (closed || level == null) {
            return;
        }
        if (level.getGameTime() < idleUntil) {
            staffingPending = true;
            return;
        }
        staffingPending = false;
        // A turn nothing is left for is called off while its worker is still on the way; one whose worker is here
        // ends when that one has done what it holds the goods for. Hands are asked for everything that is up, as
        // many as that takes: how many come is for the colony to say.
        for (Stay stay : List.copyOf(stays.values())) {
            stay.batch.removeIf(id -> !live.containsKey(id));
            if (stay.batch.isEmpty() && stay.who == null) {
                stays.remove(stay.id());
                asks.withdraw(stay.id());
            }
        }
        Set<UUID> spoken = new HashSet<>();
        stays.values().forEach(stay -> spoken.addAll(stay.batch));
        // Each piece not spoken for, by what it still waits on, and what waits on each.
        Map<UUID, Set<UUID>> waits = new HashMap<>();
        Map<UUID, List<Piece>> after = new HashMap<>();
        List<Piece> seeds = new ArrayList<>();
        for (Piece piece : live.values()) {
            UUID id = piece.node().id();
            if (spoken.contains(id) || piece.work().step().isEmpty()) {
                continue;
            }
            Set<UUID> before = before(piece);
            waits.put(id, before);
            before.forEach(first -> after.computeIfAbsent(first, key -> new ArrayList<>()).add(piece));
            if (before.isEmpty() && up(piece)) {
                seeds.add(piece);
            }
        }
        // A way to raise comes first: other work waits on it, whatever order it came up in.
        seeds.sort(Comparator.comparingInt(piece -> piece.work().id() < 0 ? 0 : 1));
        Set<UUID> taken = new HashSet<>();
        for (Piece seed : seeds) {
            if (!taken.add(seed.node().id())) {
                continue;
            }
            Optional<Stances> positions = positions(level, seed);
            if (positions.isEmpty()) {
                continue;
            }
            List<Piece> batch = new ArrayList<>(List.of(seed));
            Set<UUID> ids = new HashSet<>(Set.of(seed.node().id()));
            Load load = new Load(seed, seed.work().id() >= 0 ? accessReserve(level, positions.get()) : 0);
            // What comes up behind the piece first, then more of what is up at once that takes the same goods, a
            // floor to lay or a face to clear: never another kind of goods, which the colony may be short of.
            for (int at = 0; at < batch.size(); at++) {
                for (Piece next : after.getOrDefault(batch.get(at).node().id(), List.of())) {
                    UUID id = next.node().id();
                    if (taken.contains(id) || !ids.containsAll(waits.get(id)) || !load.takes(next, false)) {
                        continue;
                    }
                    taken.add(id);
                    ids.add(id);
                    batch.add(next);
                }
            }
            for (Piece more : seeds) {
                if (more.work().id() < 0 || taken.contains(more.node().id()) || !load.takes(more, true)) {
                    continue;
                }
                taken.add(more.node().id());
                batch.add(more);
            }
            Stay stay = new Stay(level, batch, positions.get());
            stays.put(stay.id(), stay);
            asks.submit(dimension, Grown.of(stay), this::ended);
        }
    }

    // What one turn takes on as it is batched: its goods, what its work leaves, and how long the work is.
    private static final class Load {

        private final Map<ItemSpec, Long> goods = new LinkedHashMap<>();
        private List<Amount> left = List.of();
        private long ticks;
        private final int reserve;

        Load(Piece first, int reserve) {
            this.reserve = reserve;
            add(first);
        }

        // Takes the piece on when the turn stays within its time and its pack, and, {@code alike}, when the piece
        // takes no goods the turn does not take already.
        boolean takes(Piece piece, boolean alike) {
            NodeSpec spec = piece.node().spec();
            if (ticks + spec.estimate() > TURN_TICKS
                || alike && spec.needs().stream().anyMatch(need -> !goods.containsKey(need.spec()))) {
                return false;
            }
            Map<ItemSpec, Long> with = new LinkedHashMap<>(goods);
            spec.needs().forEach(need -> with.merge(need.spec(), need.count(), Long::sum));
            if (reserve > 0) {
                with.merge(SCAFFOLDS, (long) reserve, Math::max);
            }
            if (cells(with) + Leaves.cells(withLeaves(left, piece)) > MOST_CELLS) {
                return false;
            }
            add(piece);
            return true;
        }

        private void add(Piece piece) {
            NodeSpec spec = piece.node().spec();
            spec.needs().forEach(need -> goods.merge(need.spec(), need.count(), Long::sum));
            left = withLeaves(left, piece);
            ticks += spec.estimate();
        }
    }

    private static List<Amount> withLeaves(List<Amount> left, Piece piece) {
        List<Amount> with = new ArrayList<>(left);
        with.addAll(piece.node().spec().leaves());
        return Leaves.merged(with);
    }

    private static int cells(Map<ItemSpec, Long> goods) {
        int cells = 0;
        for (long count : goods.values()) {
            cells += (int) ((count + 63) / 64);
        }
        return cells;
    }

    /**
     * The dependency frontier says what may be done; each hand chooses a nearby piece it has the goods for.
     * Movement continues beside the fixed work clock; a route or a prettier order never delays an operation.
     */
    private Workload.Within.Turn hand(Stay stay, ServerLevel level, Worker who) {
        long now = level.getGameTime();
        Mob body = who.body();
        stay.who = who;
        stay.asked = now;
        boolean coming = false;
        boolean full = false;
        List<Piece> choices = new ArrayList<>();
        for (Piece piece : live.values()) {
            if (piece.doing != null || piece.work().step().isEmpty() || !supplied(who, piece)) {
                continue;
            }
            if (!roomFor(who, piece)) {
                full = true;
                continue;
            }
            if (!up(piece) || now < piece.restUntil) {
                coming = true;
                continue;
            }
            choices.add(piece);
        }
        // Distance keeps a worker on one patch. The previous piece breaks ties in favour of a continuous run,
        // while each claimed piece disappears from the other hands' choices.
        choices.sort(Comparator.comparingInt((Piece piece) ->
                piece.work().step().orElseThrow().kind() == BuildJob.Kind.RAISE ? 0 : 1)
            .thenComparingDouble(piece -> score(stay, body, piece))
            .thenComparingLong(piece -> piece.work().key()).thenComparingInt(piece -> piece.work().id()));
        if (!choices.isEmpty()) {
            Piece chosen = choices.getFirst();
            chosen.doing = stay.id();
            stay.waitingSince = Long.MIN_VALUE;
            // Walking dresses the work; it never gates the next operation. Keep a destination long enough to
            // reach it instead of resetting the walk for every block laid on the way there.
            if (stay.toward == null || stay.aim == null
                || !SiteApproach.stands(level, stay.aim, chosen.cells())
                || !Reach.withinReach(stay.aim, chosen.work().focus())
                || Math.abs(chosen.work().focus().getY() - stay.toward.work().focus().getY()) > 1
                || now - stay.towardSince >= APPROACH_TICKS || lowering(chosen)) {
                stay.toward = chosen;
                stay.towardSince = now;
                approach(stay, level, body, chosen);
            }
            look(body, chosen);
            return out(stay, level, chosen);
        }
        if (!coming || full) {
            return Workload.Within.LEAVE;
        }
        if (stay.waitingSince == Long.MIN_VALUE) {
            stay.waitingSince = now;
        }
        return now - stay.waitingSince < PATIENCE_TICKS ? Workload.Within.WAIT : Workload.Within.LEAVE;
    }

    private static boolean lowering(Piece piece) {
        return piece.work().step().map(step -> step.kind() == BuildJob.Kind.LOWER).orElse(false);
    }

    private void animate(Stay stay, ServerLevel level, Mob body) {
        Piece toward = stay.toward;
        if (closed || toward == null) {
            return;
        }
        if (lowering(toward) && !live.containsKey(toward.node().id())) {
            stopApproach(stay);
            return;
        }
        if (ScaffoldNode.returning(body, temporary)) {
            return;
        }
        boolean arrived = near(level, body, stay);
        if (arrived) {
            body.getNavigation().stop();
            stay.walking = null;
            // Finished floors become available during the shift. Reconsider the working surface at a bounded
            // rate even after arrival, so a worker can leave the ground when an upper floor opens up.
            if (level.getGameTime() < stay.repathAt) {
                return;
            }
        }
        if (body.getNavigation().isDone()) {
            if (stay.walking != null && !stay.walking.isDone()
                && level.getGameTime() < stay.repathAt) {
                body.getNavigation().moveTo(stay.walking, toward.node().spec().pace());
            } else if (level.getGameTime() >= stay.repathAt) {
                approach(stay, level, body, toward);
            }
        }
    }

    private static double score(Stay stay, Mob body, Piece piece) {
        BlockPos focus = piece.work().focus();
        double distance = body.distanceToSqr(focus.getX() + 0.5D, focus.getY(), focus.getZ() + 0.5D);
        return distance + Math.abs(focus.getY() - body.getY()) * 4.0D
            + (stay.last == null ? 0.0D : stay.last.distSqr(focus) * 0.25D);
    }

    private static void look(Mob body, Piece piece) {
        BlockPos focus = piece.work().focus();
        body.getLookControl().setLookAt(focus.getX() + 0.5D, focus.getY() + 0.5D, focus.getZ() + 0.5D);
    }

    // Test only a bounded number of nearby poses per approach. Geometry is read again on a retry because the
    // preceding pieces may have changed both the floor and the space to stand in.
    private record Approach(@Nullable BlockPos aim, @Nullable Path path) {
    }

    private void approach(Stay stay, ServerLevel level, Mob body, Piece piece) {
        if (!ScaffoldNode.returning(body, temporary)) {
            walk(stay, level, body, piece, route(level, body, piece, MOST_PATHS));
        }
    }

    // A completed cleanup has no destination left. Its old route into the access column must not restart
    // after the resident has safely left it and the supporting scaffold has been removed.
    private void stopApproach(Stay stay) {
        stay.toward = null;
        stay.aim = null;
        stay.walking = null;
        if (stay.who != null) {
            stay.who.body().getNavigation().stop();
        }
    }

    private Approach route(ServerLevel level, Mob body, Piece piece, int budget) {
        List<BlockPos> tops = scaffoldTops(level, piece);
        List<BlockPos> preferred = new ArrayList<>(tops);
        preferred.addAll(piece.work().stances());
        List<BlockPos> poses = new ArrayList<>(SiteApproach.stances(level, piece.work().focus(), piece.cells(), preferred));
        poses.sort(Comparator.comparingInt((BlockPos feet) -> SiteApproach.heightGap(piece.work().focus(), feet))
            .thenComparingInt(feet -> level.getBlockState(feet).is(Blocks.SCAFFOLDING)
                || level.getBlockState(feet.below()).is(Blocks.SCAFFOLDING) ? 1 : 0)
            .thenComparingInt(feet -> tops.contains(feet) ? 0 : 1)
            .thenComparingDouble(feet -> SiteApproach.score(body.blockPosition(), feet)));
        // Reserve one attempt for the nearest current floor if the preferred upper surface is inaccessible.
        BlockPos nearest = poses.stream().min(Comparator.comparingDouble(feet ->
            SiteApproach.score(body.blockPosition(), feet))).orElse(null);
        BlockPos scaffold = poses.stream().filter(tops::contains).findFirst().orElse(null);
        List<BlockPos> attempts = new ArrayList<>(poses.subList(0,
            Math.min(Math.max(0, budget - (scaffold == null ? 1 : 2)), poses.size())));
        if (scaffold != null && !attempts.contains(scaffold)) {
            attempts.add(scaffold);
        }
        if (nearest != null && !attempts.contains(nearest)) {
            attempts.add(nearest);
        }
        for (BlockPos pose : poses) {
            if (attempts.size() >= budget) {
                break;
            }
            if (!attempts.contains(pose)) {
                attempts.add(pose);
            }
        }
        Approach fallback = new Approach(null, null);
        for (int at = 0; at < attempts.size(); at++) {
            BlockPos feet = attempts.get(at);
            Path path = body.getNavigation().createPath(feet, 0);
            if (path != null && path.canReach()) {
                improveAccess(level, body, piece, feet);
                return new Approach(feet, path);
            }
            if (at == 0 || fallback.path() == null && path != null) {
                fallback = new Approach(feet, path);
            }
        }
        improveAccess(level, body, piece, null);
        return fallback;
    }

    // Prepare a way up before the upper layers are underway. Seeing an upper floor is not evidence of a route
    // onto it; temporary access remains independent of the clock of ordinary construction operations.
    private void improveAccess(ServerLevel level, Mob body, Piece piece, @Nullable BlockPos reachable) {
        if (piece.work().step().orElseThrow().kind() != BuildJob.Kind.LAY) {
            return;
        }
        BlockPos focus = piece.work().focus();
        if (raised.stream().anyMatch(way -> Math.abs(way.column().getFirst().getX() - focus.getX()) <= WAY_APART
            && Math.abs(way.column().getFirst().getZ() - focus.getZ()) <= WAY_APART)) {
            return;
        }
        // Look ahead within this patch so an early low layer does not receive a column that is already too
        // short when the next layer is built. This uses only pending geometry, not building identities.
        int height = focus.getY();
        int remaining = 0;
        int round = WAY_ROUND + 2;
        // Bounded local lookahead: do not rescan the whole blueprint on every presentation route.
        for (BlockPos at : BlockPos.betweenClosed(focus.offset(-round, 0, -round),
                focus.offset(round, TALLEST_WAY, round))) {
            BlockState wanted = target.cells().get(at);
            if (wanted != null && !wanted.isAir() && !latched.contains(at)) {
                height = Math.max(height, at.getY());
                remaining++;
            }
        }
        if (remaining < 4 || height < body.blockPosition().getY() + 3
            || reachable != null && SiteApproach.heightGap(focus.atY(height), reachable) == 0) {
            return;
        }
        // Access reaches the top of the working floor, rather than stopping two cells below it. In a pit a
        // short column can reach the work but leaves its resident unable to climb back over the surrounding wall.
        Optional<List<BlockPos>> column = way(level, focus.atY(height + 2), true, body.blockPosition().getY() + 1);
        if (column.isEmpty()) {
            return;
        }
        BlockPos foot = column.get().getFirst();
        Step raise = Step.raise(column.get());
        Node node = node(level, raise, Stances.at(level, foot));
        live.put(node.id(), new Piece(new Layering.Work(--spare, Layering.Kind.SCAFFOLD, foot, Optional.of(raise),
            Optional.empty(), true, 0, 0L, List.of()), node));
        raised.add(new Raised(column.get()));
        staff();
    }

    private void walk(Stay stay, ServerLevel level, Mob body, Piece piece, Approach route) {
        stay.repathAt = level.getGameTime() + REPATH_TICKS;
        stay.aim = route.aim();
        stay.walking = route.path();
        // Reuse a partial route when none reaches a pose; do not launch another unbudgeted path search.
        if (route.path() != null) {
            body.getNavigation().moveTo(route.path(), piece.node().spec().pace());
        } else {
            body.getNavigation().stop();
        }
    }

    private BuildNode node(ServerLevel level, Step step, Stances footings) {
        BuildNode node = BuildNode.of(step.job(level, target, footings, latched), (ended, how) -> { }, temporary);
        if (step.kind() == BuildJob.Kind.RAISE || step.kind() == BuildJob.Kind.LOWER || step.kind() == BuildJob.Kind.JOIN) {
            return node;
        }
        return node.keeping(latched::contains);
    }

    private Workload.Within.Turn out(Stay stay, ServerLevel level, Piece piece) {
        stay.last = piece.work().focus();
        Node node = node(level, piece.work().step().orElseThrow(), Stances.WHEREVER);
        piece.doing = node.id();
        handed.put(node.id(), piece.node().id());
        return new Workload.Within.Turn.Piece(node);
    }

    private boolean near(ServerLevel level, Mob body, Stay stay) {
        BlockPos aim = stay.aim;
        return aim != null && stay.toward != null
            && body.distanceToSqr(aim.getX() + 0.5D, body.getY(), aim.getZ() + 0.5D) <= 0.65D * 0.65D
            && Math.abs(body.getY() - aim.getY()) <= 0.6D
            && SiteApproach.stands(level, aim, stay.toward.cells());
    }

    // Every exposed scaffold surface can be a destination, including the end of a horizontal walkway.
    private List<BlockPos> scaffoldTops(ServerLevel level, Piece piece) {
        List<BlockPos> tops = new ArrayList<>();
        Step step = piece.work().step().orElseThrow();
        if (step.kind() == BuildJob.Kind.RAISE || step.kind() == BuildJob.Kind.LOWER) {
            return tops;
        }
        for (WorldPos cell : temporary.cells()) {
            if (!cell.in(level) || temporary.contains(cell.above())) {
                continue;
            }
            BlockPos top = cell.block(level).above();
            if (Reach.withinReach(top, piece.work().focus()) && SiteApproach.stands(level, top, piece.cells())) {
                tops.add(top);
            }
        }
        return tops;
    }

    // Now and then, where the site stands: how many pieces are up, and what keeps the rest of those from being done.
    private void tell(ServerLevel level) {
        long now = level.getGameTime();
        if (now - toldAt < TOLD_TICKS || live.isEmpty()) {
            return;
        }
        toldAt = now;
        List<Worker> present = new ArrayList<>();
        for (Stay stay : stays.values()) {
            if (stay.who != null && now - stay.asked <= PRESENT_TICKS) {
                present.add(stay.who);
            }
        }
        int up = 0;
        int doing = 0;
        int resting = 0;
        int unstood = 0;
        int unheld = 0;
        int spoken = 0;
        Map<String, Integer> wanting = new java.util.TreeMap<>();
        for (Piece piece : live.values()) {
            UUID id = piece.node().id();
            spoken += stays.values().stream().anyMatch(stay -> stay.batch.contains(id)) ? 1 : 0;
            if (piece.doing != null) {
                doing++;
                continue;
            }
            if (!up(piece)) {
                continue;
            }
            up++;
            resting += now < piece.restUntil ? 1 : 0;
            unstood += piece.shortOf > 0 ? 1 : 0;
            if (present.stream().noneMatch(who -> supplied(who, piece))) {
                unheld++;
                piece.node().spec().needs().forEach(need -> wanting.merge(String.valueOf(need.spec()), 1, Integer::sum));
            }
        }
        LOGGER.info("site {} live={} held={} up={} doing={} resting={} unstood={} unheld={} wanting={} spoken={} turns={} "
            + "present={} raised={} left={}", order.toString().substring(0, 8), live.size(), held.size(), up, doing,
            resting, unstood, unheld, wanting, spoken, stays.size(), present.size(), raised.size(), left());
    }

    // A piece up for a while with nobody on the site holding what it takes is batched afresh: whoever was to bring
    // its goods has left with them, or sleeps, or is far off, and the goods are no one's in particular.
    private boolean refund(ServerLevel level) {
        long now = level.getGameTime();
        List<Worker> present = new ArrayList<>();
        for (Stay stay : stays.values()) {
            if (stay.who != null && now - stay.asked <= PRESENT_TICKS) {
                present.add(stay.who);
            }
        }
        // What a turn still on its way is to bring for is not starved: one the colony cannot bring yet it tries
        // again in its own time, and tells what it is short of; batched afresh, it would only start over.
        Set<UUID> coming = new HashSet<>();
        for (Stay stay : stays.values()) {
            if (stay.who == null) {
                coming.addAll(stay.batch);
            }
        }
        boolean freed = false;
        for (Piece piece : live.values()) {
            if (piece.doing != null || !up(piece) || coming.contains(piece.node().id())
                    || present.stream().anyMatch(who -> supplied(who, piece))) {
                piece.starvedSince = Long.MIN_VALUE;
                continue;
            }
            if (piece.starvedSince == Long.MIN_VALUE) {
                piece.starvedSince = now;
            } else if (now - piece.starvedSince >= STARVED_TICKS) {
                UUID id = piece.node().id();
                for (Stay stay : stays.values()) {
                    freed |= stay.batch.remove(id);
                }
                piece.starvedSince = now;
            }
        }
        return freed;
    }

    /**
     * Raises scaffolds by what is too high to look reached from the ground, and takes them down when the work is
     * done. A piece up with nowhere to stand within a few blocks under it, and no scaffold anywhere near, has a
     * supported access structure raised beside it, with horizontal extensions when nearby vertical shafts
     * are blocked. What a scaffold stands on is not cleared from under it. With nothing else left to do,
     * whole connected structures come down, and what they stood on is cleared last.
     */
    private boolean access(ServerLevel level) {
        long now = level.getGameTime();
        if (now - accessAt < ACCESS_TICKS) {
            return false;
        }
        accessAt = now;
        if (refund(level)) {
            staff();
        }
        tell(level);
        boolean changed = false;
        if (latched.size() == target.cells().size() && joined.containsAll(target.joints())) {
            for (Piece piece : List.copyOf(live.values())) {
                if (piece.doing == null && piece.work().id() < 0 && piece.work().step()
                        .filter(step -> step.kind() == BuildJob.Kind.RAISE
                            && step.after().keySet().stream().noneMatch(cell -> temporary.contains(target.cell(cell)))
                            && !neededForReturn(level, step))
                        .isPresent()) {
                    // No scaffold was placed and no materials spent. Finished work no longer needs this request.
                    live.remove(piece.node().id());
                    changed = true;
                }
            }
        }
        changed |= raised.removeIf(way -> !temporary.contains(target.cell(way.column().getFirst()))
            && live.values().stream().noneMatch(piece -> piece.work().step()
                .filter(step -> step.kind() == BuildJob.Kind.RAISE && step.after().containsKey(way.column().getFirst()))
                .isPresent()));
        int raising = 0;
        boolean others = !held.isEmpty();
        for (Piece piece : live.values()) {
            if (piece.way != null && !live.containsKey(piece.way)) {
                piece.way = null;
            }
            raising += piece.way != null ? 1 : 0;
            others |= piece.work().id() >= 0 && raised.stream().noneMatch(way -> piece.cells().contains(way.under()));
        }
        // Clean each owned connected structure together, including horizontal decks and remnants
        // whose original support has since disappeared.
        if (!others && (!raised.isEmpty() || !temporary.isEmpty())) {
            for (List<WorldPos> structure : temporary.structures()) {
                if (structure.stream().anyMatch(cell -> !cell.in(level))) {
                    continue;
                }
                List<BlockPos> cells = structure.stream().map(cell -> cell.block(level)).toList();
                BlockPos foot = cells.getFirst();
                boolean lowering = live.values().stream().anyMatch(piece -> piece.work().step()
                    .filter(step -> step.kind() == BuildJob.Kind.LOWER
                        && cells.stream().anyMatch(step.after()::containsKey)).isPresent());
                if (!lowering) {
                    Step lower = Step.lower(cells);
                    Node node = node(level, lower, Stances.at(level, foot));
                    live.put(node.id(), new Piece(new Layering.Work(--spare, Layering.Kind.SCAFFOLD, foot,
                        Optional.of(lower), Optional.empty(), true, 0, 0L, List.of()), node));
                    changed = true;
                }
            }
            if (changed) {
                staff();
            }
            return changed;
        }
        int looked = 0;
        for (Piece piece : List.copyOf(live.values())) {
            if (raising >= MOST_WAYS_AT_ONCE || looked >= MOST_LOOKED_AT) {
                break;
            }
            if (piece.looked || piece.work().id() < 0 || !up(piece)) {
                continue;
            }
            piece.looked = true;
            looked++;
            BlockPos focus = piece.work().focus();
            boolean served = false;
            for (Raised way : raised) {
                BlockPos foot = way.column().getFirst();
                served |= Math.abs(foot.getX() - focus.getX()) <= WAY_APART
                    && Math.abs(foot.getZ() - focus.getZ()) <= WAY_APART;
            }
            if (served) {
                continue;
            }
            Optional<List<BlockPos>> column = way(level, piece);
            if (column.isEmpty()) {
                continue;
            }
            BlockPos foot = column.get().getFirst();
            Step raise = Step.raise(column.get());
            Node node = node(level, raise, Stances.at(level, foot));
            live.put(node.id(), new Piece(new Layering.Work(--spare, Layering.Kind.SCAFFOLD, foot, Optional.of(raise),
                Optional.empty(), true, 0, 0L, List.of()), node));
            raised.add(new Raised(column.get()));
            piece.way = node.id();
            raising++;
            changed = true;
        }
        if (changed) {
            staff();
        }
        return changed;
    }

    private boolean neededForReturn(ServerLevel level, Step step) {
        List<BlockPos> column = List.copyOf(step.after().keySet());
        if (column.isEmpty()) {
            return false;
        }
        net.minecraft.world.phys.AABB bounds = new net.minecraft.world.phys.AABB(column.getFirst());
        for (BlockPos cell : column) {
            bounds = bounds.minmax(new net.minecraft.world.phys.AABB(cell.above()));
        }
        return level.getEntitiesOfClass(Mob.class, bounds.inflate(6.0D, 0.0D, 6.0D)).stream()
            .anyMatch(mob -> ScaffoldNode.neededForReturn(level, mob, column));
    }

    // The column to raise by a piece too high over any floor near it to look reached from there: beside the
    // piece, up from the nearest floor through open air to just under it. None when there is somewhere to stand
    // within a few blocks under the piece, or no open column near it.
    private Optional<List<BlockPos>> way(ServerLevel level, Piece piece) {
        return way(level, piece.work().focus(), false);
    }

    private Optional<List<BlockPos>> way(ServerLevel level, BlockPos focus, boolean access) {
        return way(level, focus, access, Integer.MAX_VALUE);
    }

    private Optional<List<BlockPos>> way(ServerLevel level, BlockPos focus, boolean access, int highestBase) {
        List<BlockPos> best = null;
        for (int dx = -WAY_ROUND; dx <= WAY_ROUND; dx++) {
            for (int dz = -WAY_ROUND; dz <= WAY_ROUND; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                List<BlockPos> column = new ArrayList<>();
                BlockPos cell = focus.offset(dx, -1, dz);
                // A floor level with the piece, or just under it, is somewhere to do it from: no scaffold is wanted.
                if (level.getBlockState(cell).isFaceSturdy(level, cell, Direction.UP)
                        && level.getBlockState(cell.above()).getCollisionShape(level, cell.above()).isEmpty()) {
                    if (!access) {
                        return Optional.empty();
                    }
                    continue;
                }
                if (!clearFor(level, cell) || !clearFor(level, cell.above())) {
                    continue;
                }
                cell = cell.below();
                while (column.size() < TALLEST_WAY && clearFor(level, cell)) {
                    column.add(cell);
                    cell = cell.below();
                }
                if (column.size() >= TALLEST_WAY || !level.getBlockState(cell).isFaceSturdy(level, cell, Direction.UP)) {
                    continue;
                }
                // A floor this near under the piece is near enough to do it from: no scaffold is wanted at all.
                if (column.size() < (access ? 1 : WAY_DROP)) {
                    if (access) {
                        continue;
                    }
                    return Optional.empty();
                }
                // Runtime access must start on the resident's lower working layer, not on the very
                // wall or isolated platform it is meant to reach.
                if (column.getLast().getY() > highestBase) {
                    continue;
                }
                if (best == null || column.size() < best.size()) {
                    best = column;
                }
            }
        }
        if (best != null) {
            return Optional.of(List.copyOf(best.reversed()));
        }
        return ScaffoldAccess.find(level, focus, cell -> clearFor(level, cell), access ? 1 : WAY_DROP, TALLEST_WAY)
            .filter(column -> column.getFirst().getY() <= highestBase);
    }

    // Open air a scaffold may stand in: nothing there now, and nothing the order still has to put there.
    private boolean clearFor(ServerLevel level, BlockPos cell) {
        BlockState wanted = target.cells().get(cell);
        return level.hasChunkAt(cell) && level.getBlockState(cell).isAir()
            && (wanted == null || wanted.isAir() || latched.contains(cell));
    }

    // Whether every piece this one waits for is done.
    private boolean up(Piece piece) {
        if (piece.way != null && live.containsKey(piece.way)) {
            return false;
        }
        for (Raised way : raised) {
            if (piece.cells().contains(way.under()) && temporary.contains(target.cell(way.column().getFirst()))) {
                return false;
            }
        }
        for (int before : waitsOn.getOrDefault(piece.work().id(), Set.of())) {
            UUID first = nodeOf.get(before);
            if (first != null && (live.containsKey(first) || held.containsKey(first))) {
                return false;
            }
        }
        return true;
    }

    // Whether the pack takes what the piece leaves. A piece that spends goods frees room in the doing, so it is let
    // be: the worker brought those goods in for it, and turned away it would carry them off again.
    private static boolean roomFor(Worker who, Piece piece) {
        if (lowering(piece)) {
            // Cleanup returns plain scaffolding exactly, so it can reuse the stack the raise drew from.
            // Generic block drops still need the conservative empty-slot check below.
            Container pack = who.pack();
            ItemStack scaffold = new ItemStack(Items.SCAFFOLDING);
            int left = piece.cells().size();
            int capacity = pack.getMaxStackSize(scaffold);
            for (int slot = 0; slot < pack.getContainerSize(); slot++) {
                ItemStack there = pack.getItem(slot);
                if (Containers.accepts(pack, slot, scaffold)) {
                    if (there.isEmpty()) {
                        left -= capacity;
                    } else if (ItemStack.isSameItemSameComponents(there, scaffold)) {
                        left -= Math.max(0, capacity - there.getCount());
                    }
                }
                if (left <= 0) {
                    return true;
                }
            }
            return false;
        }
        NodeSpec spec = piece.node().spec();
        return spec.leaves().isEmpty() || !spec.needs().isEmpty() || Leaves.fit(who.pack(), spec.leaves());
    }

    // Whether the worker holds what the piece takes, in its pack.
    private static boolean supplied(Worker who, Piece piece) {
        for (Need need : piece.node().spec().needs()) {
            if (count(who.pack(), need.spec()) < need.count()) {
                return false;
            }
        }
        return true;
    }

    private static long count(Container held, ItemSpec spec) {
        long count = 0;
        for (int slot = 0; slot < held.getContainerSize(); slot++) {
            ItemStack stack = held.getItem(slot);
            if (!stack.isEmpty() && Goods.matches(stack, spec)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    // Remaining physical prerequisite depth, walked without recursion for tall builds. A cycle is defensive
    // infinity here; Layering resolves cycles before exposing its executable dependency graph.
    private int depth(int id, Map<Integer, Integer> known) {
        Integer had = known.get(id);
        if (had != null) {
            return had;
        }
        Set<Integer> open = new HashSet<>();
        ArrayDeque<Integer> stack = new ArrayDeque<>();
        stack.push(id);
        while (!stack.isEmpty()) {
            int at = stack.peek();
            if (known.containsKey(at)) {
                stack.pop();
                continue;
            }
            UUID node = nodeOf.get(at);
            if (!held.containsKey(node) && !live.containsKey(node)) {
                known.put(at, -1);
                stack.pop();
                continue;
            }
            if (open.add(at)) {
                for (int before : waitsOn.getOrDefault(at, Set.of())) {
                    if (open.contains(before) && !known.containsKey(before)) {
                        known.put(before, FAR);
                    } else if (!known.containsKey(before)) {
                        stack.push(before);
                    }
                }
                continue;
            }
            int deep = 0;
            for (int before : waitsOn.getOrDefault(at, Set.of())) {
                int under = known.getOrDefault(before, FAR);
                if (under >= 0) {
                    deep = Math.max(deep, Math.min(FAR, under + 1));
                }
            }
            known.put(at, deep);
            open.remove(at);
            stack.pop();
        }
        return known.get(id);
    }

    // Scaffolds an earlier build left standing come down, each column as one piece of its own.
    private void lowerSpare(ServerLevel level) {
        for (List<WorldPos> column : temporary.structures()) {
            List<BlockPos> cells = new ArrayList<>();
            for (WorldPos cell : column) {
                if (cell.in(level)) {
                    cells.add(cell.block(level));
                }
            }
            if (cells.size() != column.size() || cells.isEmpty()) {
                continue;
            }
            Optional<Stances> footings = Stances.of(level, besideFoot(level, cells.getFirst()));
            if (footings.isEmpty()) {
                continue;
            }
            Step lower = Step.lower(cells);
            Node node = node(level, lower, footings.get());
            live.put(node.id(), new Piece(new Layering.Work(--spare, Layering.Kind.SCAFFOLD, cells.getFirst(),
                Optional.of(lower), Optional.empty(), true, 0, 0L, List.of()), node));
        }
        staff();
    }

    private static List<BlockPos> besideFoot(ServerLevel level, BlockPos foot) {
        List<BlockPos> found = new ArrayList<>();
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -SPARE_SPAN; dx <= SPARE_SPAN; dx++) {
                for (int dz = -SPARE_SPAN; dz <= SPARE_SPAN; dz++) {
                    BlockPos feet = foot.offset(dx, dy, dz);
                    if ((dx != 0 || dz != 0) && level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
                        && level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()
                        && level.getBlockState(feet.below()).isFaceSturdy(level, feet.below(), Direction.UP)) {
                        found.add(feet);
                    }
                }
            }
        }
        return found;
    }

    private void listen(ServerLevel level) {
        BlockPos min = null;
        BlockPos max = null;
        for (BlockPos cell : target.cells().keySet()) {
            min = min == null ? cell : BlockPos.min(min, cell);
            max = max == null ? cell : BlockPos.max(max, cell);
        }
        if (min != null) {
            listening = BlockChanges.listen(level, BoundingBox.fromCorners(min, max).inflatedBy(5), cell -> {
                told.add(cell);
                geometryVersion++;
            });
        }
    }

    void ended(UUID node, Ending how) {
        endings.add(new Heard(node, how));
    }

    /** The pieces still to do that the piece waits for, by their nodes. */
    Set<UUID> before(Piece piece) {
        Set<UUID> before = new LinkedHashSet<>();
        for (int id : waitsOn.getOrDefault(piece.work().id(), Set.of())) {
            UUID first = nodeOf.get(id);
            if (first != null && (live.containsKey(first) || held.containsKey(first))) {
                before.add(first);
            }
        }
        return before;
    }

    /** The turns on the site asked for and not ended yet. */
    int turns() {
        return stays.size();
    }

    /**
     * Takes in what was heard since the last tick: pieces ended, blocks set. Answers whether any cell was met, or
     * any piece came or went.
     */
    boolean tick(ServerLevel level) {
        if (closed) {
            return false;
        }
        standing = level;
        boolean changed = access(level);
        for (Heard heard; (heard = endings.poll()) != null; ) {
            changed |= end(level, heard);
        }
        boolean geometryChanged = !told.isEmpty();
        if (geometryChanged) {
            Set<UUID> touched = new LinkedHashSet<>();
            for (BlockPos cell : List.copyOf(told)) {
                BlockState wanted = target.cells().get(cell);
                if (wanted != null && !latched.contains(cell)
                    && target.satisfied(level, cell)) {
                    latched.add(cell);
                    refused.remove(cell);
                    changed = true;
                    UUID piece = pieceAt.get(cell);
                    if (piece != null) {
                        touched.add(piece);
                    }
                }
            }
            told.clear();
            touched.forEach(this::dropIfMet);
        }
        changed |= refreshPositions(level);
        if (changed || geometryChanged || staffingPending && level.getGameTime() >= idleUntil) {
            joinWhatIsLaid(level);
            release();
            staff();
        }
        // With nothing left to do, the turns still asked for are for nothing.
        if (live.isEmpty() && held.isEmpty() && !stays.isEmpty()) {
            stays.keySet().forEach(asks::withdraw);
            stays.clear();
        }
        return changed;
    }

    private boolean end(ServerLevel level, Heard heard) {
        Stay stay = stays.remove(heard.node());
        if (stay != null) {
            for (Piece piece : live.values()) {
                if (stay.id().equals(piece.doing)) {
                    piece.doing = null;
                }
            }
            // The only turn out, and it came to nothing: hands are not asked for again at once, since whatever
            // kept its worker from every piece is likely still so.
            if (stay.did == 0 && stays.isEmpty()) {
                idleUntil = level.getGameTime() + IDLE_TICKS;
            }
            return true;
        }
        UUID of = handed.remove(heard.node());
        Piece piece = live.get(of == null ? heard.node() : of);
        if (piece == null) {
            return false;
        }
        UUID id = piece.node().id();
        piece.doing = null;
        Step step = piece.work().step().orElse(null);
        boolean done = heard.how() instanceof Ending.Done;
        // A piece that lays none of the target's cells, a scaffold taken down, is met only by being done.
        boolean met = step != null && step.after().keySet().stream().anyMatch(target.cells()::containsKey);
        if (step != null) {
            for (BlockPos cell : step.after().keySet()) {
                BlockState wanted = target.cells().get(cell);
                if (wanted == null || latched.contains(cell)) {
                    continue;
                }
                // A shot fired is a shot done, as for the cannon: what the world makes of the cell afterwards
                // never undoes it.
                if (done && BuildPlanner.satisfied(step.after().get(cell), wanted) || target.satisfied(level, cell)) {
                    latched.add(cell);
                    refused.remove(cell);
                } else {
                    met = false;
                }
            }
        }
        if (piece.work().joint().isPresent()) {
            Joint joint = piece.work().joint().get();
            met = joined.contains(joint) || Joints.joiner().joined(level, joint.from(), joint.to());
            if (met) {
                joined.add(joint);
            } else if (done) {
                joints.add(joint);
            }
        }
        if (done || met) {
            live.remove(id);
            return true;
        }
        // A hitch that passes, someone in the way or the walk giving out, rests the piece a while and no more;
        // only a piece that cannot be built is counted against, and at last given up.
        piece.restUntil = level.getGameTime() + REST_TICKS;
        boolean hitch = heard.how() instanceof Ending.Failed failed && failed.why() != BuildRefusal.NO_WAY_TO_BUILD
            || heard.how() instanceof Ending.Dropped;
        if (hitch || ++piece.tries < MOST_TRIES) {
            return true;
        }
        live.remove(id);
        RefusalKind why = heard.how() instanceof Ending.Failed failed ? failed.why() : BuildRefusal.NO_WAY_TO_BUILD;
        BuildRefusal named = why instanceof BuildRefusal refusal ? refusal : BuildRefusal.NO_WAY_TO_BUILD;
        if (step != null) {
            step.after().keySet().stream().filter(target.cells()::containsKey).forEach(cell -> refused.put(cell, named));
        }
        return true;
    }

    // A piece whose every cell someone else met is not to be done again: it is withdrawn, and what comes after it
    // goes on without it.
    private void dropIfMet(UUID id) {
        boolean asked = live.containsKey(id);
        Piece piece = asked ? live.get(id) : held.get(id);
        if (piece == null || piece.work().step().isEmpty()) {
            return;
        }
        for (BlockPos cell : piece.work().step().get().after().keySet()) {
            if (target.cells().containsKey(cell) && !latched.contains(cell)) {
                return;
            }
        }
        if (asked) {
            live.remove(id);
        } else {
            held.remove(id);
        }
    }

    // A joint goes in once both its ends are laid: what it costs is read off the blocks it joins.
    private void joinWhatIsLaid(ServerLevel level) {
        for (Joint joint : List.copyOf(joints)) {
            if (!latched.contains(joint.from()) || !latched.contains(joint.to())) {
                continue;
            }
            joints.remove(joint);
            if (joined.contains(joint) || Joints.joiner().joined(level, joint.from(), joint.to())) {
                joined.add(joint);
                continue;
            }
            Optional<List<ItemStack>> cost = Joints.joiner().cost(level, joint.from(), joint.to());
            if (cost.isEmpty()) {
                refused.put(joint.from(), BuildRefusal.NO_WAY_TO_BUILD);
                continue;
            }
            List<Need> needs = new ArrayList<>();
            for (ItemStack stack : cost.get()) {
                needs.add(new Need(ItemSpec.of(BuiltInRegistries.ITEM.getKey(stack.getItem())), stack.getCount()));
            }
            Step step = Step.join(level, joint, needs);
            Optional<Stances> footings = Stances.of(level, besideFoot(level, joint.from()));
            if (footings.isEmpty()) {
                refused.put(joint.from(), BuildRefusal.NO_STANCE);
                continue;
            }
            Node node = node(level, step, footings.get());
            live.put(node.id(), new Piece(new Layering.Work(--spare, Layering.Kind.JOIN, joint.from(), Optional.of(step),
                Optional.of(joint), true, 0, 0L, List.of()), node));
        }
        staff();
    }

    /** Every cell met, every joint made, every piece ended: scaffolds left over included. */
    boolean complete() {
        return !closed && stays.isEmpty() && live.isEmpty() && held.isEmpty() && joints.isEmpty()
            && latched.size() == target.cells().size() && joined.containsAll(target.joints()) && temporary.isEmpty();
    }

    boolean idle() {
        return live.isEmpty() && held.isEmpty();
    }

    /** The cells met so far, as the realm has them, to be kept with the order. */
    Set<BlockPos> met() {
        Set<BlockPos> kept = new LinkedHashSet<>();
        latched.forEach(cell -> kept.add(cell.subtract(target.storageOrigin())));
        return kept;
    }

    Set<Joint> joined() {
        Set<Joint> kept = new LinkedHashSet<>();
        joined.forEach(joint -> kept.add(joint.moved(target.storageOrigin().multiply(-1))));
        return Set.copyOf(kept);
    }

    Set<BlockPos> metCells() {
        return Set.copyOf(latched);
    }

    Set<Joint> metJoints() {
        return Set.copyOf(joined);
    }

    int left() {
        return target.cells().size() - latched.size();
    }

    List<Piece> pieces() {
        return List.copyOf(live.values());
    }

    Map<BlockPos, BuildRefusal> refusals() {
        return Map.copyOf(refused);
    }

    Optional<BuildOrderView> progress() {
        return progress;
    }

    void progress(Optional<BuildOrderView> seen) {
        progress = seen;
    }

    /** Gives the order's work up: every piece still out is withdrawn, and the site hears no more. */
    void close() {
        if (closed) {
            return;
        }
        stays.keySet().forEach(asks::withdraw);
        forget();
    }

    /** Stops hearing, the colony closing: its work goes with it, so nothing is withdrawn. */
    void forget() {
        closed = true;
        live.clear();
        held.clear();
        reopen.run();
        reopen = () -> { };
        stays.clear();
        handed.clear();
        if (listening != null) {
            listening.close();
            listening = null;
        }
    }
}
