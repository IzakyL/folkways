package io.github.izakyl.folkways.plugins.groundwork;

import io.github.izakyl.folkways.core.api.ground.Ground;
import io.github.izakyl.folkways.core.api.ground.Grounds;
import io.github.izakyl.folkways.core.api.resident.body.Bodies;
import io.github.izakyl.folkways.core.api.terms.Footing;
import io.github.izakyl.folkways.core.api.terms.Reach;
import io.github.izakyl.folkways.core.api.terms.Realm;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.farming.FarmingContent;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.Supplier;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

// A fill publishes one head of a verified complete construction and actual-retreat sequence. A retained
// request is never permission: the complete tail, live dependency lease and real people gate every write.
final class FillPlan implements AutoCloseable {

    enum Phase {
        FILLING, RETREATING, CLOSING, WAITING, COMPLETE;
    }

    private static final int MARGIN = 8;
    private static final int BAND = 2;
    private static final int MOST_TRIALS = 64;
    private static final int MOST_EXIT_DISTANCE = 64;
    private static final int MOST_STANCES = 16;
    // Navigation accepts a stance within 0.9 horizontally and one block vertically. Leave that whole margin
    // inside hand reach, so arriving at a valid stance never creates an endlessly refused edge-of-reach job.
    private static final double WORK_REACH = Reach.PLAYER_BLOCK_REACH - Math.hypot(0.9D, 1.0D);

    // Every loaded colony in this level shares the same exclusion of future fill space from trusted exits.
    private static final Map<ServerLevel, Set<FillPlan>> LIVE = new WeakHashMap<>();
    private static final Map<ServerLevel, Long> LIVE_REVISIONS = new WeakHashMap<>();
    private static final Map<ServerLevel, ArrayDeque<Activation>> ACTIVATIONS = new WeakHashMap<>();
    private static final Map<ServerLevel, Map<UUID, Allowance>> ALLOWANCES = new WeakHashMap<>();

    record Limits(int work, long nanos) {
        static final Limits NORMAL = new Limits(16384, 2_000_000L);
    }

    // Time spent between calls is not charged to the planner. All plans and execution checks belonging to
    // this colony nevertheless share one allowance per tick, including repeated attempts by many workers.
    private static final class Allowance {
        long tick = Long.MIN_VALUE;
        int work;
        long nanos;
        int plans;
        long commitUntil = Long.MIN_VALUE;
        UUID commitZone;
    }

    private record Activation(long revision, BoundingBox bounds, boolean added) {
    }

    private record Ranked(Job job, int part, int depth, boolean passage) {
    }

    private record View(LivingEntity body, Ground ground, Ground.Access access) {
    }

    private record Change(BlockPos cell, BlockState material) {
    }

    private record Trial(Ground ground, Ground.Access access) {
    }

    // An execution intent is not a safety result. Terrain changes discard its proof, while this
    // actual supplied material can be re-proved during the runner's next windup.
    private record CommitRequest(BlockState block, long version, long until) {
    }

    private enum CommitResult {
        READY, PENDING, BLOCKED, CHANGED;
    }

    private final ServerLevel level;
    private final UUID zone;
    private final UUID colony;
    private final Set<BlockPos> cells;
    private final Realm realm;
    private final BlockPos origin;
    private final BoundingBox workBox;
    private final BoundingBox anchorBounds;
    private final Supplier<Collection<BlockPos>> external;
    private final boolean externalTargets;
    private final Limits limits;
    private BoundingBox bounds;
    private AABB worldBounds;
    private Grounds.Watch watch;
    private final Mob surveyor;
    private final Runnable changed;
    private Set<BlockPos> pending = new LinkedHashSet<>();
    private List<Job> remaining = List.of();
    private final Map<Integer, Integer> cursors = new HashMap<>();
    private volatile Map<BlockPos, Job> offered = Map.of();
    // A proved request outlives its current geometry, including the interval before the core claims it.
    // This map grants no placement permission: every retained node still runs the fresh commit proof.
    private volatile Map<BlockPos, Job> issued = Map.of();
    private Set<BlockPos> inputCells = Set.of();
    private long requestVersion;
    private volatile List<Grown> steppingAside = List.of();
    private volatile Phase phase = Phase.WAITING;
    private volatile boolean closed;
    private Set<String> wanted = Set.of();
    private List<Job> inputs = List.of();
    private BlockState material;
    private Planning planning;
    private Context context;
    private CompletionRuntime completion;
    private final Map<BlockPos, BlockState> receipts = new HashMap<>();
    private final Set<String> receiptKeys = new HashSet<>();
    private CompoundTag restoredCompletion;
    private Runnable persisted = () -> { };
    private long completionPersistCalls;
    private long totalCompletionPersistNanos;
    private long maxCompletionPersistNanos;
    private Context anchorSource;
    private Iterator<BlockPos> anchorCursor;
    private final List<BlockPos> anchors = new ArrayList<>();
    private boolean reconsider;
    private final Map<BlockPos, Candidate> proofs = new HashMap<>();
    private final Map<BlockPos, Candidate> commitProofs = new HashMap<>();
    private final Map<BlockPos, CommitRequest> commitRequests = new LinkedHashMap<>();
    private final Set<BlockPos> proving = new HashSet<>();
    private final Set<BlockPos> provingDestinations = new HashSet<>();
    // Deliberately observable by the pressure fixture, without enabling chat logging.
    private long slices;
    private long totalWork;
    private long totalNanos;
    private long maxSliceNanos;
    private int lastWork;
    private long commitAttempts;
    private long commitBudgetYields;
    private long commitRefused;
    private long commitProved;
    private long totalCommitNanos;
    private long maxCommitNanos;

    FillPlan(ServerLevel level, UUID zone, UUID colony, Set<BlockPos> cells,
            Supplier<Collection<BlockPos>> external, Runnable changed) {
        this(level, zone, colony, cells, external, changed, false, Limits.NORMAL);
    }

    // Explicit non-normal limits inject computation allowances for deterministic model fixtures.
    // They never bypass world, people, material, certificate or actual-write checks. Production uses NORMAL.
    FillPlan(ServerLevel level, UUID zone, UUID colony, Set<BlockPos> cells,
            Supplier<Collection<BlockPos>> external, Runnable changed, boolean externalTargets, Limits limits) {
        this.level = level;
        this.zone = zone;
        this.colony = colony;
        this.cells = Set.copyOf(cells);
        this.changed = changed;
        this.external = external;
        this.externalTargets = externalTargets;
        this.limits = limits;
        ALLOWANCES.computeIfAbsent(level, any -> new HashMap<>())
            .computeIfAbsent(colony, any -> new Allowance()).plans++;
        BlockPos first = cells.iterator().next();
        realm = WorldPos.of(level, first).realm();
        origin = new WorldPos(realm, BlockPos.ZERO).block(level);
        workBox = BoundingBox.encapsulatingPositions(cells).orElseThrow();
        anchorBounds = new BoundingBox(workBox.minX() - MOST_EXIT_DISTANCE, workBox.minY() - MOST_EXIT_DISTANCE,
            workBox.minZ() - MOST_EXIT_DISTANCE, workBox.maxX() + MOST_EXIT_DISTANCE,
            workBox.maxY() + MOST_EXIT_DISTANCE, workBox.maxZ() + MOST_EXIT_DISTANCE);
        reframe(List.of());
        surveyor = PersonBody.RESIDENT.get().create(level);
        surveyor.setPos(Vec3.atBottomCenterOf(first));
        LIVE.computeIfAbsent(level, any -> new HashSet<>()).add(this);
        liveChanged(true);
        if (limits.equals(Limits.NORMAL)) ServerFillBudget.register(level.getServer(), this);
    }

    private void liveChanged(boolean added) {
        long revision = LIVE_REVISIONS.merge(level, 1L, Long::sum);
        ArrayDeque<Activation> activations = ACTIVATIONS.computeIfAbsent(level, any -> new ArrayDeque<>());
        activations.addLast(new Activation(revision, new BoundingBox(workBox.minX(), workBox.minY() - 1,
            workBox.minZ(), workBox.maxX(), workBox.maxY() + 1, workBox.maxZ()), added));
        while (activations.size() > 128) activations.removeFirst();
    }

    Phase phase() {
        return phase;
    }

    boolean waiting() {
        return phase == Phase.WAITING && !pending.isEmpty();
    }

