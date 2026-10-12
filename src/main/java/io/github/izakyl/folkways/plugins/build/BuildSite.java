package io.github.izakyl.folkways.plugins.build;

import io.github.izakyl.folkways.core.api.ground.BlockChanges;
import io.github.izakyl.folkways.core.api.terms.Closures;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.Reach;
import io.github.izakyl.folkways.core.api.terms.Realm;
import io.github.izakyl.folkways.core.api.terms.RefusalKind;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Before;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Grown;
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
import net.minecraft.world.Container;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * One order's work, planned once and kept. The whole of what is left is laid out by {@link Layering} as pieces in the
 * order they go in. The colony is not asked for the pieces: it is asked for hands, each a turn on the site that one
 * worker goes into, bringing what a batch of the pieces needs, and stays in while the site has a piece for it. The
 * site hands the pieces out itself, to whoever is on it and holds what a piece takes, as each comes up and where
 * the ground as it stands then lets it be done from; so a piece never waits on work queued behind something else
 * in another worker's day. From then on it is heard: a piece done, or a block set in the site by anyone, meets its
 * cells, a cell met is met for good, and what that frees comes up. The order is built when every cell has been met
 * and every joint made. While it stands the ways are closed over the site, so it is come to by its edge.
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

    // How far past the work the ways are closed: room to stand to work at its edge.
    private static final int CLOSED_MARGIN = 2;

    private static final int FAR = Integer.MAX_VALUE / 2;

    // How many pieces one turn brings the goods for, a piece up and what comes up behind it, in how many cells of
    // a pack; and how the plan reckons the walking between them.
    private static final int MOST_PIECES = 8;
    private static final int MOST_CELLS = 6;
    private static final int WALK_TICKS = 40;

    // How long a worker on the site waits for a piece to come up before it leaves, how long a piece that hit a
    // hitch rests before it is handed out again, and how long the site asks for nobody after a turn that came to
    // nothing.
    private static final int PATIENCE_TICKS = 300;
    // How long a piece up may go with nobody on the site holding what it takes before hands are asked for it anew,
    // and how lately a worker must have asked for a piece to count as on the site.
    private static final int STARVED_TICKS = 200;
    // How long a turn asked for is given to bring its worker before the pieces it was to bring for are batched
    // afresh, and how many pieces of the same goods one turn takes on where many are up at once.
    private static final int LATE_TICKS = 2400;
    private static final int MOST_ABREAST = 32;
    private static final int PRESENT_TICKS = 40;
    private static final int REST_TICKS = 100;
    private static final int IDLE_TICKS = 200;

    // How often the site looks for pieces nobody can come to, how many it raises a way to at once, how tall such
    // a way may be, and how far over the ground cut off it looks for where to raise one.
    private static final int ACCESS_TICKS = 40;
    private static final int MOST_WAYS_AT_ONCE = 1;
    private static final int TALLEST_WAY = 32;
    private static final int MOST_CUT_OFF = 512;

    // A scaffold the site raised to come to work by: its cells, bottom first.
    private record Raised(List<BlockPos> column) {

        BlockPos under() {
            return column.getFirst().below();
        }
    }

    private static final int SPARE_SPAN = 2;

    // How long the ground is taken as read before it is read again, once something in it has changed.
    private static final int GROUND_TICKS = 20;

    // How many places to stand a worker is given for a piece, and how many ways in it is brought to.
    private static final int MOST_STANDS = 12;
    private static final int MOST_GATES = 6;
    private static final int MOST_SPREAD = 24;

    // How often a worker on its way over the site is stood afresh, its walk giving out each time.
    private static final int MOST_ASKED = 3;

    // How near a piece a worker makes for it before doing it, across and up or down: near enough to look as if it
    // reached, which is all that is asked on a site. And how long it is given to come that near: after that the
    // piece is done from wherever it has got to.
    private static final double NEAR_ACROSS = 6.0D;
    private static final int NEAR_UP = 5;
    private static final int NEAR_DOWN = 8;
    private static final int APPROACH_TICKS = 300;

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
    private final Set<Integer> givenUp = new HashSet<>();
    private Runnable reopen = () -> { };
    private final Map<BlockPos, UUID> pieceAt = new HashMap<>();
    private final List<Joint> joints = new ArrayList<>();
    private final Map<BlockPos, BuildRefusal> refused = new LinkedHashMap<>();
    private final Set<BlockPos> told = new LinkedHashSet<>();
    private final ConcurrentLinkedQueue<Heard> endings = new ConcurrentLinkedQueue<>();
    @Nullable
    private BlockChanges.Heard listening;
    @Nullable
    private ResourceKey<Level> dimension;
    private Optional<BuildOrderView> progress = Optional.empty();
    private boolean closed;
    @Nullable
    private BoundingBox shut;
    // Pieces the plan did not lay out, a scaffold left over or a joint, are numbered down from here.
    private int spare;
    // The turns asked for and not ended yet, each a worker's stay on the site; the nodes handed out to the workers
    // on it, by the piece each is for; and when a turn that came to nothing ended.
    private final Map<UUID, Stay> stays = new LinkedHashMap<>();
    private final Map<UUID, UUID> handed = new HashMap<>();
    private long idleUntil = Long.MIN_VALUE;
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
        dimension = level.dimension();
        standing = level;
        met.forEach(cell -> latched.add(cell.offset(target.storageOrigin())));
        for (Map.Entry<BlockPos, BlockState> cell : target.cells().entrySet()) {
            if (level.hasChunkAt(cell.getKey())
                && BuildPlanner.satisfied(level.getBlockState(cell.getKey()), cell.getValue())) {
                latched.add(cell.getKey());
            }
        }
        latched.retainAll(target.cells().keySet());
        Layering.Plan plan = Layering.of(level, target, latched);
        refused.putAll(plan.refused());
        Map<Integer, Node> nodes = new LinkedHashMap<>();
        for (Layering.Work work : plan.works()) {
            if (work.kind() == Layering.Kind.JOIN) {
                work.joint().ifPresent(joints::add);
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
            Node node = BuildNode.of(step.job(target, footings.get()), (ended, how) -> { }, temporary);
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

    // The ways are closed over the site and a little past it while it stands: it is come to by its edge and walked
    // in, and nobody's way runs through it.
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
        shut = BoundingBox.fromCorners(min, max).inflatedBy(CLOSED_MARGIN);
        reopen = Closures.close(level, shut);
    }

    /**
     * Brings up every piece held back that fewer than {@link #DEPTH} pieces still stand before, one waiting on the
     * next: hands are asked for only so far ahead. A piece given up stands before what waits on it for good.
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

    // One worker's stay on the site, as the colony is asked for it: come to at the site's edge, bringing what a
    // batch of the pieces up takes, and stayed in while the site has a piece for the worker. The goods are for
    // whichever pieces they suit, not for the batch they were counted from.
    private final class Stay implements Node, Workload.Within {

        private final NodeSpec spec;
        private final Set<UUID> batch;
        private long waitingSince = Long.MIN_VALUE;
        private int did;
        // The worker on the site for it, as it last asked for a piece, and when.
        @Nullable
        private Worker who;
        private long asked = NEVER;
        private final long born;
        // The piece its worker is making for, and since when.
        @Nullable
        private Piece toward;
        private long towardSince;

        Stay(ServerLevel level, List<Piece> batch) {
            Set<UUID> ids = new LinkedHashSet<>();
            Map<ItemSpec, Long> goods = new LinkedHashMap<>();
            List<ToolUse> tools = new ArrayList<>();
            int ticks = 0;
            for (Piece piece : batch) {
                ids.add(piece.node().id());
                NodeSpec one = piece.node().spec();
                one.needs().forEach(need -> goods.merge(need.spec(), need.count(), Long::sum));
                one.tools().stream().filter(use -> !tools.contains(use)).forEach(tools::add);
                ticks += one.estimate() + WALK_TICKS;
            }
            this.batch = ids;
            this.born = level.getGameTime();
            List<Need> needs = new ArrayList<>();
            // Brought in the pack: the pieces are done all over the site, not beside a store at its edge.
            goods.forEach((item, count) -> needs.add(new Need(item, count, true)));
            Piece first = batch.getFirst();
            this.spec = NodeSpec.of(UUID.randomUUID(), BuildContent.ID,
                    new WorkSite.AtBlock(target.cell(first.work().focus())), edge(level, first), this)
                .needs(needs)
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
            }
            endings.add(new Heard(piece.id(), how));
        }

        @Override
        public Outcome commit(ServerLevel level, Worker who) {
            return Outcome.done();
        }

        // A turn nobody could come to the site for is asked for again in a while: the site stands where it does.
        @Override
        public Recourse failing(ServerLevel level, RefusalKind why) {
            return Recourse.waitFor(REST_TICKS);
        }
    }

    // Where a turn is come to: the open ground just outside the closed box, the nearest of it and then a spread of
    // the rest all round. Which of it a way leads to is for the ways to say: a site on a hillside has much that none
    // does.
    private Stances edge(ServerLevel level, Piece first) {
        BlockPos focus = first.work().focus();
        List<BlockPos> ways = new ArrayList<>();
        for (Closures.Closed one : Closures.in(level)) {
            if (shut != null && one.box().equals(shut)) {
                one.doors().forEach(door -> ways.add(door.cell()));
            }
        }
        ways.sort(Comparator.comparingDouble(way -> way.distSqr(focus)));
        List<BlockPos> chosen = new ArrayList<>(ways.subList(0, Math.min(MOST_GATES, ways.size())));
        int rest = ways.size() - chosen.size();
        for (int at = 0; at < MOST_SPREAD && rest > 0; at++) {
            chosen.add(ways.get(MOST_GATES + (int) ((long) at * rest / MOST_SPREAD)));
        }
        return Stances.of(level, new LinkedHashSet<>(chosen)).orElseGet(() -> first.node().spec().stances());
    }

    /**
     * Asks for hands: a turn for each piece up that nobody is bringing the goods for, which brings them for that
     * piece and for the pieces that come up behind it as it is done, so far as one pack holds. So whoever comes
     * finds its work up, or brings it up itself, and nobody stands on the site holding goods for work that waits
     * on someone else. A turn ended, or a piece nobody on the site can do, is asked for again here.
     */
    private void staff() {
        ServerLevel level = standing;
        if (closed || level == null || level.getGameTime() < idleUntil) {
            return;
        }
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
            List<Piece> batch = new ArrayList<>(List.of(seed));
            Set<UUID> ids = new HashSet<>(Set.of(seed.node().id()));
            Map<ItemSpec, Long> goods = new LinkedHashMap<>();
            seed.node().spec().needs().forEach(need -> goods.merge(need.spec(), need.count(), Long::sum));
            for (int at = 0; at < batch.size() && batch.size() < MOST_PIECES; at++) {
                for (Piece next : after.getOrDefault(batch.get(at).node().id(), List.of())) {
                    UUID id = next.node().id();
                    if (batch.size() >= MOST_PIECES || taken.contains(id) || !ids.containsAll(waits.get(id))) {
                        continue;
                    }
                    Map<ItemSpec, Long> with = new LinkedHashMap<>(goods);
                    next.node().spec().needs().forEach(need -> with.merge(need.spec(), need.count(), Long::sum));
                    if (cells(with) > MOST_CELLS) {
                        continue;
                    }
                    goods.clear();
                    goods.putAll(with);
                    taken.add(id);
                    ids.add(id);
                    batch.add(next);
                }
            }
            // Where much is up at once, a floor to lay or a face to clear, one turn takes on more of what is up
            // that the same pack holds the goods for.
            for (Piece more : seeds) {
                if (batch.size() >= MOST_ABREAST) {
                    break;
                }
                if (more.work().id() < 0 || taken.contains(more.node().id())) {
                    continue;
                }
                Map<ItemSpec, Long> with = new LinkedHashMap<>(goods);
                more.node().spec().needs().forEach(need -> with.merge(need.spec(), need.count(), Long::sum));
                if (cells(with) > Math.max(1, cells(goods))) {
                    continue;
                }
                goods.clear();
                goods.putAll(with);
                taken.add(more.node().id());
                batch.add(more);
            }
            Stay stay = new Stay(level, batch);
            stays.put(stay.id(), stay);
            asks.submit(dimension, Grown.of(stay), this::ended);
        }
    }

    private static int cells(Map<ItemSpec, Long> goods) {
        int cells = 0;
        for (long count : goods.values()) {
            cells += (int) ((count + 63) / 64);
        }
        return cells;
    }

    /**
     * What the worker on the site does next. On a site nothing is walked to as a place: the worker makes for a
     * piece as near as its feet take it and does the piece from there, a scaffold being raised beside what is too
     * high to look reached. It takes the first piece up that nobody is at and that it
     * holds the goods for; with none, it waits a while when one it could do is still to come up, and otherwise
     * leaves.
     */
    private Workload.Within.Turn hand(Stay stay, ServerLevel level, Worker who) {
        long now = level.getGameTime();
        Mob body = who.body();
        stay.who = who;
        stay.asked = now;
        if (stay.toward != null) {
            Piece piece = stay.toward;
            if (!live.containsKey(piece.node().id()) || !stay.id().equals(piece.doing)) {
                stay.toward = null;
            } else if (!near(body, piece) && !body.getNavigation().isDone()
                    && now - stay.towardSince < APPROACH_TICKS) {
                return Workload.Within.WAIT;
            } else {
                // Near or not, the piece is done from wherever its feet have taken it.
                stay.toward = null;
                body.getNavigation().stop();
                piece.shortOf += near(body, piece) ? 0 : 1;
                return out(stay, piece);
            }
        }
        boolean coming = false;
        for (Piece piece : List.copyOf(live.values())) {
            if (piece.doing != null || piece.work().step().isEmpty() || !supplied(who, piece)) {
                continue;
            }
            if (!up(piece) || now < piece.restUntil) {
                coming = true;
                continue;
            }
            piece.doing = stay.id();
            stay.waitingSince = Long.MIN_VALUE;
            if (near(body, piece)) {
                return out(stay, piece);
            }
            BlockPos aim = aim(piece);
            body.getNavigation().moveTo(aim.getX() + 0.5D, aim.getY(), aim.getZ() + 0.5D,
                piece.node().spec().pace());
            stay.toward = piece;
            stay.towardSince = now;
            return Workload.Within.WAIT;
        }
        // With nothing it holds of use to any piece, the worker leaves. Holding what a piece still to come up
        // takes, it waits a while for that: its own batch comes up as it works, so a long wait is for someone
        // else's work, and the piece is batched afresh for whoever comes when it is up.
        if (!coming) {
            return Workload.Within.LEAVE;
        }
        if (stay.waitingSince == Long.MIN_VALUE) {
            stay.waitingSince = now;
        }
        return now - stay.waitingSince < PATIENCE_TICKS ? Workload.Within.WAIT : Workload.Within.LEAVE;
    }

    // The piece handed to the worker, done from wherever it stands.
    private Workload.Within.Turn out(Stay stay, Piece piece) {
        Node node = BuildNode.of(piece.work().step().orElseThrow().job(target, Stances.WHEREVER),
            (ended, how) -> { }, temporary);
        piece.doing = node.id();
        handed.put(node.id(), piece.node().id());
        return new Workload.Within.Turn.Piece(node);
    }

    // Whether the body looks near enough the piece to do it: close by across, and not too far under or over it.
    private static boolean near(Mob body, Piece piece) {
        BlockPos focus = piece.work().focus();
        double dx = focus.getX() + 0.5D - body.getX();
        double dz = focus.getZ() + 0.5D - body.getZ();
        double up = focus.getY() - body.getY();
        return dx * dx + dz * dz <= NEAR_ACROSS * NEAR_ACROSS && up <= NEAR_UP && up >= -NEAR_DOWN;
    }

    // Where a worker makes for to do the piece: the top of a scaffold raised by it, else the piece itself.
    private BlockPos aim(Piece piece) {
        BlockPos focus = piece.work().focus();
        for (Raised way : raised) {
            BlockPos top = way.column().getLast().above();
            if (Reach.withinReach(top, focus) && temporary.contains(target.cell(way.column().getFirst()))) {
                return top;
            }
        }
        return focus;
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
        // What a turn still on its way is to bring for is not starved yet, until the turn is late.
        Set<UUID> coming = new HashSet<>();
        for (Stay stay : stays.values()) {
            if (stay.who == null && now - stay.born < LATE_TICKS) {
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
     * column raised beside it, from the nearest floor up to just under it; one at a time, and few. What a scaffold stands on is not cleared from under it. With nothing else
     * left to do the scaffolds come down, and what they stood on is cleared last.
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
        boolean changed = raised.removeIf(way -> !temporary.contains(target.cell(way.column().getFirst()))
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
        // Nothing left but what the scaffolds stand on: they come down, each as a piece of its own.
        if (!others && !raised.isEmpty()) {
            for (Raised way : raised) {
                BlockPos foot = way.column().getFirst();
                boolean lowering = live.values().stream().anyMatch(piece -> piece.work().step()
                    .filter(step -> step.kind() == BuildJob.Kind.LOWER && step.after().containsKey(foot)).isPresent());
                if (!lowering && temporary.contains(target.cell(foot))) {
                    Step lower = Step.lower(way.column());
                    Node node = BuildNode.of(lower.job(target, Stances.at(level, foot)), (ended, how) -> { },
                        temporary);
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
            Node node = BuildNode.of(raise.job(target, Stances.at(level, foot)), (ended, how) -> { }, temporary);
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

    // The column to raise by a piece too high over any floor near it to look reached from there: beside the
    // piece, up from the nearest floor through open air to just under it. None when there is somewhere to stand
    // within a few blocks under the piece, or no open column near it.
    private Optional<List<BlockPos>> way(ServerLevel level, Piece piece) {
        BlockPos focus = piece.work().focus();
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
                    return Optional.empty();
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
                if (column.size() < WAY_DROP) {
                    return Optional.empty();
                }
                if (best == null || column.size() < best.size()) {
                    best = column;
                }
            }
        }
        return best == null ? Optional.empty() : Optional.of(List.copyOf(best.reversed()));
    }

    // Open air a scaffold may stand in: nothing there now, and nothing the order still has to put there.
    private boolean clearFor(ServerLevel level, BlockPos cell) {
        BlockState wanted = target.cells().get(cell);
        return level.getBlockState(cell).isAir() && (wanted == null || wanted.isAir() || latched.contains(cell));
    }

    // Whether every piece this one waits for is done.
    private boolean up(Piece piece) {
        for (Raised way : raised) {
            if (piece.cells().contains(way.under()) && temporary.contains(target.cell(way.column().getFirst()))) {
                return false;
            }
        }
        for (int before : waitsOn.getOrDefault(piece.work().id(), Set.of())) {
            UUID first = nodeOf.get(before);
            if (givenUp.contains(before) || first != null && (live.containsKey(first) || held.containsKey(first))) {
                return false;
            }
        }
        return true;
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

    // How many pieces still stand before this one, one waiting on the next: none for a piece done, and past all
    // counting behind a piece given up. Walked without recursion, since a long build chains thousands of pieces one
    // behind another; a piece found waiting, however indirectly, on itself counts as past all counting too.
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
            if (givenUp.contains(at)) {
                known.put(at, FAR);
                stack.pop();
                continue;
            }
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
        for (List<WorldPos> column : temporary.columns()) {
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
            Node node = BuildNode.of(lower.job(target, footings.get()), (ended, how) -> { }, temporary);
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
            listening = BlockChanges.listen(level, BoundingBox.fromCorners(min, max), told::add);
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
        if (!told.isEmpty()) {
            Set<UUID> touched = new LinkedHashSet<>();
            for (BlockPos cell : List.copyOf(told)) {
                BlockState wanted = target.cells().get(cell);
                if (wanted != null && !latched.contains(cell)
                    && BuildPlanner.satisfied(level.getBlockState(cell), wanted)) {
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
        if (changed) {
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
                if (wanted == null) {
                    continue;
                }
                // Work done met its cells as it was done, whatever has come of them since.
                if (done || BuildPlanner.satisfied(level.getBlockState(cell), wanted)) {
                    latched.add(cell);
                    refused.remove(cell);
                } else {
                    met = false;
                }
            }
        }
        if (piece.work().joint().isPresent()) {
            Joint joint = piece.work().joint().get();
            met = Joints.joiner().joined(level, joint.from(), joint.to());
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
        if (piece.work().id() >= 0) {
            givenUp.add(piece.work().id());
        }
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
            if (Joints.joiner().joined(level, joint.from(), joint.to())) {
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
            Node node = BuildNode.of(step.job(target, footings.get()), (ended, how) -> { }, temporary);
            live.put(node.id(), new Piece(new Layering.Work(--spare, Layering.Kind.JOIN, joint.from(), Optional.of(step),
                Optional.of(joint), true, 0, 0L, List.of()), node));
        }
        staff();
    }

    /** Every cell met, every joint made, every piece ended: scaffolds left over included. */
    boolean complete() {
        return !closed && stays.isEmpty() && live.isEmpty() && held.isEmpty() && joints.isEmpty() && target.unloaded().isEmpty()
            && latched.size() == target.cells().size();
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
