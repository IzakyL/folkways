package io.github.izakyl.folkways.carts;

import com.jusipat.astikorcartsredux.entity.AbstractCargoCart;
import io.github.izakyl.folkways.core.api.colony.Asking;
import io.github.izakyl.folkways.core.api.colony.Closing;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.ColonyView;
import io.github.izakyl.folkways.core.api.colony.Held;
import io.github.izakyl.folkways.core.api.colony.Holding;
import io.github.izakyl.folkways.core.api.colony.Release;
import io.github.izakyl.folkways.core.api.transport.TransportPublication;
import io.github.izakyl.folkways.core.api.transport.TransportSnapshot;
import io.github.izakyl.folkways.core.api.transport.TransportTravel;
import io.github.izakyl.folkways.core.api.work.Before;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.front.api.Fronts;
import io.github.izakyl.folkways.front.api.PathView;
import io.github.izakyl.folkways.plugins.pasture.PastureHorses;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.level.Level;

// The colony's cart lines: each drawn line gets a cart of its kind made and set down for it, a supply cart a horse
// from the colony's pastures. Supply teams navigate themselves; hand carts are drawn by haulers. What the line
// can carry between its stations is offered to the plan as a way of moving goods.
final class CartLines {

    private static final int EVERY = 20;
    private static final String TAG_LINES = "lines";

    private final Colony colony;
    private final Asking asking;
    private final Map<UUID, LineState> states = new LinkedHashMap<>();
    private Map<UUID, Line> lines = Map.of();
    private final Map<ResourceKey<Level>, TransportPublication.Offering> offered = new HashMap<>();
    private long postVersion;
    private int countdown;

    CartLines(Colony colony) {
        this.colony = colony;
        this.asking = new Asking(colony, FolkwaysCartsMod.OWNER, this::ended);
        load(colony.kept(FolkwaysCartsMod.OWNER));
    }

    Colony colony() {
        return colony;
    }

    Optional<LineState> state(UUID line) {
        return Optional.ofNullable(states.get(line));
    }

    // A line known without being read from the colony's drawings, until the next refresh reads them.
    LineState track(Line line) {
        Map<UUID, Line> known = new LinkedHashMap<>(lines);
        known.put(line.id(), line);
        lines = Map.copyOf(known);
        return states.computeIfAbsent(line.id(), LineState::new);
    }

    void tick(MinecraftServer server) {
        if (--countdown > 0) {
            return;
        }
        countdown = EVERY;
        refresh(server);
    }

    void refresh(MinecraftServer server) {
        Map<UUID, Line> found = new LinkedHashMap<>();
        Map<UUID, ServerLevel> where = new HashMap<>();
        for (ColonyView view : colony.views(server)) {
            for (PathView path : Fronts.of(colony).paths()) {
                Line.of(path, view.level()).ifPresent(line -> {
                    found.put(line.id(), line);
                    where.put(line.id(), view.level());
                });
            }
        }
        lines = Map.copyOf(found);
        for (UUID gone : List.copyOf(states.keySet())) {
            if (!found.containsKey(gone)) {
                LineState state = states.remove(gone);
                letGo(server, state);
            }
        }
        for (Line line : found.values()) {
            ServerLevel level = where.get(line.id());
            LineState state = states.computeIfAbsent(line.id(), LineState::new);
            keepCart(level, line, state);
            keepHorse(level, line, state);
        }
        asking.offer(server, this::goals);
        for (ColonyView view : colony.views(server)) {
            colony.publishTransport(FolkwaysCartsMod.OWNER, view.dimension(), posted(view.level()));
        }
        save();
    }

    // A line's cart is the one the colony set down for it while the colony still holds it; one of the wrong kind,
    // after the line was changed, is let go for a new one.
    private void keepCart(ServerLevel level, Line line, LineState state) {
        if (state.cart.isEmpty()) {
            return;
        }
        Optional<Holding> held = colony.holdingOf(state.cart.get())
            .filter(holding -> holding.owner().equals(FolkwaysCartsMod.OWNER));
        if (held.isEmpty()) {
            state.cart = Optional.empty();
            state.aboard.clear();
            return;
        }
        state.cart(level).filter(cart -> !line.kind().is(cart)).ifPresent(cart -> {
            cart.setPulling(null);
            colony.release(held.get().id(), Release.LET_GO);
            state.cart = Optional.empty();
            state.aboard.clear();
        });
    }