    List<Grown> steppingAside() {
        return steppingAside;
    }

    // Refresh only replaces inputs. Expensive proofs retain their progress between ordinary sweeps.
    List<Job> refresh(List<Job> jobs, Set<String> desired, BlockState block) {
        if (closed) {
            return List.of();
        }
        boolean materialChanged = !block.equals(material);
        boolean expanded = !wanted.containsAll(desired);
        boolean different = !desired.equals(wanted);
        Set<BlockPos> nextInputs = new HashSet<>();
        jobs.forEach(job -> nextInputs.add(job.cell()));
        boolean inputsChanged = !nextInputs.equals(inputCells);
        inputs = List.copyOf(jobs);
        inputCells = Set.copyOf(nextInputs);
        wanted = Set.copyOf(desired);
        material = block;
        if (materialChanged) {
            requestVersion++;
            issued = Map.of();
            receipts.clear();
            receiptKeys.clear();
            if (completion != null) {
                completion.close();
                completion = null;
            }
        } else {
            issued = retainCurrent(issued);
        }
        offered = retainCurrent(offered);
        if (inputsChanged || different) {
            // The access graph may be reusable, but a placement proof protects this exact work set.
            proofs.clear();
            commitProofs.clear();
        }
        discardStaleCommits();
        if (proving.removeIf(cell -> !requested(cell))) {
            releaseCommit(false);
        }
        if (materialChanged || expanded || planning == null) {
            restart();
        } else if (planning.done || different || inputsChanged) {
            // A normal sweep is not a revocation. Workers may still be walking to this valid face;
            // keep its offers and proofs while a new people snapshot is considered in the background.
            reconsider = true;
        }
        if (completion == null) {
            completion = new CompletionRuntime(this, level, zone, colony, cells,
                this::certificateAnchors, changed);
            if (restoredCompletion != null) {
                completion.load(restoredCompletion);
                restoredCompletion = null;
            }
        }
        Set<BlockPos> targets = new LinkedHashSet<>();
        for (BlockPos cell : cells) {
            if (wanted.contains(Job.keyOf(cell)) && !receipts.containsKey(cell)) targets.add(cell);
        }
        pending = targets;
        completion.refresh(Set.copyOf(targets), material, requestVersion);
        syncCompletion();
        return requests();
    }

    List<Job> jobs() {
        return List.copyOf(offered.values());
    }

    List<Job> requests() {
        return List.copyOf(issued.values());
    }

    long requestVersion() {
        return requestVersion;
    }

    boolean requested(BlockPos cell, long version) {
        return version == requestVersion && requested(cell);
    }

    private boolean requested(BlockPos cell) {
        return needed(cell) && issued.containsKey(cell) && executable(cell);
    }

    private boolean needed(BlockPos cell) {
        return !closed && cells.contains(cell) && wanted.contains(Job.keyOf(cell))
            && (completion != null || inputCells.contains(cell));
    }

    private boolean executable(BlockPos cell) {
        BlockPos below = cell.below();
        return level.isLoaded(cell) && level.isLoaded(below)
            && (completion != null || !(cells.contains(below) && Survey.open(level.getBlockState(below))))
            && !FarmingContent.weed(level.getBlockState(below), false, true);
    }

    private Map<BlockPos, Job> retainCurrent(Map<BlockPos, Job> jobs) {
        if (jobs.keySet().stream().allMatch(this::needed)) {
            return jobs;
        }
        Map<BlockPos, Job> kept = new LinkedHashMap<>();
        jobs.forEach((cell, job) -> {
            if (needed(cell)) {
                kept.put(cell, job);
            }
        });
        return Collections.unmodifiableMap(kept);
    }

    String stage() {
        return completion == null ? planning == null ? "idle" : planning.stage
            : completion.nextJob().isPresent() ? "complete" : completion.status();
    }

    String completionStatus() { return completion == null ? "uninitialized" : completion.status(); }
    String completionReason() { return completion == null ? "awaiting project input" : completion.reason(); }
    int completionCursor() { return completion == null ? 0 : completion.cursor(); }
    boolean complete() { return completion != null && completion.complete(); }
    Optional<UUID> worker() { return completion == null ? Optional.empty() : completion.worker(); }

    void persistCompletionWith(Runnable persist) {
        persisted = persist;
    }

    void restoreCompletion(CompoundTag facts) {
        if (completion != null) throw new IllegalStateException("completion facts must precede the first refresh");
        restoredCompletion = facts.copy();
    }

    CompoundTag saveCompletion() {
        return completion != null ? completion.save()
            : restoredCompletion == null ? new CompoundTag() : restoredCompletion.copy();
    }

    boolean receipted(String key) {
        return receiptKeys.contains(key);
    }

    private void persistCompletion() {
        long began = System.nanoTime();
        try {
            persisted.run();
        } finally {
            long elapsed = System.nanoTime() - began;
            completionPersistCalls++;
            totalCompletionPersistNanos += elapsed;
            maxCompletionPersistNanos = Math.max(maxCompletionPersistNanos, elapsed);
        }
    }

    boolean certificateExterior(BlockPos feet) {
        return !closed && outsideAll(feet) && clearAsideStance(surveyor, feet);
    }

    private List<BlockPos> certificateAnchors(Ground.Budget budget) {
        if (closed || budget.exhausted()) return null;
        // An absent exterior is a discovery result, not a permanent proof. Removing a nearby
        // fill can release a previously excluded store approach even outside this watch's margin.
        // This check runs only when the runtime needs anchors; it does not revoke a valid witness.
        if (context == null || !context.anchorsValid() || !context.valid()) {
            context = new Context();
            planning = new Planning(context);
        }
        if (!context.advance(budget)) return null;
        if (anchorSource != context) {
            anchorSource = context;
            anchorCursor = new ArrayList<>(context.trusted).iterator();
            anchors.clear();
        }
        while (anchorCursor.hasNext()) {
            if (!budget.tryStep()) return null;
            BlockPos feet = anchorCursor.next();
            if (certificateExterior(feet)) anchors.add(feet);
        }
        return List.copyOf(anchors);
    }

    private void syncCompletion() {
        if (completion == null) return;
        Optional<Job> head = completion.nextJob();
        Map<BlockPos, Job> next = head.map(job -> Map.of(job.cell(), job)).orElse(Map.of());
        if (!offered.equals(next)) offered = next;
        if (head.isPresent() || completion.complete()) {
            if (!issued.equals(next)) issued = next;
        } else {
            // An already certified logical request can wait for its replacement proof. This map is
            // never an execution permission; permits requires the complete current tail certificate.
            issued = retainCurrent(issued);
        }
        List<Grown> moves = completion.steppingAside();
        if (!steppingAside.equals(moves)) steppingAside = List.copyOf(moves);
        phase = completion.complete() ? Phase.COMPLETE : !moves.isEmpty() ? Phase.RETREATING
            : head.isPresent() ? Phase.FILLING : Phase.WAITING;
    }

    boolean advance() {
        return allotted(this::advance);
    }

    // A deterministic work-only budget is injectable by GameTests. A yield never discards a frontier.
    boolean advance(Ground.Budget budget) {
        if (closed || material == null || budget.exhausted()) {
            return false;
        }
        if (completion != null) {
            Map<BlockPos, Job> previous = issued;
            Map<BlockPos, Job> face = offered;
            List<Grown> moves = steppingAside;
            discardStaleCommits();
            Map<UUID, Allowance> allowances = ALLOWANCES.get(level);
            Allowance allowance = allowances == null ? null : allowances.get(colony);
            if (allowance != null && allowance.commitZone != null && !zone.equals(allowance.commitZone)
                    && level.getServer().getTickCount() <= allowance.commitUntil) return false;
            boolean wasComplete = completion.complete();
            boolean changed = completion.advance(budget);
            syncCompletion();
            if (!wasComplete && completion.complete()) persistCompletion();
            return changed || previous != issued || face != offered || moves != steppingAside;
        }
        discardStaleCommits();
        if (context != null && !context.valid()) {
            restart();
        }
        if (!commitRequests.isEmpty()) {
            yieldToCommit();
        }
        Map<UUID, Allowance> allowances = ALLOWANCES.get(level);
        Allowance allowance = allowances == null ? null : allowances.get(colony);
        if (allowance != null && allowance.commitZone != null
                && level.getServer().getTickCount() <= allowance.commitUntil) {
            if (zone.equals(allowance.commitZone)) {
                advanceCommit(budget);
            }
            return false;
        }
        if (reconsider) {
            if (!context.advance(budget) || budget.exhausted()) {
                return false;
            }
            // With no offered work, discover newly available anchors as well (for example another
            // colony withdrawing a fill which had excluded our only exterior). A live offered face
            // retains its exact exit geometry and its caches while workers approach it.
            Context next = offered.isEmpty() ? new Context() : context.withPeople();
            if (!next.valid()) {
                restart();
            } else {
                context = next;
                planning = new Planning(context);
                reconsider = false;
            }
        }
        if (planning == null) {
            return false;
        }
        Map<BlockPos, Job> before = offered;
        Map<BlockPos, Job> requests = issued;
        List<Grown> moves = steppingAside;
        planning.advance(budget);
        return before != offered || requests != issued || moves != steppingAside;
    }

