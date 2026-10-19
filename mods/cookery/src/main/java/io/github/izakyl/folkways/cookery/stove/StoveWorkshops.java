package io.github.izakyl.folkways.cookery.stove;

import com.github.ysbbbbbb.kaleidoscopecookery.block.kitchen.StoveBlock;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModBlocks;
import com.github.ysbbbbbb.kaleidoscopecookery.init.tag.TagMod;
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
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

public final class StoveWorkshops {
    public static final ToolNeed IGNITION = new ToolNeed.Tagged(TagMod.LIT_STOVE);

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
        return bookings.free(at) && at.in(level) && level.isLoaded(at.block(level))
                && matches(level.getBlockState(at.block(level)))
                && !level.getBlockState(at.block(level)).getValue(StoveBlock.LIT)
            ? List.of(Grown.of(new Light(colony, at, stances, bookings, changed))) : List.of();
    }

    public static void release(UUID colony, ServerLevel level, WorldPos at) {
    }

    static final class Light implements Node {
        private final WorldPos at;
        private final KitchenBookings bookings;
        private final long generation;
        private final Runnable changed;
        private final NodeSpec spec;
        private Hold hold;

        Light(UUID colony, WorldPos at, Stances stances, KitchenBookings bookings, Runnable changed) {
            this.at = at;
            this.bookings = bookings;
            this.generation = bookings.generation(at);
            this.changed = changed;
            UUID id = UUID.nameUUIDFromBytes(("stove/" + colony + "/" + at).getBytes(StandardCharsets.UTF_8));
            spec = NodeSpec.of(id, CookeryContent.ID, new WorkSite.AtBlock(at), stances, Workload.Once.of(20))
                .tools(new ToolUse(IGNITION, 0)).vocation(WaresContent.trade()).gesture(WorkGesture.SWING)
                .focus(at.cell()).doing(WaresContent.FUELING).done();
        }

        public NodeSpec spec() { return spec; }
        public Optional<RefusalKind> planned(ServerLevel level) {
            if (generation != bookings.generation(at)) {
                return Optional.of(CookeryRefusal.STATION_CHANGED);
            }
            hold = bookings.hold(at, id());
            return hold.take();
        }
        public boolean ready(ServerLevel level) {
            return bookings.cancelled(at, id(), generation) || bookings.first(at, id());
        }
        public Optional<RefusalKind> refusal(ServerLevel level) {
            if (bookings.cancelled(at, id(), generation)) {
                return Optional.of(CookeryRefusal.STATION_CHANGED);
            }
            return Optional.empty();
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
            if (hold != null) {
                hold.release(how);
                hold = null;
            }
            changed.run();
        }
    }
}