    // A supply cart line takes a tamed grown horse from one of the colony's horse pastures; a hand cart line keeps
    // none.
    private void keepHorse(ServerLevel level, Line line, LineState state) {
        if (state.horse.isPresent()
                && colony.holdingOf(state.horse.get()).filter(this::ours).isEmpty()) {
            if (state.team != null) {
                state.team.release();
                state.team = null;
            }
            state.horse = Optional.empty();
        }
        if (line.kind() != Line.Kind.SUPPLY) {
            if (state.horse.isPresent()) {
                letGoHorse(level.getServer(), state);
            }
            return;
        }
        if (state.horse.isEmpty()) {
            state.horse = PastureHorses.claim(colony, level, FolkwaysCartsMod.OWNER).map(AbstractHorse::getUUID);
        }
        state.horse(level).ifPresent(horse -> {
            if (state.team == null || !state.team.horse().equals(horse)) {
                if (state.team != null) {
                    state.team.release();
                }
                state.team = new HorseLine(level, line, state, horse);
            }
            state.team.follow(line);
        });
    }

    private boolean ours(Holding holding) {
        return holding.owner().equals(FolkwaysCartsMod.OWNER);
    }

    private void letGo(MinecraftServer server, LineState state) {
        server.getAllLevels().forEach(level -> state.cart(level).ifPresent(cart -> cart.setPulling(null)));
        state.cart.flatMap(colony::holdingOf).filter(this::ours)
            .ifPresent(holding -> colony.release(holding.id(), Release.LET_GO));
        letGoHorse(server, state);
    }

    private void letGoHorse(MinecraftServer server, LineState state) {
        if (state.team != null) {
            state.team.release();
            state.team = null;
        }
        // A line may be gone before its level is visited; keep the horse's id until every level is searched.
        for (ServerLevel level : server.getAllLevels()) {
            state.horse(level).ifPresent(horse -> {
                PastureHorses.release(colony, FolkwaysCartsMod.OWNER, horse);
                if (horse.isLeashed()) {
                    horse.dropLeash(true, false);
                }
                state.cart(level).filter(cart -> cart.getPulling() == horse).ifPresent(cart -> cart.setPulling(null));
            });
        }
        state.horse.flatMap(colony::holdingOf).filter(this::ours)
            .ifPresent(holding -> colony.release(holding.id(), Release.LET_GO));
        state.horse = Optional.empty();
    }

    void placed(UUID line, UUID cart) {
        LineState state = states.computeIfAbsent(line, LineState::new);
        state.cart = Optional.of(cart);
        state.lap = null;
        state.open.clear();
        save();
    }

    // Goals the plan is asked for, line by line: a cart to set down, the lap the cart is drawn round, and goods aboard
    // that belong to no batch, to be set down.
    List<Grown> goals(ColonyView view) {
        ServerLevel level = view.level();
        List<Grown> goals = new ArrayList<>();
        for (Line line : lines.values()) {
            if (!line.dimension().equals(level.dimension())) {
                continue;
            }
            LineState state = states.get(line.id());
            if (state == null) {
                continue;
            }
            if (state.cart.isEmpty()) {
                line.station(0).ifPresent(first -> goals.add(Grown.of(new LineWork.Place(this, line, first))));
                continue;
            }
            Optional<AbstractCargoCart> cart = state.cart(level);
            if (cart.isEmpty()) {
                continue;
            }
            Optional<AbstractHorse> horse = state.horse(level);
            if (line.kind() == Line.Kind.SUPPLY && horse.isEmpty()) {
                continue;
            }
            if (line.kind() == Line.Kind.SUPPLY) {
                if (!state.stray(cart.get()).isEmpty()) {
                    goals.add(Grown.of(new LineWork.Clear(id(line, "clear/" + cart.get().getUUID()), level, state,
                        line, cart.get())));
                }
                continue;
            }
            if (!state.lapUnderWay()) {
                state.lapNumber++;
                state.lap = lap(level, line, state, cart.get());
                state.open.clear();
                state.open.addAll(state.lap.completions());
            }
            goals.add(state.lap.remaining(state.open));
            if (!state.stray(cart.get()).isEmpty()) {
                goals.add(Grown.of(new LineWork.Clear(id(line, "clear/" + cart.get().getUUID()), level, state, line,
                    cart.get())));
            }
        }
        return goals;
    }