    private void restart() {
        releaseCommit(false);
        proofs.clear();
        commitProofs.clear();
        proving.clear();
        provingDestinations.clear();
        discardStaleCommits();
        proving.addAll(commitRequests.keySet());
        context = new Context();
        planning = new Planning(context);
        reconsider = false;
        offered = Map.of();
        steppingAside = List.of();
        phase = Phase.WAITING;
    }

    private <T> T allotted(Function<Ground.Budget, T> call) {
        if (closed) {
            return call.apply(new Ground.Budget(0, 0));
        }
        if (limits.equals(Limits.NORMAL)) {
            return ServerFillBudget.allotted(level.getServer(), this, budget -> {
                int before = budget.used();
                long started = System.nanoTime();
                try {
                    return call.apply(budget);
                } finally {
                    long elapsed = System.nanoTime() - started;
                    slices++;
                    lastWork = budget.used() - before;
                    totalWork += lastWork;
                    totalNanos += elapsed;
                    maxSliceNanos = Math.max(maxSliceNanos, elapsed);
                }
            });
        }
        Allowance allowance = ALLOWANCES.computeIfAbsent(level, any -> new HashMap<>())
            .computeIfAbsent(colony, any -> new Allowance());
        long tick = level.getServer().getTickCount();
        if (allowance.tick != tick) {
            allowance.tick = tick;
            allowance.work = limits.work();
            allowance.nanos = limits.nanos();
        }
        Ground.Budget budget = new Ground.Budget(Math.max(0, allowance.work),
            limits.nanos() <= 0 ? 0 : Math.max(1L, allowance.nanos));
        long started = System.nanoTime();
        try {
            return call.apply(budget);
        } finally {
            long elapsed = System.nanoTime() - started;
            allowance.work = Math.max(0, allowance.work - budget.used());
            if (limits.nanos() > 0) {
                allowance.nanos = Math.max(0L, allowance.nanos - elapsed);
                if (allowance.nanos == 0) {
                    allowance.work = 0;
                }
            }
            slices++;
            lastWork = budget.used();
            totalWork += lastWork;
            totalNanos += elapsed;
            maxSliceNanos = Math.max(maxSliceNanos, elapsed);
        }
    }
    boolean offered(BlockPos cell) {
        return !closed && offered.containsKey(cell);
    }

    Optional<GroundRefusal> permits(BlockPos cell, BlockState block, Mob worker) {
        long began = System.nanoTime();
        commitAttempts++;
        try {
            return allotted(budget -> {
                // Cancellation takes priority even when another check has spent this tick's budget.
                discardStaleCommits();
                CommitResult result = permits(cell, block, worker, budget);
                Optional<GroundRefusal> answer = switch (result) {
                    case READY -> Optional.empty();
                    case CHANGED -> Optional.of(GroundRefusal.FILL_ACCESS_CHANGED);
                    default -> Optional.of(GroundRefusal.FILL_ACCESS_BLOCKED);
                };
                if (result == CommitResult.PENDING) {
                    proving.add(cell);
                    commitRequests.put(cell, new CommitRequest(block, requestVersion,
                        level.getServer().getTickCount() + 200L));
                    commitBudgetYields++;
                    yieldToCommit();
                } else {
                    commitRequests.remove(cell);
                    proving.remove(cell);
                    if (commitRequests.isEmpty()) {
                        releaseCommit(false);
                    }
                    if (answer.isPresent()) {
                        commitRefused++;
                        if (answer.get() == GroundRefusal.FILL_ACCESS_BLOCKED && requested(cell)) {
                            reconsider = true;
                            changed.run();
                        }
                    } else {
                        commitProved++;
                    }
                }
                return answer;
            });
        } finally {
            long elapsed = System.nanoTime() - began;
            totalCommitNanos += elapsed;
            maxCommitNanos = Math.max(maxCommitNanos, elapsed);
        }
    }

    boolean proving(BlockPos cell) {
        return !closed && proving.contains(cell);
    }

    boolean provingDestination(BlockPos feet) {
        return !closed && (completion == null ? provingDestinations.contains(feet) : completion.provingDestination(feet));
    }

    private void yieldToCommit() {
        Map<UUID, Allowance> allowances = ALLOWANCES.get(level);
        Allowance allowance = allowances == null ? null : allowances.get(colony);
        long tick = level.getServer().getTickCount();
        if (allowance != null && (allowance.commitZone == null || zone.equals(allowance.commitZone)
                || tick > allowance.commitUntil)) {
            // Keep one zone's continuation through the runner's windup. A different active owner
            // cannot be displaced by a later worker merely joining its own execution queue.
            allowance.commitZone = zone;
            allowance.commitUntil = commitRequests.isEmpty() ? tick + 200L
                : commitRequests.values().iterator().next().until();
        }
    }

    private void releaseCommit(boolean any) {
        Map<UUID, Allowance> allowances = ALLOWANCES.get(level);
        Allowance allowance = allowances == null ? null : allowances.get(colony);
        if (allowance != null && (any || zone.equals(allowance.commitZone))) {
            allowance.commitZone = null;
        }
    }

    private void discardStaleCommits() {
        long tick = level.getServer().getTickCount();
        commitRequests.entrySet().removeIf(entry -> {
            CommitRequest request = entry.getValue();
            if (request.version() != requestVersion || tick > request.until() || !requested(entry.getKey())) {
                proving.remove(entry.getKey());
                commitProofs.remove(entry.getKey());
                return true;
            }
            return false;
        });
        if (commitRequests.isEmpty() && provingDestinations.isEmpty()) {
            releaseCommit(false);
        }
    }

    // Only geometry advances here. People, their capabilities, current footings and the actual worker
    // are sampled afresh by permits; no background result can approve a placement by itself.
    private void advanceCommit(Ground.Budget budget) {
        if (commitRequests.isEmpty() || !context.advance(budget)) {
            return;
        }
        int left = commitRequests.size();
        while (left-- > 0 && !commitRequests.isEmpty() && !budget.exhausted()) {
            int used = budget.used();
            Map.Entry<BlockPos, CommitRequest> next = commitRequests.entrySet().iterator().next();
            Candidate proof = commitProof(next.getKey(), next.getValue().block());
            if (!proof.advance(budget)) {
                return;
            }
            commitRequests.remove(next.getKey());
            if (proof.stands.isEmpty()) {
                // A complete counterexample is a real refusal, not an indefinitely renewable budget wait.
                proving.remove(next.getKey());
                reconsider = true;
                changed.run();
            } else {
                // A ready proof keeps its short lease for the runner's fresh check. Rotate it behind
                // unfinished requests, including when a slice has only one unit left.
                commitRequests.put(next.getKey(), next.getValue());
            }
            // Completed entries cost one no-work visit. At most the initial queue size is visited;
            // unfinished searches retain the whole slice and never lose their only unit to a wrapper.
            if (budget.used() == used && !budget.tryStep()) {
                return;
            }
        }
        if (commitRequests.isEmpty()) {
            releaseCommit(false);
        }
    }

    private boolean counterexample(BlockPos cell, BlockState block) {
        Candidate proof = commitProofs.get(cell);
        // Even with no remaining work, a completed current-epoch counterexample is terminal.
        // An expired negative becomes unknown and is re-proved; it never grants a placement.
        return proof != null && context != null && proof.block.equals(block)
            && proof.done && proof.stands.isEmpty() && proof.source.exits.equals(context.exits)
            && context.valid() && proof.source.valid() && (proof.access == null || proof.access.valid());
    }

