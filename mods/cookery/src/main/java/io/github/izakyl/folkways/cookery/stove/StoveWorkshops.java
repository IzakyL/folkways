package io.github.izakyl.folkways.cookery.stove;

import com.github.ysbbbbbb.kaleidoscopecookery.block.kitchen.StoveBlock;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModBlocks;
import com.github.ysbbbbbb.kaleidoscopecookery.init.tag.TagMod;
import io.github.izakyl.folkways.core.api.colony.Closing;
import io.github.izakyl.folkways.core.api.terms.Reach;
import io.github.izakyl.folkways.core.api.terms.RefusalKind;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Hold;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.ToolNeed;
import io.github.izakyl.folkways.core.api.work.ToolUse;
import io.github.izakyl.folkways.core.api.work.WorkGesture;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.core.api.work.Workshop;
import io.github.izakyl.folkways.cookery.CookeryContent;
import io.github.izakyl.folkways.cookery.CookeryRefusal;
import io.github.izakyl.folkways.cookery.KitchenBookings;
import io.github.izakyl.folkways.plugins.wares.WaresContent;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

public final class StoveWorkshops {
    public static final ToolNeed IGNITION = new ToolNeed.Tagged(TagMod.LIT_STOVE);

    private record Key(UUID colony, WorldPos at) { }
    private record Kept(Grown goal, long generation) { }
    private static final Map<Key, Kept> ACTIVE = new ConcurrentHashMap<>();

    public static List<Grown> active(UUID colony, ServerLevel level, WorldPos at, KitchenBookings bookings) {
        if (!at.in(level)) { return List.of(); }
        Key key = new Key(colony, at);
        Kept kept = ACTIVE.get(key);
        if (kept == null) { return List.of(); }
        Node node = kept.goal().nodes().getFirst();
        if (bookings.valid(at, node.id(), kept.generation()) && bookings.first(at, node.id())) {
            return List.of(kept.goal());
        }
        ACTIVE.remove(key, kept);
        return List.of();
    }

    private StoveWorkshops() {
    }

    public static boolean matches(BlockState state) { return state.is(ModBlocks.STOVE.get()); }
    public static BlockPos anchor(Level level, BlockPos cell) { return cell; }

    public static Optional<Workshop> freeze(UUID key, UUID colony, ServerLevel level, WorldPos at,
                                            Stances stances, KitchenBookings bookings, Runnable changed) {
        return Optional.empty();
    }

    public static List<Grown> recovery(UUID colony, ServerLevel level, WorldPos at, Stances stances,
                                       KitchenBookings bookings, Runnable changed) {
        List<Grown> active = active(colony, level, at, bookings);
        if (!active.isEmpty()) { return active; }
        return bookings.free(at) && at.in(level) && level.isLoaded(at.block(level))
                && matches(level.getBlockState(at.block(level)))
                && !level.getBlockState(at.block(level)).getValue(StoveBlock.LIT) && StoveIgnition.dry(level, at)
            ? List.of(new Light(colony, at, stances, bookings, changed).goal) : List.of();
    }

    public static void release(UUID colony, ServerLevel level, WorldPos at) {
        ACTIVE.remove(new Key(colony, at));
    }

    public static void stop(UUID colony, Closing why) {
        ACTIVE.keySet().removeIf(key -> key.colony().equals(colony));
    }

    static final class Light implements Node {
        private final UUID colony;
        private final WorldPos at;
        private final KitchenBookings bookings;
        private final long generation;
        private final Runnable changed;
        private final NodeSpec spec;
        private final Grown goal;
        private Hold hold;

        Light(UUID colony, WorldPos at, Stances stances, KitchenBookings bookings, Runnable changed) {
            this.colony = colony;
            this.at = at;
            this.bookings = bookings;
            this.generation = bookings.generation(at);
            this.changed = changed;
            UUID id = UUID.nameUUIDFromBytes(("stove/" + colony + "/" + at).getBytes(StandardCharsets.UTF_8));
            spec = NodeSpec.of(id, CookeryContent.ID, new WorkSite.AtBlock(at), stances, Workload.Once.of(20))
                .tools(new ToolUse(IGNITION, 0)).vocation(WaresContent.trade()).gesture(WorkGesture.SWING)
                .focus(at.cell()).doing(WaresContent.FUELING).done();
            goal = Grown.of(this);
        }

        public NodeSpec spec() { return spec; }
        public Optional<RefusalKind> planned(ServerLevel level) {
            if (generation != bookings.generation(at)) {
                return Optional.of(CookeryRefusal.STATION_CHANGED);
            }
            hold = bookings.hold(at, id(), spec().estimate());
            Optional<RefusalKind> refused = hold.take();
            if (refused.isEmpty()) { ACTIVE.put(new Key(colony, at), new Kept(goal, generation)); }
            return refused;
        }
        public boolean ready(ServerLevel level) {
            return refusal(level).isPresent() || bookings.first(at, id())
                && (level.getBlockState(at.block(level)).getValue(StoveBlock.LIT) || StoveIgnition.dry(level, at));
        }
        public Optional<RefusalKind> refusal(ServerLevel level) {
            if (bookings.cancelled(at, id(), generation)) {
                return Optional.of(CookeryRefusal.STATION_CHANGED);
            }
            return !at.in(level) || !level.isLoaded(at.block(level)) || !matches(level.getBlockState(at.block(level)))
                ? Optional.of(CookeryRefusal.NOT_A_STATION) : Optional.empty();
        }
        public Outcome commit(ServerLevel level, Worker who) {
            if (refusal(level).isPresent() || !bookings.first(at, id()) || !at.in(level)
                    || !level.isLoaded(at.block(level)) || !matches(level.getBlockState(at.block(level)))) {
                return Outcome.failed(CookeryRefusal.STATION_CHANGED);
            }
            if (level.getBlockState(at.block(level)).getValue(StoveBlock.LIT)) {
                return Outcome.done();
            }
            boolean reachable = Reach.stanceHolding(who.body(), spec.stances())
                .flatMap(feet -> WorldSpaces.storage(level, new WorldPos(at.realm(), feet)))
                .filter(feet -> Reach.withinReach(feet, at.block(level)) && Reach.inSight(level, feet, at.block(level)))
                .isPresent();
            if (!reachable || !StoveIgnition.dry(level, at)) {
                return Outcome.failed(CookeryRefusal.NO_HEAT_SOURCE);
            }
            boolean lit = StoveIgnition.light(level, at, who.body(), who.held(IGNITION));
            who.pack().setChanged();
            changed.run();
            return lit ? Outcome.done() : Outcome.failed(CookeryRefusal.NOTHING_TO_WORK_WITH);
        }
        public void ended(ServerLevel level, Ending how) {
            ACTIVE.computeIfPresent(new Key(colony, at), (key, kept) -> kept.goal().nodes().getFirst() == this ? null : kept);
            if (hold != null) {
                hold.release(how);
                hold = null;
            }
            changed.run();
        }
    }
}