    // A hand-cart lap belongs to one hauler. Supply teams never request driving or leading work.
    private Grown lap(ServerLevel level, Line line, LineState state, AbstractCargoCart cart) {
        String lap = "lap/" + state.lapNumber + "/";
        List<Node> nodes = new ArrayList<>();
        List<Line.Mark> marks = line.lap(state.lapNumber);
        nodes.add(new LineWork.Hitch(id(line, lap + "hitch"), level, state, cart));
        for (int at = state.lapNumber == 0 ? 1 : 0; at < marks.size(); at++) {
            nodes.add(new LineWork.Drive(id(line, lap + at), state, marks.get(at)));
        }
        List<Before> order = new ArrayList<>();
        for (int at = 1; at < nodes.size(); at++) {
            order.add(new Before(nodes.get(at - 1).id(), nodes.get(at).id()));
        }
        return new Grown(nodes, order).byOneWorker();
    }

    private static UUID id(Line line, String what) {
        return UUID.nameUUIDFromBytes(("folkways:carts/" + line.id() + "/" + what).getBytes());
    }

    private void ended(UUID node, Ending how) {
        states.values().forEach(state -> state.open.remove(node));
    }

    // The lines of this level whose cart stands ready, as one way of moving goods. The same lines offered again
    // unchanged keep what was quoted from them.
    private List<TransportPublication.Offering> posted(ServerLevel level) {
        List<CartTransport.Running> running = new ArrayList<>();
        for (Line line : lines.values()) {
            LineState state = states.get(line.id());
            if (!line.dimension().equals(level.dimension()) || state == null) {
                continue;
            }
            Optional<AbstractCargoCart> cart = state.cart(level);
            if (cart.isEmpty() || line.kind() == Line.Kind.SUPPLY && state.horse.isEmpty()) {
                continue;
            }
            running.add(new CartTransport.Running(line, cart.get().getUUID(), state.freeCells(cart.get())));
        }
        if (running.isEmpty()) {
            offered.remove(level.dimension());
            return List.of();
        }
        CartTransport mode = new CartTransport(this, running);
        TransportPublication.Offering before = offered.get(level.dimension());
        if (before != null && before.mode().equals(mode)) {
            return List.of(before);
        }
        TransportPublication.Offering now = new TransportPublication.Offering(mode,
            new TransportSnapshot(++postVersion, 0, TransportTravel.UNKNOWN, List.of()), true);
        offered.put(level.dimension(), now);
        return List.of(now);
    }

    void closed(MinecraftServer server, Closing why) {
        states.values().forEach(state -> {
            if (why != Closing.SHUTDOWN) {
                letGo(server, state);
            } else if (state.team != null) {
                state.team.release();
                state.team = null;
            }
        });
        offered.clear();
        save();
    }

    private void save() {
        ListTag list = new ListTag();
        states.values().forEach(state -> {
            CompoundTag one = new CompoundTag();
            one.putUUID("line", state.line);
            state.cart.ifPresent(cart -> one.putUUID("cart", cart));
            state.horse.ifPresent(horse -> one.putUUID("horse", horse));
            one.putInt("lap", state.lapNumber);
            one.putInt("nextMark", state.nextMark);
            one.putInt("direction", state.direction);
            list.add(one);
        });
        CompoundTag tag = new CompoundTag();
        tag.put(TAG_LINES, list);
        colony.keep(FolkwaysCartsMod.OWNER, tag);
    }

    // What was aboard when the colony was saved is not carried on: it is stray, and set down at the next station.
    private void load(CompoundTag tag) {
        for (Tag entry : tag.getList(TAG_LINES, Tag.TAG_COMPOUND)) {
            CompoundTag one = (CompoundTag) entry;
            if (!one.hasUUID("line")) {
                continue;
            }
            LineState state = new LineState(one.getUUID("line"));
            if (one.hasUUID("cart")) {
                state.cart = Optional.of(one.getUUID("cart"));
            }
            if (one.hasUUID("horse")) {
                state.horse = Optional.of(one.getUUID("horse"));
            }
            state.lapNumber = one.getInt("lap") - 1;
            state.nextMark = one.getInt("nextMark");
            state.direction = one.getInt("direction") == -1 ? -1 : 1;
            states.put(state.line, state);
        }
    }
}