    private Candidate commitProof(BlockPos cell, BlockState block) {
        Candidate proof = proofs.get(cell);
        if (proof == null || !proof.block.equals(block) || !proof.source.valid()
                || !proof.source.exits.equals(context.exits) || proof.access != null && !proof.access.valid()) {
            // Broad item filters can supply a different material from the planner's prediction.
            proof = commitProofs.computeIfAbsent(cell, any -> new Candidate(context, cell, block, List.of(), null));
            if (!proof.block.equals(block) || !proof.source.valid()
                    || !proof.source.exits.equals(context.exits) || proof.access != null && !proof.access.valid()) {
                proof = new Candidate(context, cell, block, List.of(), null);
                commitProofs.put(cell, proof);
            }
        }
        return proof;
    }

    private CommitResult permits(BlockPos cell, BlockState block, Mob worker, Ground.Budget budget) {
        if (!requested(cell)) return CommitResult.CHANGED;
        if (completion == null) return CommitResult.PENDING;
        CompletionRuntime.Permit answer = completion.permits(cell, block, worker, budget);
        syncCompletion();
        return switch (answer) {
            case READY -> CommitResult.READY;
            case PENDING -> CommitResult.PENDING;
            case BLOCKED -> CommitResult.BLOCKED;
            case CHANGED -> CommitResult.CHANGED;
        };
    }
    void committed(BlockPos cell, BlockState block, Mob worker) {
        // This callback is only reached after this node's own successful setBlock. External occupancy
        // never consumes a witness prefix or creates a material receipt.
        receipts.put(cell, block);
        receiptKeys.add(Job.keyOf(cell));
        if (completion != null) completion.committed(cell, block, worker);
        commitRequests.remove(cell);
        proving.remove(cell);
        pending.remove(cell);
        Map<BlockPos, Job> next = new LinkedHashMap<>(offered);
        next.remove(cell);
        offered = Map.copyOf(next);
        Map<BlockPos, Job> requests = new LinkedHashMap<>(issued);
        requests.remove(cell);
        issued = Map.copyOf(requests);
        commitProofs.remove(cell);
        releaseCommit(false);
        // Save the real receipt and retreat obligation before a normal save can observe targets empty.
        // This is fact persistence, never a saved placement permission.
        persistCompletion();
        changed.run();
    }

    boolean safeDestination(LivingEntity body, BlockPos target) {
        return allotted(budget -> completion != null && completion.safeDestination(body, target, budget));
    }

    boolean clearOfFill(LivingEntity body) {
        Vec3 here = position(body);
        return missesPending(body.getBoundingBox().move(here.subtract(body.position())));
    }

    boolean movedAside(UUID resident) {
        if (closed) return false;
        if (completion != null) {
            if (!completion.movedAside(resident)) return false;
            syncCompletion();
            persistCompletion();
        } else {
            List<Grown> before = steppingAside;
            steppingAside = steppingAside.stream().filter(move -> move.nodes().stream()
                .noneMatch(node -> node.worker().filter(resident::equals).isPresent())).toList();
            if (before.size() == steppingAside.size()) return false;
            reconsider = true;
        }
        changed.run();
        return true;
    }

    // A candidate is a continuation. The graph search, people, boundary ports and remaining jobs all
    // retain their own cursor; one large component cannot turn a yield into a restart or hide another.
    private final class Candidate {
        final Context source;
        final BlockPos cell;
        final BlockState block;
        final List<LivingEntity> people;
        final Candidate geometry;
        final List<AABB> shape;
        final List<Job> jobs;
        View before;
        Ground after;
        Ground.Access access;
        List<BlockPos> stands = List.of();
        int stage;
        int person;
        int port;
        int job;
        View own;
        FootSearch foot;
        Ground.Access escape;
        boolean done;

        Candidate(Context source, BlockPos cell, BlockState block, List<LivingEntity> people, Candidate geometry) {
            this.source = source;
            this.cell = cell;
            this.block = block;
            this.people = people;
            this.geometry = geometry;
            jobs = inputs;
            shape = block.getCollisionShape(level, cell).move(cell.getX(), cell.getY(), cell.getZ()).toAabbs();
        }

        boolean advance(Ground.Budget budget) {
            while (!done && !budget.exhausted()) {
                if (!source.valid()) {
                    reject();
                    break;
                }
                if (stage > 4 && stage < 8 && own != null && !own.ground().valid()) {
                    // Movement normally preserves geometry, but waking, rail rules or changed movement
                    // penalties can replace this person's capability while a search is suspended.
                    stage = 4;
                    own = null;
                    foot = null;
                    escape = null;
                }
                // Searches consume their own units. A wrapper charge would starve one-unit slices.
                if (stage != 2 && stage != 3 && stage != 4 && stage != 6 && stage != 7 && stage != 8
                        && !budget.tryStep()) {
                    return false;
                }
                switch (stage) {
                    case 0 -> {
                        if (!Survey.open(level.getBlockState(cell)) || !block.canSurvive(level, cell)
                                || block.getBlock() instanceof FallingBlock
                                    && FallingBlock.isFree(level.getBlockState(cell.below()))) {
                            reject();
                        } else {
                            stage++;
                        }
                    }
                    case 1 -> {
                        if (person < people.size()) {
                            LivingEntity body = people.get(person++);
                            Vec3 here = position(body);
                            AABB box = body.getBoundingBox().move(here.subtract(body.position()));
                            if (shape.stream().anyMatch(box::intersects)) {
                                reject();
                            }
                        } else {
                            person = 0;
                            before = source.view(surveyor);
                            stage++;
                        }
                    }
                    case 2 -> {
                        if (!before.access().advance(budget)) {
                            return false;
                        }
                        Trial placed = geometry == null ? source.trial(before, cell, block) : null;
                        after = geometry == null ? placed.ground() : geometry.after;
                        access = geometry == null ? placed.access() : geometry.access;
                        stage++;
                    }
                    case 3 -> {
                        if (!access.advance(budget)) {
                            return false;
                        }
                        if (!budget.tryStep()) {
                            return false;
                        }
                        List<BlockPos> approaches = geometry == null
                            ? source.workStances(before.access(), cell) : before.access().stancesFor(cell);
                        stands = approaches.stream().filter(feet -> access.distance(feet) >= 0).toList();
                        if (stands.isEmpty()) {
                            reject();
                        } else {
                            stage++;
                        }
                    }
                    case 4 -> {
                        if (person == people.size()) {
                            stage = geometry == null ? 8 : 10;
                            continue;
                        }
                        LivingEntity body = people.get(person);
                        own = source.view(body);
                        if (!own.access().advance(budget)) {
                            return false;
                        }
                        foot = source.footing(body, own.ground());
                        if (!foot.advance(budget)) {
                            return false;
                        }
                        if (foot.feet == null) {
                            person++;
                            continue;
                        }
                        if (own.access().distance(foot.feet) >= 0) {
                            // Same-capability bodies share this explicit-exit graph. Checking their
                            // current footings costs one query each, rather than copying every path.
                            escape = source.trial(own, cell, block).access();
                            stage = 6;
                        } else {
                            escape = own.ground().beginAccess(List.of(foot.feet));
                            stage = 7;
                        }
                    }
                    case 6 -> {
                        if (!escape.advance(budget)) {
                            return false;
                        }
                        if (escape.distance(foot.feet) < 0) {
                            reject();
                        } else {
                            person++;
                            stage = 4;
                        }
                    }
                    case 7 -> {
                        if (!escape.advance(budget)) {
                            return false;
                        }
                        if (!escape.exhaustive() || escape.distance(cell) >= 0 || escape.distance(cell.below()) >= 0
                                || escape.distance(cell.above()) >= 0
                                || Direction.Plane.HORIZONTAL.stream().anyMatch(side -> escape.distance(cell.relative(side)) >= 0)) {
                            reject();
                        } else {
                            person++;
                            stage = 4;
                        }
                    }
                    case 8 -> {
                        if (!source.portsReady(before, budget)) {
                            return false;
                        }
                        if (!budget.tryStep()) {
                            return false;
                        }
                        if (port < source.ports.size()) {
                            if (access.distance(source.ports.get(port++)) < 0) {
                                reject();
                            }
                        } else {
                            stage++;
                        }
                    }
                    case 9 -> {
                        if (job == jobs.size()) {
                            stage++;
                            continue;
                        }
                        Job next = jobs.get(job++);
                        if (next.cell().equals(cell) || !needed(next.cell())
                                || !Survey.open(level.getBlockState(next.cell()))) {
                            continue;
                        }
                        List<BlockPos> previous = source.workStances(before.access(), next.cell());
                        if (!previous.isEmpty() && previous.stream().noneMatch(feet -> access.canWorkFrom(feet, next.cell()))
                                && source.workStances(access, next.cell()).isEmpty()) {
                            reject();
                        }
                    }
                    default -> done = true;
                }
            }
            return done;
        }

