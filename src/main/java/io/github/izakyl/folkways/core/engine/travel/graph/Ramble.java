package io.github.izakyl.folkways.core.engine.travel.graph;

import io.github.izakyl.folkways.core.api.terms.Gait;
import io.github.izakyl.folkways.core.api.terms.Realm;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.pathfinder.Node;

final class Ramble {

    private static final double CROWDED = Waypoints.SPACING / 2.0D;

    private static final int MOST_MADE = 32;

    private final ServerLevel level;
    private final Realm realm;
    private final Waypoints net;
    private final Roamer roamer;
    private final long from;

    private int spent;

    Ramble(ServerLevel level, Realm realm, Waypoints net, Roamer roamer, long from) {
        this.level = level;
        this.realm = realm;
        this.net = net;
        this.roamer = roamer;
        this.from = from;
    }

    int spent() {
        return spent;
    }

    int run(long now, int limit) {
        Optional<Reading> read =
            Look.at(level, realm, roamer, from, Set.of(), BlockPos.of(from), true, limit);
        if (read.isEmpty()) {
            return 0;
        }
        spent = read.get().swept().visited();
        return harvest(read.get(), now);
    }

    private int harvest(Reading read, long now) {
        Map<Node, Integer> anchor = new HashMap<>();
        Map<Node, Double> since = new HashMap<>();
        LongArrayList made = new LongArrayList();
        IntArrayList madeAt = new IntArrayList();
        int laid = 0;
        for (Node node : read.swept().walked()) {
            Optional<BlockPos> mine = read.on().toLocal(new BlockPos(node.x, node.y, node.z));
            if (mine.isEmpty()) {
                continue;
            }
            BlockPos cell = mine.get();
            Node came = node.cameFrom;
            if (came == null) {
                anchor.put(node, net.admit(cell.asLong(), now));
                since.put(node, 0.0D);
                continue;
            }
            Integer held = anchor.get(came);
            if (held == null) {
                continue;
            }
            double run = since.get(came) + node.distanceTo(came);
            int standing = net.at(cell.asLong());
            int beside = standing >= 0 ? -1 : crowding(made, madeAt, cell);
            if (standing < 0 && (run < Waypoints.SPACING || beside < 0 && made.size() >= MOST_MADE)) {
                net.remember(cell.asLong(), held, now);
                anchor.put(node, held);
                since.put(node, run);
                continue;
            }
            int id;
            if (standing >= 0) {
                id = standing;
            } else if (beside >= 0) {
                id = beside;
            } else {
                id = net.admit(cell.asLong(), now);
                made.add(cell.asLong());
                madeAt.add(id);
            }
            double drift = Math.sqrt(cell.distSqr(BlockPos.of(net.cellOf(id))));
            if (id != held) {
                int cost = Math.max(1, (int) Math.round((run + drift) / Gait.BLOCKS_PER_TICK));
                net.linkAtMost(held, id, cost, now);
                net.linkAtMost(id, held, cost, now);
                laid++;
            }
            anchor.put(node, id);
            since.put(node, drift);
        }
        return laid;
    }

    private static int crowding(LongArrayList made, IntArrayList madeAt, BlockPos cell) {
        for (int at = made.size() - 1; at >= 0; at--) {
            if (cell.distSqr(BlockPos.of(made.getLong(at))) <= CROWDED * CROWDED) {
                return madeAt.getInt(at);
            }
        }
        return -1;
    }
}
