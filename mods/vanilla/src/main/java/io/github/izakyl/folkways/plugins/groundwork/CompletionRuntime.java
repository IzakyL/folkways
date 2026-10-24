package io.github.izakyl.folkways.plugins.groundwork;

import io.github.izakyl.folkways.core.api.ground.BlockChanges;
import io.github.izakyl.folkways.core.api.ground.Ground;
import io.github.izakyl.folkways.core.api.ground.Grounds;
import io.github.izakyl.folkways.core.api.resident.Locomotions;
import io.github.izakyl.folkways.core.api.resident.ResidentKinds;
import io.github.izakyl.folkways.core.api.resident.body.Bodies;
import io.github.izakyl.folkways.core.api.terms.Footing;
import io.github.izakyl.folkways.core.api.terms.Reach;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.plugins.build.BuildContent;
import io.github.izakyl.folkways.plugins.groundwork.plan.CompletionSearch;
import io.github.izakyl.folkways.plugins.groundwork.plan.CompletionVerifier;
import io.github.izakyl.folkways.plugins.groundwork.plan.Construction;
import io.github.izakyl.folkways.plugins.groundwork.plan.Construction.Action;
import io.github.izakyl.folkways.plugins.groundwork.plan.Construction.Box;
import io.github.izakyl.folkways.plugins.groundwork.plan.Construction.Capability;
import io.github.izakyl.folkways.plugins.groundwork.plan.Construction.Cell;
import io.github.izakyl.folkways.plugins.groundwork.plan.Construction.Finish;
import io.github.izakyl.folkways.plugins.groundwork.plan.Construction.Place;
import io.github.izakyl.folkways.plugins.groundwork.plan.Construction.Problem;
import io.github.izakyl.folkways.plugins.groundwork.plan.Construction.State;
import io.github.izakyl.folkways.plugins.groundwork.plan.Construction.Voxel;
import io.github.izakyl.folkways.plugins.groundwork.plan.Construction.Walk;
import io.github.izakyl.folkways.plugins.groundwork.plan.Construction.Witness;
import io.github.izakyl.folkways.plugins.groundwork.plan.ConstructionRules;
import io.github.izakyl.folkways.plugins.groundwork.plan.ConstructionSnapshot;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/** A whole construction certificate is an intent; only a fresh live prefix check permits a write. */
final class CompletionRuntime implements AutoCloseable {

    enum Permit { READY, PENDING, BLOCKED, CHANGED }