        void reject() {
            stands = List.of();
            done = true;
        }
    }
    private final class Aside {
        final Context source;
        final Map<BlockPos, Integer> parts;
        final Set<Integer> active;
        final List<Grown> moves = new ArrayList<>();
        int person;
        LivingEntity body;
        List<BlockPos> way;
        int cursor;
        int last;
        Integer part;
        boolean crosses;
        View view;
        final List<BlockPos> safeExits = new ArrayList<>();
        int exit;
        Ground.Access retreat;
        boolean rerouting;
        boolean rerouted;

        Aside(Context source, Map<BlockPos, Integer> parts, Set<Integer> active) {
            this.source = source;
            this.parts = parts;
            this.active = active;
        }

        boolean advance(Ground.Budget budget) {
            while (person < source.people.size() && !budget.exhausted()) {
                if (body == null) {
                    LivingEntity next = source.people.get(person);
                    if (!(next instanceof Mob) || Bodies.of(next).flatMap(held -> held.colonyId())
                            .filter(colony::equals).isEmpty()) {
                        if (!budget.tryStep()) {
                            return false;
                        }
                        person++;
                        continue;
                    }
                    View current = source.view(next);
                    if (!current.access().advance(budget)) {
                        return false;
                    }
                    FootSearch search = source.footing(next, current.ground());
                    if (!search.advance(budget)) {
                        return false;
                    }
                    if (search.feet == null) {
                        person++;
                        continue;
                    }
                    body = next;
                    view = current;
                    way = view.access().path(search.feet);
                    part = partAt(search.feet, parts);
                    cursor = 0;
                    last = -1;
                    crosses = false;
                }
                if (!body.isAlive()) {
                    nextBody();
                    continue;
                }
                if (!view.ground().valid()) {
                    if (!budget.tryStep()) {
                        return false;
                    }
                    // A suspended retreat belongs to this body's actual current dimensions and gait.
                    // Waking or changing capability must not publish a path from the old body.
                    resetBody();
                    continue;
                }
                if (rerouting) {
                    if (exit < source.exits.size()) {
                        if (!budget.tryStep()) {
                            return false;
                        }
                        BlockPos at = source.exits.get(exit++);
                        if (clearAsideStance(body, at)) {
                            safeExits.add(at);
                        }
                        continue;
                    }
                    if (safeExits.isEmpty()) {
                        if (!budget.tryStep()) {
                            return false;
                        }
                        nextBody();
                        continue;
                    }
                    if (retreat == null) {
                        retreat = view.ground().beginAccess(safeExits);
                    }
                    if (!retreat.advance(budget)) {
                        return false;
                    }
                    FootSearch search = source.footing(body, view.ground());
                    if (!search.advance(budget)) {
                        return false;
                    }
                    way = search.feet == null ? List.of() : retreat.path(search.feet);
                    part = search.feet == null ? null : partAt(search.feet, parts);
                    cursor = 0;
                    last = -1;
                    crosses = false;
                    rerouting = false;
                    rerouted = true;
                }
                if (!budget.tryStep()) {
                    return false;
                }
                if (cursor < way.size()) {
                    BlockPos at = way.get(cursor);
                    crosses |= occupiesPending(at);
                    if (part == null) {
                        part = partAt(at, parts);
                    }
                    if (!clearAsideStance(body, at)) {
                        last = cursor;
                    }
                    cursor++;
                } else {
                    if (!way.isEmpty() && (!clearOfFill(body) || crosses)
                            && (part == null || !active.contains(part)) && last >= 0) {
                        if (last + 1 < way.size()) {
                            moves.add(Grown.of(new StepAsideNode(FillPlan.this, zone, (Mob) body, way.get(last + 1), level)));
                        } else if (!rerouted) {
                            // The closest trusted exit can be only one block beyond the fill. It is a
                            // valid escape anchor but still inside the navigation arrival buffer. Keep
                            // the escape proof unchanged and find an actual retreat to farther, clear
                            // trusted roots; never invent a reachable extra step past a finished path.
                            rerouting = true;
                            continue;
                        }
                    }
                    nextBody();
                }
            }
            return person == source.people.size();
        }

        private void nextBody() {
            person++;
            resetBody();
        }

        private void resetBody() {
            body = null;
            view = null;
            retreat = null;
            safeExits.clear();
            exit = 0;
            rerouting = false;
            rerouted = false;
        }
    }

    private static Integer partAt(BlockPos feet, Map<BlockPos, Integer> parts) {
        Integer part = parts.get(feet);
        if (part == null) {
            part = parts.get(feet.above());
        }
        return part == null ? parts.get(feet.below()) : part;
    }
    private boolean occupiesPending(BlockPos feet) {
        return pending.contains(feet) || pending.contains(feet.above()) || pending.contains(feet.below());
    }

    private boolean clearAsideStance(LivingEntity body, BlockPos feet) {
        double half = body.getBbWidth() / 2.0D + 0.9D;
        return missesPending(new AABB(feet.getX() + 0.5D - half, feet.getY() - 1.0D,
            feet.getZ() + 0.5D - half, feet.getX() + 0.5D + half,
            feet.getY() + body.getBbHeight() + 1.0D, feet.getZ() + 0.5D + half));
    }

    private boolean missesPending(AABB box) {
        int checked = 0;
        for (BlockPos cell : BlockPos.betweenClosed(BlockPos.containing(box.minX, box.minY, box.minZ),
                BlockPos.containing(box.maxX, box.maxY, box.maxZ))) {
            if (++checked > 4096) {
                return false;
            }
            if (pending.contains(cell) && box.intersects(new AABB(cell))) {
                return false;
            }
        }
        return true;
    }

    private final class Planning {
        final Context source;
        final Set<BlockPos> left = new LinkedHashSet<>();
        final List<Job> jobs = new ArrayList<>();
        final Map<BlockPos, Integer> parts = new HashMap<>();
        final ArrayDeque<BlockPos> open = new ArrayDeque<>();
        final Set<BlockPos> passage = new HashSet<>();
        final List<Ranked> ranks = new ArrayList<>();
        final PriorityQueue<Ranked> ordered = new PriorityQueue<>(Comparator
            .comparingInt((Ranked rank) -> rank.job().cell().getY())
            .thenComparing(Comparator.comparingInt(Ranked::depth).reversed())
            .thenComparingLong(rank -> rank.job().cell().asLong()));
        final Map<Integer, Part> byPart = new LinkedHashMap<>();
        final List<Part> candidates = new ArrayList<>();
        final Set<BlockPos> rejected = new HashSet<>();
        final Map<BlockPos, Job> refreshedRequests = new HashMap<>();
        final List<Job> inputJobs = inputs;
        final Set<String> targetKeys = wanted;
        Iterator<BlockPos> cellsLeft = cells.iterator();
        Iterator<BlockPos> seeds;
        Iterator<BlockPos> path;
        View view;
        int step;
        int part = -1;
        int person;
        int job;
        int rank;
        int nextPart;
        int finishedParts;
        Aside aside;
        Publication publication;
        Set<Integer> active = Set.of();
        boolean done;
        String stage = "inputs";

        Planning(Context source) {
            this.source = source;
        }

