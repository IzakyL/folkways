package io.github.izakyl.folkways.efficientconstruction;

import io.github.izakyl.folkways.plugins.build.BlockData;
import io.github.izakyl.folkways.plugins.build.BuildContent;
import io.github.izakyl.folkways.plugins.build.BuildJob;
import io.github.izakyl.folkways.plugins.build.BuildPlanner;
import io.github.izakyl.folkways.plugins.build.BuildRefusal;
import io.github.izakyl.folkways.plugins.build.BuildSite;
import io.github.izakyl.folkways.plugins.build.BuildTarget;
import io.github.izakyl.folkways.plugins.build.Bystanders;
import io.github.izakyl.folkways.plugins.build.EfficientConstruction;
import io.github.izakyl.folkways.plugins.build.EfficientSite;
import io.github.izakyl.folkways.plugins.build.Joints;
import io.github.izakyl.folkways.plugins.build.Layering;
import io.github.izakyl.folkways.plugins.build.Rubble;
import io.github.izakyl.folkways.plugins.build.SchematicPlacement;
import io.github.izakyl.folkways.plugins.build.Step;
import io.github.izakyl.folkways.plugins.build.draft.Joint;

import io.github.izakyl.folkways.core.api.terms.Containers;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.StoreRule;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Need;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.WorkGesture;
import io.github.izakyl.folkways.core.api.work.WorkNoise;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.core.api.work.Xp;
import io.github.izakyl.folkways.core.engine.labor.LaborRefusal;
import io.github.izakyl.folkways.core.engine.plan.Stores;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * An order's work raised by the site itself rather than by hands on every block.
 *
 * <p>The plan is the one {@link Layering} lays out for the ordinary site, in the order it can be done. What changes
 * is who does it: a resident standing on the site is handed one shot at a time ({@link ShotNode}), and the site fires
 * it as the schematicannon would. No resident walks to a block, so nothing of the site's cost is pathfinding, and a
 * cell that needs something under it to stand is left until that thing is there.
 *
 * <p>Only created when {@link EfficientConstruction#active()} is set by the add-on, so a world without it never
 * reaches this class.
 */
final class EfficientBuilder implements EfficientSite {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("folkways-build");

    /** How many hands one asking may bring, so the plan is not flooded with journeys at once. */
    private static final int HANDS_A_PASS = 2;

    /** How long a hand stays at a site nothing has come of before it goes back to its other work. */
    private static final int PATIENCE_TICKS = 600;

    /** A run is cut by what it costs, not by a count of pieces; this is only a backstop against a runaway run. */
    private static final int MAX_SLICE_PIECES = 2048;

    /** How few pieces must be left before the site reads out why the rest will not go up. */
    private static final int TAIL_PIECES = 200;

    /**
     * How many pieces a hand takes on when its own run is done: enough to keep it busy for a while, and few enough
     * that looking for the next one it can do still costs less than laying it.
     */
    private static final int CONTINUE_PIECES = 96;

    /** How long the site waits after asking before it costs another run: a hand takes a while to be taken up. */
    private static final int ASK_REST_TICKS = 8;

    /** Ore, and what comes of it: what a mine is dug for is never left lying on the ground. */
    private static final TagKey<Block> ORE_BLOCKS =
        TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath("c", "ores"));

    /** How long a site that has nowhere to put its haul stands down before it looks again. */
    private static final int NO_ROOM_TICKS = 600;

    /** How long the site may lay nothing before its askings are taken back, one at a time and then all of them. */
    private static final int WATCH_TICKS = 600;

    /** How long after the first asking is taken back the next stage waits, and after the second. */
    private static final int WATCH_STAGE_TICKS = 200;
    private static final int WATCH_LAST_TICKS = 400;

    /** How long a site may lay nothing before it asks the colony for less material on the next asking. */
    private static final int TRIP_DOWN_TICKS = 1200;

    /** Arrivals in a row whose asking was met before the site asks for a wider trip again. */
    private static final int ARRIVALS_TO_GROW = 3;

    /** How many waits for a hand are kept to reckon what a walk to this site costs. */
    private static final int WAITS_KEPT = 8;

    /**
     * Stacks one hand is asked for at first; the site halves that when nothing comes of an asking. A resident's pack
     * holds 11 cells at its smallest, and a trip has to leave room for what the work breaks as well, so this starts
     * well under that and the configured trip is only ever a ceiling.
     */
    private static final double START_TRIP_STACKS = 4.0D;

    /** The narrowest trip the site will ask for, however little the colony can fill. */
    private static final double LEAST_TRIP_STACKS = 2.0D;

    /**
     * How long a hand may be asked for and not come before the asking is taken back and made again. This is a
     * backstop for a journey that never ends, not a travel budget: a resident walking to a site a hundred blocks
     * out takes minutes, and taking the asking back under it only sends another resident out behind it.
     */
    private static final int ASK_TICKS = 6000;

    private final UUID order;
    private final BuildSite.Asks asks;
    private final BuildTarget target;
    private final Set<BlockPos> latched;
    private final Set<Joint> joined;
    private final Map<BlockPos, BuildRefusal> refused;

    private final List<Layering.Work> works = new ArrayList<>();
    private final Map<Integer, Layering.Work> byId = new LinkedHashMap<>();
    private final Set<Integer> finished = new HashSet<>();
    private final Set<Integer> claimed = new HashSet<>();
    private final Map<UUID, PresenceNode> present = new LinkedHashMap<>();
    private final Map<UUID, Long> askedAt = new LinkedHashMap<>();
    private final List<BlockPos> stances = new ArrayList<>();

    /** Where the next slice of the plan begins: each hand takes a run of pieces and builds only those. */
    private int sliceAt;

    /** Runs handed to a hand that never came, put back in front of the cursor rather than behind it. */
    private final Set<Integer> abandoned = new LinkedHashSet<>();

    private boolean closed;
    private long staffAt = Long.MIN_VALUE;
    private long lastShotAt = Long.MIN_VALUE;
    private long toldWhyAt = Long.MIN_VALUE;
    private int asked;
    private boolean toldFirstShot;
    private boolean dirty;
    private BlockPos anchor = BlockPos.ZERO;
    private ServerLevel standing;

    /**
     * How many stacks of material the site asks one hand for, in stacks. It starts small, halves when an asking goes
     * unfilled and climbs again once askings are met, up to the configured trip: a list wider than the colony can
     * fill is a node waiting for goods, not a hand at work, and a narrow list that fills is always better than none.
     */
    private double trip = START_TRIP_STACKS;

    /** Arrivals in a row whose asking was met: enough of them and the site asks for a wider trip again. */
    private int arrivals;

    /** Where the site's still stand is being answered: 0 nothing yet, 1 one asking back, 2 half of them. */
    private int watch;
    private long watchAt = Long.MIN_VALUE;
    private int healed;

    /** The waits for a hand to reach the site, kept as a ring: what a walk here costs, averaged over the last few. */
    private final long[] waits = new long[WAITS_KEPT];
    private int waitsAt;
    private int waitsSeen;

    /** What the site's own work costs the server thread, kept between readouts. */
    private long staffNanos;
    private long staffMax;
    private int staffCalls;
    private long handNanos;
    private long handMax;
    private int handCalls;
    private long runNanos;
    private long runMax;
    private int runCalls;

    /** Until when the site stands down for want of anywhere to put what it digs out. */
    private long noRoomUntil = Long.MIN_VALUE;

    /** When the site last said it had cells left that no piece was ever asked for. */
    private long toldSettleAt = Long.MIN_VALUE;

    /** When the site last said it had nowhere to put the ore it would dig out. */
    private long toldNoRoomAt = Long.MIN_VALUE;

    /** When the site last asked for less material, so it does not do it twice in the same breath. */
    private long downAt = Long.MIN_VALUE;

    EfficientBuilder(UUID order, BuildSite.Asks asks, BuildTarget target, Set<BlockPos> latched,
            Set<Joint> joined, Map<BlockPos, BuildRefusal> refused) {
        this.order = order;
        this.asks = asks;
        this.target = target;
        this.latched = latched;
        this.joined = joined;
        this.refused = refused;
    }

    /** Takes the order up: what was met before, then the plan for what is left. */
    public void start(ServerLevel level, Set<BlockPos> met, Set<Joint> metJoints) {
        standing = level;
        lastShotAt = level.getGameTime();
        met.forEach(cell -> latched.add(cell.offset(target.storageOrigin())));
        metJoints.forEach(joint -> joined.add(joint.moved(target.storageOrigin())));
        for (Map.Entry<BlockPos, BlockState> cell : target.cells().entrySet()) {
            if (!latched.contains(cell.getKey()) && level.hasChunkAt(cell.getKey())
                && target.satisfied(level, cell.getKey())) {
                latched.add(cell.getKey());
            }
        }
        latched.retainAll(target.cells().keySet());
        joined.retainAll(target.joints());
        Layering.Plan plan = Layering.of(level, target, latched);
        refused.putAll(plan.refused());
        works.addAll(plan.works());
        works.forEach(work -> byId.put(work.id(), work));
        for (Layering.Work work : works) {
            Optional<Step> step = work.step();
            if (step.isEmpty() || work.kind() == Layering.Kind.SCAFFOLD) {
                finished.add(work.id());
                continue;
            }
            if (work.joint().isPresent() && joined.contains(work.joint().get())) {
                finished.add(work.id());
                continue;
            }
            if (work.kind() != Layering.Kind.JOIN && satisfied(level, step.get())) {
                // What the world already holds is met as well, or the site would never come to its end.
                latch(level, step.get());
                finished.add(work.id());
            }
        }
        stances.addAll(findStances(level));
        anchor = target.cells().keySet().stream().findFirst().orElse(BlockPos.ZERO);
        LOGGER.info("efficient site {}: {} cell(s), {} piece(s), {} stance(s)", shortId(),
            target.cells().size(), works.size(), stances.size());
        if (target.cells().isEmpty()) {
            return;
        }
        LOGGER.info("efficient site {}: trips start at {} stack(s), up to {}", shortId(), tripStacks(),
            EfficientConstruction.settings().stacksPerTrip());
        staff();
    }

    /** One turn of the site: hands asked for, and one hand kept company. */
    public boolean tick(ServerLevel level) {
        if (closed) {
            return false;
        }
        standing = level;
        if (finished.size() >= works.size()) {
            settleCells(level);
        }
        if (nothingLeft()) {
            return false;
        }
        forgetHandsThatNeverCame(level);
        turnDownWhenIdle(level);
        watch(level);
        if (level.getGameTime() % 400L == 0L) {
            reportTail(level);
        }
        if (level.getGameTime() % 200L == 0L) {
            reportCost();
            LOGGER.info("efficient site {}: built {}/{} cell(s), {} piece(s) left, hands here {}/{} asked, "
                + "pool {} item(s), trip {} stack(s), due {} tick(s), watch {}", shortId(), latched.size(),
                target.cells().size(), works.size() - finished.size(), arrived(), present.size(), poolItems(),
                tripStacks(), watchTicks(), watch);
        }
        long staffStart = System.nanoTime();
        staff();
        long staffSpent = System.nanoTime() - staffStart;
        staffNanos += staffSpent;
        staffCalls++;
        if (staffSpent > staffMax) {
            staffMax = staffSpent;
        }
        boolean changed = dirty;
        dirty = false;
        return changed;
    }

    /** What a hand that is here does next: a shot of its own slice of the plan, in the order the plan lays it. */
    Workload.Within.Turn hand(ServerLevel level, PresenceNode hand) {
        long started = System.nanoTime();
        try {
            return handNow(level, hand);
        } finally {
            long spent = System.nanoTime() - started;
            handNanos += spent;
            handCalls++;
            if (spent > handMax) {
                handMax = spent;
            }
        }
    }

    private Workload.Within.Turn handNow(ServerLevel level, PresenceNode hand) {
        if (closed || complete()) {
            return Workload.Within.LEAVE;
        }
        if (claimed.isEmpty() && level.getGameTime() - lastShotAt > PATIENCE_TICKS) {
            rest(200);
            return Workload.Within.LEAVE;
        }
        int unsupported = 0;
        int broke = 0;
        int toolShort = 0;
        int noGoods = 0;
        int noRoom = 0;
        boolean mine = false;
        for (int id : hand.slice()) {
            Layering.Work work = byId.get(id);
            if (work == null || finished.contains(id) || claimed.contains(id)) {
                continue;
            }
            mine = true;
            Optional<Step> maybe = work.step();
            if (maybe.isEmpty() || work.kind() == Layering.Kind.SCAFFOLD) {
                finished.add(id);
                continue;
            }
            Step step = maybe.get();
            if (work.kind() == Layering.Kind.JOIN) {
                Joint joint = work.joint().orElse(null);
                if (joint == null || joined.contains(joint)) {
                    finished.add(id);
                    continue;
                }
            } else if (satisfied(level, step)) {
                latch(level, step);
                finished.add(id);
                continue;
            }
            if (!stands(level, step)) {
                unsupported++;
                continue;
            }
            BuildJob job;
            try {
                job = step.job(level, target, Stances.WHEREVER, latched);
            } catch (RuntimeException broken) {
                if (broke++ == 0) {
                    LOGGER.warn("efficient site {}: piece {} will not address", shortId(), id, broken);
                }
                continue;
            }
            List<Need> cost = work.kind() == Layering.Kind.JOIN ? joinCost(level, work) : job.cost();
            if (cost == null) {
                continue;
            }
            // Only what this hand actually carries: a piece it cannot pay for would come back for want of goods.
            if (!hand.canPay(cost)) {
                noGoods++;
                continue;
            }
            // Nothing is dug out bare-handed: a piece that breaks a block only a tool can take waits for a hand
            // carrying one, and the run moves on to what can be done meanwhile.
            Map<BlockPos, BlockState> dug = breaking(level, job);
            if (!dug.isEmpty() && !anyRoomAtAll(hand)) {
                tellNoRoom(level);
                noRoomUntil = level.getGameTime() + NO_ROOM_TICKS;
                rest(NO_ROOM_TICKS);
                return Workload.Within.LEAVE;
            }
            if (needsTool(dug) && toolFor(dug) == null) {
                toolShort++;
                continue;
            }
            // A mine is dug for what it yields: a piece whose ore would fit nowhere waits for room, and the site
            // says how much room is missing rather than leaving the player to guess.
            int shortBy = oreRoomShort(hand, dug);
            if (shortBy > 0) {
                noRoom += shortBy;
                if (level.getGameTime() - toldNoRoomAt >= 200L) {
                    toldNoRoomAt = level.getGameTime();
                    LOGGER.info("efficient site {}: {} more cell(s) of room needed for the ore under this piece",
                        shortId(), shortBy);
                }
                continue;
            }
            claimed.add(id);
            BlockPos stance = hand.stance() == null ? anchor : hand.stance();
            hand.paid();
            return new Workload.Within.Turn.Piece(new ShotNode(this, hand, work, job, stance, cost, level));
        }
        if (level.getGameTime() - toldWhyAt >= 200L) {
            toldWhyAt = level.getGameTime();
            LOGGER.info("efficient site {}: nothing up, finished={} claimed={} unsupported={} broken={} no_tool={} "
                + "no_goods={} no_room={}", shortId(), finished.size(), claimed.size(), unsupported, broke, toolShort,
                noGoods, noRoom);
        }
        // Its own run of the plan is built and the site still holds something to build with: it takes another run
        // rather than leaving, so the material already on the site is spent before anyone walks back for more.
        if (!mine) {
            List<Integer> next = nextIds();
            if (!next.isEmpty()) {
                hand.reslice(next);
                hand.paid();
                return Workload.Within.WAIT;
            }
            rest(40);
            return Workload.Within.LEAVE;
        }
        hand.waited(level);
        if (hand.bored(level)) {
            rest(100);
            return Workload.Within.LEAVE;
        }
        return Workload.Within.WAIT;
    }

    // What this site's own work costs the server thread, in microseconds: the figures that say whether it is the
    // site keeping the tick busy or something else entirely.
    private void reportCost() {
        if (staffCalls == 0 && handCalls == 0 && runCalls == 0) {
            return;
        }
        LOGGER.info("efficient site {}: cost staff {}us avg/{}us max ({} tick(s)), hand {}us avg/{}us max "
            + "({} turn(s)), run {}us avg/{}us max ({} asking(s))", shortId(),
            staffNanos / Math.max(1, staffCalls) / 1000L, staffMax / 1000L, staffCalls,
            handNanos / Math.max(1, handCalls) / 1000L, handMax / 1000L, handCalls,
            runNanos / Math.max(1, runCalls) / 1000L, runMax / 1000L, runCalls);
        staffNanos = 0L;
        staffMax = 0L;
        staffCalls = 0;
        handNanos = 0L;
        handMax = 0L;
        handCalls = 0;
        runNanos = 0L;
        runMax = 0L;
        runCalls = 0;
    }

    // What is left of a site and why none of it has gone up: pieces that wait for something to stand on, pieces the
    // hands here cannot pay for, and pieces that could go up now. Read only once few are left, so a site with
    // thousands of them to go is not walked every few seconds.
    private void reportTail(ServerLevel level) {
        int left = works.size() - finished.size();
        if (left <= 0 || left > TAIL_PIECES) {
            return;
        }
        // What the goods side of this reads off is what the hands here carry: with none here, every piece would look
        // short of goods when the truth is that nobody came, so the line says that instead.
        if (onSite().isEmpty()) {
            LOGGER.info("efficient site {}: {} piece(s) left, no hand on site to say what they wait for",
                shortId(), left);
            return;
        }
        int waiting = 0;
        int shortOfGoods = 0;
        int ready = 0;
        for (Layering.Work work : works) {
            if (finished.contains(work.id()) || claimed.contains(work.id())
                    || work.kind() == Layering.Kind.SCAFFOLD) {
                continue;
            }
            Optional<Step> maybe = work.step();
            if (maybe.isEmpty()) {
                continue;
            }
            Step step = maybe.get();
            if (work.kind() != Layering.Kind.JOIN && !stands(level, step)) {
                waiting++;
                continue;
            }
            try {
                BuildJob job = step.job(level, target, Stances.WHEREVER, latched);
                List<Need> cost = work.kind() == Layering.Kind.JOIN ? joinCost(level, work) : job.cost();
                if (cost == null || !poolPays(cost)) {
                    shortOfGoods++;
                } else {
                    ready++;
                }
            } catch (RuntimeException broken) {
                shortOfGoods++;
            }
        }
        LOGGER.info("efficient site {}: {} piece(s) left: {} waiting to stand, {} short of goods, {} ready to lay",
            shortId(), left, waiting, shortOfGoods, ready);
    }

    /** Fires the shot the hand was given: what the goods it was handed pay for lands in the world. */
    Outcome shoot(PresenceNode hand, Layering.Work work, BuildJob job, ServerLevel level, Worker who) {
        claimed.remove(work.id());
        dirty = true;
        lastShotAt = level.getGameTime();
        watch = 0;
        healed = 0;
        if (work.kind() == Layering.Kind.JOIN) {
            Joint joint = work.joint().orElse(null);
            boolean made = false;
            if (joint != null) {
                Joints.Joining joining = Joints.joiner().join(level, joint.from(), joint.to(), who.supplied());
                if (joining.joined()) {
                    joined.add(joint);
                    made = true;
                }
                joining.left().forEach(stack -> Block.popResource(level, joint.from(), stack));
            }
            // A turn that did not join is not done: it is up for another hand, not marked behind the site's back.
            if (made) {
                finished.add(work.id());
            }
        } else {
            // Nobody is laid into a wall: a body standing where the shot goes is stepped aside, and a shot that
            // cannot be stepped clear waits rather than sealing someone in.
            if (!Bystanders.clear(level, fill(job), stances)) {
                return Outcome.failed(BuildRefusal.OCCUPIED);
            }
            int cells = fill(job).size();
            // Whatever is in the way is dug out with a hand's own tool, as a resident digging it would: the tool
            // chooses the drops and wears with every block taken.
            Map<BlockPos, BlockState> dug = breaking(level, job);
            HeldTool tool = needsTool(dug) ? toolFor(dug) : null;
            if (tool == null && needsTool(dug)) {
                // The tool went out of the pack since the piece was handed over: the piece waits for another hand.
                return Outcome.failed(BuildRefusal.OCCUPIED);
            }
            List<ItemStack> drops = lay(level, job, tool == null ? ItemStack.EMPTY : tool.stack());
            if (tool != null) {
                wear(who, tool, dug.size());
            }
            hand.laid(cells);
            work.step().ifPresent(step -> latch(level, step));
            gather(hand, level, job.focus().cell(), drops);
            finished.add(work.id());
        }
        if (!toldFirstShot && !finished.isEmpty()) {
            toldFirstShot = true;
            LOGGER.info("efficient site {}: first block at {} ({} piece(s) left)", shortId(),
                job.focus().cell(), works.size() - finished.size());
        }
        return new Outcome.Done(
            List.of(WorkNoise.placed(job.focus().cell(), level.getBlockState(job.focus().cell()))),
            List.of(), Optional.of(new Xp(BuildContent.trade(), 1)), List.of());
    }

    /** The shot was given up before it was fired: the piece is up for another hand. */
    void release(Layering.Work work) {
        claimed.remove(work.id());
    }

    /** The core has handed a shot back: the piece is free again, and the site says what became of it. */
    void dropped(ShotNode shot, Ending how) {
        claimed.remove(shot.work().id());
        if (how instanceof Ending.Failed failed && failed.why() == LaborRefusal.GOODS_GONE) {
            shot.hand().drain();
        }
        if (!(how instanceof Ending.Done)) {
            LOGGER.info("efficient site {}: a shot at {} came back {}", shortId(), shot.focus(), how);
        }
    }

    // The cells of a shot that are still to be filled, as the world stands now.
    private Map<BlockPos, BlockState> fill(BuildJob job) {
        Map<BlockPos, BlockState> after = new LinkedHashMap<>();
        job.after().forEach((pos, state) -> {
            BlockPos at = pos.cell();
            if (!latched.contains(at)) {
                after.put(at, state);
            }
        });
        return after;
    }

    private List<ItemStack> lay(ServerLevel level, BuildJob job, ItemStack tool) {
        Map<BlockPos, BlockState> before = new LinkedHashMap<>();
        job.before().forEach((pos, state) -> {
            BlockPos at = pos.cell();
            if (level.hasChunkAt(at)) {
                before.put(at, level.getBlockState(at));
            }
        });
        Map<BlockPos, BlockState> after = fill(job);
        if (after.isEmpty()) {
            return List.of();
        }
        Set<BlockPos> shot = new LinkedHashSet<>();
        job.shot().forEach(pos -> shot.add(pos.cell()));
        Map<BlockPos, BlockData> data = new LinkedHashMap<>();
        job.data().forEach((pos, payload) -> data.put(pos.cell(), payload));
        spill(level, before.keySet());
        return Rubble.lay(level, null, before, after, shot, job.focus().cell(), data, tool,
            SchematicPlacement.icon(job.cost()), true);
    }

    // The blocks this shot will break, as they stand now: what a hand digs out before the piece goes in.
    private static Map<BlockPos, BlockState> breaking(ServerLevel level, BuildJob job) {
        Map<BlockPos, BlockState> breaking = new LinkedHashMap<>();
        job.before().forEach((pos, state) -> {
            BlockPos at = pos.cell();
            if (!level.hasChunkAt(at)) {
                return;
            }
            BlockState was = level.getBlockState(at);
            if (BuildJob.inTheWay(was)) {
                breaking.put(at, was);
            }
        });
        return breaking;
    }

    // Whether any of it can only be taken with a tool, as stone and ore can.
    private static boolean needsTool(Map<BlockPos, BlockState> dug) {
        for (BlockState was : dug.values()) {
            if (was.requiresCorrectToolForDrops()) {
                return true;
            }
        }
        return false;
    }

    /**
     * A tool the hands on the site carry that takes every block of this shot which needs one, or null when none does.
     * It is any slot of any pack, not the hand in use: a resident keeps its tools where it keeps everything else.
     */
    @Nullable
    private HeldTool toolFor(Map<BlockPos, BlockState> dug) {
        List<BlockState> wanted = new ArrayList<>();
        for (BlockState was : dug.values()) {
            if (was.requiresCorrectToolForDrops()) {
                wanted.add(was);
            }
        }
        if (wanted.isEmpty()) {
            return null;
        }
        for (PresenceNode hand : onSite()) {
            Container pack = hand.pack();
            if (pack == null) {
                continue;
            }
            for (int slot = 0; slot < pack.getContainerSize(); slot++) {
                ItemStack stack = pack.getItem(slot);
                if (stack.isEmpty()) {
                    continue;
                }
                boolean takes = true;
                for (BlockState state : wanted) {
                    if (!stack.isCorrectToolForDrops(state)) {
                        takes = false;
                        break;
                    }
                }
                if (takes) {
                    return new HeldTool(pack, slot, stack);
                }
            }
        }
        return null;
    }

    // A block taken wears the tool that took it, by the game's own reckoning: unbreakable tools are left alone and
    // one worn out is gone, as it would be in a resident's hand.
    private static void wear(Worker who, HeldTool tool, int blocks) {
        if (blocks <= 0 || !tool.stack().isDamageableItem()) {
            return;
        }
        if (who.body().level() instanceof ServerLevel level) {
            tool.stack().hurtAndBreak(blocks, level, who.body(), gone -> { });
            tool.pack().setChanged();
        }
    }

    /**
     * The site this order gets: none when the order digs more than it lays. A mine or a quarry is worked from the
     * inside, and hands that raise a site from outside can only stand about its rim, far from the work and stranger
     * to watch than the ordinary crew that walks in. Those orders keep the crew they had.
     */
    static EfficientSite make(UUID order, BuildSite.Asks asks, BuildTarget target, Set<BlockPos> latched,
            Set<Joint> joined, Map<BlockPos, BuildRefusal> refused) {
        if (underground(target)) {
            LOGGER.info("efficient: order {} lies mostly below the ground it is anchored to, the crew walks in", order);
            return null;
        }
        return new EfficientBuilder(order, asks, target, latched, joined, refused);
    }

    // Whether most of an order lies well under the cell its stores are anchored to, and it reaches deep enough below
    // that no one would mistake it for a house with a cellar.
    private static boolean underground(BuildTarget target) {
        if (target.cells().isEmpty()) {
            return false;
        }
        int below = 0;
        int low = Integer.MAX_VALUE;
        int high = Integer.MIN_VALUE;
        int anchorY = target.storageOrigin().getY();
        for (BlockPos cell : target.cells().keySet()) {
            if (cell.getY() < anchorY - 2) {
                below++;
            }
            low = Math.min(low, cell.getY());
            high = Math.max(high, cell.getY());
        }
        return high - low >= 4 && below * 2 >= target.cells().size();
    }

    /** A tool one of the hands on the site carries, and where it lies, so taking blocks can wear it. */
    private record HeldTool(Container pack, int slot, ItemStack stack) {
    }

    // What the shot broke goes into the packs of the hands on the site first, then the colony's stores, and only
    // what none of them can take is left on the ground. Ore goes into a store whether or not its rules admit it: a
    // mine is dug for what it yields, and that is not to be lost to a chest that was sorted for something else.
    private void gather(PresenceNode hand, ServerLevel level, BlockPos at, List<ItemStack> drops) {
        Worker who = hand.handWorker();
        List<ItemStack> spilled = new ArrayList<>();
        for (ItemStack stack : drops) {
            if (stack.isEmpty()) {
                continue;
            }
            ItemStack left = stack;
            for (PresenceNode other : onSite()) {
                Container pack = other.pack();
                if (pack == null) {
                    continue;
                }
                left = Containers.insert(pack, left);
                if (left.isEmpty()) {
                    break;
                }
            }
            if (!left.isEmpty() && who != null) {
                left = putAway(level, who, left);
            }
            if (!left.isEmpty()) {
                spilled.add(left);
            }
        }
        if (spilled.isEmpty()) {
            return;
        }
        for (ItemStack stack : spilled) {
            Block.popResource(level, at, stack);
        }
        LOGGER.info("efficient site {}: {} drop(s) left on the ground, no pack or store had room",
            shortId(), spilled.size());
    }

    // Whether anything at all can still take what a piece would dig out: a full pack is one thing, but a site with
    // no free cell anywhere between its hands and the colony's stores would spill what it digs, and waits instead.
    private boolean anyRoomAtAll(PresenceNode hand) {
        for (PresenceNode other : onSite()) {
            Container pack = other.pack();
            if (pack != null && freeSlots(pack) > 0) {
                return true;
            }
        }
        Worker who = hand.handWorker();
        if (who != null) {
            for (Worker.Store store : who.within()) {
                if (freeSlots(store.contents()) > 0) {
                    return true;
                }
            }
        }
        return false;
    }

    // Says, above the hotbar of whoever is near the site, that it has stopped for want of somewhere to put what it
    // digs out. The word is said every time the site stands down rather than left to a log, and the log keeps the
    // slower beat.
    private void tellNoRoom(ServerLevel level) {
        Component said = Component.translatable("folkways_efficient_construction.site_no_storage",
            Component.literal(anchor.toShortString()));
        for (ServerPlayer player : level.players()) {
            if (player.blockPosition().closerThan(anchor, 128.0D)) {
                player.displayClientMessage(said, true);
            }
        }
        if (level.getGameTime() - toldNoRoomAt >= 6000L) {
            toldNoRoomAt = level.getGameTime();
            LOGGER.info("efficient site {}: stood down, no free cell anywhere for what it digs out", shortId());
        }
    }

    /**
     * How many more cells the ore of this piece needs than the packs and stores here have: zero when it all fits.
     * A piece that digs out ore nothing can take waits for room rather than spilling what a mine is dug for.
     */
    private int oreRoomShort(PresenceNode hand, Map<BlockPos, BlockState> dug) {
        int ore = 0;
        for (BlockState was : dug.values()) {
            if (was.is(ORE_BLOCKS)) {
                ore++;
            }
        }
        if (ore == 0) {
            return 0;
        }
        int free = 0;
        for (PresenceNode other : onSite()) {
            Container pack = other.pack();
            if (pack != null) {
                free += freeSlots(pack);
            }
        }
        Worker who = hand.handWorker();
        if (who != null) {
            for (Worker.Store store : who.within()) {
                free += freeSlots(store.contents());
            }
        }
        return Math.max(0, ore - free);
    }

    // Whatever a broken cell held is put on the ground rather than lost with the block.
    private static void spill(ServerLevel level, Set<BlockPos> cells) {
        for (BlockPos cell : cells) {
            BlockEntity entity = level.getBlockEntity(cell);
            if (!(entity instanceof Container container)) {
                continue;
            }
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack stack = container.getItem(slot);
                if (!stack.isEmpty()) {
                    Block.popResource(level, cell, stack);
                    container.setItem(slot, ItemStack.EMPTY);
                }
            }
        }
    }

    /**
     * A hand puts away what the site has no use for: material this run spends, food and tools stay, and the rest goes
     * into the colony's stores the hand can reach, so the pack has room for the trip it was asked to carry. Asked
     * again as it leaves, keeping only food and tools, so what it did not spend does not walk off with it.
     */
    void stow(PresenceNode hand, boolean all) {
        Worker who = hand.handWorker();
        Container pack = hand.pack();
        ServerLevel level = standing;
        if (who == null || pack == null || level == null) {
            return;
        }
        List<ItemSpec> keep = new ArrayList<>();
        if (!all) {
            for (Need need : hand.spec().needs()) {
                keep.add(need.spec());
            }
        }
        int free = freeSlots(pack);
        int wanted = all ? 0 : cellsOf(hand.spec().needs());
        if (!all && free >= wanted) {
            return;
        }
        int stowed = 0;
        for (int slot = 0; slot < pack.getContainerSize() && (all || free < wanted); slot++) {
            ItemStack stack = pack.getItem(slot);
            if (stack.isEmpty() || keep(stack, keep)) {
                continue;
            }
            ItemStack left = putAway(level, who, stack.copy());
            if (left.getCount() >= stack.getCount()) {
                continue;
            }
            pack.setItem(slot, left.isEmpty() ? ItemStack.EMPTY : left);
            stowed++;
            free++;
        }
        if (stowed > 0) {
            pack.setChanged();
            LOGGER.info("efficient site {}: a hand put away {} stack(s) to make room", shortId(), stowed);
        }
    }

    // What a hand keeps by: what it eats, what it works with, and the material its own run is due to spend.
    private static boolean keep(ItemStack stack, List<ItemSpec> wanted) {
        if (stack.getFoodProperties(null) != null || stack.isDamageableItem()) {
            return true;
        }
        for (ItemSpec spec : wanted) {
            if (Goods.matches(stack, spec)) {
                return true;
            }
        }
        return false;
    }

    // Puts away what is left of a stack into the stores the hand can reach: what fits nowhere comes back to it.
    private static ItemStack putAway(ServerLevel level, Worker who, ItemStack stack) {
        ItemStack left = stack;
        for (Worker.Store store : who.within()) {
            if (left.isEmpty()) {
                break;
            }
            if (!StoreRule.stores(Stores.rulesAt(level, store.at()))) {
                continue;
            }
            left = Containers.insert(store.contents(), left);
        }
        return left;
    }

    private static int freeSlots(Container pack) {
        int free = 0;
        for (int slot = 0; slot < pack.getContainerSize(); slot++) {
            if (pack.getItem(slot).isEmpty()) {
                free++;
            }
        }
        return free;
    }

    private static int cellsOf(List<Need> needs) {
        long cells = 0L;
        for (Need need : needs) {
            cells += (need.count() + 63L) / 64L;
        }
        return (int) Math.min(Integer.MAX_VALUE, cells);
    }

    // What a turn between two laid ends costs, read off the blocks it joins. Null when the joiner cannot quote it.
    @Nullable
    private static List<Need> joinCost(ServerLevel level, Layering.Work work) {
        Joint joint = work.joint().orElse(null);
        if (joint == null) {
            return List.of();
        }
        Optional<List<ItemStack>> quoted = Joints.joiner().cost(level, joint.from(), joint.to());
        if (quoted.isEmpty()) {
            return null;
        }
        List<Need> needs = new ArrayList<>();
        for (ItemStack stack : quoted.get()) {
            needs.add(new Need(ItemSpec.of(BuiltInRegistries.ITEM.getKey(stack.getItem())), stack.getCount()));
        }
        return needs;
    }

    private void latch(ServerLevel level, Step step) {
        for (BlockPos cell : step.after().keySet()) {
            if (target.cells().containsKey(cell) && !latched.contains(cell) && target.satisfied(level, cell)) {
                latched.add(cell);
                refused.remove(cell);
            }
        }
    }

    private boolean satisfied(ServerLevel level, Step step) {
        for (Map.Entry<BlockPos, BlockState> cell : step.after().entrySet()) {
            if (latched.contains(cell.getKey())) {
                continue;
            }
            if (!BuildPlanner.satisfied(level.getBlockState(cell.getKey()), cell.getValue())) {
                return false;
            }
        }
        return true;
    }

    // A block that needs something to lean on, or hangs in the air until it falls, waits for what holds it up.
    private static boolean stands(ServerLevel level, Step step) {
        BlockState wanted = step.after().get(step.focus());
        if (wanted == null || wanted.isAir()) {
            return true;
        }
        if (!wanted.canSurvive(level, step.focus())) {
            return false;
        }
        if (wanted.getBlock() instanceof FallingBlock) {
            BlockPos below = step.focus().below();
            return level.getBlockState(below).isFaceSturdy(level, below, Direction.UP);
        }
        return true;
    }

    /** Every cell met and every joint made: the order is built. */
    public boolean complete() {
        return !closed && latched.size() == target.cells().size() && joined.containsAll(target.joints());
    }

    /**
     * Looks once more at the cells no piece was ever asked for. A plan can come to its end with a few of them: ground
     * the order cleared, a cell the world already matched, one that only needed setting straight. Latching what the
     * world now holds lets the site finish instead of waiting for work that will never be asked for.
     */
    private void settleCells(ServerLevel level) {
        int left = 0;
        for (Map.Entry<BlockPos, BlockState> cell : target.cells().entrySet()) {
            if (latched.contains(cell.getKey())) {
                continue;
            }
            if (level.hasChunkAt(cell.getKey()) && target.satisfied(level, cell.getKey())) {
                latched.add(cell.getKey());
                refused.remove(cell.getKey());
            } else {
                left++;
            }
        }
        if (left > 0 && standing != null && standing.getGameTime() - toldSettleAt >= 200L) {
            toldSettleAt = standing.getGameTime();
            LOGGER.info("efficient site {}: {} cell(s) left with no piece to build them", shortId(), left);
        }
    }

    private boolean nothingLeft() {
        return latched.size() >= target.cells().size() && joined.containsAll(target.joints());
    }

    // The hands at the site, asked for as far as there are places to stand and work to do. A few at a time and no
    // more often than the colony can take them.
    void staff() {
        ServerLevel level = standing;
        if (closed || level == null || nothingLeft()) {
            return;
        }
        if (level.getGameTime() < staffAt || level.getGameTime() < noRoomUntil) {
            return;
        }
        int wanted = Math.min(EfficientConstruction.settings().workersPerSite(), Math.max(1, stances.size()));
        int added = 0;
        while (present.size() < wanted && added < HANDS_A_PASS) {
            // Each hand is given its own run of the plan, so what it is asked to carry is exactly what it will lay.
            Run run = nextRun();
            if (run.ids().isEmpty()) {
                return;
            }
            BlockPos stance = stances.isEmpty() ? null : stances.get(asked % stances.size());
            asked++;
            PresenceNode node = new PresenceNode(this, stance, run);
            present.put(node.id(), node);
            askedAt.put(node.id(), level.getGameTime());
            asks.submit(level.dimension(), Grown.of(node), this::ended);
            LOGGER.info("efficient site {}: asked for a hand at {} to build {} piece(s) for {} stack(s)", shortId(),
                stance == null ? "-" : stance.toShortString(), run.ids().size(), tripStacks());
            added++;
        }
        if (added > 0) {
            LOGGER.info("efficient site {}: asked for {} hand(s), {} at the site", shortId(), added, present.size());
            // A hand asked for at once is not asked for again on the next tick: what it was given takes time to be
            // taken up, and costing another run every tick is the most this site ever asks of the server thread.
            rest(ASK_REST_TICKS);
        }
    }

    /** One hand's run of the plan, and what that run spends: worked out together, so the asking matches the work. */
    record Run(List<Integer> ids, Map<ItemSpec, Long> cost) {
    }

    /**
     * The next run of the plan nobody has taken, cut by what it costs rather than by a count of pieces: a hand is
     * asked for the material its own run will spend, so it arrives with everything it will lay.
     */
    private Run nextRun() {
        long started = System.nanoTime();
        try {
            return nextRunNow();
        } finally {
            long spent = System.nanoTime() - started;
            runNanos += spent;
            runCalls++;
            if (spent > runMax) {
                runMax = spent;
            }
        }
    }

    private Run nextRunNow() {
        Map<ItemSpec, Long> cost = new LinkedHashMap<>();
        List<Integer> ids = new ArrayList<>();
        long cap = tripItems();
        long items = 0L;
        // Runs given up by a hand that never came are taken first, or they would wait for the cursor to come round.
        List<Integer> given = new ArrayList<>(abandoned);
        abandoned.clear();
        for (int id : given) {
            if (ids.size() >= MAX_SLICE_PIECES) {
                abandoned.add(id);
                continue;
            }
            if (finished.contains(id)) {
                continue;
            }
            long adding = spend(id, cost);
            if (items > 0L && items + adding > cap) {
                abandoned.add(id);
                continue;
            }
            ids.add(id);
            items += adding;
        }
        int scanned = 0;
        while (ids.size() < MAX_SLICE_PIECES && scanned < works.size()) {
            if (sliceAt >= works.size()) {
                sliceAt = 0;
            }
            Layering.Work work = works.get(sliceAt);
            if (finished.contains(work.id()) || claimed.contains(work.id())) {
                sliceAt++;
                scanned++;
                continue;
            }
            long adding = spend(work.id(), cost);
            if (items > 0L && items + adding > cap) {
                break;
            }
            sliceAt++;
            scanned++;
            ids.add(work.id());
            items += adding;
        }
        return new Run(ids, cost);
    }

    /**
     * The next pieces of the plan nobody has taken, without costing them. A hand carrying on needs work, not a
     * shopping list: only asking for a hand has to know what its run will spend, and costing a whole run is the most
     * a site ever does to the server thread in one tick.
     */
    private List<Integer> nextIds() {
        List<Integer> ids = new ArrayList<>();
        List<Integer> given = new ArrayList<>(abandoned);
        abandoned.clear();
        for (int id : given) {
            if (ids.size() >= CONTINUE_PIECES) {
                abandoned.add(id);
                continue;
            }
            if (!finished.contains(id) && !claimed.contains(id)) {
                ids.add(id);
            }
        }
        int scanned = 0;
        while (ids.size() < CONTINUE_PIECES && scanned < works.size()) {
            if (sliceAt >= works.size()) {
                sliceAt = 0;
            }
            Layering.Work work = works.get(sliceAt);
            if (finished.contains(work.id()) || claimed.contains(work.id())) {
                sliceAt++;
                scanned++;
                continue;
            }
            sliceAt++;
            scanned++;
            ids.add(work.id());
        }
        return ids;
    }

    // What one piece adds to a run's cost, and how many items that is. A piece that will not address adds nothing.
    private long spend(int id, Map<ItemSpec, Long> into) {
        Layering.Work work = byId.get(id);
        ServerLevel level = standing;
        if (work == null || level == null || work.kind() == Layering.Kind.JOIN) {
            return 0L;
        }
        Optional<Step> maybe = work.step();
        if (maybe.isEmpty()) {
            return 0L;
        }
        try {
            BuildJob job = maybe.get().job(level, target, Stances.WHEREVER, latched);
            long items = 0L;
            for (Need need : job.cost()) {
                into.merge(need.spec(), need.count(), Long::sum);
                items += need.count();
            }
            return items;
        } catch (RuntimeException broken) {
            LOGGER.warn("efficient site {}: piece {} will not address", shortId(), id, broken);
            return 0L;
        }
    }

    /** Holds the next asking back, so a site nothing can be done at does not ask again in the same breath. */
    void rest(long ticks) {
        ServerLevel level = standing;
        if (level != null) {
            staffAt = level.getGameTime() + ticks;
        }
    }

    /** The asking the site made has ended, whether a hand took it or not. */
    void ended(UUID node, Ending how) {
        PresenceNode hand = present.get(node);
        if (hand != null) {
            gone(hand, how);
        }
    }

    /** The hand's own stay is over: why it is over is the only thing that says where the site's pace went. */
    void handLeft(PresenceNode hand, Ending how) {
        if (present.containsKey(hand.id())) {
            // What it did not spend comes off it before it goes, so the material stays at the site and its pack does
            // not walk back to the colony full of another order's goods.
            stow(hand, true);
            gone(hand, how);
        }
    }

    private void gone(PresenceNode hand, Ending how) {
        present.remove(hand.id());
        askedAt.remove(hand.id());
        LOGGER.info("efficient site {}: a hand is gone after {} block(s), {} tick(s) on site [{}]: {}",
            shortId(), hand.laid(), standing == null ? -1L : hand.onSiteFor(standing),
            hand.arrived() ? "arrived" : "never arrived", why(how));
    }

    private static String why(Ending how) {
        return how instanceof Ending.Failed failed ? "failed:" + failed.why() : how.getClass().getSimpleName();
    }

    // A site that lays nothing is a site nobody can work at: the askings nobody has come for are taken back one at a
    // time, then half of them, then all of them, so the colony may put a different resident, or a different journey,
    // where this one was. An asking still within the time a walk here takes is not judged at all: a hand on its way
    // is not a hand that will not come, and taking its asking back only sends another resident out behind it.
    private void watch(ServerLevel level) {
        long now = level.getGameTime();
        long standstill = watchTicks() << Math.min(3, Math.max(0, healed - 2));
        if (now - lastShotAt < standstill) {
            watch = 0;
            return;
        }
        List<PresenceNode> stuck = stuckAsks(now);
        if (stuck.isEmpty()) {
            watch = 0;
            return;
        }
        if (watch == 0) {
            watch = 1;
            watchAt = now;
            takeAsks(stuck, true);
            return;
        }
        if (watch == 1 && now - watchAt >= WATCH_STAGE_TICKS) {
            watch = 2;
            watchAt = now;
            takeAsks(stuckAsks(now), false);
            return;
        }
        if (watch == 2 && now - watchAt >= WATCH_LAST_TICKS) {
            startAgain();
            watch = 0;
            watchAt = Long.MIN_VALUE;
            healed++;
        }
    }

    /**
     * How long a hand may be asked for and not come before it counts as stuck here. A site that has had hands before
     * knows what the walk costs and judges by the average of the last few; one that has not would only be guessing, so
     * it waits the backstop out and lets asking for less material {@link #turnDownWhenIdle} do the work instead.
     */
    private long watchTicks() {
        if (waitsSeen == 0) {
            return ASK_TICKS;
        }
        long total = 0L;
        for (int at = 0; at < waitsSeen; at++) {
            total += waits[at];
        }
        return Math.max(WATCH_TICKS, (total / waitsSeen) * 3L);
    }

    // The askings nobody has come for that have had the time a walk here takes: the only ones the site may judge.
    private List<PresenceNode> stuckAsks(long now) {
        long due = watchTicks();
        List<PresenceNode> stuck = new ArrayList<>();
        for (PresenceNode hand : present.values()) {
            Long since = askedAt.get(hand.id());
            if (hand.arrived() || hand.laid() > 0 || since == null) {
                continue;
            }
            if (now - since >= due) {
                stuck.add(hand);
            }
        }
        return stuck;
    }

    // Asks nobody came for are taken back, so the colony may send someone who can reach this site.
    private void takeAsks(List<PresenceNode> stuck, boolean onlyOne) {
        int take = onlyOne ? Math.min(1, stuck.size()) : (stuck.size() + 1) / 2;
        int done = 0;
        for (PresenceNode hand : stuck) {
            if (done >= take) {
                break;
            }
            withdraw(hand.id());
            done++;
        }
        if (done > 0) {
            LOGGER.info("efficient site {}: no hand came in {} tick(s); {} asking(s) taken back", shortId(),
                watchTicks(), done);
        }
    }

    private void withdraw(UUID node) {
        asks.withdraw(node);
        PresenceNode gone = present.remove(node);
        if (gone != null) {
            abandoned.addAll(gone.slice());
        }
        askedAt.remove(node);
    }

    // A site that lays nothing for long enough is asking for more material than the colony can bring. Asking for
    // less costs nothing and is what lets a stuck site start again by itself, so it is done whoever is on the way.
    private void turnDownWhenIdle(ServerLevel level) {
        long now = level.getGameTime();
        if (now - lastShotAt < TRIP_DOWN_TICKS) {
            downAt = Long.MIN_VALUE;
            return;
        }
        if (downAt != Long.MIN_VALUE && now - downAt < TRIP_DOWN_TICKS) {
            return;
        }
        downAt = now;
        turnDown();
    }

    // Everything the site was waiting on is let go and the site starts again: what is built stays built.
    private void startAgain() {
        for (UUID node : List.copyOf(present.keySet())) {
            asks.withdraw(node);
        }
        for (PresenceNode hand : present.values()) {
            abandoned.addAll(hand.slice());
        }
        present.clear();
        askedAt.clear();
        claimed.clear();
        rest(200);
        LOGGER.info("efficient site {}: the site stood still; every asking is taken back and it starts again",
            shortId());
    }

    // A hand asked for long ago that has never come is a place on the site nobody is using. The asking is taken
    // back, so the colony may send someone who can actually get there. The asking is collected first and the map
    // changed after, since taking one back may answer at once.
    private void forgetHandsThatNeverCame(ServerLevel level) {
        long now = level.getGameTime();
        List<UUID> stale = new ArrayList<>();
        for (Map.Entry<UUID, PresenceNode> entry : present.entrySet()) {
            Long since = askedAt.get(entry.getKey());
            if (since != null && !entry.getValue().arrived() && now - since > ASK_TICKS) {
                stale.add(entry.getKey());
            }
        }
        for (UUID node : stale) {
            withdraw(node);
            LOGGER.info("efficient site {}: a hand never came; asking again", shortId());
        }
    }

    // An asking nothing came of: the site asks for less on the next one, down to a couple of stacks.
    private void turnDown() {
        arrivals = 0;
        double least = Math.min(LEAST_TRIP_STACKS, EfficientConstruction.settings().stacksPerTrip());
        double next = Math.max(least, trip / 2.0D);
        if (next >= trip) {
            return;
        }
        trip = next;
        LOGGER.info("efficient site {}: an ask went unfilled; the next trip is {} stack(s)", shortId(), tripStacks());
    }

    // A resident has just come: whatever kept the site idle until now is not this one's to answer for.
    void touched() {
        lastShotAt = standing == null ? lastShotAt : standing.getGameTime();
    }

    void handArrived(PresenceNode node, Worker who) {
        int stacks = 0;
        Container pack = who.pack();
        for (int slot = 0; slot < pack.getContainerSize(); slot++) {
            stacks += pack.getItem(slot).isEmpty() ? 0 : 1;
        }
        int shared = 0;
        for (PresenceNode other : present.values()) {
            if (other != node && other.stance() != null && other.stance().equals(node.stance())) {
                shared++;
            }
        }
        Long since = askedAt.get(node.id());
        long waited = since == null || standing == null ? -1L : standing.getGameTime() - since;
        if (waited >= 0L) {
            waits[waitsAt] = waited;
            waitsAt = (waitsAt + 1) % WAITS_KEPT;
            if (waitsSeen < WAITS_KEPT) {
                waitsSeen++;
            }
        }
        LOGGER.info("efficient site {}: a hand is here at {} after {} tick(s) with {} stack(s), {} piece(s) to "
            + "build, {} other hand(s) on the same spot", shortId(),
            node.stance() == null ? "-" : node.stance().toShortString(), waited, stacks, node.slice().size(), shared);
        arrivals++;
        if (arrivals >= ARRIVALS_TO_GROW) {
            arrivals = 0;
            double most = EfficientConstruction.settings().stacksPerTrip();
            if (trip < most) {
                trip = Math.min(most, trip * 1.5D);
                LOGGER.info("efficient site {}: askings are being met; the next trip is {} stack(s)", shortId(),
                    tripStacks());
            }
        }
    }

    private int arrived() {
        int at = 0;
        for (PresenceNode node : present.values()) {
            at += node.arrived() ? 1 : 0;
        }
        return at;
    }

    /** The hands standing on the site: their packs, between them, are the site's pool of material. */
    List<PresenceNode> onSite() {
        List<PresenceNode> there = new ArrayList<>();
        for (PresenceNode hand : present.values()) {
            if (hand.arrived()) {
                there.add(hand);
            }
        }
        return there;
    }

    /** Whether the hands on the site hold, between them, what a piece costs. */
    boolean poolPays(List<Need> cost) {
        List<Container> packs = new ArrayList<>();
        for (PresenceNode hand : onSite()) {
            Container pack = hand.pack();
            if (pack != null) {
                packs.add(pack);
            }
        }
        for (Need need : cost) {
            long have = 0L;
            for (Container pack : packs) {
                for (int slot = 0; slot < pack.getContainerSize(); slot++) {
                    ItemStack stack = pack.getItem(slot);
                    if (!stack.isEmpty() && Goods.matches(stack, need.spec())) {
                        have += stack.getCount();
                    }
                }
            }
            if (have < need.count()) {
                return false;
            }
        }
        return true;
    }

    /** What the hands on the site are carrying, counted in items. */
    private long poolItems() {
        long items = 0L;
        for (PresenceNode hand : onSite()) {
            Container pack = hand.pack();
            if (pack == null) {
                continue;
            }
            for (int slot = 0; slot < pack.getContainerSize(); slot++) {
                items += pack.getItem(slot).getCount();
            }
        }
        return items;
    }

    /** The stay a resident is kept in while it is on the site. It carries exactly what its own run will spend. */
    NodeSpec staySpec(PresenceNode node, @Nullable BlockPos stance, Run run) {
        BlockPos where = stance == null ? anchor : stance;
        Stances footing = stance == null ? Stances.WHEREVER : Stances.at(standing, stance);
        return NodeSpec.of(UUID.randomUUID(), BuildContent.ID, new WorkSite.AtBlock(target.cell(where)), footing,
                node)
            .needs(needs(run))
            .vocation(BuildContent.trade())
            .gesture(WorkGesture.SWING)
            .focus(where)
            .estimate(100)
            .doing(BuildContent.BUILDING)
            .done();
    }

    /**
     * What one hand's run is asked for, as the colony sees it: the kinds it spends most of, up to a whole trip. The
     * counts are the run's own, so what a hand is asked for is what it will lay and no more.
     */
    List<Need> needs(Run run) {
        long left = tripItems();
        List<Map.Entry<ItemSpec, Long>> kinds = new ArrayList<>(run.cost().entrySet());
        kinds.sort(Map.Entry.<ItemSpec, Long>comparingByValue().reversed());
        List<Need> needs = new ArrayList<>();
        for (Map.Entry<ItemSpec, Long> kind : kinds) {
            if (left <= 0L) {
                break;
            }
            long count = Math.min(kind.getValue(), left);
            if (count <= 0L) {
                continue;
            }
            needs.add(new Need(kind.getKey(), count, true));
            left -= count;
        }
        return needs;
    }

    /** How many stacks one hand is asked for now, between the narrowest trip and the configured one. */
    private double tripStacks() {
        double most = EfficientConstruction.settings().stacksPerTrip();
        double least = Math.min(LEAST_TRIP_STACKS, most);
        return Math.max(least, Math.min(most, trip));
    }

    private long tripItems() {
        return Math.max(64L, (long) Math.round(tripStacks() * 64.0D));
    }

    BuildTarget target() {
        return target;
    }

    /** Gives the order up: every hand is turned away and the site hears no more. */
    public void close() {
        closed = true;
        // Withdrawing may answer at once and change the map, so the ids are copied first.
        for (UUID node : List.copyOf(present.keySet())) {
            asks.withdraw(node);
        }
        present.clear();
        askedAt.clear();
    }

    // Where a resident can stand and still be on the site: room to stand, room to breathe, floor underfoot, and
    // nothing the order still means to put there.
    private List<BlockPos> findStances(ServerLevel level) {
        List<BlockPos> found = new ArrayList<>();
        if (target.cells().isEmpty()) {
            return found;
        }
        BlockPos min = null;
        BlockPos max = null;
        for (BlockPos cell : target.cells().keySet()) {
            min = min == null ? cell : BlockPos.min(min, cell);
            max = max == null ? cell : BlockPos.max(max, cell);
        }
        if (min == null || max == null) {
            return found;
        }
        // Outside the footprint first: standing room inside is the first thing a finished wall takes away, and a
        // hand that cannot walk to its place is a place on the site nobody is using.
        collect(level, min.offset(-2, -1, -2), max.offset(2, 1, 2), min, max, true, found);
        if (found.isEmpty()) {
            collect(level, min, max, min, max, false, found);
        }
        return found;
    }

    private void collect(ServerLevel level, BlockPos min, BlockPos max, BlockPos insideMin, BlockPos insideMax,
            boolean onlyOutside, List<BlockPos> into) {
        for (BlockPos cell : BlockPos.betweenClosed(min, max)) {
            if (into.size() >= 16) {
                return;
            }
            BlockPos at = cell.immutable();
            boolean inside = at.getX() >= insideMin.getX() && at.getX() <= insideMax.getX()
                && at.getY() >= insideMin.getY() && at.getY() <= insideMax.getY()
                && at.getZ() >= insideMin.getZ() && at.getZ() <= insideMax.getZ();
            if (onlyOutside && inside) {
                continue;
            }
            if (target.occupies(level, at) || target.cells().containsKey(at)) {
                continue;
            }
            if (!level.hasChunkAt(at) || !level.getFluidState(at).isEmpty()
                || !level.getFluidState(at.above()).isEmpty()) {
                continue;
            }
            if (!level.getBlockState(at).getCollisionShape(level, at).isEmpty()
                || !level.getBlockState(at.above()).getCollisionShape(level, at.above()).isEmpty()) {
                continue;
            }
            BlockPos floor = at.below();
            if (!level.getBlockState(floor).isFaceSturdy(level, floor, Direction.UP)) {
                continue;
            }
            into.add(at);
        }
    }

    private String shortId() {
        return order.toString().substring(0, 8);
    }
}
