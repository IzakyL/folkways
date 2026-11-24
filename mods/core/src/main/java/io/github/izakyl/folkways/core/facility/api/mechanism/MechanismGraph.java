package io.github.izakyl.folkways.core.facility.api.mechanism;

import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;

/**
 * A mechanism as the world shows it now: its parts and how they act on each other. It is read afresh for each request,
 * never kept, and holds plain data only (numbers, strings, booleans, lists and string-keyed maps).
 *
 * @param nodes       the parts, in scan order
 * @param edges       how parts act on each other; plain relays between two parts are folded into the edge
 * @param paths       how a turn of each source or crank reaches each actuator it drives
 * @param unsupported what the scan saw but cannot describe
 * @param truncated   whether parts were left out to keep the graph within its size limit
 */
public record MechanismGraph(List<Node> nodes, List<Edge> edges, List<Path> paths, List<String> unsupported,
                             boolean truncated) {

    public static final int MAX_NODES = 128;
    public static final int MAX_EDGES = 256;
    public static final int MAX_PATHS = 64;

    public MechanismGraph {
        nodes = List.copyOf(nodes);
        edges = List.copyOf(edges);
        paths = List.copyOf(paths);
        unsupported = List.copyOf(unsupported);
    }

    public enum Role {
        /** Puts rotation into a network: a motor, a water wheel, a crank while it turns. */
        SOURCE,
        /** Something a resident or player operates: a lever, a crank, a cannon's aim. */
        CONTROL,
        /** Carries rotation on, possibly changing its ratio or direction. */
        TRANSMISSION,
        /** Moves or turns something else: a piston, a bearing, a cannon mount, a door. */
        ACTUATOR,
        /** A moving structure: a contraption, a cannon barrel, a sub-level. */
        MOVING,
        /** Uses rotation without moving anything. */
        MACHINE,
        /** Gives a redstone signal. */
        REDSTONE
    }

    public enum Link {
        /** Rotation passes from one part to the next; `ratio` is the speed of `to` per unit speed of `from`. */
        DRIVES,
        /** A redstone signal from `from` reaches `to`. */
        POWERS,
        /** Operating `from` commands `to` without rotation or redstone, as pilot controls steer a walker. */
        CONTROLS,
        /** `from` carries the moving structure `to`. */
        MOVES
    }

    /**
     * One part. {@code at} is in its realm: a moving structure's parts are told in its local coordinates, and an
     * entity's in the block it occupies.
     */
    public record Node(String id, String kind, Set<Role> roles, ResourceLocation block, WorldPos at,
                       Optional<UUID> entity, Map<String, Object> state) {

        public Node {
            roles = Set.copyOf(roles);
            state = Map.copyOf(state);
        }
    }

    public record Edge(String from, String to, Link link, Map<String, Object> details) {

        public Edge {
            details = Map.copyOf(details);
        }
    }

    /**
     * A source's way to an actuator: the actuator turns at {@code factor} times the source's speed. {@code through}
     * names the switching parts on the way (clutches, gearshifts) whose state the factor assumes.
     */
    public record Path(String from, String to, double factor, int hops, List<String> through,
                       Map<String, Object> details) {

        public Path {
            through = List.copyOf(through);
            details = Map.copyOf(details);
        }
    }
}
