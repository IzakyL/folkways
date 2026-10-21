package io.github.izakyl.folkways.cookery.board;

import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.ChoppingBoardBlockEntity;
import com.github.ysbbbbbb.kaleidoscopecookery.crafting.recipe.ChoppingBoardRecipe;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModRecipes;
import com.github.ysbbbbbb.kaleidoscopecookery.init.tag.TagMod;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaConfig;
import io.github.izakyl.folkways.core.api.terms.ItemDrops;
import io.github.izakyl.folkways.core.api.terms.Reach;
import io.github.izakyl.folkways.core.api.terms.RefusalKind;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
import io.github.izakyl.folkways.core.api.work.Amount;
import io.github.izakyl.folkways.core.api.work.Leaves;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Hold;
import io.github.izakyl.folkways.core.api.work.Need;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.ToolNeed;
import io.github.izakyl.folkways.core.api.work.ToolUse;
import io.github.izakyl.folkways.core.api.work.WorkEffort;
import io.github.izakyl.folkways.core.api.work.WorkGesture;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.core.api.work.Xp;
import io.github.izakyl.folkways.cookery.CookeryContent;
import io.github.izakyl.folkways.cookery.CookeryRefusal;
import io.github.izakyl.folkways.cookery.KitchenBookings;
import io.github.izakyl.folkways.cookery.KitchenDelivery;
import io.github.izakyl.folkways.plugins.wares.WaresContent;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class BoardWork implements Node, Workload.Within {

    private static final Logger LOGGER = LoggerFactory.getLogger("folkways-cookery");

    static final ToolNeed KNIFE = new ToolNeed.Tagged(TagMod.KITCHEN_KNIFE);

    private static final int ACTION_TICKS = 20;

    private final NodeSpec spec;
    private final WorldPos at;
    private final ChoppingBoardWorkshops.Job job;
    private final KitchenBookings bookings;
    private final long generation;
    private final Runnable changed;
    private final BoardOwnership.Batch batch;
    private final boolean recovering;
    private Hold recoveryHold;
    private boolean loaded;
    private boolean finished;
    private RefusalKind failed;

    BoardWork(UUID colony, WorldPos at, Stances stances, ChoppingBoardWorkshops.Job job,
              KitchenBookings bookings, Runnable changed, BoardOwnership.Batch previous) {
        this.at = at;
        this.job = job;
        this.bookings = bookings;
        this.generation = bookings.generation(at);
        this.changed = changed;
        this.recovering = previous != null;
        this.loaded = recovering;
        UUID id = recovering
            ? UUID.nameUUIDFromBytes(("folkways:board/recover/" + previous.id()).getBytes(StandardCharsets.UTF_8))
            : UUID.randomUUID();
        this.batch = recovering ? previous : new BoardOwnership.Batch(colony, id, job.recipe());
        this.spec = declare(id, stances, this)
            .needs(recovering ? List.of() : List.of(new Need(job.input(), 1, true)))
            .gives(job.gives())
            .leaves(job.gives())
            .tools(new ToolUse(KNIFE, 0))
            .effort(WorkEffort.NONE)
            .estimate(ACTION_TICKS * (job.cuts() + (recovering ? 1 : 2)))
            .doing(CookeryContent.CHOPPING, job.input())
            .done();
    }

    private NodeSpec.Builder declare(UUID id, Stances stances, Workload workload) {
        return NodeSpec.of(id, CookeryContent.ID, new WorkSite.AtBlock(at), stances, workload)
            .vocation(WaresContent.trade()).gesture(WorkGesture.SWING).focus(at.cell());
    }

    @Override
    public NodeSpec spec() {
        return spec;
    }

    @Override
    public List<Amount> planningLeaves(List<Amount> promised) {
        return Leaves.unpromised(spec.leaves(), promised);
    }

    @Override
    public Optional<RefusalKind> planned(ServerLevel level) {
        // ColonyLabor applies planned on the server thread after adopting a refinement.
        if (!recovering) {
            return bookings.cancelled(at, id(), generation) ? Optional.of(CookeryRefusal.STATION_CHANGED) : Optional.empty();
        }
        if (generation != bookings.generation(at) || !bookings.free(at) || !owned(level)) {
            return Optional.of(CookeryRefusal.STATION_BUSY);
        }
        recoveryHold = bookings.hold(at, id(), spec().estimate());
        Optional<RefusalKind> why = recoveryHold.take();
        if (why.isEmpty()) {
            changed.run();
        }
        return why;
    }

    @Override
    public boolean ready(ServerLevel level) {
        return bookings.valid(at, id(), generation) && bookings.first(at, id())
            && ChoppingBoardWorkshops.board(level, at).isPresent()
            && (loaded ? owned(level) : empty(level));
    }

    @Override
    public Optional<RefusalKind> refusal(ServerLevel level) {
        if (bookings.cancelled(at, id(), generation)) {
            return Optional.of(CookeryRefusal.STATION_CHANGED);
        }
        if (!at.in(level) || !level.isLoaded(at.block(level))) {
            return Optional.empty();
        }
        if (ChoppingBoardWorkshops.board(level, at).isEmpty()) {
            return Optional.of(CookeryRefusal.NOT_A_STATION);
        }
        return loaded && !finished && !owned(level)
            || !loaded && bookings.first(at, id()) && !empty(level)
            ? Optional.of(CookeryRefusal.STATION_CHANGED) : Optional.empty();
    }

    @Override
    public Turn next(ServerLevel level, Worker who) {
        if (bookings.cancelled(at, id(), generation)) {
            failed = CookeryRefusal.STATION_CHANGED;
            return LEAVE;
        }
        if (finished || failed != null) {
            return LEAVE;
        }
        if (!bookings.first(at, id())) {
            return WAIT;
        }
        if (!loaded) {
            return new Turn.Piece(new Put());
        }
        if (!owned(level)) {
            failed = CookeryRefusal.STATION_CHANGED;
            return LEAVE;
        }
        return new Turn.Piece(new Cut());
    }

    @Override
    public void ended(ServerLevel level, Node piece, Ending how) {
        if (how instanceof Ending.Failed failure) {
            failed = failure.why();
        }
    }

    @Override
    public Outcome commit(ServerLevel level, Worker who) {
        return finished && bookings.valid(at, id(), generation) ? Outcome.done()
            : Outcome.failed(failed == null ? CookeryRefusal.STATION_CHANGED : failed);
    }

    @Override
    public void ended(ServerLevel level, Ending how) {
        if (recoveryHold != null) {
            recoveryHold.release(how);
            recoveryHold = null;
        }
        changed.run();
    }

    private boolean empty(ServerLevel level) {
        return ChoppingBoardWorkshops.board(level, at)
            .map(board -> board.getCurrentCutStack().isEmpty()).orElse(false);
    }

    private boolean owned(ServerLevel level) {
        return ChoppingBoardWorkshops.board(level, at).flatMap(board -> BoardOwnership.read(board, level))
            .filter(batch::equals).isPresent();
    }

    private boolean reachable(ServerLevel level, Worker who) {
        return Reach.stanceHolding(who.body(), spec.stances())
            .flatMap(footing -> WorldSpaces.storage(level, new WorldPos(at.realm(), footing)))
            .filter(feet -> Reach.withinReach(feet, at.block(level))
                && Reach.inSight(level, feet, at.block(level)))
            .isPresent();
    }

    private abstract class Action implements Node {

        private final NodeSpec action;

        Action(ResourceLocation doing, boolean input, boolean knife) {
            NodeSpec.Builder builder = declare(UUID.randomUUID(), spec.stances(),
                Workload.Once.of(ACTION_TICKS, WaresContent::haste)).doing(doing, job.input());
            if (input) {
                builder.needs(new Need(job.input(), 1, true));
            }
            if (knife) {
                builder.tools(new ToolUse(KNIFE, 0));
            }
            this.action = builder.done();
        }

        @Override
        public NodeSpec spec() {
            return action;
        }

        @Override
        public boolean ready(ServerLevel level) {
            return bookings.valid(at, BoardWork.this.id(), generation) && bookings.first(at, BoardWork.this.id())
                && (loaded ? owned(level) : empty(level));
        }

        @Override
        public Optional<RefusalKind> refusal(ServerLevel level) {
            return bookings.cancelled(at, BoardWork.this.id(), generation)
                ? Optional.of(CookeryRefusal.STATION_CHANGED) : Optional.empty();
        }
    }

    private final class Put extends Action {

        Put() {
            super(CookeryContent.PREPARING, true, false);
        }

        @Override
        public Outcome commit(ServerLevel level, Worker who) {
            Optional<ChoppingBoardBlockEntity> found = ChoppingBoardWorkshops.board(level, at);
            if (found.isEmpty()) {
                return Outcome.failed(CookeryRefusal.NOT_A_STATION);
            }
            ChoppingBoardBlockEntity board = found.get();
            if (bookings.cancelled(at, BoardWork.this.id(), generation) || !bookings.first(at, BoardWork.this.id())
                    || !board.getCurrentCutStack().isEmpty()
                    || !reachable(level, who) || who.held(KNIFE).isEmpty()) {
                return Outcome.failed(CookeryRefusal.STATION_CHANGED);
            }
            List<ItemStack> supplied = who.supplied();
            ItemStack input = supplied.stream().filter(stack -> !stack.isEmpty()).findFirst()
                .map(stack -> stack.copyWithCount(1)).orElse(ItemStack.EMPTY);
            Optional<RecipeHolder<ChoppingBoardRecipe>> actual = level.getRecipeManager()
                .getRecipeFor(ModRecipes.CHOPPING_BOARD_RECIPE, new SingleRecipeInput(input), level);
            if (FolkwaysVanillaConfig.refusedRecipes().contains(job.recipe()) || actual.isEmpty()
                    || !actual.get().id().equals(job.recipe()) || !job.matches(actual.get().value())) {
                return Outcome.failed(CookeryRefusal.RECIPE_CHANGED);
            }
            ItemStack expected = input.copy();
            KitchenDelivery.Result result;
            try {
                result = KitchenDelivery.run(level, at.block(level), who, input,
                    () -> board.onPutItem(level, who.body(), input));
            } catch (RuntimeException | LinkageError broken) {
                if (!ItemStack.matches(board.getCurrentCutStack(), expected)) {
                    throw broken;
                }
                // Once the real board holds the input, the core must not refund another copy on failure.
                LOGGER.warn("board {} accepted its ingredient before its interaction threw", at, broken);
                failed = CookeryRefusal.STATION_CHANGED;
                result = new KitchenDelivery.Result(false, broken instanceof ItemDrops.CaptureFailure captured
                    ? captured.items() : List.of());
            }
            if (!result.success() && !ItemStack.matches(board.getCurrentCutStack(), expected)) {
                return Outcome.failed(CookeryRefusal.NOTHING_TO_WORK_WITH);
            }
            if (!result.success()) {
                failed = CookeryRefusal.STATION_CHANGED;
            }
            loaded = true;
            BoardOwnership.remember(board, level, batch);
            changed.run();
            List<ItemStack> unused = new ArrayList<>();
            boolean spent = false;
            for (ItemStack stack : supplied) {
                ItemStack left = stack.copy();
                if (!spent && !left.isEmpty()) {
                    left.shrink(1);
                    spent = true;
                }
                if (!left.isEmpty()) {
                    unused.add(left);
                }
            }
            return new Outcome.Done(List.of(), result.outputs(), Optional.empty(), unused);
        }
    }

    private final class Cut extends Action {

        Cut() {
            super(CookeryContent.CHOPPING, false, true);
        }

        @Override
        public Outcome commit(ServerLevel level, Worker who) {
            Optional<ChoppingBoardBlockEntity> found = ChoppingBoardWorkshops.board(level, at);
            if (found.isEmpty()) {
                return Outcome.failed(CookeryRefusal.NOT_A_STATION);
            }
            ChoppingBoardBlockEntity board = found.get();
            if (bookings.cancelled(at, BoardWork.this.id(), generation) || !bookings.first(at, BoardWork.this.id())
                    || !owned(level) || !reachable(level, who)) {
                return Outcome.failed(CookeryRefusal.STATION_CHANGED);
            }
            ItemStack knife = who.held(KNIFE);
            if (knife.isEmpty() || !knife.is(TagMod.KITCHEN_KNIFE)) {
                return Outcome.failed(CookeryRefusal.NOTHING_TO_WORK_WITH);
            }
            CompoundTag before = board.saveWithoutMetadata(level.registryAccess());
            KitchenDelivery.Result result;
            try {
                result = KitchenDelivery.run(level, at.block(level), who, knife,
                    () -> board.onCutItem(level, who.body(), knife));
            } catch (ItemDrops.CaptureFailure broken) {
                if (!board.getCurrentCutStack().isEmpty()) {
                    // A failure between two popResource calls still leaves the entire product list in the board.
                    // None of its captured drops spawned, so restoring this one call makes a later retry safe.
                    board.loadWithComponents(before, level.registryAccess());
                    board.setChanged();
                    LOGGER.warn("board {} interaction failed before take-out completed; restored its batch", at, broken);
                    changed.run();
                    return Outcome.failed(CookeryRefusal.STATION_CHANGED);
                }
                // The board has already cleared its batch: settle the captured products rather than running it again.
                LOGGER.warn("board {} emptied before its take-out interaction threw", at, broken);
                result = new KitchenDelivery.Result(true, broken.items());
            }
            if (!result.success()) {
                return Outcome.failed(CookeryRefusal.STATION_CHANGED);
            }
            finished = board.getCurrentCutStack().isEmpty();
            if (finished) {
                BoardOwnership.clear(board);
            } else {
                BoardOwnership.remember(board, level, batch);
            }
            // The block's player interaction wears the knife on one in four successful calls, including take-out.
            if (level.random.nextDouble() < 0.25D) {
                knife.hurtAndBreak(1, level, who.body(), ignored -> {});
                who.pack().setChanged();
            }
            changed.run();
            return new Outcome.Done(List.of(), result.outputs(), finished
                ? Optional.of(new Xp(WaresContent.trade(), 1)) : Optional.empty());
        }
    }
}
