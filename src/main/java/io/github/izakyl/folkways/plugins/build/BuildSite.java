package io.github.izakyl.folkways.plugins.build;

import io.github.izakyl.folkways.core.api.ground.BlockChanges;
import io.github.izakyl.folkways.core.api.terms.Closures;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.Realm;
import io.github.izakyl.folkways.core.api.terms.RefusalKind;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Before;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Need;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.plugins.build.draft.Joint;
import java.util.ArrayList;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * One order's work, planned once and kept. The whole of what is left is laid out by {@link Layering} as pieces in the
 * order they go in, and asked of the colony only so far ahead: each piece once fewer than {@link #DEPTH} still stand
 * before it. From then on it is heard: a piece done, or a block set in the site by anyone, meets its cells, a cell met
 * is met for good, and what that frees is asked for. The order is built when every cell has been met and every joint
 * made. While it stands the ways are closed over the site, so it is come to by its edge.
 *
 * <p>A piece that ends undone is asked again as it was, the pieces after it waiting on it meanwhile, unless its cells
 * were met some other way. Nothing is looked at again on a clock.
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

        Piece(Layering.Work work, Node node) {
            this.work = work;
            this.node = node;
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

    static final int MOST_TRIES = 5;

    // How deep the asked work runs: a piece is asked for once fewer than this many pieces still stand before it, one
    // waiting on the next.
    static final int DEPTH = 3;

    // How far past the work the ways are closed: room to stand to work at its edge.
    private static final int CLOSED_MARGIN = 2;

    private static final int FAR = Integer.MAX_VALUE / 2;

    private static final int SPARE_SPAN = 2;

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
        reopen = Closures.close(level, BoundingBox.fromCorners(min, max).inflatedBy(CLOSED_MARGIN));
    }

    /**
     * Asks for every piece held back that fewer than {@link #DEPTH} pieces still stand before, one waiting on the
     * next: what is asked runs only so deep. A piece given up stands before what waits on it for good.
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
        Set<UUID> freshIds = new HashSet<>();
        going.forEach(id -> freshIds.add(nodeOf.get(id)));
        List<Node> fresh = new ArrayList<>();
        List<Before> links = new ArrayList<>();
        List<Before> follows = new ArrayList<>();
        for (int id : going) {
            UUID node = nodeOf.get(id);
            Piece piece = held.remove(node);
            live.put(node, piece);
            fresh.add(piece.node());
            for (int before : waitsOn.getOrDefault(id, Set.of())) {
                UUID first = nodeOf.get(before);
                if (freshIds.contains(first)) {
                    links.add(new Before(first, node));
                } else if (live.containsKey(first)) {
                    follows.add(new Before(first, node));
                }
            }
        }
        asks.submit(dimension, new Grown(fresh, links).following(follows), this::ended);
    }

    // How many pieces still stand before this one, one waiting on the next: none for a piece done, and past all
    // counting behind a piece given up.
    private int depth(int id, Map<Integer, Integer> known) {
        Integer had = known.get(id);
        if (had != null) {
            return had;
        }
        UUID node = nodeOf.get(id);
        int deep;
        if (givenUp.contains(id)) {
            deep = FAR;
        } else if (!held.containsKey(node) && !live.containsKey(node)) {
            deep = -1;
        } else {
            deep = 0;
            for (int before : waitsOn.getOrDefault(id, Set.of())) {
                int under = depth(before, known);
                if (under >= 0) {
                    deep = Math.max(deep, Math.min(FAR, under + 1));
                }
            }
        }
        known.put(id, deep);
        return deep;
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
            live.put(node.id(), new Piece(new Layering.Work(-1, Layering.Kind.SCAFFOLD, cells.getFirst(),
                Optional.of(lower), Optional.empty(), true, 0, 0L, List.of()), node));
            asks.submit(dimension, Grown.of(node), this::ended);
        }
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

    private void ended(UUID node, Ending how) {
        endings.add(new Heard(node, how));
    }

    /**
     * Takes in what was heard since the last tick: pieces ended, blocks set. Answers whether any cell was met, or
     * any piece came or went.
     */
    boolean tick(ServerLevel level) {
        if (closed) {
            return false;
        }
        boolean changed = false;
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
        }
        return changed;
    }

    private boolean end(ServerLevel level, Heard heard) {
        Piece piece = live.get(heard.node());
        if (piece == null) {
            return false;
        }
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
            live.remove(heard.node());
            return true;
        }
        if (++piece.tries < MOST_TRIES) {
            Grown again = Grown.of(piece.node());
            asks.submit(dimension, again, this::ended);
            return true;
        }
        live.remove(heard.node());
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
            asks.withdraw(id);
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
            live.put(node.id(), new Piece(new Layering.Work(-1, Layering.Kind.JOIN, joint.from(), Optional.of(step),
                Optional.of(joint), true, 0, 0L, List.of()), node));
            asks.submit(dimension, Grown.of(node), this::ended);
        }
    }

    /** Every cell met, every joint made, every piece ended: scaffolds left over included. */
    boolean complete() {
        return !closed && live.isEmpty() && held.isEmpty() && joints.isEmpty() && target.unloaded().isEmpty()
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
        live.keySet().forEach(asks::withdraw);
        forget();
    }

    /** Stops hearing, the colony closing: its work goes with it, so nothing is withdrawn. */
    void forget() {
        closed = true;
        live.clear();
        held.clear();
        reopen.run();
        reopen = () -> { };
        if (listening != null) {
            listening.close();
            listening = null;
        }
    }
}
