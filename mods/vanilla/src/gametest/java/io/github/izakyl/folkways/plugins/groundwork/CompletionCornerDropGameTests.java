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
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CompletionCornerDropGameTests {
    private static final BlockState DIRT = Blocks.DIRT.defaultBlockState();
    private static final ItemSpec MATERIAL = ItemSpec.of(ResourceLocation.withDefaultNamespace("dirt"));

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(CompletionCornerDropGameTests.class);
    }

    @GameTest(template = "empty_large", batch = "completion_corner_drop_native", timeoutTicks = 2000)
    public static void aRealRunnerDescendsOntoSharedLandingSupportBesideAnOpenStairShaft(GameTestHelper helper) {
        new Dispatch(helper, Obstruction.NONE).tick(1900);
    }

    @GameTest(template = "empty_large", batch = "completion_corner_drop_air", timeoutTicks = 800)
    public static void removingTheOnlySharedLandingSupportCannotReuseItsOldProjectionPermit(GameTestHelper helper) {
        new Dispatch(helper, Obstruction.AIR).tick(700);
    }

    @GameTest(template = "empty_large", batch = "completion_corner_drop_unknown", timeoutTicks = 800)
    public static void unknownLandingSupportCannotBecomeAFullCubeByKeepingAnOldMovementToken(GameTestHelper helper) {
        new Dispatch(helper, Obstruction.UNKNOWN).tick(700);
    }

    @GameTest(template = "empty_large", batch = "completion_corner_drop_fence", timeoutTicks = 800)
    public static void aNewNativeFenceAtTheLandingCannotAuthorizeTheRetainedCornerDescent(GameTestHelper helper) {
        new Dispatch(helper, Obstruction.FENCE).tick(700);
    }

    @GameTest(template = "empty_large", batch = "completion_corner_drop_no_projection", timeoutTicks = 800)
    public static void aValidFullLandingCertificateDoesNotPermitADescentWithoutActualSupportProjection(GameTestHelper helper) {
        new Dispatch(helper, Obstruction.NO_PROJECTION).tick(700);
    }

    private enum Obstruction { NONE, AIR, UNKNOWN, FENCE, NO_PROJECTION }

    private static final class Dispatch {
        private final GameTestHelper helper;
        private final ServerLevel level;
        private final BlockPos target;
        private final BlockPos stance;
        private final BlockPos anchor;
        private final ColonyData colony;
        private final ColonyLabor labor;
        private final ResidentEntity body;
        private final Asking asking;
        private final FillPlan plan;
        private final Chores chores;
        private final Method run;
        private final Method closeLabor;
        private final Object inline;
        private final Obstruction obstruction;
        private int stage;
        private double cornerX;
        private boolean actualCorner;
        private boolean initialAttempt;
        private int negativeTicks;
        private long blockedCommands;
        private long originalGeneration;
        private Object originalHead;
        private boolean closed;
        private ConstructionFixtureUrges fixtureUrges;

        Dispatch(GameTestHelper helper, Obstruction obstruction) {
            this.helper = helper;
            level = helper.getLevel();
            this.obstruction = obstruction;
            target = at(12, 1, 11);
            stance = at(11, 1, 11);
            anchor = at(1, 2, 1);
            for (int x = 0; x <= 23; x++) {
                for (int z = 0; z <= 23; z++) {
                    for (int y = 0; y <= 6; y++) level.setBlock(at(x, y, z),
                        y <= 1 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), 3);
                }
            }
            // The final three-cell corner translated up two: the adjacent old stair shaft remains
            // AIR one level below the landing. Its presence must not be disguised as a full plane.
            for (BlockPos cell : List.of(stance, target, at(11, 1, 12), at(10, 1, 11), at(10, 0, 11))) {
                level.setBlock(cell, Blocks.AIR.defaultBlockState(), 3);
            }
            // A negative may remove the landing without turning this disposable test into a long fall.
            level.setBlock(at(11, -1, 11), Blocks.STONE.defaultBlockState(), 3);
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
            try {
                fixtureUrges = ConstructionFixtureUrges.isolate(helper, colony);
                // The only position write. Even reaching the later corner is real native movement.
                body.setPos(Vec3.atBottomCenterOf(stance));
                body.setOnGround(true);
                body.pack().setItem(0, new ItemStack(Items.DIRT, 8));
                body.pack().setItem(1, new ItemStack(Items.DIAMOND_SHOVEL));
                body.pack().setItem(2, new ItemStack(Items.DIAMOND_AXE));
                body.pack().setItem(3, new ItemStack(Items.DIAMOND_PICKAXE));
                helper.assertTrue(level.addFreshEntity(body), "the actual native worker was added");
                colony.works().hold(PersonContent.ID, Held.Entity.of(body)).orElseThrow();
                body.joinColony(colony.colonyId());
                colony.works().entered(body);
                plan.refresh(new Survey(level, Set.of(target)).fill(), Set.of(Job.keyOf(target)), DIRT);
                plan.advance(Ground.Budget.unlimited());
                helper.assertTrue(plan.jobs().size() == 1 && plan.jobs().getFirst().footings().cells().size() == 1
                        && plan.jobs().getFirst().footings().cells().iterator().next().block(level).equals(stance),
                    "the real complete certificate selects the lower adjacent landing; stage=" + plan.stage()
                        + "; reason=" + plan.completionReason());
                originalGeneration = ((Number) completion().diagnostics().get("generation")).longValue();
                originalHead = head();
            } catch (RuntimeException | Error failure) {
                close();
                throw failure;
            }
        }

        void tick(int remaining) {
            try {
                helper.assertTrue(body.isAlive() && body.getHealth() == body.getMaxHealth()
                        && !body.isInWall() && level.noCollision(body, body.getBoundingBox()),
                    "ordinary native corner movement never hurts or embeds the actual worker");
                helper.assertTrue(remaining > 0, "the actual corner runner must progress; stage=" + stage
                    + "; body=" + body.position() + "; completion=" + plan.completionStatus()
                    + "; approach=" + completion().diagnostics().get("approachStatus"));
                if (stage < 3) {
                    walkToCorner();
                    helper.runAfterDelay(1, () -> tick(remaining - 1));
                    return;
                }
                if (obstruction != Obstruction.NONE) {
                    plan.advance();
                    if (obstruction == Obstruction.NO_PROJECTION) {
                        helper.assertTrue(completion().status().equals("READY")
                                && ((Number) completion().diagnostics().get("generation")).longValue() == originalGeneration
                                && head() == originalHead && level.getBlockState(stance.below()).is(Blocks.STONE),
                            "the unmodified-certificate negative preserves its complete generation, exact lower head and FULL landing");
                    }
                    helper.assertTrue(level.getBlockState(target).isAir() && body.pack().count(Items.DIRT) == 8
                            && !plan.complete(),
                        "the rejected old landing projection never writes the target or consumes material");
                    helper.assertTrue(((Number) completion().diagnostics().get("approachProjectedDropCommands")).longValue()
                            == blockedCommands,
                        "a stale or obstructed landing cannot keep issuing the old projected descent");
                    if (++negativeTicks == 15) {
                        close();
                        helper.succeed();
                        return;
                    }
                } else {
                    if (level.getBlockState(target).is(Blocks.DIRT) && plan.complete()) {
                        fixtureUrges.run(body, () -> invoke(run, labor, level, inline));
                        helper.assertTrue(actualCorner && initialAttempt,
                            "normal movement reached a real supported corner whose feet centre was unsupported");
                        helper.assertTrue(((Number) completion().diagnostics().get("approachProjectedDropCommands")).longValue() > 0,
                            "the actual pipeline used the shared-FULL projection descent");
                        helper.assertTrue(((Number) completion().diagnostics().get("generation")).longValue() == originalGeneration,
                            "the same original complete certificate reached the corner and executed its head without a replan escape");
                        helper.assertTrue(chores.spent().contains(Job.keyOf(target)) && body.pack().count(Items.DIRT) == 7,
                            "the original real request consumes exactly one material and settles");
                        helper.assertTrue(Reach.standingAt(body.position(), anchor),
                            "the original worker physically executes its final retreat before completion");
                        close();
                        helper.succeed();
                        return;
                    }
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
                    if (raw instanceof Map<?, ?> attempt && Boolean.TRUE.equals(attempt.get("standingAtStance"))
                            && Boolean.FALSE.equals(attempt.get("nativeBeforeStandable"))) {
                        if (!initialAttempt) System.out.println("CONSTRUCTION_CORNER_INITIAL actor=" + body.getUUID()
                            + " position=" + body.position() + " attempt=" + attempt);
                        initialAttempt = true;
                    }
                }
                helper.runAfterDelay(1, () -> tick(remaining - 1));
            } catch (RuntimeException | Error failure) {
                close();
                throw failure;
            }
        }

        private void walkToCorner() {
            Vec3 upper = Vec3.atBottomCenterOf(at(11, 2, 10));
            if (stage == 0) {
                body.getNavigation().moveTo(upper.x, upper.y, upper.z, 1.0D);
                if (body.onGround() && Math.abs(body.getY() - upper.y) < 0.02D
                        && body.getZ() < at(0, 0, 11).getZ()) {
                    body.getNavigation().stop();
                    stage = 1;
                }
                return;
            }
            Vec3 corner = Vec3.atLowerCornerOf(at(0, 0, 0)).add(
                obstruction == Obstruction.NO_PROJECTION ? 10.66D : 10.9720769D, 2.0D,
                obstruction == Obstruction.NO_PROJECTION ? 11.22D : 11.05267D);
            Vec3 wanted = stage == 1 ? new Vec3(corner.x, corner.y, upper.z)
                : new Vec3(cornerX, corner.y, corner.z);
            Vec3 delta = wanted.subtract(body.position());
            body.getNavigation().stop();
            double yaw = Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90.0D;
            double turn = Math.IEEEremainder(yaw - body.getYRot(), 360.0D);
            double speed = delta.horizontalDistanceSqr() < 0.04D ? 0.2D : 1.0D;
            body.getMoveControl().setWantedPosition(wanted.x, wanted.y, wanted.z, Math.abs(turn) > 1.0D ? 0.0D : speed);
            if (!body.onGround() || delta.horizontalDistanceSqr() > 0.0004D || Math.abs(delta.y) > 0.02D) return;
            if (stage == 1) {
                cornerX = body.getX();
                stage = 2;
                return;
            }
            body.getMoveControl().setWantedPosition(body.getX(), body.getY(), body.getZ(), 0.0D);
            helper.assertTrue(level.getBlockState(body.blockPosition().below()).isAir()
                    && level.getBlockCollisions(body, body.getBoundingBox().move(0.0D, -0.01D, 0.0D)).iterator().hasNext(),
                "the reached real body is supported by the upper edge, while its native feet centre has AIR below");
            boolean projection = body.getBoundingBox().intersects(new AABB(stance.below()).inflate(0.0D, 4.0D, 0.0D));
            helper.assertTrue(Reach.standingAt(body.position(), stance)
                    && (obstruction == Obstruction.NO_PROJECTION ? !projection : projection)
                    && level.getBlockState(at(10, 0, 11)).isAir(),
                "the exact real corner overlaps the lower known support beside an actual AIR catch-plane hole");
            actualCorner = true;
            stage = 3;
            if (obstruction == Obstruction.NONE) return;
            CompletionRuntime completion = completion();
            helper.assertTrue(completion.permits(target, DIRT, body, Ground.Budget.unlimited()) != CompletionRuntime.Permit.READY,
                "the initial overhang needs native correction and has not received a write permit");
            switch (obstruction) {
                case AIR -> level.setBlock(stance.below(), Blocks.AIR.defaultBlockState(), 3);
                case UNKNOWN -> level.setBlock(stance.below(), Blocks.HONEY_BLOCK.defaultBlockState(), 3);
                case FENCE -> level.setBlock(stance, Blocks.OAK_FENCE.defaultBlockState(), 3);
                case NO_PROJECTION -> helper.assertTrue(completion.status().equals("READY")
                        && ((Number) completion.diagnostics().get("generation")).longValue() == originalGeneration
                        && head() == originalHead
                        && level.getBlockState(stance.below()).is(Blocks.STONE),
                    "no dependency changed: a complete certificate and FULL native landing cannot replace missing actual projection");
                default -> throw new IllegalStateException("unexpected obstruction");
            }
            helper.assertTrue(!body.isInWall() && level.noCollision(body, body.getBoundingBox()),
                "the changed fixture does not place any obstacle inside the real body");
            helper.assertTrue(completion.permits(target, DIRT, body, Ground.Budget.unlimited()) != CompletionRuntime.Permit.READY,
                "a fresh attempt rejects removed, unknown or natively fenced landing support");
            blockedCommands = ((Number) completion.diagnostics().get("approachProjectedDropCommands")).longValue();
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

        private Object head() {
            try {
                Field field = CompletionRuntime.class.getDeclaredField("head");
                field.setAccessible(true);
                return field.get(completion());
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