        void advance(Ground.Budget budget) {
            while (!done && !budget.exhausted()) {
                if (publication != null) {
                    if (!publication.advance(budget)) {
                        return;
                    }
                    active = publication.active;
                    publication = null;
                }
                switch (step) {
                    case 0 -> {
                        if (!budget.tryStep()) {
                            return;
                        }
                        if (cellsLeft.hasNext()) {
                            BlockPos cell = cellsLeft.next();
                            if (targetKeys.contains(Job.keyOf(cell)) && Survey.open(level.getBlockState(cell))) {
                                left.add(cell);
                            }
                        } else {
                            pending = left;
                            step++;
                        }
                    }
                    case 1 -> {
                        if (!budget.tryStep()) {
                            return;
                        }
                        if (job < inputJobs.size()) {
                            Job next = inputJobs.get(job++);
                            if (pending.contains(next.cell())) {
                                jobs.add(next);
                            }
                        } else {
                            remaining = Collections.unmodifiableList(jobs);
                            seeds = pending.iterator();
                            job = 0;
                            stage = "components";
                            step++;
                        }
                    }
                    case 2 -> {
                        if (!budget.tryStep()) {
                            return;
                        }
                        if (!open.isEmpty()) {
                            BlockPos at = open.removeFirst();
                            for (Direction side : Direction.values()) {
                                BlockPos next = at.relative(side);
                                if (pending.contains(next) && parts.putIfAbsent(next, part) == null) {
                                    open.addLast(next);
                                }
                            }
                        } else if (seeds.hasNext()) {
                            BlockPos seed = seeds.next();
                            if (!parts.containsKey(seed)) {
                                parts.put(seed, ++part);
                                open.add(seed);
                            }
                        } else {
                            stage = "exits";
                            step++;
                        }
                    }
                    case 3 -> {
                        if (!source.advance(budget)) {
                            return;
                        }
                        view = source.view(surveyor);
                        if (!view.access().advance(budget)) {
                            return;
                        }
                        stage = "people routes";
                        step++;
                    }
                    case 4 -> {
                        if (path != null && path.hasNext()) {
                            if (!budget.tryStep()) {
                                return;
                            }
                            protect(passage, path.next());
                            continue;
                        }
                        path = null;
                        if (person == source.people.size()) {
                            stage = "work routes";
                            step++;
                            continue;
                        }
                        LivingEntity body = source.people.get(person);
                        View own = source.view(body);
                        if (!own.access().advance(budget)) {
                            return;
                        }
                        FootSearch search = source.footing(body, own.ground());
                        if (!search.advance(budget)) {
                            return;
                        }
                        path = search.feet == null ? Collections.emptyIterator() : own.access().path(search.feet).iterator();
                        person++;
                    }
                    case 5 -> {
                        if (!budget.tryStep()) {
                            return;
                        }
                        if (path != null && path.hasNext()) {
                            protect(passage, path.next());
                            continue;
                        }
                        path = null;
                        if (job == remaining.size()) {
                            stage = "order";
                            step++;
                            continue;
                        }
                        Job next = remaining.get(job++);
                        List<BlockPos> stands = source.workStances(view.access(), next.cell());
                        if (!stands.isEmpty()) {
                            BlockPos entry = stands.stream().min(Comparator.comparingInt(view.access()::distance)).orElseThrow();
                            path = view.access().path(entry).iterator();
                            ranks.add(new Ranked(next, parts.get(next.cell()), view.access().distance(entry) / BAND, false));
                        }
                    }
                    case 6 -> {
                        if (!budget.tryStep()) {
                            return;
                        }
                        if (rank < ranks.size()) {
                            Ranked next = ranks.get(rank++);
                            ordered.add(new Ranked(next.job(), next.part(), next.depth(), passage.contains(next.job().cell())));
                        } else {
                            step++;
                        }
                    }
                    case 7 -> {
                        if (!budget.tryStep()) {
                            return;
                        }
                        if (!ordered.isEmpty()) {
                            Ranked next = ordered.remove();
                            Part group = byPart.get(next.part());
                            if (group == null) {
                                group = new Part(next.part());
                                byPart.put(next.part(), group);
                                candidates.add(group);
                            }
                            group.ranks.add(next);
                        } else {
                            stage = "candidates";
                            step++;
                        }
                    }
                    case 8 -> {
                        if (candidates.isEmpty()) {
                            finishCandidates();
                            continue;
                        }
                        // Rotate before advancing. Even a search that needs the entire remaining slice
                        // yields its turn to the next component on the following tick.
                        Part group = candidates.get(Math.floorMod(nextPart++, candidates.size()));
                        if (group.done) {
                            if (!budget.tryStep()) {
                                return;
                            }
                        } else {
                            int used = budget.used();
                            group.advance(budget);
                            if (budget.used() == used && !budget.tryStep()) {
                                return;
                            }
                            if (group.published) {
                                publish();
                                group.published = false;
                            }
                        }
                        if (finishedParts == candidates.size()) {
                            finishCandidates();
                        }
                    }
                    default -> {
                        if (aside == null) {
                            aside = new Aside(source, parts, active);
                        }
                        if (!aside.advance(budget)) {
                            return;
                        }
                        steppingAside = List.copyOf(aside.moves);
                        stage = "complete";
                        done = true;
                    }
                }
            }
        }

        void finishCandidates() {
            publish();
            stage = "retreat";
            step = 9;
        }

        void publish() {
            publication = new Publication();
        }

        final class Publication {
            final Iterator<Part> groups = candidates.iterator();
            final Iterator<Job> requests = issued.values().iterator();
            final Iterator<Job> previous = offered.values().iterator();
            final Map<BlockPos, Job> next = new LinkedHashMap<>();
            final Map<BlockPos, Job> nextRequests = new LinkedHashMap<>();
            final Set<Integer> active = new HashSet<>();
            Iterator<Job> jobs = Collections.emptyIterator();
            boolean retreat;
            boolean closing = true;

            boolean advance(Ground.Budget budget) {
                while (budget.tryStep()) {
                    if (requests.hasNext()) {
                        Job job = requests.next();
                        if (needed(job.cell()) && executable(job.cell()) && !rejected.contains(job.cell())) {
                            nextRequests.put(job.cell(), refreshedRequests.getOrDefault(job.cell(), job));
                        }
                    } else if (previous.hasNext()) {
                        Job job = previous.next();
                        if (needed(job.cell()) && pending.contains(job.cell()) && !rejected.contains(job.cell())) {
                            next.put(job.cell(), job);
                            Integer part = parts.get(job.cell());
                            if (part != null) {
                                active.add(part);
                            }
                            closing &= job.footings().cells().stream().noneMatch(at -> cells.contains(at.block(level)));
                        }
                    } else if (jobs.hasNext()) {
                        Job job = jobs.next();
                        if (needed(job.cell()) && executable(job.cell())) {
                            next.put(job.cell(), job);
                            nextRequests.put(job.cell(), job);
                            closing &= job.footings().cells().stream().noneMatch(at -> cells.contains(at.block(level)));
                        }
                    } else if (groups.hasNext()) {
                        Part group = groups.next();
                        if (!group.front.isEmpty()) {
                            jobs = group.front.values().iterator();
                            active.add(group.id);
                            retreat |= !group.ordinary;
                        }
                    } else {
                        offered = Collections.unmodifiableMap(next);
                        issued = Collections.unmodifiableMap(nextRequests);
                        phase = next.isEmpty() ? Phase.WAITING : closing ? Phase.CLOSING
                            : retreat ? Phase.RETREATING : Phase.FILLING;
                        return true;
                    }
                }
                return false;
            }
        }

        final class Part {
            final int id;
            final List<Ranked> ranks = new ArrayList<>();
            final Map<BlockPos, Job> front = new LinkedHashMap<>();
            int tried;
            int start = -1;
            int layer;
            int depth;
            boolean ordinary;
            boolean done;
            boolean published;
            Candidate trial;
            Ranked next;

            Part(int id) {
                this.id = id;
            }

            void advance(Ground.Budget budget) {
                if (start < 0) {
                    start = Math.floorMod(cursors.getOrDefault(id, 0), ranks.size());
                }
                if (trial == null) {
                    next = ranks.get((start + tried) % ranks.size());
                    trial = new Candidate(source, next.job().cell(), material, source.people, null);
                }
                if (!trial.advance(budget)) {
                    return;
                }
                if (!trial.stands.isEmpty()) {
                    proofs.put(next.job().cell(), trial);
                    Job proved = next.job();
                    Job refreshed = new Job(proved.key(), proved.site(), proved.cell(), proved.cells(), proved.canopy(),
                        Stances.of(level, trial.stands).orElseThrow());
                    refreshedRequests.put(proved.cell(), refreshed);
                    boolean normal = !next.passage();
                    int y = next.job().cell().getY();
                    if (front.isEmpty() || normal && !ordinary || normal == ordinary
                            && (y < layer || y == layer && next.depth() > depth)) {
                        front.clear();
                        layer = y;
                        depth = next.depth();
                        ordinary = normal;
                    }
                    if (normal == ordinary && y == layer && next.depth() == depth) {
                        front.put(refreshed.cell(), refreshed);
                        published = true;
                    }
                } else {
                    // Only a new counterexample may revoke an otherwise unchanged offered face.
                    rejected.add(next.job().cell());
                }
                trial = null;
                tried++;
                // A different part may start placing before this round finishes. Keep the rejected
                // prefix cursor now, so those placements cannot repeatedly hide candidate 65.
                cursors.put(id, front.isEmpty() ? (start + tried) % ranks.size() : 0);
                if (tried == Math.min(MOST_TRIALS, ranks.size())) {
                    cursors.put(id, front.isEmpty() ? (start + MOST_TRIALS) % ranks.size() : 0);
                    done = true;
                    finishedParts++;
                    published = true;
                }
            }
        }
    }

