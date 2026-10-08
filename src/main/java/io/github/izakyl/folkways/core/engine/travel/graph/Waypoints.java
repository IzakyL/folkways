package io.github.izakyl.folkways.core.engine.travel.graph;

import io.github.izakyl.folkways.core.api.terms.Gait;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2IntLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongToIntFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

final class Waypoints {

    static final int SPACING = 12;

    static final int SNAP = 2;

    private static final int FEWEST_POINTS = 512;

    private static final int MOST_POINTS_EVER = 65_536;

    private static final int ATTACHED_PER_POINT = 4;

    private int mostPoints = FEWEST_POINTS;

    private int mostAttached = FEWEST_POINTS * ATTACHED_PER_POINT;

    private static final long PROBATION_TICKS = 600L;

    private static final int MOST_STRIKES = 5;

    private static final int LEVEL = 1;

    private static final int MOST_STRAIGHTENED = 64;

    private static final int STRAIGHT_ENOUGH = 5;

    private static final int MOST_MERGED = SPACING * 3;

    static final class Link {

        final int to;
        int ticks;
        int strikes;
        long liveAt;

        Link(int to, int ticks) {
            this.to = to;
            this.ticks = ticks;
        }

        boolean live(long now) {
            return now >= liveAt;
        }
    }

    private final Long2IntOpenHashMap byCell = new Long2IntOpenHashMap();
    private final LongArrayList cells = new LongArrayList();
    private final List<List<Link>> out = new ArrayList<>();
    private final LongArrayList used = new LongArrayList();

    private final BitSet pinned = new BitSet();

    private final Long2IntLinkedOpenHashMap attached = new Long2IntLinkedOpenHashMap();

    private final Int2ObjectOpenHashMap<LongLinkedOpenHashSet> attachedTo = new Int2ObjectOpenHashMap<>();

    private final Long2LongOpenHashMap unsnappable = new Long2LongOpenHashMap();

    private final IntArrayList pieceUp = new IntArrayList();
    private final IntArrayList pieceSize = new IntArrayList();
    private final BitSet pieceSealed = new BitSet();
    private final List<Bounds> pieceGround = new ArrayList<>();

    private List<BoundingBox> shut = List.of();

    private boolean changed = true;
    private int era;

    private long wakesAt = Long.MAX_VALUE;

    private boolean pointsMoved = true;
    private boolean attachedMoved = true;
    private Long2IntMap frozenByCell;
    private Long2IntMap frozenAttached;
    private long[] frozenCells;

    Waypoints() {
        byCell.defaultReturnValue(-1);
        attached.defaultReturnValue(-1);
        unsnappable.defaultReturnValue(Long.MIN_VALUE);
    }

    void room(long area, int places) {
        long skeleton = Math.max(1L, area / ((long) SPACING * SPACING)) * 4L;
        mostPoints = (int) Math.clamp(skeleton + 2L * places, FEWEST_POINTS, MOST_POINTS_EVER);
        mostAttached = mostPoints * ATTACHED_PER_POINT;
    }

    IntArrayList admitSite(LongList cells, long now) {
        IntArrayList reps = new IntArrayList();
        LongOpenHashSet left = new LongOpenHashSet(cells);
        while (!left.isEmpty()) {
            long seed = left.iterator().nextLong();
            LongArrayList cluster = new LongArrayList();
            LongArrayList ahead = new LongArrayList();
            left.remove(seed);
            ahead.add(seed);
            for (int at = 0; at < ahead.size(); at++) {
                long here = ahead.getLong(at);
                cluster.add(here);
                for (long beside : neighbours(here)) {
                    if (left.remove(beside)) {
                        ahead.add(beside);
                    }
                }
            }
            long rep = cluster.getLong(0);
            for (int at = 1; at < cluster.size(); at++) {
                rep = Math.min(rep, cluster.getLong(at));
            }
            int id = admit(rep, now);
            reps.add(id);
            for (int at = 0; at < cluster.size(); at++) {
                if (cluster.getLong(at) != rep) {
                    remember(cluster.getLong(at), id, now);
                }
            }
        }
        return reps;
    }

