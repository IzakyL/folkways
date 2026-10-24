package io.github.izakyl.folkways.plugins.groundwork;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.colony.Asking;
import io.github.izakyl.folkways.core.api.colony.Held;
import io.github.izakyl.folkways.core.api.ground.Ground;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.Reach;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.engine.colony.ColonyData;
import io.github.izakyl.folkways.core.engine.colony.ColonyRegistry;
import io.github.izakyl.folkways.core.engine.colony.ColonyWays;
import io.github.izakyl.folkways.core.engine.labor.ColonyLabor;
import io.github.izakyl.folkways.front.api.PastDay;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaMod;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.person.PersonContent;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CompletionApproachGameTests {
    private static final BlockState DIRT = Blocks.DIRT.defaultBlockState();
    private static final ItemSpec MATERIAL = ItemSpec.of(ResourceLocation.withDefaultNamespace("dirt"));

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(CompletionApproachGameTests.class);
    }

    @GameTest(template = "empty_large", batch = "completion_approach_up_step", timeoutTicks = 1600)
    public static void aRealRunnerKeepsTheProvedStepWhileItsAirborneFeetCentreHasNoSupport(GameTestHelper helper) {
        new Dispatch(helper, false).tick(1500);
    }

    @GameTest(template = "empty_large", batch = "completion_approach_fence", timeoutTicks = 1800)
    public static void aFencedLandingCannotConsumeMaterialOrBypassNativeCollisionAndRecoversWhenOpened(GameTestHelper helper) {
        new Dispatch(helper, true).tick(1700);
    }

    private static final class Dispatch {
        private final GameTestHelper helper;
        private final ServerLevel level;
        private final BlockPos target;
        private final BlockPos stance;
        private final BlockPos anchor;
        private final List<BlockPos> neighbours;
        private final ColonyData colony;
        private final ColonyLabor labor;
        private final ResidentEntity body;
        private final Asking asking;
        private final FillPlan plan;
        private final Chores chores;
        private final Method run;
        private final Method closeLabor;
        private final Object inline;
        private final boolean fenced;
        private final Vec3 start;
        private int elapsed;
        private boolean initialAttempt;
        private boolean unsupportedAirborne;
        private boolean closed;
        private ConstructionFixtureUrges fixtureUrges;

        Dispatch(GameTestHelper helper, boolean fenced) {
            this.helper = helper;
            level = helper.getLevel();
            this.fenced = fenced;
            target = at(10, 1, 10);
            stance = at(9, 2, 10);
            anchor = at(1, 1, 1);
            neighbours = List.of(at(9, 1, 10), at(11, 1, 10), at(10, 1, 9), at(10, 1, 11));
            for (int x = 0; x <= 23; x++) {
                for (int z = 0; z <= 23; z++) {
                    for (int y = 0; y <= 6; y++) level.setBlock(at(x, y, z),
                        y == 0 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), 3);
                }
            }
            // The old four isolated wall blocks left closer low-floor stances, which the search
            // correctly prefers for its native arrival margin. A five-wide plateau reproduces the
            // late real site's filled surroundings and makes the adjacent upper stance the first
            // complete safe choice without constraining or substituting any production decision.
            for (int x = 8; x <= 12; x++) {
                for (int z = 8; z <= 12; z++) {
                    BlockPos cell = at(x, 1, z);
                    if (!cell.equals(target)) level.setBlock(cell, DIRT, 3);
                }
            }
            try {
                run = java.util.Arrays.stream(ColonyLabor.class.getMethods())
                    .filter(method -> method.getName().equals("run") && method.getParameterCount() == 2)
                    .findFirst().orElseThrow();
                Field field = run.getParameterTypes()[1].getDeclaredField("INLINE");
                field.setAccessible(true);
                inline = field.get(null);
                closeLabor = ColonyLabor.class.getDeclaredMethod("close");
                closeLabor.setAccessible(true);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException(failure);
            }
            colony = ColonyRegistry.get(level.getServer()).mint(level.getServer());
            labor = new ColonyLabor(colony, level.dimension());
            body = PersonBody.RESIDENT.get().create(level);
            asking = new Asking(colony.works(), GroundworkContent.ID, () -> { });
            plan = new FillPlan(level, UUID.randomUUID(), colony.colonyId(), Set.of(target),
                () -> List.of(anchor), () -> { });
            chores = new Chores(UUID.randomUUID(), "fill", (at, chore) -> new FillNode(chore, MATERIAL, plan),
                () -> { }, new PastDay());
            start = Vec3.atLowerCornerOf(at(0, 0, 0)).add(10.300000011920929D, 1.0D, 10.699999988079071D);
            try {
                fixtureUrges = ConstructionFixtureUrges.isolate(helper, colony);
                // The only position write: even initial certificate capture uses the real pit-bottom
                // actor. All later motion, including the first up-step, is native AI/BodyRunner.
                body.setPos(start);
                body.setYRot(-75.0F);
                body.setOnGround(true);
                body.pack().setItem(0, new ItemStack(Items.DIRT, 8));
                body.pack().setItem(1, new ItemStack(Items.DIAMOND_SHOVEL));
                body.pack().setItem(2, new ItemStack(Items.DIAMOND_AXE));
                body.pack().setItem(3, new ItemStack(Items.DIAMOND_PICKAXE));
                helper.assertTrue(level.addFreshEntity(body), "the actual worker was added to the server level");
                colony.works().hold(PersonContent.ID, Held.Entity.of(body)).orElseThrow();
                body.joinColony(colony.colonyId());
                colony.works().entered(body);
                plan.refresh(new Survey(level, Set.of(target)).fill(), Set.of(Job.keyOf(target)), DIRT);
                plan.advance(Ground.Budget.unlimited());
                List<Job> heads = plan.jobs();
                helper.assertTrue(heads.size() == 1 && heads.getFirst().footings().cells().size() == 1
                        && heads.getFirst().footings().cells().iterator().next().block(level).equals(stance),
                    "the complete certificate uses the actual failed run's adjacent one-block-higher stance; heads="
                        + heads.stream().map(job -> job.footings().cells()).toList()
                        + "; completion=" + plan.completionStatus() + "; reason=" + plan.completionReason());
                helper.assertTrue(body.blockPosition().equals(target) && Reach.standingAt(body.position(), stance),
                    "the real pit-bottom pose is accepted by Core's 0.9/1 arrival tolerance");
                helper.assertTrue(level.noCollision(body, body.getBoundingBox()) && !body.isInWall(),
                    "the initial native body is not embedded in any of the four walls");
                if (fenced) neighbours.forEach(cell -> level.setBlock(cell.above(), Blocks.OAK_FENCE.defaultBlockState(), 3));
            } catch (RuntimeException | Error failure) {
                close();
                throw failure;
            }
        }

        void tick(int remaining) {
            try {
                helper.assertTrue(body.isAlive() && body.getHealth() == body.getMaxHealth()
                        && !body.isInWall() && level.noCollision(body, body.getBoundingBox()),
                    "normal approach physics preserves full health and never embeds the worker");
                elapsed++;
                if (fenced && elapsed <= 60) {
                    helper.assertTrue(level.getBlockState(target).isAir() && body.pack().count(Items.DIRT) == 8
                            && !plan.complete(),
                        "closed native fence exits preserve the open pit, all material, and the retreat obligation");
                }
                if (fenced && elapsed == 61) neighbours.forEach(cell -> level.setBlock(cell.above(), Blocks.AIR.defaultBlockState(), 3));
                if (!body.onGround() && body.getY() > start.y + 0.2D
                        && level.getBlockState(body.blockPosition().below()).isAir()) unsupportedAirborne = true;
                if (level.getBlockState(target).is(Blocks.DIRT) && plan.complete()) {
                    fixtureUrges.run(body, () -> invoke(run, labor, level, inline));
                    helper.assertTrue(initialAttempt && unsupportedAirborne,
                        "a real commit saw the initial pit-bottom arrival, followed by a genuine unsupported-centre jump");
                    helper.assertTrue(chores.spent().contains(Job.keyOf(target)) && body.pack().count(Items.DIRT) == 7,
                        "the original real request settles and consumes exactly one material");
                    helper.assertTrue(Reach.standingAt(body.position(), anchor) && body.position().distanceToSqr(start) > 1.0D,
                        "the same actual builder performs the final retreat before completion");
                    helper.assertTrue(((Number) completion().diagnostics().get("approachStepCommands")).longValue() > 0,
                        "the bounded native up-step correction actually executed");
                    close();
                    helper.succeed();
                    return;
                }
                helper.assertTrue(remaining > 0, "the real one-block up-step must finish; body=" + body.position()
                    + "; completion=" + plan.completionStatus() + "; reason=" + plan.completionReason()
                    + "; approach=" + completion().diagnostics().get("approachStatus"));
                plan.refresh(new Survey(level, Set.of(target)).fill(), Set.of(Job.keyOf(target)), DIRT);
                plan.advance();
                chores.settle(plan.requests());
                List<Grown> goals = new ArrayList<>();
                chores.goals(level, goals);
                goals.addAll(plan.steppingAside());
                asking.offer(Map.of(level.dimension(), goals));
                ColonyWays.advance(colony, level);
                fixtureUrges.run(body, () -> invoke(run, labor, level, inline));
                Object raw = completion().diagnostics().get("lastAttempt");
                if (raw instanceof Map<?, ?> attempt && target.toShortString().equals(attempt.get("actualFeet"))
                        && Boolean.TRUE.equals(attempt.get("standingAtStance"))) {
                    if (!initialAttempt) System.out.println("CONSTRUCTION_UP_STEP_INITIAL actor=" + body.getUUID()
                        + " position=" + body.position() + " attempt=" + attempt);
                    initialAttempt = true;
                }
                helper.runAfterDelay(1, () -> tick(remaining - 1));
            } catch (RuntimeException | Error failure) {
                close();
                throw failure;
            }
        }

        private CompletionRuntime completion() {
            try {
                Field field = FillPlan.class.getDeclaredField("completion");
                field.setAccessible(true);
                return (CompletionRuntime) field.get(plan);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException(failure);
            }
        }

        private BlockPos at(int x, int y, int z) {
            return helper.absolutePos(new BlockPos(x, y, z));
        }

        private void close() {
            if (closed) return;
            closed = true;
            try {
                invoke(closeLabor, labor);
            } finally {
                try {
                    if (fixtureUrges != null) fixtureUrges.close();
                } finally {
                    plan.close();
                    body.discard();
                    colony.works().raze();
                    ColonyWays.forget(colony.colonyId());
                }
            }
        }

        private static void invoke(Method method, Object target, Object... arguments) {
            try {
                method.invoke(target, arguments);
            } catch (InvocationTargetException failure) {
                if (failure.getCause() instanceof RuntimeException broken) throw broken;
                if (failure.getCause() instanceof Error broken) throw broken;
                throw new IllegalStateException(failure.getCause());
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException(failure);
            }
        }
    }
}