    private final class FootSearch {
        final LivingEntity body;
        final Ground ground;
        final Vec3 actual;
        final List<BlockPos> candidates = new ArrayList<>();
        int index;
        BlockPos feet;
        boolean done;

        FootSearch(LivingEntity body, Ground ground) {
            this.body = body;
            this.ground = ground;
            actual = position(body);
            BlockPos at = BlockPos.containing(actual);
            for (int dy : new int[] {0, -1, 1, -2}) {
                candidates.add(at.offset(0, dy, 0));
            }
            List<BlockPos> nearby = new ArrayList<>();
            for (BlockPos near : BlockPos.betweenClosed(at.offset(-1, -1, -1), at.offset(1, 1, 1))) {
                if (Reach.standingAt(actual, near)) {
                    nearby.add(near.immutable());
                }
            }
            nearby.sort(Comparator.comparingDouble(near -> Vec3.atBottomCenterOf(near).distanceToSqr(actual)));
            candidates.addAll(nearby);
        }

        boolean advance(Ground.Budget budget) {
            while (!done && index < candidates.size()) {
                BlockPos at = candidates.get(index);
                if (!ground.prepare(at, budget) || !budget.tryStep()) {
                    return false;
                }
                index++;
                if (ground.standable(at)) {
                    feet = at;
                    done = true;
                }
            }
            done |= index == candidates.size();
            return done;
        }
    }

    private static void protect(Set<BlockPos> into, BlockPos feet) {
        into.add(feet);
        into.add(feet.above());
        into.add(feet.above(2));
    }
    private Vec3 position(LivingEntity body) {
        return WorldSpaces.relative(level, realm, body.position()).orElse(body.position())
            .add(origin.getX(), origin.getY(), origin.getZ());
    }

    private AABB worldBounds() {
        AABB result = null;
        for (int x : new int[] {bounds.minX(), bounds.maxX() + 1}) {
            for (int y : new int[] {bounds.minY(), bounds.maxY() + 1}) {
                for (int z : new int[] {bounds.minZ(), bounds.maxZ() + 1}) {
                    Vec3 shown = WorldSpaces.world(level, new WorldPos(realm, new BlockPos(x, y, z).subtract(origin)))
                        .orElse(new Vec3(x, y, z));
                    AABB point = new AABB(shown, shown);
                    result = result == null ? point : result.minmax(point);
                }
            }
        }
        return result.inflate(2.0D);
    }

    private void reframe(List<BlockPos> entrances) {
        List<BlockPos> points = new ArrayList<>(entrances);
        points.add(new BlockPos(workBox.minX(), workBox.minY(), workBox.minZ()));
        points.add(new BlockPos(workBox.maxX(), workBox.maxY(), workBox.maxZ()));
        BoundingBox box = BoundingBox.encapsulatingPositions(points).orElseThrow();
        BoundingBox next = new BoundingBox(box.minX() - MARGIN,
            Math.max(level.getMinBuildHeight(), box.minY() - MARGIN), box.minZ() - MARGIN,
            box.maxX() + MARGIN, Math.min(level.getMaxBuildHeight() - 1, box.maxY() + MARGIN), box.maxZ() + MARGIN);
        if (!next.equals(bounds)) {
            if (watch != null) {
                watch.close();
            }
            bounds = next;
            watch = Grounds.watch(level, bounds);
        }
        worldBounds = worldBounds();
    }

    private boolean outside(BlockPos feet) {
        return !cells.contains(feet) && !cells.contains(feet.above()) && !cells.contains(feet.below());
    }

    private boolean outsideAll(BlockPos feet) {
        return LIVE.getOrDefault(level, Set.of()).stream().allMatch(plan -> plan.outside(feet));
    }

    private double apart(BlockPos feet) {
        int x = Math.clamp(feet.getX(), workBox.minX(), workBox.maxX());
        int y = Math.clamp(feet.getY(), workBox.minY(), workBox.maxY());
        int z = Math.clamp(feet.getZ(), workBox.minZ(), workBox.maxZ());
        return feet.distSqr(new BlockPos(x, y, z));
    }

    // Both trusted store approaches and the six boundary faces are enumerated under the same budget as
    // graph searches. A store no longer hides a synchronous 11 x 11 x 11 Reach.workableCells scan.
    private final class Context {
        private List<LivingEntity> people = new ArrayList<>();
        private List<BlockPos> exits = List.of();
        private final Map<UUID, View> views;
        private final Map<UUID, FootSearch> footings;
        private final Map<Ground.Access, Map<BlockPos, List<BlockPos>>> approaches;
        private final Map<Ground.Access, Map<Change, Trial>> trials;
        private final PriorityQueue<BlockPos> trusted = new PriorityQueue<>(Comparator
            .comparingDouble(FillPlan.this::apart).thenComparingLong(BlockPos::asLong).reversed());
        private Iterator<BlockPos> stores;
        private Iterator<BlockPos> near;
        private BlockPos store;
        private final Set<BlockPos> found = new LinkedHashSet<>();
        private Boundary perimeter;
        private Boundary edges;
        private final List<BlockPos> ports;
        private boolean portsDone;
        private int stage;
        private Grounds.Watch source;
        private long revision = Long.MIN_VALUE;
        private long liveRevision;
        private long anchorRevision;

        Context() {
            views = new HashMap<>();
            footings = new HashMap<>();
            approaches = new HashMap<>();
            trials = new HashMap<>();
            ports = new ArrayList<>();
            source = watch;
            liveRevision = LIVE_REVISIONS.getOrDefault(level, 0L);
            anchorRevision = liveRevision;
        }

        Context(Context earlier) {
            views = earlier.views;
            footings = earlier.footings;
            approaches = earlier.approaches;
            trials = earlier.trials;
            exits = earlier.exits;
            ports = earlier.ports;
            portsDone = earlier.portsDone;
            perimeter = earlier.perimeter;
            edges = earlier.edges;
            source = earlier.source;
            revision = earlier.revision;
            liveRevision = earlier.liveRevision;
            anchorRevision = earlier.anchorRevision;
            people = people();
            stage = 3;
        }

        Context withPeople() {
            watch.refresh();
            return new Context(this);
        }

        private List<LivingEntity> people() {
            // A moving realm can change its world projection without changing the local terrain.
            return new ArrayList<>(level.getEntitiesOfClass(LivingEntity.class, worldBounds(),
                body -> body.isAlive() && !body.isSpectator()));
        }

        private void stamp() {
            source = watch;
            revision = watch.revision();
            liveRevision = LIVE_REVISIONS.getOrDefault(level, 0L);
        }

        boolean anchorsValid() {
            long now = LIVE_REVISIONS.getOrDefault(level, 0L);
            if (anchorRevision == now) return true;
            ArrayDeque<Activation> changes = ACTIVATIONS.get(level);
            if (changes == null || changes.isEmpty() || changes.getFirst().revision() > anchorRevision + 1) return false;
            for (Activation change : changes) {
                if (change.revision() > anchorRevision && change.bounds().intersects(anchorBounds)) return false;
            }
            anchorRevision = now;
            return true;
        }