    private static long[] neighbours(long cell) {
        BlockPos at = BlockPos.of(cell);
        long[] found = new long[4 * (2 * LEVEL + 1)];
        int held = 0;
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            BlockPos beside = at.relative(facing);
            for (int rise = -LEVEL; rise <= LEVEL; rise++) {
                found[held++] = beside.above(rise).asLong();
            }
        }
        return java.util.Arrays.copyOf(found, held);
    }

    int era() {
        return era;
    }

    boolean changed(long now) {
        return changed || now >= wakesAt;
    }

    int size() {
        return byCell.size();
    }

    long cellOf(int id) {
        return cells.getLong(id);
    }

    List<Link> linksFrom(int id) {
        return out.get(id);
    }

    int at(long cell) {
        return byCell.get(cell);
    }

    int reaching(long cell) {
        int here = byCell.get(cell);
        return here >= 0 ? here : attached.getAndMoveToFirst(cell);
    }

    boolean knows(long cell) {
        return byCell.get(cell) >= 0 || attached.containsKey(cell);
    }

    int near(long cell, long now) {
        int here = reaching(cell);
        if (here >= 0 || unsnappable.get(cell) > now) {
            return here;
        }
        return nearest(cell, probe -> {
            int id = byCell.get(probe);
            return id >= 0 ? id : attached.get(probe);
        });
    }

    static int nearest(long cell, LongToIntFunction lookup) {
        int x = BlockPos.getX(cell);
        int y = BlockPos.getY(cell);
        int z = BlockPos.getZ(cell);
        int best = -1;
        int closest = Integer.MAX_VALUE;
        for (int dy = -LEVEL; dy <= LEVEL; dy++) {
            for (int dx = -SNAP; dx <= SNAP; dx++) {
                for (int dz = -SNAP; dz <= SNAP; dz++) {
                    int apart = dx * dx + dy * dy + dz * dz;
                    if (apart == 0 || apart >= closest) {
                        continue;
                    }
                    int id = lookup.applyAsInt(BlockPos.asLong(x + dx, y + dy, z + dz));
                    if (id >= 0) {
                        best = id;
                        closest = apart;
                    }
                }
            }
        }
        return best;
    }

    boolean wrongEdge(long from, long to, long now) {
        int leaving = byCell.get(from);
        int arriving = byCell.get(to);
        changed = true;
        if (leaving >= 0 && arriving >= 0) {
            Link link = linkFrom(leaving, arriving);
            if (link == null) {
                return false;
            }
            strike(link, now);
            if (link.strikes > MOST_STRIKES) {
                out.get(leaving).remove(link);
            }
            unseal(leaving);
            return true;
        }
        if (leaving < 0) {
            refuse(from, now);
        }
        if (arriving < 0) {
            refuse(to, now);
        }
        return true;
    }

    void walked(long from, long to, int ticks, long now) {
        int leaving = byCell.get(from);
        int arriving = byCell.get(to);
        if (leaving < 0 || arriving < 0) {
            return;
        }
        Link link = linkFrom(leaving, arriving);
        if (link == null) {
            return;
        }
        used.set(leaving, now);
        used.set(arriving, now);
        int learned = Math.max(1, (link.ticks * 3 + Math.max(1, ticks) + 2) / 4);
        if (learned != link.ticks || link.strikes != 0) {
            link.ticks = learned;
            link.strikes = 0;
            changed = true;
        }
    }

    private void refuse(long cell, long now) {
        detach(cell);
        unsnappable.put(cell, now + PROBATION_TICKS);
    }

    int pin(long cell, long now) {
        int id = admit(cell, now);
        pinned.set(id);
        return id;
    }

    IntArrayList pinSite(LongList cells, long now) {
        IntArrayList reps = admitSite(cells, now);
        for (int at = 0; at < reps.size(); at++) {
            pinned.set(reps.getInt(at));
        }
        return reps;
    }

    int admit(long cell, long now) {
        int here = byCell.get(cell);
        if (here >= 0) {
            used.set(here, now);
            return here;
        }
        unsnappable.remove(cell);
        int id = cells.size();
        cells.add(cell);
        out.add(new ArrayList<>());
        used.add(now);
        byCell.put(cell, id);
        pointsMoved = true;
        int previous = detach(cell);

        pieceUp.add(id);
        pieceSize.add(1);
        pieceGround.add(null);
        changed = true;
        if (previous >= 0) {
            connect(id, previous, now);
        }
        return id;
    }

    private void connect(int from, int to, long now) {
        int ticks = Math.max(1, Net.ticks(BlockPos.of(cellOf(from)), BlockPos.of(cellOf(to))));
        linkAtMost(from, to, ticks, now);
        linkAtMost(to, from, ticks, now);
    }

    void link(int from, int to, int ticks, long now) {
        if (from == to) {
            return;
        }
        used.set(from, now);
        used.set(to, now);
        for (Link link : out.get(from)) {
            if (link.to != to) {
                continue;
            }
            if (link.ticks != ticks) {
                link.ticks = ticks;
                changed = true;
            }
            if (!link.live(now)) {
                link.liveAt = now;
                changed = true;
            }
            return;
        }
        out.get(from).add(new Link(to, ticks));

        joinPieces(from, to);
        changed = true;
    }

    void linkAtMost(int from, int to, int ticks, long now) {
        for (Link link : out.get(from)) {
            if (link.to != to) {
                continue;
            }
            if (link.ticks > ticks) {
                break;
            }
            used.set(from, now);
            used.set(to, now);
            if (!link.live(now)) {
                link.liveAt = now;
                changed = true;
            }
            return;
        }
        link(from, to, ticks, now);
    }

    void seal(int waypoint, Bounds ground) {
        if (ground == null) {
            return;
        }
        int piece = rootPiece(waypoint);
        Bounds had = pieceGround.get(piece);
        pieceGround.set(piece, had == null ? ground : had.with(ground));
        pieceSealed.set(piece);
        changed = true;
    }

    private int rootPiece(int piece) {
        return root(pieceUp.elements(), piece);
    }

    private void joinPieces(int left, int right) {
        int one = rootPiece(left);
        int other = rootPiece(right);
        if (one == other) {
            return;
        }

        if (pieceSize.getInt(one) > pieceSize.getInt(other)) {
            int swap = one;
            one = other;
            other = swap;
        }
        pieceUp.set(one, other);
        pieceSize.set(other, pieceSize.getInt(one) + pieceSize.getInt(other));
        unseal(other);
        pieceSealed.clear(one);
        pieceGround.set(one, null);
    }

    private void unseal(int piece) {
        int root = rootPiece(piece);
        if (pieceSealed.get(root) || pieceGround.get(root) != null) {
            pieceSealed.clear(root);
            pieceGround.set(root, null);
            changed = true;
        }
    }

    void reconsider(Ask ask) {
        unsealAt(ask.from());
        for (int at = 0; at < ask.goals().size(); at++) {
            unsealAt(ask.goals().getLong(at));
        }
    }

    private void unsealAt(long cell) {
        int waypoint = reaching(cell);
        if (waypoint >= 0) {
            unseal(waypoint);
        }
    }

    private void unsealEverything() {
        if (pieceSealed.isEmpty()) {
            return;
        }
        pieceSealed.clear();
        for (int piece = 0; piece < pieceGround.size(); piece++) {
            pieceGround.set(piece, null);
        }
        changed = true;
    }

    void remember(long cell, int waypoint, long now) {
        if (Math.abs(BlockPos.getY(cell) - BlockPos.getY(cells.getLong(waypoint))) > LEVEL) {
            return;
        }
        unsnappable.remove(cell);
        int previous = reaching(cell);
        if (previous >= 0 && previous != waypoint) {
            int junction = admit(cell, now);
            connect(junction, waypoint, now);
            return;
        }
        if (byCell.get(cell) >= 0) {
            return;
        }
        attach(cell, waypoint);
        changed = true;
    }

    private void attach(long cell, int waypoint) {
        int had = attached.putAndMoveToFirst(cell, waypoint);
        if (had >= 0 && had != waypoint) {
            unhold(had, cell);
        }
        attachedTo.computeIfAbsent(waypoint, key -> new LongLinkedOpenHashSet()).add(cell);
        while (attached.size() > mostAttached) {
            long last = attached.lastLongKey();
            unhold(attached.removeLastInt(), last);
        }
        attachedMoved = true;
    }

    private int detach(long cell) {
        int had = attached.remove(cell);
        if (had >= 0) {
            unhold(had, cell);
            attachedMoved = true;
        }
        return had;
    }

    private void detachAll(int waypoint) {
        LongLinkedOpenHashSet held = attachedTo.remove(waypoint);
        if (held == null) {
            return;
        }
        for (long cell : held) {
            attached.remove(cell);
        }
        attachedMoved = true;
    }

    private void unhold(int waypoint, long cell) {
        LongLinkedOpenHashSet held = attachedTo.get(waypoint);
        if (held != null && held.remove(cell) && held.isEmpty()) {
            attachedTo.remove(waypoint);
        }
    }

    void wrongAt(long cell, long now) {
        int here = byCell.get(cell);
        detach(cell);

        changed = true;
        if (here < 0) {
            unsealEverything();
            return;
        }

        unseal(here);

        boolean leadsIn = false;
        for (List<Link> links : out) {
            for (Iterator<Link> walking = links.iterator(); walking.hasNext(); ) {
                Link link = walking.next();
                if (link.to != here) {
                    continue;
                }
                strike(link, now);
                if (link.strikes > MOST_STRIKES) {
                    walking.remove();
                } else {
                    leadsIn = true;
                }
            }
        }
        for (Iterator<Link> walking = out.get(here).iterator(); walking.hasNext(); ) {
            Link link = walking.next();
            strike(link, now);
            if (link.strikes > MOST_STRIKES) {
                walking.remove();
            }
        }

        if (out.get(here).isEmpty() && !leadsIn) {
            drop(here);
        }
    }

    private void strike(Link link, long now) {
        link.strikes++;
        link.liveAt = now + (PROBATION_TICKS << Math.min(link.strikes, MOST_STRIKES));
    }

    void drop(int id) {
        for (List<Link> links : out) {
            links.removeIf(link -> link.to == id);
        }
        forget(id);
    }

    private void dropBetween(int id, int left, int right) {
        out.get(left).removeIf(link -> link.to == id);
        out.get(right).removeIf(link -> link.to == id);
        forget(id);
    }

    private void forget(int id) {
        byCell.remove(cells.getLong(id));
        pointsMoved = true;
        out.get(id).clear();
        detachAll(id);
        pinned.clear(id);
        used.set(id, Long.MIN_VALUE);
        unseal(id);
        changed = true;
    }

    boolean joins(int from, int to, long now) {
        return joinsAny(from, IntArrayList.of(to), now);
    }

    boolean joinsAny(int from, IntArrayList goals, long now) {
        if (from < 0) {
            return false;
        }
        IntOpenHashSet ends = new IntOpenHashSet();
        int piece = rootPiece(from);
        for (int at = 0; at < goals.size(); at++) {
            int to = goals.getInt(at);
            if (to >= 0 && rootPiece(to) == piece) {
                ends.add(to);
            }
        }
        if (ends.isEmpty()) {
            return false;
        }
        IntOpenHashSet seen = new IntOpenHashSet();
        IntArrayList ahead = new IntArrayList();
        seen.add(from);
        ahead.add(from);
        for (int at = 0; at < ahead.size(); at++) {
            int here = ahead.getInt(at);
            if (ends.contains(here)) {
                return true;
            }
            for (Link link : out.get(here)) {
                if (link.live(now) && seen.add(link.to)) {
                    ahead.add(link.to);
                }
            }
        }
        return false;
    }

    int links() {
        int count = 0;
        for (List<Link> from : out) {
            count += from.size();
        }
        return count;
    }

    /**
     * Closes the ways over {@code box}: every waypoint in it or near enough to snap into it goes, with every link
     * to it and every link whose straight line runs through it, and no cell in it is held to a waypoint any more.
     */
    void closeOff(BoundingBox box) {
        List<BoundingBox> now = new ArrayList<>(shut);
        now.add(box);
        shut = List.copyOf(now);
        BoundingBox snapped = box.inflatedBy(SNAP);
        BitSet gone = new BitSet();
        for (int id = 0; id < cells.size(); id++) {
            if (alive(id) && snapped.isInside(BlockPos.of(cells.getLong(id)))) {
                gone.set(id);
            }
        }
        for (int id = 0; id < cells.size(); id++) {
            if (!alive(id) || gone.get(id)) {
                continue;
            }
            BlockPos from = BlockPos.of(cells.getLong(id));
            out.get(id).removeIf(link -> gone.get(link.to)
                || crosses(box, from, BlockPos.of(cells.getLong(link.to))));
        }
        for (int id = gone.nextSetBit(0); id >= 0; id = gone.nextSetBit(id + 1)) {
            forget(id);
        }
        LongArrayList inside = new LongArrayList();
        for (long cell : attached.keySet()) {
            if (snapped.isInside(BlockPos.of(cell))) {
                inside.add(cell);
            }
        }
        for (int at = 0; at < inside.size(); at++) {
            detach(inside.getLong(at));
        }
        unsnappable.keySet().removeIf(cell -> snapped.isInside(BlockPos.of(cell)));
        rebuildPieces();
        era++;
        changed = true;
    }

    /** Opens the ways over {@code box} again. Whatever was found closed in while it was shut may not be any more. */
    void reopen(BoundingBox box) {
        List<BoundingBox> now = new ArrayList<>(shut);
        now.remove(box);
        shut = List.copyOf(now);
        unsealEverything();
        changed = true;
    }

    private boolean crossesShut(BlockPos from, BlockPos to) {
        for (BoundingBox box : shut) {
            if (crosses(box, from, to)) {
                return true;
            }
        }
        return false;
    }

    // Whether the straight line between the two cells' middles passes through the box.
    static boolean crosses(BoundingBox box, BlockPos from, BlockPos to) {
        double[] start = {from.getX() + 0.5D, from.getY() + 0.5D, from.getZ() + 0.5D};
        double[] end = {to.getX() + 0.5D, to.getY() + 0.5D, to.getZ() + 0.5D};
        double[] low = {box.minX(), box.minY(), box.minZ()};
        double[] high = {box.maxX() + 1.0D, box.maxY() + 1.0D, box.maxZ() + 1.0D};
        double enter = 0.0D;
        double leave = 1.0D;
        for (int axis = 0; axis < 3; axis++) {
            double along = end[axis] - start[axis];
            if (Math.abs(along) < 1.0E-9D) {
                if (start[axis] < low[axis] || start[axis] > high[axis]) {
                    return false;
                }
                continue;
            }
            double one = (low[axis] - start[axis]) / along;
            double other = (high[axis] - start[axis]) / along;
            enter = Math.max(enter, Math.min(one, other));
            leave = Math.min(leave, Math.max(one, other));
            if (enter > leave) {
                return false;
            }
        }
        return true;
    }

    int straighten(long now) {
        if (cells.size() < 3) {
            return 0;
        }
        Incoming in = reverse();
        IntOpenHashSet touched = new IntOpenHashSet();
        int gone = 0;
        for (int id = 0; id < cells.size() && gone < MOST_STRAIGHTENED; id++) {
            if (straighten(id, in, touched, now)) {
                gone++;
            }
        }
        return gone;
    }

    private boolean straighten(int id, Incoming in, IntOpenHashSet touched, long now) {
        if (pinned.get(id) || !alive(id) || touched.contains(id)) {
            return false;
        }
        int left = -1;
        int right = -1;
        for (Link link : out.get(id)) {
            if (!link.live(now)) {

                return false;
            }
            if (link.to == left || link.to == right) {
                return false;
            }
            if (left < 0) {
                left = link.to;
            } else if (right < 0) {
                right = link.to;
            } else {
                return false;
            }
        }
        if (right < 0) {
            return false;
        }
        if (!in.onlyFrom(id, left, right)) {
            return false;
        }
        Link intoLeft = linkFrom(id, left);
        Link intoRight = linkFrom(id, right);
        Link fromLeft = linkFrom(left, id);
        Link fromRight = linkFrom(right, id);
        if (fromLeft == null || fromRight == null
            || !fromLeft.live(now) || !fromRight.live(now)) {

            return false;
        }
        BlockPos here = BlockPos.of(cells.getLong(id));
        BlockPos one = BlockPos.of(cells.getLong(left));
        BlockPos other = BlockPos.of(cells.getLong(right));
        if (Math.sqrt(one.distSqr(other)) > MOST_MERGED || crossesShut(one, other)) {
            return false;
        }
        if (Math.abs(here.getY() - one.getY()) > LEVEL
            || Math.abs(here.getY() - other.getY()) > LEVEL) {

            return false;
        }
        int onwards = fromLeft.ticks + intoRight.ticks;
        int backwards = fromRight.ticks + intoLeft.ticks;
        int straight = Net.ticks(one, other);
        if (Math.max(onwards, backwards) > Math.max(2, straight * STRAIGHT_ENOUGH / 4)) {
            return false;
        }
        Bounds walled = sealOf(left);
        LongLinkedOpenHashSet held = attachedTo.get(id);
        LongArrayList orphans = held == null ? new LongArrayList() : new LongArrayList(held);
        orphans.add(here.asLong());
        linkAtMost(left, right, onwards, now);
        linkAtMost(right, left, backwards, now);
        dropBetween(id, left, right);
        for (int at = 0; at < orphans.size(); at++) {
            long orphan = orphans.getLong(at);
            remember(orphan, nearer(orphan, left, right), now);
        }
        reseal(left, walled);
        touched.add(id);
        touched.add(left);
        touched.add(right);
        return true;
    }

    private Bounds sealOf(int waypoint) {
        int piece = rootPiece(waypoint);
        return pieceSealed.get(piece) ? pieceGround.get(piece) : null;
    }

    private void reseal(int waypoint, Bounds ground) {
        if (ground == null) {
            return;
        }
        int piece = rootPiece(waypoint);
        pieceGround.set(piece, ground);
        pieceSealed.set(piece);
    }

    private boolean alive(int id) {
        return used.getLong(id) != Long.MIN_VALUE;
    }

    private Link linkFrom(int from, int to) {
        for (Link link : out.get(from)) {
            if (link.to == to) {
                return link;
            }
        }
        return null;
    }

    private int nearer(long cell, int one, int other) {
        BlockPos at = BlockPos.of(cell);
        return at.distSqr(BlockPos.of(cells.getLong(one)))
            <= at.distSqr(BlockPos.of(cells.getLong(other))) ? one : other;
    }

    private record Incoming(int[] head, int[] from) {

        boolean onlyFrom(int id, int left, int right) {
            for (int at = head[id]; at < head[id + 1]; at++) {
                if (from[at] != left && from[at] != right) {
                    return false;
                }
            }
            return true;
        }
    }

    private Incoming reverse() {
        int count = cells.size();
        int[] head = new int[count + 1];
        for (int id = 0; id < count; id++) {
            for (Link link : out.get(id)) {
                head[link.to + 1]++;
            }
        }
        for (int id = 0; id < count; id++) {
            head[id + 1] += head[id];
        }
        int[] from = new int[head[count]];
        int[] filled = head.clone();
        for (int id = 0; id < count; id++) {
            for (Link link : out.get(id)) {
                from[filled[link.to]++] = id;
            }
        }
        return new Incoming(head, from);
    }

    boolean trim(long now) {
        if (cells.size() <= mostPoints) {
            return false;
        }
        IntArrayList order = new IntArrayList();
        for (int id = 0; id < cells.size(); id++) {
            if (alive(id)) {
                order.add(id);
            }
        }
        order.sort((left, right) -> Long.compare(used.getLong(right), used.getLong(left)));
        int keep = Math.min(order.size(), mostPoints * 3 / 4);
        int[] renumbered = new int[cells.size()];
        java.util.Arrays.fill(renumbered, -1);
        for (int rank = 0; rank < keep; rank++) {
            renumbered[order.getInt(rank)] = rank;
        }
        BitSet keptPins = new BitSet();
        for (int rank = 0; rank < keep; rank++) {
            if (pinned.get(order.getInt(rank))) {
                keptPins.set(rank);
            }
        }
        pinned.clear();
        pinned.or(keptPins);
        LongArrayList keptCells = new LongArrayList();
        List<List<Link>> keptOut = new ArrayList<>();
        LongArrayList keptUsed = new LongArrayList();
        for (int rank = 0; rank < keep; rank++) {
            int old = order.getInt(rank);
            keptCells.add(cells.getLong(old));
            keptUsed.add(used.getLong(old));
            List<Link> links = new ArrayList<>();
            for (Link link : out.get(old)) {
                if (renumbered[link.to] >= 0) {
                    Link moved = new Link(renumbered[link.to], link.ticks);
                    moved.strikes = link.strikes;
                    moved.liveAt = link.liveAt;
                    links.add(moved);
                }
            }
            keptOut.add(links);
        }
        cells.clear();
        cells.addAll(keptCells);
        out.clear();
        out.addAll(keptOut);
        used.clear();
        used.addAll(keptUsed);
        byCell.clear();
        for (int id = 0; id < cells.size(); id++) {
            byCell.put(cells.getLong(id), id);
        }
        Long2IntLinkedOpenHashMap moved = new Long2IntLinkedOpenHashMap();
        moved.defaultReturnValue(-1);
        for (Long2IntMap.Entry entry : attached.long2IntEntrySet()) {
            if (renumbered[entry.getIntValue()] >= 0) {
                moved.put(entry.getLongKey(), renumbered[entry.getIntValue()]);
            }
        }
        attached.clear();
        attached.putAll(moved);
        attachedTo.clear();
        for (Long2IntMap.Entry entry : attached.long2IntEntrySet()) {
            attachedTo.computeIfAbsent(entry.getIntValue(), key -> new LongLinkedOpenHashSet())
                .add(entry.getLongKey());
        }
        pointsMoved = true;
        attachedMoved = true;

        rebuildPieces();
        era++;
        changed = true;
        return true;
    }

    private void rebuildPieces() {
        pieceUp.clear();
        pieceSize.clear();
        pieceGround.clear();
        pieceSealed.clear();
        for (int id = 0; id < cells.size(); id++) {
            pieceUp.add(id);
            pieceSize.add(1);
            pieceGround.add(null);
        }
        for (int id = 0; id < cells.size(); id++) {
            for (Link link : out.get(id)) {
                joinPieces(id, link.to);
            }
        }
    }

    Net freeze(long now) {
        changed = false;
        int count = cells.size();
        int[] head = new int[count + 1];
        int live = 0;
        wakesAt = Long.MAX_VALUE;
        for (int id = 0; id < count; id++) {
            head[id] = live;
            for (Link link : out.get(id)) {
                if (link.live(now)) {
                    live++;
                } else {
                    wakesAt = Math.min(wakesAt, link.liveAt);
                }
            }
        }
        head[count] = live;
        int[] to = new int[live];
        int[] cost = new int[live];
        int at = 0;
        for (int id = 0; id < count; id++) {
            for (Link link : out.get(id)) {
                if (link.live(now)) {
                    to[at] = link.to;
                    cost[at] = link.ticks;
                    at++;
                }
            }
        }
        if (frozenByCell == null || pointsMoved) {
            frozenByCell = new Long2IntOpenHashMap(byCell);
            frozenCells = cells.toLongArray();
            pointsMoved = false;
        }
        if (frozenAttached == null || attachedMoved) {
            frozenAttached = new Long2IntOpenHashMap(attached);
            attachedMoved = false;
        }
        int[] mutual = StrongComponents.of(frozenCells, head, to);
        LongOpenHashSet refused = new LongOpenHashSet();
        unsnappable.long2LongEntrySet().removeIf(entry -> entry.getLongValue() <= now);
        for (Long2LongMap.Entry entry : unsnappable.long2LongEntrySet()) {
            refused.add(entry.getLongKey());
            wakesAt = Math.min(wakesAt, entry.getLongValue());
        }
        return new Net(frozenByCell, frozenAttached, frozenCells, head, to, cost,
            parts(count, head, to), pieces(mutual), mutual, refused);
    }

    private Pieces pieces(int[] mutual) {
        int count = mutual.length;
        Int2IntOpenHashMap dense = new Int2IntOpenHashMap();
        dense.defaultReturnValue(-1);
        int[] of = new int[count];
        List<Bounds> ground = new ArrayList<>();
        LongArrayList where = new LongArrayList();
        for (int id = 0; id < count; id++) {
            int root = mutual[id];
            int at = dense.get(root);
            if (at < 0) {
                at = ground.size();
                dense.put(root, at);
                int history = rootPiece(id);
                ground.add(pieceSealed.get(history) ? pieceGround.get(history) : null);
                where.add(cells.getLong(root));
            }
            of[id] = at;
        }

        return new Pieces(of, ground.toArray(new Bounds[0]), where.toLongArray());
    }

    record Pieces(int[] of, Bounds[] ground, long[] cell) {

        boolean shutOutOf(int waypoint, BlockPos cell) {
            if (waypoint < 0) {
                return false;
            }
            Bounds walked = ground[of[waypoint]];
            return walked != null && !walked.holds(cell);
        }

        BlockPos cellOf(int waypoint) {
            return BlockPos.of(cell[of[waypoint]]);
        }
    }

    private static int[] parts(int count, int[] head, int[] to) {
        int[] part = new int[count];
        for (int id = 0; id < count; id++) {
            part[id] = id;
        }
        for (int id = 0; id < count; id++) {
            for (int edge = head[id]; edge < head[id + 1]; edge++) {
                join(part, id, to[edge]);
            }
        }
        for (int id = 0; id < count; id++) {
            part[id] = root(part, id);
        }
        return part;
    }

    static int root(int[] part, int id) {
        while (part[id] != id) {
            part[id] = part[part[id]];
            id = part[id];
        }
        return id;
    }

    static void join(int[] part, int left, int right) {
        int one = root(part, left);
        int other = root(part, right);
        if (one != other) {
            part[one] = other;
        }
    }

    record Net(Long2IntMap byCell, Long2IntMap attached, long[] cells, int[] head, int[] to,
               int[] cost, int[] part, Pieces pieces, int[] mutual, LongSet unsnappable) {

        int near(long cell) {
            int here = reaching(cell);
            if (here >= 0 || unsnappable.contains(cell)) {
                return here;
            }
            return nearest(cell, probe -> {
                int id = byCell.getOrDefault(probe, -1);
                return id >= 0 ? id : attached.getOrDefault(probe, -1);
            });
        }

        boolean joined(int from, int to) {
            return from >= 0 && to >= 0 && part[from] == part[to];
        }

        boolean shutBetween(BlockPos from, BlockPos to) {
            return pieces.shutOutOf(near(from.asLong()), to) || pieces.shutOutOf(near(to.asLong()), from);
        }

        int reaching(BlockPos cell) {
            return reaching(cell.asLong());
        }

        int reaching(long cell) {
            int here = byCell.getOrDefault(cell, -1);
            return here >= 0 ? here : attached.getOrDefault(cell, -1);
        }

        boolean answers(Ask ask) {
            int from = near(ask.from());
            if (from < 0) {
                return false;
            }
            for (long goal : ask.goals()) {
                int to = near(goal);
                if (to >= 0 && mutual[from] == mutual[to]) {
                    return true;
                }
            }
            return false;
        }

        String spellPieces(int most) {
            Map<Integer, Integer> sizes = new LinkedHashMap<>();
            for (int id : byCell.values()) {
                sizes.merge(part[id], 1, Integer::sum);
            }
            List<Map.Entry<Integer, Integer>> order = new ArrayList<>(sizes.entrySet());
            order.sort((left, right) -> Integer.compare(right.getValue(), left.getValue()));
            StringBuilder said = new StringBuilder("[");
            for (int at = 0; at < Math.min(most, order.size()); at++) {
                if (at > 0) {
                    said.append(" | ");
                }
                BlockPos where = BlockPos.of(cells[order.get(at).getKey()]);
                said.append(order.get(at).getValue()).append('@')
                    .append(where.getX()).append(',').append(where.getY()).append(',')
                    .append(where.getZ());
            }
            return said.append(order.size() > most ? " | …]" : "]").toString();
        }

        int apart() {
            int roots = 0;
            for (int id : byCell.values()) {
                roots += part[id] == id ? 1 : 0;
            }
            return roots;
        }

        BlockPos pieceCell(int waypoint) {
            return pieces.cellOf(waypoint);
        }

        int approach(BlockPos cell, int waypoint) {
            return waypoint < 0 ? 0 : ticks(BlockPos.of(cells[waypoint]), cell);
        }

        static int ticks(BlockPos from, BlockPos to) {
            return (int) Math.round(Math.sqrt(from.distSqr(to)) / Gait.BLOCKS_PER_TICK);
        }
    }
}
