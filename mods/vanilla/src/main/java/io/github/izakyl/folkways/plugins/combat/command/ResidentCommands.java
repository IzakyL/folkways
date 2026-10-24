package io.github.izakyl.folkways.plugins.combat.command;

import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.resident.body.Body;
import io.github.izakyl.folkways.core.api.terms.RefusalKind;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Urge;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/** Server-side orders executed exclusively by Core's existing labour runner. */
public final class ResidentCommands {

    public enum Mode { MOVE, HOLD, RETREAT }
    public enum Status { MOVING, HOLDING, ARRIVED, RELEASED, FAILED }

    /** A handle can release only its own order, including after a replacement was issued. */
    public final class Order implements AutoCloseable {
        private final UUID id = UUID.randomUUID();
        private final Body body;
        private final WorldPos destination;
        private final Mode mode;
        private final long issued = server.overworld().getGameTime();
        private volatile Status status = Status.MOVING;
        private volatile RefusalKind failure;

        private Order(Body body, WorldPos destination, Mode mode) {
            this.body = body;
            this.destination = destination;
            this.mode = mode;
        }

        public UUID id() { return id; }
        public UUID resident() { return body.id(); }
        public WorldPos destination() { return destination; }
        public Mode mode() { return mode; }
        public Status status() { return status; }
        public Optional<RefusalKind> failure() { return Optional.ofNullable(failure); }
        public void close() { release(this); }
    }

    private final Colony colony;
    private final MinecraftServer server;
    private final Map<UUID, Order> active = new HashMap<>();

    ResidentCommands(Colony colony, MinecraftServer server) {
        this.colony = colony;
        this.server = server;
    }

    public static ResidentCommands of(Colony colony) {
        return colony.service(CommandContent.ID, ResidentCommands.class).orElseThrow();
    }

    public Order move(Body body, WorldPos destination) { return issue(body, destination, Mode.MOVE); }
    public Order hold(Body body, WorldPos destination) { return issue(body, destination, Mode.HOLD); }
    public Order retreat(Body body, WorldPos destination) { return issue(body, destination, Mode.RETREAT); }

    public Optional<Order> current(UUID resident) {
        requireServerThread();
        return Optional.ofNullable(active.get(resident));
    }

    private Order issue(Body body, WorldPos destination, Mode mode) {
        requireServerThread();
        Objects.requireNonNull(destination);
        if (!valid(body) || !body.kind().id().equals(PersonBody.ID)) {
            throw new IllegalArgumentException("orders require a loaded person held by this colony");
        }
        ServerLevel level = (ServerLevel) body.mob().level();
        WorldPos from = WorldSpaces.at(body.mob());
        if (!destination.sameRealm(from) || !destination.in(level)
                || !level.hasChunkAt(destination.block(level)) || destination.cell().distSqr(from.cell()) > 128 * 128) {
            throw new IllegalArgumentException("order destination must be loaded, in the same realm and within 128 blocks");
        }
        Order next = new Order(body, destination, mode);
        Order previous = active.put(body.id(), next);
        if (previous != null) { previous.status = Status.RELEASED; }
        return next;
    }

    public void release(Order order) {
        requireServerThread();
        if (active.remove(order.resident(), order)) { order.status = Status.RELEASED; }
    }

    public void release(UUID resident) {
        requireServerThread();
        Order order = active.remove(resident);
        if (order != null) { order.status = Status.RELEASED; }
    }

    List<Urge> urges(Worker who) {
        Order order = active.get(who.resident().id());
        if (order == null || !valid(order.body)) { return List.of(); }
        // A replacement has its own urge identity: it safely interrupts even a trip already in progress.
        ResourceLocation intent = ResourceLocation.fromNamespaceAndPath("folkways", "command/" + order.id);
        return List.of(new Urge(intent, Urge.DIRE + (order.mode == Mode.RETREAT ? 2 : 1), true,
            () -> new CommandNode(this, order)));
    }

    boolean active(Order order) {
        return active.get(order.resident()) == order && valid(order.body);
    }

    boolean arrived(Order order) {
        if (!active(order)) { return false; }
        if (order.mode == Mode.MOVE) {
            order.status = Status.ARRIVED;
            active.remove(order.resident(), order);
            return false;
        }
        order.status = Status.HOLDING;
        return true;
    }

    void ended(Order order, Ending how) {
        if (how instanceof Ending.Failed failed && active.remove(order.resident(), order)) {
            order.failure = failed.why();
            order.status = Status.FAILED;
        }
    }

    void tick(MinecraftServer ignored) {
        for (Order order : List.copyOf(active.values())) {
            if (!valid(order.body)) {
                release(order);
            } else if (order.status == Status.MOVING && server.overworld().getGameTime() - order.issued >= 1200) {
                active.remove(order.resident(), order);
                order.status = Status.FAILED;
            }
        }
    }

    void clear() {
        active.values().forEach(order -> order.status = Status.RELEASED);
        active.clear();
    }

    private boolean valid(Body body) {
        return body.mob().level() instanceof ServerLevel level && level.getServer() == server
            && body.mob().isAlive() && !body.mob().isRemoved()
            && body.colonyId().filter(colony.id()::equals).isPresent()
            && colony.holdingOf(body.id()).isPresent();
    }

    private void requireServerThread() {
        if (!server.isSameThread()) { throw new IllegalStateException("resident orders belong to the server thread"); }
    }
}