        boolean valid() {
            if (closed || source != watch) {
                return false;
            }
            if (revision == Long.MIN_VALUE) {
                return true;
            }
            if (revision != watch.revision()) {
                return false;
            }
            long now = LIVE_REVISIONS.getOrDefault(level, 0L);
            if (liveRevision != now) {
                ArrayDeque<Activation> changes = ACTIVATIONS.get(level);
                if (changes == null || changes.isEmpty() || changes.getFirst().revision() > liveRevision + 1) {
                    return false;
                }
                // Only a newly added fill can turn a proved exterior into future fill space. Removal
                // merely releases space, and a distant colony cannot affect any exit inside this watch.
                // The bounded journal also makes this check independent of the total number of zones.
                for (Activation change : changes) {
                    if (change.added() && change.revision() > liveRevision && change.bounds().intersects(bounds)) {
                        return false;
                    }
                }
                liveRevision = now;
            }
            return true;
        }

        boolean advance(Ground.Budget budget) {
            if (revision == Long.MIN_VALUE) {
                if (!budget.tryStep()) {
                    return false;
                }
                stamp();
            }
            while (stage < 3 && budget.tryStep()) {
                if (stage == 0) {
                    if (stores == null) {
                        stores = external.get().iterator();
                    }
                    if (near != null && near.hasNext()) {
                        BlockPos feet = near.next().immutable();
                        if (outsideAll(feet) && apart(feet) <= MOST_EXIT_DISTANCE * MOST_EXIT_DISTANCE
                                && level.isLoaded(feet) && Footing.withFeetAt(level, feet)
                                && (!externalTargets || Reach.withinReach(feet, store) && Reach.inSight(level, feet, store))) {
                            if (!trusted.contains(feet)) {
                                trusted.add(feet);
                                if (trusted.size() > 32) {
                                    trusted.remove();
                                }
                            }
                        }
                    } else if (stores.hasNext()) {
                        store = stores.next();
                        near = externalTargets
                            ? BlockPos.betweenClosed(store.offset(-5, -5, -5), store.offset(5, 5, 5)).iterator()
                            : List.of(store).iterator();
                    } else {
                        List<BlockPos> ordered = trusted.stream().sorted(Comparator
                            .comparingDouble(FillPlan.this::apart).thenComparingLong(BlockPos::asLong)).toList();
                        reframe(ordered);
                        stamp();
                        found.addAll(ordered);
                        perimeter = new Boundary(bounds, false);
                        people = people();
                        stage++;
                    }
                } else if (stage == 1) {
                    if (perimeter.hasNext()) {
                        BlockPos column = perimeter.next();
                        int x = column.getX();
                        int z = column.getZ();
                        if (level.hasChunk(x >> 4, z >> 4)) {
                            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                            BlockPos feet = new BlockPos(x, y, z);
                            if (bounds.isInside(feet) && outsideAll(feet) && Footing.withFeetAt(level, feet)) {
                                found.add(feet);
                            }
                        }
                    } else {
                        // Array construction is split too; very long outer walls need many explicit exits.
                        near = found.iterator();
                        stage++;
                    }
                } else {
                    if (near.hasNext()) {
                        // exits remains private to this continuation until setup finishes.
                        if (!(exits instanceof ArrayList<?>)) {
                            exits = new ArrayList<>();
                        }
                        exits.add(near.next());
                    } else {
                        exits = Collections.unmodifiableList(exits);
                        edges = new Boundary(bounds, true);
                        stage++;
                    }
                }
            }
            return stage == 3;
        }

        View view(LivingEntity body) {
            View view = views.get(body.getUUID());
            if (view == null || view.body() != body || !view.ground().valid()) {
                Ground ground = watch.now(body);
                view = new View(body, ground, ground.beginAccess(exits));
                views.put(body.getUUID(), view);
            }
            return view;
        }

        FootSearch footing(LivingEntity body, Ground ground) {
            FootSearch search = footings.get(body.getUUID());
            if (search == null || search.body != body || !search.ground.valid() || !search.actual.equals(position(body))) {
                search = new FootSearch(body, ground);
                footings.put(body.getUUID(), search);
            }
            return search;
        }

        Trial trial(View view, BlockPos cell, BlockState block) {
            Map<Change, Trial> known = trials.computeIfAbsent(view.access(), any -> new HashMap<>());
            Change change = new Change(cell, block);
            Trial trial = known.get(change);
            if (trial == null || !trial.access().valid()) {
                Ground ground = view.ground().with(Map.of(cell, block));
                trial = new Trial(ground, ground.beginAccess(exits));
                known.put(change, trial);
            }
            return trial;
        }

        List<BlockPos> workStances(Ground.Access access, BlockPos cell) {
            return approaches.computeIfAbsent(access, any -> new HashMap<>()).computeIfAbsent(cell, any ->
                access.stancesFor(cell).stream().filter(feet ->
                    Vec3.atBottomCenterOf(feet).add(0.0D, surveyor.getEyeHeight(), 0.0D)
                        .distanceToSqr(Vec3.atCenterOf(cell)) <= WORK_REACH * WORK_REACH)
                    .limit(MOST_STANCES).toList());
        }

        boolean portsReady(View view, Ground.Budget budget) {
            while (!portsDone && budget.tryStep()) {
                if (edges.hasNext()) {
                    BlockPos feet = edges.next();
                    if (view.access().distance(feet) >= 0) {
                        ports.add(feet);
                    }
                } else {
                    portsDone = true;
                }
            }
            return portsDone;
        }
    }

    // A constant-space cursor over four exterior columns or six faces; never a materialized volume.
    private static final class Boundary implements Iterator<BlockPos> {
        final BoundingBox box;
        final boolean faces;
        final long x;
        final long y;
        final long z;
        final long total;
        long index;

        Boundary(BoundingBox box, boolean faces) {
            this.box = box;
            this.faces = faces;
            x = (long) box.maxX() - box.minX() + 1;
            y = (long) box.maxY() - box.minY() + 1;
            z = (long) box.maxZ() - box.minZ() + 1;
            total = faces ? 2L * (x * y + z * y + x * z) : 2L * (x + z);
        }

        public boolean hasNext() {
            return index < total;
        }

        public BlockPos next() {
            long at = index++;
            if (!faces) {
                if (at < 2 * x) {
                    return new BlockPos(box.minX() + (int) (at / 2), box.minY(),
                        at % 2 == 0 ? box.minZ() : box.maxZ());
                }
                at -= 2 * x;
                return new BlockPos(at % 2 == 0 ? box.minX() : box.maxX(), box.minY(), box.minZ() + (int) (at / 2));
            }
            if (at < 2 * x * y) {
                return new BlockPos(box.minX() + (int) (at / 2 / y), box.minY() + (int) (at / 2 % y),
                    at % 2 == 0 ? box.minZ() : box.maxZ());
            }
            at -= 2 * x * y;
            if (at < 2 * z * y) {
                return new BlockPos(at % 2 == 0 ? box.minX() : box.maxX(), box.minY() + (int) (at / 2 % y),
                    box.minZ() + (int) (at / 2 / y));
            }
            at -= 2 * z * y;
            return new BlockPos(box.minX() + (int) (at / 2 % x), at % 2 == 0 ? box.minY() : box.maxY(),
                box.minZ() + (int) (at / 2 / x));
        }
    }
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (limits.equals(Limits.NORMAL)) ServerFillBudget.close(level.getServer(), this);
        if (completion != null) {
            completion.close();
            completion = null;
        }
        receipts.clear();
        receiptKeys.clear();
        restoredCompletion = null;
        persisted = () -> { };
        anchors.clear();
        anchorCursor = null;
        anchorSource = null;
        releaseCommit(false);
        offered = Map.of();
        issued = Map.of();
        inputCells = Set.of();
        steppingAside = List.of();
        watch.close();
        planning = null;
        context = null;
        proofs.clear();
        commitProofs.clear();
        commitRequests.clear();
        proving.clear();
        provingDestinations.clear();
        cursors.clear();
        inputs = List.of();
        remaining = List.of();
        wanted = Set.of();
        material = null;
        reconsider = false;
        pending.clear();
        Map<UUID, Allowance> allowances = ALLOWANCES.get(level);
        if (allowances != null) {
            Allowance allowance = allowances.get(colony);
            if (allowance != null && --allowance.plans == 0) {
                allowances.remove(colony);
            }
            if (allowances.isEmpty()) {
                ALLOWANCES.remove(level);
            }
        }
        Set<FillPlan> live = LIVE.get(level);
        if (live != null && live.remove(this)) {
            liveChanged(false);
            if (live.isEmpty()) {
                LIVE.remove(level);
                LIVE_REVISIONS.remove(level);
                ACTIVATIONS.remove(level);
            }
        }
    }
}
