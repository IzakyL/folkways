package io.github.izakyl.folkways.vanilla.cookery.board;

import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.ChoppingBoardBlockEntity;
import com.github.ysbbbbbb.kaleidoscopecookery.crafting.recipe.ChoppingBoardRecipe;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModRecipes;
import com.github.ysbbbbbb.kaleidoscopecookery.init.tag.TagMod;
import io.github.izakyl.folkways.core.facility.api.ProcessHolding;
import io.github.izakyl.folkways.core.facility.api.ProcessLoop;
import io.github.izakyl.folkways.core.item.api.ItemDrops;
import io.github.izakyl.folkways.core.resident.api.RefusalKind;
import io.github.izakyl.folkways.core.work.api.Amount;
import io.github.izakyl.folkways.core.work.api.Hold;
import io.github.izakyl.folkways.core.work.api.HoldingContext;
import io.github.izakyl.folkways.core.work.api.HoldingStep;
import io.github.izakyl.folkways.core.work.api.Need;
import io.github.izakyl.folkways.core.work.api.NodeSpec;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.Readiness;
import io.github.izakyl.folkways.core.work.api.ToolNeed;
import io.github.izakyl.folkways.core.work.api.ToolUse;
import io.github.izakyl.folkways.core.work.api.WorkAction;
import io.github.izakyl.folkways.core.work.api.WorkEffort;
import io.github.izakyl.folkways.core.work.api.WorkGesture;
import io.github.izakyl.folkways.core.work.api.WorkHolding;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.api.Workload;
import io.github.izakyl.folkways.core.work.api.Xp;
import io.github.izakyl.folkways.core.work.support.Leaves;
import io.github.izakyl.folkways.core.world.api.Stances;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.vanilla.FolkwaysVanillaConfig;
import io.github.izakyl.folkways.vanilla.cookery.CookeryContent;
import io.github.izakyl.folkways.vanilla.cookery.CookeryRefusal;
import io.github.izakyl.folkways.vanilla.cookery.KitchenBookings;
import io.github.izakyl.folkways.vanilla.cookery.KitchenClosure;
import io.github.izakyl.folkways.vanilla.cookery.KitchenDelivery;
import io.github.izakyl.folkways.vanilla.cookery.KitchenWork;
import io.github.izakyl.folkways.vanilla.wares.WaresContent;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class BoardWork implements KitchenWork, ProcessLoop.Steps {

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
    private final ProcessLoop loop;
    private final KitchenClosure closure;

    BoardWork(UUID colony, WorldPos at, Stances stances, ChoppingBoardWorkshops.Job job,
              KitchenBookings bookings, Runnable changed, BoardOwnership.Batch previous) {
        this.at = at;
        this.job = job;
        this.bookings = bookings;
        this.generation = bookings.generation(at);
        this.changed = changed;
        this.recovering = previous != null;
        this.loop = new ProcessLoop(this, recovering);
        UUID id = recovering
            ? UUID.nameUUIDFromBytes(("folkways_cookery:board/recover/" + previous.id()).getBytes(StandardCharsets.UTF_8))
            : UUID.randomUUID();
        this.batch = recovering ? previous : new BoardOwnership.Batch(colony, id, job.recipe());
        this.spec = declare(id, stances, new Workload.Custom())
            .needs(recovering ? List.of() : List.of(new Need(job.input(), 1, true)))
            .gives(job.gives())
            .leaves(job.gives())
            .tools(new ToolUse(KNIFE, 0))
            .effort(WorkEffort.NONE)
            .estimate(ACTION_TICKS * (job.cuts() + (recovering ? 1 : 2)))
            .doing(CookeryContent.CHOPPING, job.input())
            .done();
        closure = new KitchenClosure(how -> {
            if (recoveryHold != null) {
                recoveryHold.release(how);
                recoveryHold = null;
            }
        }, changed);
    }

    private NodeSpec.Builder declare(UUID id, Stances stances, Workload workload) {
        return NodeSpec.of(id, CookeryContent.ID, new WorkSite.AtBlock(at), stances, workload)
            .vocation(WaresContent.trade()).gesture(WorkGesture.SWING).focus(at.cell());
    }

    @Override
    public boolean producesBeforeSettlement() {
        return true;
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
    public Optional<RefusalKind> planned(ServerLevel level, Consumer<RefusalKind> fail) {
        Optional<RefusalKind> refused = planned(level);
        if (refused.isEmpty()) {
            if (closure.closed() || bookings.cancelled(at, id(), generation)) {
                return Optional.of(CookeryRefusal.STATION_CHANGED);
            }
            closure.onWithdrawal(bookings, at, () -> fail.accept(CookeryRefusal.STATION_CHANGED));
        }
        return refused;
    }

    @Override
    public Readiness readiness(ServerLevel level) {
        return Readiness.of(bookings.valid(at, id(), generation) && bookings.first(at, id())
            && ChoppingBoardWorkshops.board(level, at).isPresent()
            && (loop.isLoaded() ? owned(level) : empty(level)));
    }

    @Override
    public Optional<RefusalKind> refusal(ServerLevel level) {
        if (closure.closed()) {
            return Optional.of(CookeryRefusal.STATION_CHANGED);
        }
        if (bookings.cancelled(at, id(), generation)) {
            return Optional.of(CookeryRefusal.STATION_CHANGED);
        }
        if (!at.in(level) || !level.isLoaded(at.block(level))) {
            return Optional.empty();
        }
        if (ChoppingBoardWorkshops.board(level, at).isEmpty()) {
            return Optional.of(CookeryRefusal.NOT_A_STATION);
        }
        return loop.isLoaded() && !loop.isFinished() && !owned(level)
            || !loop.isLoaded() && bookings.first(at, id()) && !empty(level)
            ? Optional.of(CookeryRefusal.STATION_CHANGED) : Optional.empty();
    }

    @Override
    public WorkHolding acquire(HoldingContext context) {
        return new BoardHolding();
    }

    private final class BoardHolding extends ProcessHolding {

        BoardHolding() {
            super(loop::next, loop::over, BoardWork.this::commit, loop::fail);
        }

        @Override
        public HoldingStep tick(HoldingContext context) {
            if (bookings.cancelled(at, id(), generation)) {
                loop.fail(CookeryRefusal.STATION_CHANGED);
                hardRelease(
                    context,
                    io.github.izakyl.folkways.core.work.api.Disposition.END,
                    io.github.izakyl.folkways.core.work.api.PreemptReason.REVOKED);
                return new HoldingStep.Failed(CookeryRefusal.STATION_CHANGED);
            }
            return super.tick(context);
        }

        @Override
        protected HoldingStep idle(HoldingContext context) {
            return loop.failure().<HoldingStep>map(HoldingStep.Failed::new)
                .orElseGet(() -> loop.isFinished() ? new HoldingStep.Done() : new HoldingStep.Running());
        }
    }

    @Override
    public boolean turn(ServerLevel level) {
        return bookings.first(at, id());
    }

    @Override
    public WorkAction load(ServerLevel level, Worker who) {
        return new Put();
    }

    @Override
    public boolean outputReady(ServerLevel level) {
        return owned(level);
    }

    @Override
    public WorkAction take(ServerLevel level, Worker who) {
        return new Cut();
    }

    @Override
    public Outcome commit(ServerLevel level, Worker who) {
        return loop.isFinished() && bookings.valid(at, id(), generation) ? Outcome.done()
            : Outcome.failed(loop.failure().orElse(CookeryRefusal.STATION_CHANGED));
    }

    @Override
    public KitchenClosure closure() {
        return closure;
    }

    private boolean empty(ServerLevel level) {
        return ChoppingBoardWorkshops.board(level, at)
            .map(board -> board.getCurrentCutStack().isEmpty()).orElse(false);
    }

    private boolean owned(ServerLevel level) {
        return ChoppingBoardWorkshops.board(level, at).flatMap(board -> BoardOwnership.read(board, level))
            .filter(batch::equals).isPresent();
    }

    private abstract class Action implements WorkAction {

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
                    || who.held(KNIFE).isEmpty()) {
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
                loop.fail(CookeryRefusal.STATION_CHANGED);
                result = new KitchenDelivery.Result(false, broken instanceof ItemDrops.CaptureFailure captured
                    ? captured.items() : List.of());
            }
            if (!result.success() && !ItemStack.matches(board.getCurrentCutStack(), expected)) {
                return Outcome.failed(CookeryRefusal.NOTHING_TO_WORK_WITH);
            }
            if (!result.success()) {
                loop.fail(CookeryRefusal.STATION_CHANGED);
            }
            loop.loaded();
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
                    || !owned(level)) {
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
            if (board.getCurrentCutStack().isEmpty()) {
                loop.finished();
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
            return new Outcome.Done(List.of(), result.outputs(), loop.isFinished()
                ? Optional.of(new Xp(WaresContent.trade(), 1)) : Optional.empty());
        }
    }
}