    private static final int MOST_PEOPLE = 512;
    private static final int MOST_CENSUS_ENTITIES = 2048;
    private static final int MOST_PROFILES = 8;
    private static final int[][] SIDES = { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } };
    private static final String SUPPLY = "; conditional on the colony supplying one real block per remaining target";

    private final FillPlan owner;
    private final ServerLevel level;
    private final UUID zone;
    private final UUID colony;
    private final Set<BlockPos> cells;
    private final Function<Ground.Budget, List<BlockPos>> trustedAnchors;
    private final Runnable changed;
    private final AABB vicinity;
    private final Map<BlockPos, BlockState> receipts = new LinkedHashMap<>();
    private final Set<BlockPos> receiptDirty = new LinkedHashSet<>();
    private final BlockChanges.Heard receiptListener;
    private final Map<UUID, BlockPos> destinations = new LinkedHashMap<>();
    private final Map<Capability, ReturnPaths> returns = new HashMap<>();
    private final Map<Capability, ReturnPaths> beforeReturns = new HashMap<>();
    private final Map<DestinationKey, ReturnPaths> destinationPaths = new HashMap<>();
    private final Map<UUID, DestinationNative> destinationNative = new HashMap<>();
    private final Map<Capability, Problem> profiles = new HashMap<>();
    private long profileCells;
    private long profileBuilds;
    private long totalProfileNanos;
    private long maxProfileNanos;
    private long censusCalls;
    private long totalCensusScanned;
    private long totalCensusNanos;
    private long maxCensusNanos;
    private int lastCensusScanned;
    private int lastCensusPeople;
    private boolean lastCensusLimited;
    private long uncommittedBuilderReselections;
    private long executionGates;
    private long totalExecutionGateNanos;
    private long maxExecutionGateNanos;
    private Set<BlockPos> remaining = Set.of();
    private BlockState configuredMaterial;
    private BlockState certificateMaterial;
    private long version;
    private UUID builder;
    private UUID retreatOwed;
    private Set<BlockPos> retainedSafe = Set.of();
    private String phase = "DISCOVERING";
    private String reason = "awaiting project input";
    private boolean closed;
    private boolean finished;
    private boolean conflicted;
    private String conflictReason = "";
    private long retryAt;
    private List<Grown> moves = List.of();
    private List<Mob> candidates;
    private int candidate;
    private Mob selected;
    private double selectionDistance = Double.POSITIVE_INFINITY;
    private List<BlockPos> rawAnchors;
    private int anchor;
    private int anchorVoxel = -1;
    private boolean anchorGood;
    private final Set<Cell> safe = new LinkedHashSet<>();
    private ConstructionSnapshot.Capture capture;
    private ConstructionSnapshot.Captured captured;
    private ConstructionSnapshot.Validation validation;
    private CompletionSearch search;
    private CompletionVerifier verifier;
    private Witness witness;
    private State prefix;
    private Place head;
    private Job logicalHead;
    private boolean safeHeadReady;
    private State headAfter;
    private int action;
    private Cell finish;
    private Grounds.Watch nativeWatch;
    private Ground nativeBefore;
    private Ground nativeAfter;
    private Mob nativeBody;
    private BlockPos nativeFeet;
    private int nativeStage;
    private PeoplePass peoplePass;
    private ActorJoin actorJoin;
    private Prepared prepared;
    private DestinationPrepared destinationPrepared;
    private ExactApproach approach;
    private Ground approachBefore;
    private Ground approachAfter;
    private Mob approachBody;
    private int approachStage;
    private ApproachStep approachStep;
    private ApproachDrop approachDrop;
    private String approachStatus = "none";
    private long approachCommands;
    private long approachDropCommands;
    private long approachStepCommands;
    private long approachAlignCommands;
    private long approachProjectedDropCommands;
    private long certificateGeneration;
    private long slices;
    private long totalWork;
    private long reproofs;
    private long commits;
    private long permitYields;
    private long permitted;
    private long actorEscapeReuses;
    private long actorEscapeJoinReuses;
    private long maxSliceNanos;
    private int lastWork;
    private int targetsLeft;
    private String lastPermitReason = "no execution attempt";
    private Map<String, Object> lastAttempt = Map.of();

    private record Prepared(BlockPos cell, BlockState material, UUID body, long tick, long version,
                            long generation, int action) { }
    private record DestinationPrepared(UUID body, BlockPos target, long tick, long generation) { }
    private record DestinationKey(Capability capability, Cell target) { }
    private record DestinationNative(LivingEntity body, BlockPos target, Capability capability, Ground ground) { }
    private record ExactApproach(UUID body, BlockPos target, BlockPos stance, long generation,
                                 long version, long until) { }
    private record ApproachStep(Cell launch, Cell landing, Vec3 takeoff) { }
    private record ApproachDrop(Cell landing, Vec3 launch, Vec3 previous) { }
    private enum ApproachResult { MOVING, PENDING, BLOCKED, CENTRED }
    private record Person(LivingEntity body, Cell feet, Capability capability, AABB box) { }
    private static final class PeoplePass {
        final List<Person> people;
        final long stamp;
        int cursor;
        int stage;
        boolean blocked;
        PeoplePass(List<Person> people, long stamp) { this.people = people; this.stamp = stamp; }
    }

    CompletionRuntime(FillPlan owner, ServerLevel level, UUID zone, UUID colony, Set<BlockPos> cells,
            Function<Ground.Budget, List<BlockPos>> trustedAnchors, Runnable changed) {
        this.owner = owner;
        this.level = level;
        this.zone = zone;
        this.colony = colony;
        this.cells = Set.copyOf(cells);
        this.trustedAnchors = trustedAnchors;
        this.changed = changed;
        BoundingBox bounds = BoundingBox.encapsulatingPositions(cells).orElseThrow();
        vicinity = AABB.encapsulatingFullBlocks(new BlockPos(bounds.minX(), bounds.minY(), bounds.minZ()),
            new BlockPos(bounds.maxX(), bounds.maxY(), bounds.maxZ())).inflate(64.0D);
        receiptListener = BlockChanges.listen(level, bounds, at -> {
            if (receipts.containsKey(at)) receiptDirty.add(at.immutable());
        });
    }

    void refresh(Set<BlockPos> targets, BlockState material, long requestVersion) {
        if (closed || conflicted) return;
        Set<BlockPos> actual = new LinkedHashSet<>(targets);
        actual.removeAll(receipts.keySet());
        if (!cells.containsAll(actual) || !ConstructionSnapshot.supportedMaterial(material)) {
            waiting("UNSUPPORTED", "targets or material lie outside the declared static full-cube domain");
            return;
        }
        boolean specification = configuredMaterial != null
            && (!configuredMaterial.equals(material) || version != requestVersion);
        boolean externalChange = !actual.equals(remaining);
        configuredMaterial = material;
        version = requestVersion;
        if (certificateMaterial == null || specification) certificateMaterial = material;
        remaining = Set.copyOf(actual);
        targetsLeft = remaining.size();
        if (specification || externalChange && !matchesUnexecutedTargets(actual)) {
            invalidate("the requested remaining work changed; recomputing the entire suffix");
        }
        if (remaining.isEmpty() && retreatOwed == null && receipts.isEmpty()) {
            finished = true;
            phase = "COMPLETE";
            reason = "no work was performed and no retreat is owed";
        }
    }

    private boolean matchesUnexecutedTargets(Set<BlockPos> targets) {
        if (captured == null || prefix == null) return false;
        if (captured.problem().orderedTargets().size() - prefix.placed().size() != targets.size()) return false;
        for (Cell at : captured.problem().orderedTargets()) {
            if (!prefix.placed().contains(at) && !targets.contains(position(at))) return false;
        }
        return true;
    }

    boolean advance(Ground.Budget budget) {
        if (closed || conflicted || configuredMaterial == null || budget.exhausted()) return false;
        int began = budget.used();
        long time = System.nanoTime();
        String previous = phase;
        Job previousJob = logicalHead;
        List<Grown> previousMoves = moves;
        try {
            if (!checkReceipts(budget) || "CONFLICT".equals(phase)) return false;
            if (finished) return false;
            if (builder != null && receipts.isEmpty() && retreatOwed == null
                    && (!(level.getEntity(builder) instanceof Mob body) || !eligible(body))) {
                // A provisional choice is not an execution receipt. Losing it must not bind an
                // untouched project forever; an actual receipt/retreat obligation is never swapped.
                builder = null;
                logicalHead = null;
                uncommittedBuilderReselections++;
                invalidate("the uncommitted builder became unavailable; selecting a new actual builder and complete suffix");
                changed.run();
            }
            safeHeadReady = false;
            if (("WAITING".equals(phase) || "UNSUPPORTED".equals(phase)) && level.getGameTime() < retryAt) return false;
            if ("WAITING".equals(phase) || "UNSUPPORTED".equals(phase)) invalidate("retrying the bounded construction domain");
            while (!budget.exhausted() && !closed && !finished) {
                if (captured == null) {
                    if (!discover(budget)) break;
                    if (capture == null) break;
                    ConstructionSnapshot.Status result = capture.advance(adapt(budget));
                    if (result == ConstructionSnapshot.Status.PENDING) break;
                    if (result != ConstructionSnapshot.Status.READY) {
                        waiting(result == ConstructionSnapshot.Status.UNSUPPORTED ? "UNSUPPORTED" : "WAITING", capture.reason());
                        break;
                    }
                    captured = capture.result().orElseThrow();
                    capture.close();
                    capture = null;
                    prefix = captured.problem().initialState();
                    search = new CompletionSearch(captured.problem());
                    phase = "PLANNING";
                    reason = "searching the complete remaining construction and retreat" + SUPPLY;
                } else if (witness == null) {
                    CompletionSearch.Status result = search.advance(adapt(budget));
                    if (result == CompletionSearch.Status.SEARCHING) break;
                    if (result != CompletionSearch.Status.READY) {
                        waiting(result == CompletionSearch.Status.UNSUPPORTED ? "UNSUPPORTED" : "WAITING", search.reason());
                        break;
                    }
                    witness = search.witness().orElseThrow();
                    verifier = new CompletionVerifier(captured.problem(), witness);
                    phase = "VERIFYING";
                    reason = "replaying every movement, placement, entry and immediate retreat edge" + SUPPLY;
                } else if (verifier.status() != CompletionVerifier.Status.VALID) {
                    CompletionVerifier.Status result = verifier.advance(adapt(budget));
                    if (result == CompletionVerifier.Status.VERIFYING) break;
                    if (result == CompletionVerifier.Status.INVALID) {
                        conflict("the complete witness failed independent replay: " + verifier.reason());
                        break;
                    }
                    validation = captured.validate(level, prefix, witness.dependencies());
                    phase = "VALIDATING";
                    reason = "checking the complete live prefix before first publication" + SUPPLY;
                } else {
                    if (head == null && finish == null && !selectHead(budget)) break;
                    // Local native preparation owns its cursor; a renewed live-wrapper charge
                    // must not consume every one-work slice before it can prepare one voxel.
                    if (approach != null) steerApproach(budget);
                    if (captured == null) break;
                    if (!live(budget)) break;
                    // The final charged validation step and this bounded publication gate are one
                    // atomic action. Charging a new wrapper before every resumed validation would
                    // otherwise consume a one-work slice forever without reading a dependency.
                    if (!anchorsRemainExterior()) break;
                    if (finish != null) {
                        publishRetreat();
                        break;
                    }
                    phase = "READY";
                    safeHeadReady = true;
                    reason = "complete suffix verified; the offered head still needs a fresh execution permit" + SUPPLY;
                    break;
                }
            }
            return previousJob != logicalHead || previousMoves != moves || !previous.equals(phase);
        } finally {
            slices++;
            lastWork = budget.used() - began;
            totalWork += lastWork;
            maxSliceNanos = Math.max(maxSliceNanos, System.nanoTime() - time);
        }
    }

    private boolean discover(Ground.Budget budget) {
        if (capture != null) return true;
        if (remaining.isEmpty() && retreatOwed == null) {
            finished = true;
            logicalHead = null;
            phase = "COMPLETE";
            reason = "the recorded worker completed its actual retreat";
            return false;
        }
        if (rawAnchors == null) {
            List<BlockPos> found = trustedAnchors.apply(budget);
            if (found == null) return false;
            if (found.size() > 128) {
                waiting("UNSUPPORTED", "too many unbounded external source approaches");
                return false;
            }
            LinkedHashSet<BlockPos> roots = new LinkedHashSet<>(found);
            if (retreatOwed != null) roots.addAll(retainedSafe);
            rawAnchors = List.copyOf(roots);
            if (rawAnchors.isEmpty()) {
                waiting("WAITING", "no real external material-source approach is available");
                return false;
            }
        }
        if (selected == null) {
            if (retreatOwed != null || builder != null) {
                UUID required = retreatOwed != null ? retreatOwed : builder;
                if (!budget.tryStep()) return false;
                if (level.getEntity(required) instanceof Mob body && eligible(body)) {
                    selected = body;
                } else {
                    waiting("WAITING", "the original obligated builder is absent or cannot walk in this model");
                    return false;
                }
            } else {
                if (candidates == null) {
                    if (!budget.tryStep()) return false;
                    List<LivingEntity> found = census();
                    if (found == null) return false;
                    candidates = found.stream().filter(Mob.class::isInstance).map(Mob.class::cast)
                        .filter(body -> body.getBoundingBox().intersects(vicinity)).toList();
                }
                while (candidate < candidates.size() && budget.tryStep()) {
                    Mob body = candidates.get(candidate++);
                    if (!eligible(body) || !Footing.withFeetAt(level, feet(body))) continue;
                    double distance = rawAnchors.stream().mapToDouble(at -> body.distanceToSqr(Vec3.atBottomCenterOf(at)))
                        .min().orElse(Double.POSITIVE_INFINITY);
                    if (distance < selectionDistance) {
                        selectionDistance = distance;
                        selected = body;
                    }
                }
                if (candidate < candidates.size()) return false;
                if (selected == null) {
                    waiting("WAITING", "no actual licensed walking builder of this colony is available");
                    return false;
                }
            }
            builder = selected.getUUID();
        }
        // Selection may yield after finding a real candidate but before scanning the remaining list.
        // The UUID still has to be bound when the next slice resumes with that selected object.
        builder = selected.getUUID();
        // A source approach must itself lie in the explicitly known body/halo domain. A nearby barrel
        // is UNKNOWN; another real approach farther from that barrel may still be a valid supply root.
        while (anchor < rawAnchors.size()) {
            BlockPos root = rawAnchors.get(anchor);
            if (anchorVoxel == -1) {
                if (!budget.tryStep()) return false;
                anchorGood = owner.certificateExterior(root) && level.isLoaded(root)
                    && ConstructionSnapshot.project(level.getBlockState(root.below())) == Voxel.FULL;
                anchorVoxel = 0;
            }
            while (anchorGood && anchorVoxel < 36) {
                if (!budget.tryStep()) return false;
                int index = anchorVoxel++;
                int x = index / 12 - 1;
                int y = index / 3 % 4 - 1;
                int z = index % 3 - 1;
                BlockPos checked = root.offset(x, y, z);
                if (!level.isLoaded(checked)) {
                    anchorGood = false;
                    continue;
                }
                Voxel value = ConstructionSnapshot.project(level.getBlockState(checked));
                if (value == Voxel.UNKNOWN || x == 0 && z == 0 && (y == 0 || y == 1) && value != Voxel.AIR) {
                    anchorGood = false;
                }
            }
            if (anchorGood) safe.add(cell(root));
            anchor++;
            anchorVoxel = -1;
        }
        if (safe.isEmpty()) {
            waiting("UNSUPPORTED", "no supplied approach has known full support and clear body/halo space");
            return false;
        }
        if (!budget.tryStep()) return false;
        List<LivingEntity> relevant = census();
        if (relevant == null) return false;
        List<Cell> targets = remaining.stream().map(CompletionRuntime::cell).toList();
        Cell start = cell(feet(selected));
        int minX = start.x(), minY = start.y(), minZ = start.z();
        int maxX = minX, maxY = minY, maxZ = minZ;
        for (Cell at : targets) {
            minX = Math.min(minX, at.x()); minY = Math.min(minY, at.y()); minZ = Math.min(minZ, at.z());
            maxX = Math.max(maxX, at.x()); maxY = Math.max(maxY, at.y()); maxZ = Math.max(maxZ, at.z());
        }
        for (Cell at : safe) {
            minX = Math.min(minX, at.x()); minY = Math.min(minY, at.y()); minZ = Math.min(minZ, at.z());
            maxX = Math.max(maxX, at.x()); maxY = Math.max(maxY, at.y()); maxZ = Math.max(maxZ, at.z());
        }
        // A room behind an entrance can contain somebody beyond the target/worker/source envelope.
        // Omitting that person would turn a complete builder proof into an unsafe door-closure proof.
        for (LivingEntity person : relevant) {
            if (capability(person) == null) {
                waiting("UNSUPPORTED", "a relevant body has undeclared or unsupported walking capability");
                return false;
            }
            Cell at = cell(feet(person));
            minX = Math.min(minX, at.x()); minY = Math.min(minY, at.y()); minZ = Math.min(minZ, at.z());
            maxX = Math.max(maxX, at.x()); maxY = Math.max(maxY, at.y()); maxZ = Math.max(maxZ, at.z());
        }
        Box box = new Box(new Cell(minX - 3, Math.max(level.getMinBuildHeight(), minY - 2), minZ - 3),
            new Cell(maxX + 3, Math.min(level.getMaxBuildHeight() - 1, maxY + 5), maxZ + 3));
        if (box.volume() > Construction.MAX_CELLS) {
            waiting("UNSUPPORTED", "the bounded capture exceeds 131072 voxels; no partial certificate is published");
            return false;
        }
        capture = new ConstructionSnapshot.Capture(level, box, targets, Set.of(), Set.copyOf(safe), start,
            targets.size(), capability(selected), certificateMaterial);
        nativeWatch = Grounds.watch(level, bounds(box));
        phase = "CAPTURING";
        reason = "capturing a coherent immutable static voxel input" + SUPPLY;
        return true;
    }

    private boolean eligible(Mob body) {
        return body.isAlive() && !body.isRemoved() && body.level() == level && !body.isNoAi() && !body.isSleeping()
            && body.getBbWidth() <= 1.0F && body.getBbHeight() <= 2.0F
            && Bodies.of(body).filter(value -> value.colonyId().filter(colony::equals).isPresent()
                && value.kind().locomotion().id().equals(Locomotions.WALKING)
                && value.licences().of(BuildContent.trade()).isPresent()).isPresent();
    }

    private boolean checkReceipts(Ground.Budget budget) {
        while (!receiptDirty.isEmpty()) {
            if (!budget.tryStep()) return false;
            BlockPos at = receiptDirty.iterator().next();
            if (!level.isLoaded(at)) {
                waiting("WAITING", "an executed target is unloaded; its receipt cannot yet be checked");
                return false;
            }
            receiptDirty.remove(at);
            if (level.getBlockState(at) != receipts.get(at)) {
                conflict("an already executed target was changed externally; its receipt is not completion proof");
                return false;
            }
        }
        return true;
    }

    private boolean anchorsRemainExterior() {
        // At most 128 supplied approaches. This is a live semantic check, independent of raw terrain revisions.
        for (Cell root : captured.problem().safeCells()) {
            if (!owner.certificateExterior(position(root))) {
                invalidate("a trusted approach now belongs to future construction; reprove the whole suffix");
                return false;
            }
        }
        return true;
    }

    private boolean live(Ground.Budget budget) {
        if (validation != null && validation.validNow()) return true;
        if (validation == null) validation = captured.validate(level, prefix, witness.dependencies());
        ConstructionSnapshot.Status result = validation.advance(adapt(budget));
        if (result == ConstructionSnapshot.Status.INVALID || result == ConstructionSnapshot.Status.WAITING) {
            invalidate("a required live dependency changed: " + validation.reason());
            return false;
        }
        if (result == ConstructionSnapshot.Status.PENDING) {
            phase = "VALIDATING";
            reason = validation.reason() + SUPPLY;
        }
        return result == ConstructionSnapshot.Status.VALID && validation.validNow();
    }

    private boolean executionLease(Ground.Budget budget) {
        // The final renewing/checking step pays for one bounded atomic execution gate. If another
        // call already renewed this tick, this gate still consumes its own step. It never borrows
        // a unit from the next tick or adds allowance to the caller's budget.
        if (validation != null && validation.validNow()) return budget.tryStep();
        return live(budget);
    }

    private boolean selectHead(Ground.Budget budget) {
        while (action < witness.actions().size()) {
            if (!budget.tryStep()) return false;
            Action next = witness.actions().get(action);
            if (next instanceof Walk walk) {
                // This records the intended proof position. Real navigation to the next stance is still
                // required by permits; the final Walk is only consumed by an actual retreat receipt.
                Cell end = walk.path().getLast();
                prefix = new State(prefix.placed(), end, prefix.usedMaterials());
                action++;
            } else if (next instanceof Place place) {
                head = place;
                headAfter = ConstructionRules.applyLocal(captured.problem(), prefix, head);
                BlockPos at = position(place.targetCell());
                logicalHead = Job.at(at, new WorkSite.AtBlock(WorldPos.of(level, at)),
                    Stances.at(level, position(place.stance())));
                returns.clear();
                beforeReturns.clear();
                destinationPaths.clear();
                destinationNative.clear();
                peoplePass = null;
                actorJoin = null;
                resetNative();
                changed.run();
                return true;
            } else if (next instanceof Finish done) {
                finish = done.safeCell();
                logicalHead = null;
                retainedSafe = Set.of(position(finish));
                return true;
            }
        }
        conflict("a verified witness ended without an executable head or final Finish");
        return false;
    }

    Permit permits(BlockPos at, BlockState block, Mob body, Ground.Budget budget) {
        prepared = null;
        lastAttempt = new LinkedHashMap<>();
        lastAttempt.put("worldTick", level.getGameTime());
        lastAttempt.put("actor", body.getUUID().toString());
        lastAttempt.put("actualPosition", body.position().toString());
        lastAttempt.put("actualFeet", feet(body).toShortString());
        lastAttempt.put("requestedCell", at.toShortString());
        lastAttempt.put("suppliedMaterial", block.toString());
        lastAttempt.put("version", version);
        lastAttempt.put("generation", certificateGeneration);
        lastAttempt.put("nativeStage", nativeStage);
        lastAttempt.put("workAtEntry", budget.used());
        if (closed || finished || conflicted || !remaining.contains(at)) return result(Permit.CHANGED, "project closed, complete, conflicted, or target no longer requested");
        if (!checkReceipts(budget)) return pending("checking the recorded actual block receipts");
        if (builder == null || !builder.equals(body.getUUID()) || !eligible(body)) return result(Permit.BLOCKED, "actor is not the actual eligible bound builder");
        if (!ConstructionSnapshot.supportedMaterial(block)) return result(Permit.BLOCKED, "supplied material is outside the static full-cube domain");
        if (block != certificateMaterial) {
            certificateMaterial = block;
            invalidate("the supplied full-cube material differs; proving the whole suffix for the actual material");
        }
        if (captured == null || witness == null || verifier == null
                || verifier.status() != CompletionVerifier.Status.VALID || head == null) advance(budget);
        if (captured == null || witness == null || verifier == null || verifier.status() != CompletionVerifier.Status.VALID
                || head == null || !head.targetCell().equals(cell(at))) return pending("waiting for the complete current suffix");
        lastAttempt.put("headTarget", position(head.targetCell()).toShortString());
        lastAttempt.put("headStance", position(head.stance()).toShortString());
        lastAttempt.put("headEntryRoot", position(head.entryFromSafe().getFirst()).toShortString());
        lastAttempt.put("headEscapeRoot", position(head.escapeToSafe().getLast()).toShortString());
        lastAttempt.put("prefixWorker", position(prefix.workerCell()).toShortString());
        lastAttempt.put("cursor", action);
        if (!capability(body).equals(captured.problem().capability())) {
            invalidate("the actual builder capability changed; recapturing the complete suffix");
            return pending("builder capability changed");
        }
        BlockPos stance = position(head.stance());
        boolean standing = Reach.standingAt(body.position(), stance);
        double eyeDistance = body.getEyePosition().distanceTo(Vec3.atCenterOf(at));
        boolean sight = standing && eyeDistance <= Reach.PLAYER_BLOCK_REACH && actualSight(body, at);
        boolean local = ConstructionRules.canPlaceLocal(captured.problem(), prefix, head.targetCell(), head.stance());
        lastAttempt.put("standingAtStance", standing);
        lastAttempt.put("eyeDistance", eyeDistance);
        lastAttempt.put("lineOfSight", standing && eyeDistance <= Reach.PLAYER_BLOCK_REACH ? sight : "not evaluated outside stance/reach");
        lastAttempt.put("modelCanPlace", local);
        if (!standing) return result(Permit.BLOCKED, "actual worker has not reached the certified work stance");
        if (eyeDistance > Reach.PLAYER_BLOCK_REACH) return exactApproach(body, "actual eye is beyond native work reach", budget);
        if (!sight) return exactApproach(body, "actual eye has no native line of sight to the target", budget);
        if (body.getBoundingBox().intersects(new AABB(at))) {
            return exactApproach(body, "actual arrival overhang intersects the future placed block", budget);
        }
        if (level.getBlockState(at) != captured.rawStates().get(head.targetCell())) {
            invalidate("the next target was occupied externally; no material or receipt was consumed");
            return pending("target changed");
        }
        if (!local) return result(Permit.BLOCKED, "current certificate prefix fails the local modeled placement rule");
        boolean survives = block.canSurvive(level, at);
        lastAttempt.put("canSurvive", survives);
        if (!survives) return result(Permit.BLOCKED, "actual material cannot survive at the live target");
        Permit nativeResult = nativeFoot(body, feet(body), at, block, budget);
        lastAttempt.put("nativeResult", nativeResult.name());
        lastAttempt.put("nativeStage", nativeStage);
        if (nativeResult == Permit.PENDING) return pending("preparing the actual native footing before and after placement");
        if (nativeResult == Permit.BLOCKED) {
            if (!feet(body).equals(stance)) return exactApproach(body, "actual feet differ from the certified native stance", budget);
            if (!executionLease(budget)) return pending("checking whether a changed dependency caused the native refusal");
            return result(Permit.BLOCKED, "complete native footing or actual body collision check rejected the placement");
        }
        Permit people = peoplePermit(at, block, body, budget);
        lastAttempt.put("peopleResult", people.name());
        if (people == Permit.PENDING) return people;
        if (people != Permit.READY) {
            if (!executionLease(budget)) return pending("checking live dependencies before refusing the prepared bystander proof");
            peoplePass = null;
            return result(people, "a current person lacks a complete safe directed escape or blocks the target");
        }
        // Native and pure directed proofs above are resumable preparation, never a write permit.
        // Only this separately paid final lease can authorize a real operation. Its bounded batch
        // rereads all actual facts, including a complete capped census, in one server-thread action.
        if (!executionLease(budget)) return pending("renewing the final same-tick dependency lease");
        long gateBegan = System.nanoTime();
        try {
            if (!anchorsRemainExterior() || captured == null || validation == null || !validation.validNow()) {
                return pending("the final exterior or dependency proof changed");
            }
            if (!body.getUUID().equals(builder) || !eligible(body)
                    || !capability(body).equals(captured.problem().capability())) {
                return result(Permit.BLOCKED, "the actual builder changed during execution preparation");
            }
            if (!Reach.standingAt(body.position(), stance)) return result(Permit.BLOCKED, "the actual builder moved away from the certified stance");
            if (body.getEyePosition().distanceTo(Vec3.atCenterOf(at)) > Reach.PLAYER_BLOCK_REACH
                    || !actualSight(body, at) || body.getBoundingBox().intersects(new AABB(at))) {
                return exactApproach(body, "the actual arrival changed while preparing the execution proof", budget);
            }
            if (level.getBlockState(at) != captured.rawStates().get(head.targetCell()) || !block.canSurvive(level, at)) {
                invalidate("the actual target or material changed during execution preparation");
                return pending("reproving the changed target");
            }
            nativeWatch.refresh();
            if (nativeBody != body || nativeStage != 2 || !feet(body).equals(nativeFeet)
                    || !nativeBefore.valid() || !nativeAfter.valid()) {
                resetNative();
                return pending("renewing the actual native footing after a live change");
            }
            if (!nativeBefore.standable(nativeFeet) || !nativeAfter.standable(nativeFeet)
                    || body.isInWall() || !level.noCollision(body, body.getBoundingBox())) {
                return result(Permit.BLOCKED, "the final actual native footing or body collision gate rejected the placement");
            }
            List<Person> fresh = currentPeople();
            if (fresh == null || peoplePass == null || !samePeople(peoplePass.people, fresh)) {
                peoplePass = null;
                return pending("the actual people changed while preparing their directed escapes");
            }
            boolean unobstructed = level.isUnobstructed(block, at, CollisionContext.empty());
            lastAttempt.put("unobstructed", unobstructed);
            if (!unobstructed) return result(Permit.BLOCKED, "final actual target collision gate rejected the placement");
            if (!validation.validNow()) return pending("a dependency changed during the final actual-person check");
            prepared = new Prepared(at.immutable(), block, body.getUUID(), level.getGameTime(), version, certificateGeneration, action);
            clearApproach();
            permitted++;
            reason = "same-tick head permit prepared; awaiting the real successful setBlock" + SUPPLY;
            return result(Permit.READY, "all same-tick prefix, actual-body, escape and native gates passed");
        } finally {
            long elapsed = System.nanoTime() - gateBegan;
            executionGates++;
            totalExecutionGateNanos += elapsed;
            maxExecutionGateNanos = Math.max(maxExecutionGateNanos, elapsed);
        }
    }

    private Permit pending(String detail) {
        permitYields++;
        if (!"CONFLICT".equals(phase)) reason = detail + SUPPLY;
        return result(Permit.PENDING, detail);
    }

    private Permit result(Permit answer, String detail) {
        lastPermitReason = detail;
        if (lastAttempt instanceof LinkedHashMap<?, ?>) {
            lastAttempt.put("result", answer.name());
            lastAttempt.put("reason", detail);
        }
        return answer;
    }

    private Permit exactApproach(Mob body, String why, Ground.Budget budget) {
        if (approach == null || !approach.body().equals(body.getUUID())
                || !approach.target().equals(position(head.targetCell())) || approach.generation() != certificateGeneration) {
            clearApproach();
        }
        approach = new ExactApproach(body.getUUID(), position(head.targetCell()), position(head.stance()),
            certificateGeneration, version, level.getGameTime() + 20L);
        ApproachResult answer = steerApproach(budget);
        lastAttempt.put("preciseApproach", approachStatus);
        if (answer == ApproachResult.BLOCKED || answer == ApproachResult.CENTRED) {
            return result(Permit.BLOCKED, why + "; the complete native centre/local-connection check cannot correct it");
        }
        return pending(why + "; preserving the task and material while normally walking to the certified centre");
    }

    private ApproachResult steerApproach(Ground.Budget budget) {
        ExactApproach intent = approach;
        if (intent == null) return ApproachResult.CENTRED;
        if (captured == null || prefix == null || head == null || validation == null
                || intent.generation() != certificateGeneration || intent.version() != version
                || !intent.target().equals(position(head.targetCell())) || level.getGameTime() > intent.until()
                || !(level.getEntity(intent.body()) instanceof Mob body) || !eligible(body)) {
            clearApproach();
            approachStatus = "expired or no longer belongs to the live head/actor";
            return ApproachResult.BLOCKED;
        }
        boolean ourDoing = Bodies.of(body).flatMap(value -> value.doing()).map(doing ->
            doing.what().equals(GroundworkContent.FILLING)
                || doing.toward().filter(GroundworkContent.FILLING::equals).isPresent()).orElse(true);
        if (!ourDoing || body.getTarget() != null || body.getLastHurtByMob() != null) {
            clearApproach();
            approachStatus = "yielded local movement to another activity or combat";
            return ApproachResult.BLOCKED;
        }
        Vec3 centre = Vec3.atBottomCenterOf(intent.stance());
        Vec3 delta = centre.subtract(body.position());
        if (delta.horizontalDistanceSqr() <= 0.0016D && Math.abs(delta.y) <= 0.05D) {
            clearApproach();
            approachStatus = "actual body reached the certified centre";
            return ApproachResult.CENTRED;
        }
        if (!Reach.standingAt(body.position(), intent.stance())
                || !capability(body).equals(captured.problem().capability())) {
            clearApproach();
            approachStatus = "actual body left the bounded local arrival correction";
            return ApproachResult.BLOCKED;
        }
        if (!budget.exhausted()) {
            // Active, still-owned preparation may span more than twenty one-work ticks. The
            // movement intent remains short-lived unless this live owner check keeps renewing it.
            approach = new ExactApproach(intent.body(), intent.target(), intent.stance(), intent.generation(),
                intent.version(), level.getGameTime() + 20L);
        }
        if (approachBefore == null || approachBody != body || !approachBefore.valid() || !approachAfter.valid()) {
            approachBody = body;
            approachBefore = nativeWatch.now(body);
            approachAfter = approachBefore.with(Map.of(intent.target(), certificateMaterial));
            approachStage = 0;
        }
        if (approachStage == 0) {
            if (!approachBefore.prepare(intent.stance(), budget)) {
                approachStatus = "preparing native centre before placement";
                return ApproachResult.PENDING;
            }
            approachStage = 1;
        }
        if (approachStage == 1) {
            if (!approachAfter.prepare(intent.stance(), budget)) {
                approachStatus = "preparing native centre after placement";
                return ApproachResult.PENDING;
            }
            approachStage = 2;
        }
        if (!executionLease(budget)) return ApproachResult.PENDING;
        if (!anchorsRemainExterior() || captured == null) return ApproachResult.PENDING;
        nativeWatch.refresh();
        if (!approachBefore.valid() || !approachAfter.valid()) {
            approachStage = 0;
            approachBefore = null;
            approachAfter = null;
            return ApproachResult.PENDING;
        }
        if (!approachBefore.standable(intent.stance()) || !approachAfter.standable(intent.stance())) {
            clearApproach();
            approachStatus = "fully prepared native centre is not standable before and after placement";
            return ApproachResult.BLOCKED;
        }
        Cell here = cell(feet(body));
        Cell goal = cell(intent.stance());
        AABB actual = body.getBoundingBox();
        // A real arrival can overhang a ledge although its discrete feet cell is air. If it already
        // overlaps the very same known full support as the goal, horizontal centring monotonically
        // increases that overlap; the continuous support never depends on the still-unplaced block.
        boolean sharedSupport = Math.abs(delta.y) <= 0.05D
            && ConstructionRules.voxel(captured.problem(), prefix, goal.below()) == Voxel.FULL
            && actual.maxX > goal.x() + 1.0E-6D && actual.minX < goal.x() + 1.0D - 1.0E-6D
            && actual.maxZ > goal.z() + 1.0E-6D && actual.minZ < goal.z() + 1.0D - 1.0E-6D;
        // Native arrivals can stand on a neighbouring block's edge while the feet-cell centre is
        // unsupported. Do not invent a standable centre there. A real supported pose may instead
        // move horizontally and descend at most one block onto the already proved native centre.
        // The known full catch plane also bounds an earlier gravity-driven descent along the move.
        boolean supportedDrop = delta.y < -0.05D && delta.y >= -1.00001D
            && actualFullSupport(body, actual) && knownCatchPlane(actual, centre);
        boolean retainedDrop = retainedDrop(body, goal);
        // A corner may overhang an AIR stair shaft beside the landing. Never call that AIR a full
        // catch plane: retain only the same real FULL landing support already under this body.
        boolean projectedDrop = !supportedDrop && delta.y < -0.05D && delta.y >= -1.00001D
            && actualFullSupport(body, actual) && landingSupportProjection(actual, goal);
        // An ordinary jump temporarily has AIR below its discrete feet centre. Keep the already
        // proved directed up-step while its real body remains in that short launch/landing corridor;
        // reinterpreting every airborne pose as a newly supported centre cancels the jump halfway.
        Cell waypoint = retainedStep(body) ? approachStep.landing()
            : retainedDrop || sharedSupport || supportedDrop || projectedDrop ? goal : localWaypoint(here, goal);
        if (waypoint == null) {
            clearApproach();
            approachStatus = "no supported short directed connection from the actual pose to the centre";
            return ApproachResult.BLOCKED;
        }
        Vec3 wanted = Vec3.atBottomCenterOf(position(waypoint));
        Vec3 motion = wanted.subtract(body.position());
        if (projectedDrop && approachDrop == null) {
            approachDrop = new ApproachDrop(goal, body.position(), body.position());
        }
        if (approachStep == null && waypoint.y() == here.y() + 1
                && actualFullSupport(body, actual)
                && ConstructionRules.legalMove(captured.problem(), prefix, here, waypoint)) {
            approachStep = new ApproachStep(here, waypoint, body.position());
        }
        AABB first;
        AABB second;
        if (motion.y > 0.05D) {
            first = actual.expandTowards(0.0D, motion.y, 0.0D);
            second = actual.move(0.0D, motion.y, 0.0D).expandTowards(motion.x, 0.0D, motion.z);
        } else {
            first = actual.expandTowards(motion.x, 0.0D, motion.z);
            second = actual.move(motion.x, 0.0D, motion.z).expandTowards(0.0D, motion.y, 0.0D);
        }
        if (!knownSweep(first) || !knownSweep(second)) {
            clearApproach();
            approachStatus = "continuous local sweep touches unknown or unavailable terrain";
            return ApproachResult.BLOCKED;
        }
        if (!level.noCollision(body, first.deflate(1.0E-7D)) || !level.noCollision(body, second.deflate(1.0E-7D))) {
            approachStatus = "local movement is currently obstructed; no steering was issued";
            requestApproachAside(body, first.minmax(second), budget);
            return ApproachResult.PENDING;
        }
        if (!validation.validNow()) return ApproachResult.PENDING;
        // This is an ordinary local movement intent, never a position write. The short lease is
        // canceled by any other named activity/urge and rechecked against live terrain every tick.
        body.getNavigation().stop();
        if (body.onGround() && (approachStep != null && motion.y > 0.05D || approachDrop != null)) {
            // MoveControl turns at most ninety degrees before immediately jumping, and its JUMPING
            // branch does not turn again. Let that same native control align at zero speed first.
            double yaw = Math.toDegrees(Math.atan2(motion.z, motion.x)) - 90.0D;
            double turn = Math.IEEEremainder(yaw - body.getYRot(), 360.0D);
            if (Math.abs(turn) > (approachDrop == null ? 10.0D : 1.0D)) {
                body.getMoveControl().setWantedPosition(wanted.x, body.getY(), wanted.z, 0.0D);
                approachAlignCommands++;
                approachStatus = approachDrop == null ? "native zero-speed alignment before the proved one-block jump"
                    : "native zero-speed alignment before the proved projected corner descent";
                return ApproachResult.MOVING;
            }
        }
        body.getMoveControl().setWantedPosition(wanted.x, wanted.y, wanted.z, 1.0D);
        approachCommands++;
        if (supportedDrop) approachDropCommands++;
        if (approachStep != null) approachStepCommands++;
        if (approachDrop != null) approachProjectedDropCommands++;
        approachStatus = approachStep != null ? "native proved up-step retained through its bounded airborne segment"
            : approachDrop != null ? "native descent retains the same known FULL landing support projection"
            : supportedDrop ? "native supported-edge movement and at-most-one-block descent issued"
            : "native local exact-centre movement intent issued";
        return ApproachResult.MOVING;
    }

    private boolean retainedStep(Mob body) {
        ApproachStep step = approachStep;
        if (step == null) return false;
        Vec3 landing = Vec3.atBottomCenterOf(position(step.landing()));
        Vec3 now = body.position();
        if (!ConstructionRules.legalMove(captured.problem(), prefix, step.launch(), step.landing())
                || now.y < step.takeoff().y - 0.05D || now.y > landing.y + 0.5D
                || now.x < Math.min(step.takeoff().x, landing.x) - 0.3D
                || now.x > Math.max(step.takeoff().x, landing.x) + 0.3D
                || now.z < Math.min(step.takeoff().z, landing.z) - 0.3D
                || now.z > Math.max(step.takeoff().z, landing.z) + 0.3D
                || body.onGround() && Math.abs(now.y - landing.y) <= 0.05D) {
            approachStep = null;
            return false;
        }
        return true;
    }

    private boolean landingSupportProjection(AABB actual, Cell goal) {
        return ConstructionRules.voxel(captured.problem(), prefix, goal.below()) == Voxel.FULL
            && actual.maxX > goal.x() + 1.0E-6D && actual.minX < goal.x() + 1.0D - 1.0E-6D
            && actual.maxZ > goal.z() + 1.0E-6D && actual.minZ < goal.z() + 1.0D - 1.0E-6D;
    }

    private boolean retainedDrop(Mob body, Cell goal) {
        ApproachDrop drop = approachDrop;
        if (drop == null) return false;
        Vec3 centre = Vec3.atBottomCenterOf(position(goal));
        Vec3 now = body.position();
        if (!drop.landing().equals(goal) || now.y > drop.launch().y + 0.05D || now.y < centre.y - 0.05D
                || now.x < Math.min(drop.launch().x, centre.x) - 1.0E-5D
                || now.x > Math.max(drop.launch().x, centre.x) + 1.0E-5D
                || now.z < Math.min(drop.launch().z, centre.z) - 1.0E-5D
                || now.z > Math.max(drop.launch().z, centre.z) + 1.0E-5D
                || Math.abs(now.x - centre.x) > Math.abs(drop.previous().x - centre.x) + 1.0E-5D
                || Math.abs(now.z - centre.z) > Math.abs(drop.previous().z - centre.z) + 1.0E-5D
                || !landingSupportProjection(body.getBoundingBox(), goal)
                || body.onGround() && Math.abs(now.y - centre.y) <= 0.05D) {
            approachDrop = null;
            return false;
        }
        approachDrop = new ApproachDrop(goal, drop.launch(), now);
        return true;
    }

    private boolean actualFullSupport(Mob body, AABB actual) {
        if (body.isInWall() || !level.noCollision(body, actual)) return false;
        int supportY = (int)Math.floor(actual.minY - 1.0E-6D);
        if (Math.abs(actual.minY - (supportY + 1.0D)) > 1.0E-5D) return false;
        if (!chunksLoaded(level, actual.move(0.0D, -1.0E-4D, 0.0D))) return false;
        if (level.noCollision(body, actual.move(0.0D, -1.0E-4D, 0.0D))) return false;
        for (BlockPos at : BlockPos.betweenClosed(
                BlockPos.containing(actual.minX + 1.0E-6D, supportY, actual.minZ + 1.0E-6D),
                BlockPos.containing(actual.maxX - 1.0E-6D, supportY, actual.maxZ - 1.0E-6D))) {
            if (ConstructionRules.voxel(captured.problem(), prefix, cell(at)) == Voxel.FULL) return true;
        }
        return false;
    }

    private boolean knownCatchPlane(AABB actual, Vec3 centre) {
        double dx = centre.x - (actual.minX + actual.maxX) * 0.5D;
        double dz = centre.z - (actual.minZ + actual.maxZ) * 0.5D;
        AABB footprint = actual.expandTowards(dx, 0.0D, dz);
        int floorY = (int)Math.floor(centre.y - 1.0E-6D);
        // This only reads the tiny footprint inside the existing native arrival tolerance. Requiring
        // a full plane is deliberately stricter than accepting an unknown fall beside the ledge.
        for (BlockPos at : BlockPos.betweenClosed(
                BlockPos.containing(footprint.minX + 1.0E-6D, floorY, footprint.minZ + 1.0E-6D),
                BlockPos.containing(footprint.maxX - 1.0E-6D, floorY, footprint.maxZ - 1.0E-6D))) {
            if (ConstructionRules.voxel(captured.problem(), prefix, cell(at)) != Voxel.FULL) return false;
        }
        return true;
    }

    private Cell localWaypoint(Cell from, Cell goal) {
        Problem problem = captured.problem();
        if (from.equals(goal)) return ConstructionRules.standable(problem, prefix, from) ? goal : null;
        if (ConstructionRules.legalMove(problem, prefix, from, goal)) return goal;
        if (Math.abs(from.x() - goal.x()) > 1 || Math.abs(from.z() - goal.z()) > 1
                || Math.abs(from.y() - goal.y()) > 1) return null;
        for (Cell between : List.of(new Cell(goal.x(), from.y(), from.z()), new Cell(from.x(), from.y(), goal.z()),
                new Cell(goal.x(), goal.y(), from.z()), new Cell(from.x(), goal.y(), goal.z()))) {
            if (ConstructionRules.legalMove(problem, prefix, from, between)
                    && ConstructionRules.legalMove(problem, prefix, between, goal)) return between;
        }
        return null;
    }

    private boolean knownSweep(AABB sweep) {
        if (!chunksLoaded(level, sweep)) return false;
        // Arrival correction is bounded by the native 0.9/1 tolerance, not an arbitrary long ray/path.
        for (BlockPos at : BlockPos.betweenClosed(BlockPos.containing(sweep.minX + 1.0E-7D, sweep.minY + 1.0E-7D, sweep.minZ + 1.0E-7D),
                BlockPos.containing(sweep.maxX - 1.0E-7D, sweep.maxY - 1.0E-7D, sweep.maxZ - 1.0E-7D))) {
            if (ConstructionRules.voxel(captured.problem(), prefix, cell(at)) == Voxel.UNKNOWN) return false;
        }
        return true;
    }

    private void requestApproachAside(Mob actor, AABB swept, Ground.Budget budget) {
        if (!budget.tryStep()) return;
        for (Mob other : level.getEntitiesOfClass(Mob.class, swept, body -> body != actor && body.isAlive())) {
            Capability cap = capability(other);
            if (cap == null || !Bodies.of(other).flatMap(value -> value.colonyId()).filter(colony::equals).isPresent()) continue;
            Problem model = profile(cap, budget);
            if (model == null) return;
            ReturnPaths paths = beforeReturns.computeIfAbsent(cap, value -> new ReturnPaths(model, prefix));
            Cell actual = cell(feet(other));
            if (paths.advance(actual, budget)) addDestination(other, position(paths.root(actual)));
            if (budget.exhausted()) break;
        }
    }

    private void clearApproach() {
        approach = null;
        approachBefore = null;
        approachAfter = null;
        approachBody = null;
        approachStage = 0;
        approachStep = null;
        approachDrop = null;
    }

    private Permit nativeFoot(Mob body, BlockPos actual, BlockPos at, BlockState block, Ground.Budget budget) {
        if (nativeBefore == null || nativeBody != body || !actual.equals(nativeFeet) || !nativeBefore.valid()
                || nativeAfter != null && !nativeAfter.valid()) {
            nativeWatch.refresh();
            nativeBody = body;
            nativeFeet = actual.immutable();
            nativeBefore = nativeWatch.now(body);
            nativeAfter = nativeBefore.with(Map.of(at, block));
            nativeStage = 0;
        }
        if (nativeStage == 0) {
            if (!nativeBefore.prepare(actual, budget)) return Permit.PENDING;
            nativeStage = 1;
        }
        if (nativeStage == 1) {
            if (!nativeAfter.prepare(actual, budget)) return Permit.PENDING;
            nativeStage = 2;
        }
        boolean before = nativeBefore.standable(actual);
        boolean after = nativeAfter.standable(actual);
        boolean noCollision = level.noCollision(body, body.getBoundingBox());
        boolean inWall = body.isInWall();
        lastAttempt.put("nativeBeforeStandable", before);
        lastAttempt.put("nativeAfterStandable", after);
        lastAttempt.put("actualBodyNoCollision", noCollision);
        lastAttempt.put("actualBodyInWall", inWall);
        return before && after && noCollision && !inWall ? Permit.READY : Permit.BLOCKED;
    }

    private Permit peoplePermit(BlockPos at, BlockState block, Mob actor, Ground.Budget budget) {
        if (peoplePass == null) {
            if (!budget.tryStep()) return pending("enumerating the actual people for execution preparation");
            List<Person> people = currentPeople();
            if (people == null) return captured == null ? pending("a newly relevant person requires a larger coherent capture") : Permit.BLOCKED;
            peoplePass = new PeoplePass(people, peopleStamp(people));
        }
        if (peoplePass.blocked) return Permit.BLOCKED;
        State after = headAfter;
        while (peoplePass.cursor < peoplePass.people.size()) {
            if (peoplePass.stage == 0) {
                if (!budget.tryStep()) return pending("starting the next directed bystander proof");
                peoplePass.stage = 1;
            }
            Person person = peoplePass.people.get(peoplePass.cursor);
            lastAttempt.put("peopleCursor", peoplePass.cursor);
            lastAttempt.put("peopleStage", peoplePass.stage);
            lastAttempt.put("peopleCount", peoplePass.people.size());
            lastAttempt.put("person", person.body().getUUID().toString());
            lastAttempt.put("personFeet", position(person.feet()).toShortString());
            if (person.capability() == null || person.body().isSleeping()) return blockedPeople();
            boolean collision = person.box().intersects(new AABB(at));
            // The full verifier already replayed this exact post-head escape. Repeating a complete
            // reverse search during every windup adds no proof for the bound builder at that stance.
            // Other feet cells require their own bounded directed connection; no bystander is skipped.
            boolean actorWitness = !collision && person.body() == actor && actor.getUUID().equals(builder)
                && person.capability().equals(captured.problem().capability())
                && verifier.status() == CompletionVerifier.Status.VALID && action < witness.actions().size()
                && witness.actions().get(action) == head && head.escapeToSafe().getFirst().equals(head.stance())
                && captured.problem().safeCells().contains(head.escapeToSafe().getLast())
                && nativeBody == actor && nativeStage == 2 && nativeFeet.equals(feet(actor));
            if (actorWitness) {
                if (actorJoin == null || !actorJoin.from.equals(person.feet()) || !actorJoin.to.equals(head.stance())) {
                    actorJoin = new ActorJoin(captured.problem(), after, person.feet(), head.stance());
                }
                actorJoin.advance(budget);
                lastAttempt.put("actorJoinStage", actorJoin.stage);
                if (!actorJoin.complete) return pending("continuing the bounded post-head directed connection to the verified escape");
                if (actorJoin.path != null) {
                    actorEscapeReuses++;
                    if (actorJoin.path.size() > 1) actorEscapeJoinReuses++;
                    lastAttempt.put("actorEscape", "joined a positive post-head path to the fully verified escape");
                    lastAttempt.put("actorJoinPath", actorJoin.path.stream().map(value -> position(value).toShortString()).toList());
                    peoplePass.cursor++;
                    peoplePass.stage = 0;
                    continue;
                }
            }
            Problem model = profile(person.capability(), budget);
            if (model == null) return pending("waiting for a bounded capability profile");
            ReturnPaths paths = returns.computeIfAbsent(person.capability(), cap -> new ReturnPaths(model, after));
            boolean found = paths.advance(person.feet(), budget);
            if (!found && !paths.complete()) return pending("continuing a directed escape witness for an actual body");
            if (collision || !found) {
                if (person.body() instanceof Mob mob && !mob.getUUID().equals(actor.getUUID())
                        && Bodies.of(mob).flatMap(value -> value.colonyId()).filter(colony::equals).isPresent()) {
                    ReturnPaths earlier = beforeReturns.computeIfAbsent(person.capability(), cap -> new ReturnPaths(model, prefix));
                    if (!earlier.advance(person.feet(), budget)) {
                        if (!earlier.complete()) return pending("continuing the real pre-placement step-aside witness");
                        return blockedPeople();
                    }
                    BlockPos root = position(earlier.root(person.feet()));
                    addDestination(mob, root);
                }
                return blockedPeople();
            }
            peoplePass.cursor++;
            peoplePass.stage = 0;
        }
        // This is only immutable-prefix preparation. The separately charged final execution gate
        // must compare a new complete census exactly before any prepared write token can exist.
        return Permit.READY;
    }

    private Permit blockedPeople() {
        peoplePass.blocked = true;
        return Permit.BLOCKED;
    }

    private List<Person> currentPeople() {
        List<LivingEntity> found = census();
        if (found == null) return null;
        List<Person> result = new ArrayList<>(found.size());
        for (LivingEntity body : found) {
            Cell at = cell(feet(body));
            Box box = captured.problem().bounds();
            if (!box.contains(at.offset(-1, -1, -1)) || !box.contains(at.offset(1, 2, 1))) {
                lastAttempt.put("newPersonOutsideCapture", body.getUUID().toString());
                lastAttempt.put("newPersonFeet", position(at).toShortString());
                invalidate("an actual relevant person lies outside the coherent model; recapture rather than ignore them");
                return null;
            }
            result.add(new Person(body, at, capability(body), body.getBoundingBox()));
        }
        return result;
    }

    private List<LivingEntity> census() {
        long began = System.nanoTime();
        List<LivingEntity> found = new ArrayList<>();
        int[] scanned = { 0 };
        boolean[] limited = { false };
        try {
            // Native abortable iteration is coherent on the server thread and never scans an
            // unbounded realm query. Saturation means UNKNOWN, including an exactly full scan cap.
            level.getEntities().get(EntityTypeTest.forClass(Entity.class), entity -> {
                scanned[0]++;
                if (entity instanceof LivingEntity body && body.isAlive() && !body.isSpectator()
                        && (body instanceof Player || ResidentKinds.of(BuiltInRegistries.ENTITY_TYPE.getKey(body.getType())).isPresent()
                            || body.getBoundingBox().intersects(vicinity))) found.add(body);
                if (found.size() > MOST_PEOPLE || scanned[0] >= MOST_CENSUS_ENTITIES) {
                    limited[0] = true;
                    return AbortableIterationConsumer.Continuation.ABORT;
                }
                return AbortableIterationConsumer.Continuation.CONTINUE;
            });
        } finally {
            long elapsed = System.nanoTime() - began;
            censusCalls++;
            lastCensusScanned = scanned[0];
            lastCensusPeople = found.size();
            lastCensusLimited = limited[0];
            totalCensusScanned += scanned[0];
            totalCensusNanos += elapsed;
            maxCensusNanos = Math.max(maxCensusNanos, elapsed);
        }
        if (limited[0]) {
            waiting("UNSUPPORTED", "the complete loaded-person census exceeds 512 people or the 2048-entity atomic scan cap; nobody was silently omitted");
            return null;
        }
        return found;
    }

    private static long peopleStamp(List<Person> people) {
        long sum = 0L;
        for (Person person : people) {
            long bits = person.body().getUUID().getMostSignificantBits() ^ person.body().getUUID().getLeastSignificantBits();
            bits = 31L * bits + person.feet().hashCode();
            bits = 31L * bits + (person.capability() == null ? 0 : person.capability().hashCode());
            bits = 31L * bits + person.box().hashCode();
            sum += Long.rotateLeft(bits, person.body().getId() & 63);
        }
        return 31L * sum + people.size();
    }

    private static boolean samePeople(List<Person> earlier, List<Person> current) {
        if (earlier.size() != current.size()) return false;
        Map<UUID, Person> exact = new HashMap<>();
        for (Person person : earlier) exact.put(person.body().getUUID(), person);
        for (Person person : current) {
            if (!person.equals(exact.remove(person.body().getUUID()))) return false;
        }
        return exact.isEmpty();
    }

    private Problem profile(Capability cap, Ground.Budget budget) {
        Problem problem = captured.problem();
        if (cap.equals(problem.capability())) return problem;
        Problem cached = profiles.get(cap);
        if (cached != null) return cached;
        long cells = problem.bounds().volume();
        if (profiles.size() >= MOST_PROFILES || profileCells + cells > Construction.MAX_CELLS) {
            waiting("UNSUPPORTED", "additional body profiles exceed eight profiles or 131072 total profile cells");
            return null;
        }
        if (!budget.tryStep()) return null;
        long began = System.nanoTime();
        try {
            Problem made = new Problem(problem.bounds(), problem.baseVoxels(), problem.orderedTargets(), problem.protectedCells(),
                problem.safeCells(), problem.start(), problem.materialCount(), cap, problem.ruleVersion());
            profiles.put(cap, made);
            profileCells += cells;
            profileBuilds++;
            return made;
        } finally {
            long elapsed = System.nanoTime() - began;
            totalProfileNanos += elapsed;
            maxProfileNanos = Math.max(maxProfileNanos, elapsed);
        }
    }

    boolean committed(BlockPos at, BlockState block, Mob body) {
        Prepared ready = prepared;
        prepared = null;
        if (closed || ready == null || ready.tick() != level.getGameTime() || ready.version() != version
                || ready.generation() != certificateGeneration || ready.action() != action || !ready.cell().equals(at)
                || ready.material() != block || !ready.body().equals(body.getUUID()) || head == null
                || level.getBlockState(at) != block) {
            // A failed certificate receipt cannot erase an actual successful world write. Keep its
            // physical fact and obligation, while refusing to advance any model cursor or permission.
            if (cells.contains(at) && level.isLoaded(at) && level.getBlockState(at) == block) {
                receipts.putIfAbsent(at.immutable(), block);
                if (retreatOwed == null) retreatOwed = body.getUUID();
            }
            conflict("a successful write did not match its same-tick prepared prefix; no certificate prefix was invented");
            return false;
        }
        receipts.put(at.immutable(), block);
        retreatOwed = body.getUUID();
        retainedSafe = captured.problem().safeCells().stream().map(CompletionRuntime::position)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
        prefix = ConstructionRules.applyLocal(captured.problem(), prefix, head);
        LinkedHashSet<BlockPos> next = new LinkedHashSet<>(remaining);
        next.remove(at);
        remaining = Set.copyOf(next);
        targetsLeft = remaining.size();
        action++;
        commits++;
        logicalHead = null;
        head = null;
        safeHeadReady = false;
        finish = null;
        closeValidation();
        validation = captured.validate(level, prefix, witness.dependencies());
        returns.clear();
        beforeReturns.clear();
        destinationPaths.clear();
        destinationNative.clear();
        peoplePass = null;
        actorJoin = null;
        resetNative();
        destinations.clear();
        moves = List.of();
        phase = "VALIDATING";
        reason = "one actual block receipt consumed; validating the remaining certified suffix" + SUPPLY;
        changed.run();
        return true;
    }

    private void publishRetreat() {
        if (retreatOwed == null) {
            // An empty initial project has no construction receipt and therefore no invented worker obligation.
            if (receipts.isEmpty()) {
                finished = true;
                phase = "COMPLETE";
                reason = "no placement was required";
            } else {
                conflict("a construction receipt lost its recorded final retreat obligation");
            }
            return;
        }
        if (!(level.getEntity(retreatOwed) instanceof Mob body) || !eligible(body)) {
            phase = "RETREATING";
            reason = "waiting for the original obligated builder to load and actually leave";
            return;
        }
        addDestination(body, position(finish));
        phase = "RETREATING";
        reason = "all targets were placed; the original builder must actually reach the external safe anchor";
    }

    private void addDestination(Mob body, BlockPos target) {
        if (target.equals(destinations.get(body.getUUID()))) return;
        destinations.put(body.getUUID(), target.immutable());
        List<Grown> next = new ArrayList<>();
        for (Map.Entry<UUID, BlockPos> entry : destinations.entrySet()) {
            if (level.getEntity(entry.getKey()) instanceof Mob resident) {
                next.add(Grown.of(new StepAsideNode(owner, zone, resident, entry.getValue(), level)));
            }
        }
        moves = List.copyOf(next);
        changed.run();
    }

    boolean safeDestination(LivingEntity body, BlockPos target, Ground.Budget budget) {
        destinationPrepared = null;
        if (closed || captured == null || witness == null || verifier == null
                || verifier.status() != CompletionVerifier.Status.VALID || !target.equals(destinations.get(body.getUUID()))
                || !owner.certificateExterior(target) || !checkReceipts(budget)) return false;
        Capability cap = capability(body);
        if (cap == null) return false;
        Problem model = profile(cap, budget);
        if (model == null) return false;
        DestinationKey key = new DestinationKey(cap, cell(target));
        ReturnPaths paths = destinationPaths.computeIfAbsent(key, value ->
            new ReturnPaths(model, prefix, Set.of(cell(target))));
        Cell actual = cell(feet(body));
        if (!paths.advance(actual, budget) || !paths.contains(cell(target))
                || !ConstructionRules.standable(model, prefix, cell(target))) return false;
        DestinationNative preparedGround = destinationNative.get(body.getUUID());
        if (preparedGround == null || preparedGround.body() != body || !preparedGround.target().equals(target)
                || !preparedGround.capability().equals(cap) || !preparedGround.ground().valid()) {
            preparedGround = new DestinationNative(body, target.immutable(), cap, nativeWatch.now(body));
            destinationNative.put(body.getUUID(), preparedGround);
        }
        Ground ground = preparedGround.ground();
        if (!ground.prepare(target, budget)) return false;
        if (!executionLease(budget)) return false;
        long gateBegan = System.nanoTime();
        try {
            if (captured == null || validation == null || !validation.validNow() || !owner.certificateExterior(target)
                    || !target.equals(destinations.get(body.getUUID())) || !cap.equals(capability(body))) return false;
            nativeWatch.refresh();
            if (!ground.valid()) {
                destinationNative.remove(body.getUUID());
                return false;
            }
            if (!ground.standable(target) || !paths.contains(cell(feet(body)))
                    || !ConstructionRules.standable(model, prefix, cell(target)) || !validation.validNow()) return false;
            destinationPrepared = new DestinationPrepared(body.getUUID(), target.immutable(), level.getGameTime(), certificateGeneration);
            return true;
        } finally {
            long elapsed = System.nanoTime() - gateBegan;
            executionGates++;
            totalExecutionGateNanos += elapsed;
            maxExecutionGateNanos = Math.max(maxExecutionGateNanos, elapsed);
        }
    }

    boolean provingDestination(BlockPos target) {
        return !closed && !"CONFLICT".equals(phase) && destinations.containsValue(target);
    }

    boolean movedAside(UUID resident) {
        BlockPos target = destinations.get(resident);
        DestinationPrepared ready = destinationPrepared;
        if (target == null || ready == null || !ready.body().equals(resident) || !ready.target().equals(target)
                || ready.tick() != level.getGameTime() || ready.generation() != certificateGeneration
                || !(level.getEntity(resident) instanceof Mob body) || !Reach.standingAt(body.position(), target)
                || !owner.clearOfFill(body) || body.isInWall() || !level.noCollision(body, body.getBoundingBox())) return false;
        destinationPrepared = null;
        destinations.remove(resident);
        destinationNative.remove(resident);
        moves = moves.stream().filter(move -> move.nodes().stream().noneMatch(node -> node.worker().filter(resident::equals).isPresent())).toList();
        if (finish != null && resident.equals(retreatOwed) && position(finish).equals(target) && remaining.isEmpty()) {
            retreatOwed = null;
            retainedSafe = Set.of();
            action++;
            finished = true;
            logicalHead = null;
            phase = "COMPLETE";
            reason = "every target has a real material receipt and the original builder actually completed its retreat";
            closeProof();
        }
        peoplePass = null;
        changed.run();
        return true;
    }

    Optional<Job> nextJob() { return closed || finished || !safeHeadReady ? Optional.empty() : Optional.ofNullable(logicalHead); }
    Optional<UUID> worker() { return Optional.ofNullable(retreatOwed == null ? builder : retreatOwed); }
    List<Grown> steppingAside() { return moves; }
    boolean complete() { return finished && !closed && !conflicted && receiptDirty.isEmpty(); }
    String status() { return phase; }
    String phase() { return phase; }
    String reason() { return reason; }
    int cursor() { return action; }
    int targetsLeft() { return targetsLeft; }

    Map<String, Object> diagnostics() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("phase", phase); result.put("reason", reason); result.put("cursor", action);
        result.put("targetsLeft", targetsLeft); result.put("receipts", receipts.size());
        result.put("retreatOwed", retreatOwed == null ? "" : retreatOwed.toString());
        result.put("builder", builder == null ? "" : builder.toString());
        result.put("generation", certificateGeneration); result.put("slices", slices);
        result.put("lastWork", lastWork); result.put("totalWork", totalWork); result.put("maxSliceNanos", maxSliceNanos);
        result.put("reproofs", reproofs); result.put("commits", commits); result.put("permitYields", permitYields);
        result.put("permitted", permitted); result.put("conditionalMaterialSupply", true);
        result.put("actorEscapeReuses", actorEscapeReuses);
        result.put("actorEscapeJoinReuses", actorEscapeJoinReuses);
        result.put("personRelevance", "all loaded nonspectator players and declared residents in this dimension, plus other living entities within the 64-block vicinity; scan saturation never permits");
        result.put("censusCalls", censusCalls); result.put("totalCensusScanned", totalCensusScanned);
        result.put("lastCensusScanned", lastCensusScanned); result.put("lastCensusPeople", lastCensusPeople);
        result.put("lastCensusLimited", lastCensusLimited);
        result.put("totalCensusNanos", totalCensusNanos); result.put("maxCensusNanos", maxCensusNanos);
        result.put("profiles", profiles.size()); result.put("profileCells", profileCells);
        result.put("profileBuilds", profileBuilds); result.put("totalProfileNanos", totalProfileNanos);
        result.put("maxProfileNanos", maxProfileNanos);
        result.put("uncommittedBuilderReselections", uncommittedBuilderReselections);
        result.put("executionGates", executionGates);
        result.put("totalExecutionGateNanos", totalExecutionGateNanos);
        result.put("maxExecutionGateNanos", maxExecutionGateNanos);
        result.put("lastPermitReason", lastPermitReason); result.put("lastAttempt", Map.copyOf(lastAttempt));
        result.put("approachStatus", approachStatus); result.put("approachCommands", approachCommands);
        result.put("approachDropCommands", approachDropCommands);
        result.put("approachStepCommands", approachStepCommands);
        result.put("approachAlignCommands", approachAlignCommands);
        result.put("approachProjectedDropCommands", approachProjectedDropCommands);
        result.put("snapshotCells", captured == null ? 0L : captured.problem().bounds().volume());
        result.put("witnessActions", witness == null ? 0 : witness.actions().size());
        result.put("searchStage", search == null ? "" : search.stage());
        result.put("expandedStates", search == null ? 0L : search.expandedStates());
        result.put("backtracks", search == null ? 0L : search.backtracks());
        result.put("indexedStances", search == null ? 0L : search.indexedStances());
        result.put("candidateStances", search == null ? 0L : search.candidateStances());
        return Map.copyOf(result);
    }

    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("format", 1);
        tag.putBoolean("conflict", conflicted);
        if (conflicted) tag.putString("conflictReason", conflictReason);
        tag.putBoolean("retreatSatisfied", finished && retreatOwed == null && !conflicted);
        ListTag writes = new ListTag();
        for (Map.Entry<BlockPos, BlockState> entry : receipts.entrySet()) {
            CompoundTag write = new CompoundTag();
            write.putLong("cell", entry.getKey().asLong());
            write.put("state", NbtUtils.writeBlockState(entry.getValue()));
            writes.add(write);
        }
        tag.put("receipts", writes);
        if (retreatOwed != null) tag.putUUID("retreatOwed", retreatOwed);
        tag.putLongArray("safe", retainedSafe.stream().mapToLong(BlockPos::asLong).toArray());
        if (certificateMaterial != null) tag.put("material", NbtUtils.writeBlockState(certificateMaterial));
        // Certificates, native Ground views, prepared permits and model cursors never survive reload.
        return tag;
    }

    void load(CompoundTag tag) {
        if (closed || !receipts.isEmpty() || captured != null || tag.isEmpty()) return;
        if (tag.getInt("format") != 1) { conflict("unsupported saved construction facts"); return; }
        ListTag writes = tag.getList("receipts", Tag.TAG_COMPOUND);
        if (writes.size() > cells.size() || tag.getLongArray("safe").length > 128) {
            conflict("saved construction facts exceed the bounded project");
            return;
        }
        for (int index = 0; index < writes.size(); index++) {
            CompoundTag write = writes.getCompound(index);
            BlockPos at = BlockPos.of(write.getLong("cell"));
            BlockState state = NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), write.getCompound("state"));
            if (!cells.contains(at) || !ConstructionSnapshot.supportedMaterial(state) || receipts.putIfAbsent(at, state) != null) {
                conflict("saved construction receipts do not match this project");
                return;
            }
            receiptDirty.add(at);
        }
        retreatOwed = tag.hasUUID("retreatOwed") ? tag.getUUID("retreatOwed") : null;
        builder = retreatOwed;
        LinkedHashSet<BlockPos> roots = new LinkedHashSet<>();
        for (long at : tag.getLongArray("safe")) roots.add(BlockPos.of(at));
        retainedSafe = Set.copyOf(roots);
        if (tag.contains("material", Tag.TAG_COMPOUND)) {
            certificateMaterial = NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), tag.getCompound("material"));
            if (!ConstructionSnapshot.supportedMaterial(certificateMaterial)) {
                conflict("saved construction material is outside the supported static domain");
                return;
            }
        }
        if (tag.getBoolean("conflict")) {
            conflict("restored unresolved construction conflict: " + tag.getString("conflictReason"));
            return;
        }
        if (!receipts.isEmpty() && retreatOwed == null && !tag.getBoolean("retreatSatisfied")) {
            conflict("saved receipts have neither an original-builder obligation nor an actual-retreat receipt");
            return;
        }
        phase = "DISCOVERING";
        reason = "saved receipts and original-builder retreat facts restored; all permissions must be proved again";
    }

    private void invalidate(String detail) {
        closeProof();
        certificateGeneration++;
        reproofs++;
        prepared = null;
        destinationPrepared = null;
        finished = false;
        candidates = null;
        candidate = 0;
        selected = null;
        selectionDistance = Double.POSITIVE_INFINITY;
        rawAnchors = null;
        anchor = 0;
        anchorVoxel = -1;
        safe.clear();
        phase = "DISCOVERING";
        reason = detail + SUPPLY;
        // The old logical head can survive a reproof. No call can permit it without the new full witness.
    }

    private void waiting(String state, String detail) {
        closeProof();
        phase = state;
        reason = detail + SUPPLY;
        retryAt = level.getGameTime() + 20L;
        prepared = null;
        destinationPrepared = null;
    }

    private void conflict(String detail) {
        closeProof();
        conflicted = true;
        conflictReason = detail;
        phase = "CONFLICT";
        reason = detail;
        logicalHead = null;
        moves = List.of();
        destinations.clear();
        finished = false;
        prepared = null;
        destinationPrepared = null;
    }

    private void closeValidation() {
        if (validation != null) validation.close();
        validation = null;
    }

    private void closeProof() {
        clearApproach();
        if (capture != null) capture.close();
        capture = null;
        closeValidation();
        if (nativeWatch != null) nativeWatch.close();
        nativeWatch = null;
        resetNative();
        captured = null;
        search = null;
        verifier = null;
        witness = null;
        prefix = null;
        head = null;
        headAfter = null;
        safeHeadReady = false;
        finish = null;
        action = 0;
        returns.clear();
        beforeReturns.clear();
        destinationPaths.clear();
        destinationNative.clear();
        profiles.clear();
        profileCells = 0L;
        peoplePass = null;
        actorJoin = null;
        moves = List.of();
        destinations.clear();
    }

    private void resetNative() {
        nativeBefore = null;
        nativeAfter = null;
        nativeBody = null;
        nativeFeet = null;
        nativeStage = 0;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        closeProof();
        receiptListener.close();
        logicalHead = null;
        candidates = null;
        selected = null;
        phase = "CLOSED";
        reason = "all snapshot, receipt and native watchers were closed";
    }

    private static boolean actualSight(LivingEntity body, BlockPos target) {
        if (!chunksLoaded(body.level(), new AABB(body.getEyePosition(), Vec3.atCenterOf(target)))) return false;
        BlockHitResult hit = body.level().clip(new ClipContext(body.getEyePosition(), Vec3.atCenterOf(target),
            ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, body));
        return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(target);
    }

    private static boolean chunksLoaded(net.minecraft.world.level.Level level, AABB box) {
        int minX = (int)Math.floor(box.minX) >> 4;
        int maxX = (int)Math.floor(box.maxX) >> 4;
        int minZ = (int)Math.floor(box.minZ) >> 4;
        int maxZ = (int)Math.floor(box.maxZ) >> 4;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) if (!level.hasChunk(x, z)) return false;
        }
        return true;
    }

    private static Capability capability(LivingEntity body) {
        double width = body.getPose() == Pose.SLEEPING ? body.getDimensions(Pose.STANDING).width() : body.getBbWidth();
        double height = body.getPose() == Pose.SLEEPING ? body.getDimensions(Pose.STANDING).height() : body.getBbHeight();
        boolean walking = body instanceof Player || body instanceof Mob mob && Bodies.of(mob)
            .map(value -> value.kind().locomotion().id().equals(Locomotions.WALKING)).orElse(false);
        if (!walking || width > 1.0D || height > 2.0D || width <= 0.0D || height <= 0.0D) return null;
        return new Capability(width, height, 1, Math.min(3, Math.max(0, body.getMaxFallDistance())), Reach.PLAYER_BLOCK_REACH);
    }

    private static BlockPos feet(LivingEntity body) {
        return BlockPos.containing(body.getX(), body.getBoundingBox().minY + 0.05D, body.getZ());
    }

    private static Cell cell(BlockPos at) { return new Cell(at.getX(), at.getY(), at.getZ()); }
    private static BlockPos position(Cell at) { return new BlockPos(at.x(), at.y(), at.z()); }
    private static BoundingBox bounds(Box box) {
        return new BoundingBox(box.min().x(), box.min().y(), box.min().z(), box.max().x(), box.max().y(), box.max().z());
    }
    private static Construction.Budget adapt(Ground.Budget budget) {
        return new Construction.Budget() {
            public boolean tryStep() { return budget.tryStep(); }
            public boolean exhausted() { return budget.exhausted(); }
            public int used() { return budget.used(); }
        };
    }

    /** At most two positive directed edges, charged and resumable separately; never a negative shortcut. */
    private static final class ActorJoin {
        final Problem problem;
        final State after;
        final Cell from;
        final Cell to;
        final List<Cell> middle;
        int stage;
        int candidate;
        boolean firstLeg;
        boolean complete;
        List<Cell> path;

        ActorJoin(Problem problem, State after, Cell from, Cell to) {
            this.problem = problem;
            this.after = after;
            this.from = from;
            this.to = to;
            if (from.equals(to)) {
                complete = true;
                path = List.of(from);
                middle = List.of();
            } else if (Math.abs((long)from.x() - to.x()) > 1L || Math.abs((long)from.z() - to.z()) > 1L
                    || Math.abs((long)from.y() - to.y()) > 1L) {
                complete = true;
                middle = List.of();
            } else {
                middle = List.of(new Cell(to.x(), from.y(), from.z()), new Cell(from.x(), from.y(), to.z()),
                    new Cell(to.x(), to.y(), from.z()), new Cell(from.x(), to.y(), to.z()));
            }
        }

        void advance(Ground.Budget budget) {
            while (!complete) {
                if (!budget.tryStep()) return;
                if (stage == 0) {
                    stage = 1;
                    if (ConstructionRules.legalMove(problem, after, from, to)) {
                        path = List.of(from, to);
                        complete = true;
                    }
                } else if (candidate == middle.size()) {
                    complete = true;
                } else if (!firstLeg) {
                    if (ConstructionRules.legalMove(problem, after, from, middle.get(candidate))) firstLeg = true;
                    else candidate++;
                } else {
                    if (ConstructionRules.legalMove(problem, after, middle.get(candidate), to)) {
                        path = List.of(from, middle.get(candidate), to);
                        complete = true;
                    } else {
                        candidate++;
                        firstLeg = false;
                    }
                }
            }
        }
    }

    /** Reverse graph edges prove a directed path TO a trusted safe cell, never the opposite direction. */
    private static final class ReturnPaths {
        private final Problem problem;
        private final State state;
        private final Iterator<Cell> roots;
        private final ArrayDeque<Cell> queue = new ArrayDeque<>();
        private final Map<Cell, Cell> root = new HashMap<>();
        private Cell at;
        private int side;
        private int dy;
        private boolean seeded;
        private boolean complete;

        ReturnPaths(Problem problem, State state) {
            this(problem, state, problem.safeCells());
        }

        ReturnPaths(Problem problem, State state, Set<Cell> safeCells) {
            this.problem = problem;
            this.state = state;
            roots = safeCells.iterator();
            dy = -problem.capability().drop();
        }

        boolean advance(Cell target, Ground.Budget budget) {
            if (root.containsKey(target)) return true;
            while (!seeded) {
                if (!budget.tryStep()) return false;
                if (roots.hasNext()) {
                    Cell safe = roots.next();
                    if (ConstructionRules.standable(problem, state, safe)) {
                        root.put(safe, safe);
                        queue.addLast(safe);
                    }
                } else seeded = true;
            }
            if (root.containsKey(target)) return true;
            while (!complete && !budget.exhausted()) {
                if (at == null) {
                    if (!budget.tryStep()) return false;
                    if (queue.isEmpty()) { complete = true; break; }
                    at = queue.removeFirst();
                    side = 0;
                    dy = -problem.capability().drop();
                }
                if (!budget.tryStep()) return false;
                int[] direction = SIDES[side];
                Cell before = at.offset(direction[0], -dy, direction[1]);
                if (!root.containsKey(before) && problem.bounds().contains(before)
                        && ConstructionRules.legalMove(problem, state, before, at)) {
                    root.put(before, root.get(at));
                    queue.addLast(before);
                    if (before.equals(target)) return true;
                }
                dy++;
                if (dy > problem.capability().stepUp()) {
                    dy = -problem.capability().drop();
                    side++;
                    if (side == SIDES.length) at = null;
                }
            }
            return root.containsKey(target);
        }

        boolean contains(Cell at) { return root.containsKey(at); }
        boolean complete() { return complete; }
        Cell root(Cell at) { return root.get(at); }
    }
}
