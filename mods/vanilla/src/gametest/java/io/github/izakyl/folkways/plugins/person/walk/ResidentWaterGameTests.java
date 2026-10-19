package io.github.izakyl.folkways.plugins.person.walk;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.resident.Going;
import io.github.izakyl.folkways.core.api.terms.RefusalKind;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.engine.travel.Faring;
import io.github.izakyl.folkways.core.engine.travel.graph.Roamer;
import io.github.izakyl.folkways.core.engine.travel.graph.Roaming;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaMod;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ResidentWaterGameTests {
    private static final RefusalKind LOST = () -> "folkways.refusal.labor.no_way_there";

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) { event.register(ResidentWaterGameTests.class); }

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void aResidentActuallyWalksOutOfShallowWaterOntoAOneBlockBank(GameTestHelper helper) {
        leaveWater(helper, 1, false);
    }

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void aResidentActuallySwimsOutOfTwoDeepWaterOntoAOneBlockBank(GameTestHelper helper) {
        leaveWater(helper, 2, false);
    }

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void theColonyPlansAndTheResidentCompletesTheSameShallowWaterExit(GameTestHelper helper) {
        leaveWater(helper, 1, true);
    }

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void theColonyPlansAndTheResidentCompletesTheSameTwoDeepWaterExit(GameTestHelper helper) {
        leaveWater(helper, 2, true);
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void theColonyPlansFromDeepWaterWhileItsSizingResidentIsDryOnTheBank(GameTestHelper helper) {
        Rig rig = new Rig(helper, 2);
        rig.body.moveTo(Vec3.atBottomCenterOf(rig.bank));
        helper.startSequence().thenExecuteAfter(10, () -> {
            helper.assertTrue(!rig.body.isInWater() && rig.body.onGround(), "the sizing resident stands on dry land");
            WorldPos from = WorldPos.of(rig.level, rig.submergedStart);
            Roaming ways = new Roaming(rig.level);
            Faring route = ways.answered(known -> known.journey(rig.body.resident(), from, rig.goals()),
                List.of(new Roamer(PersonBody.ID, Walking.INSTANCE, rig.body)), List.of(), List.of(), rig.level.getGameTime());
            helper.assertTrue(route instanceof Faring.Yes,
                "the explicit underwater start is independent of the sizing resident's position and water state: " + route);
            rig.body.discard();
        }).thenSucceed();
    }

    private static void leaveWater(GameTestHelper helper, int depth, boolean planned) {
        Rig rig = new Rig(helper, depth);
        boolean[] travelling = {false};
        boolean[] arrived = {false};
        boolean[] enteredWater = {false};
        OnFoot movement = new OnFoot(1.0, LOST);
        helper.onEachTick(() -> {
            enteredWater[0] |= rig.body.isInWater();
            if (travelling[0] && !arrived[0]) {
                Going progress = movement.step(rig.level, rig.body, rig.goals());
                helper.assertFalse(progress instanceof Going.Failed,
                    "real water movement cannot stall before reaching its bank: " + rig.describe());
                arrived[0] = progress instanceof Going.Arrived;
            }
        });
        helper.startSequence().thenExecuteAfter(10, () -> {
            helper.assertTrue(enteredWater[0], "the actual resident first falls into real water");
            if (planned) {
                WorldPos from = WorldPos.of(rig.level, rig.body.blockPosition());
                Roaming ways = new Roaming(rig.level);
                Faring route = ways.answered(known -> known.journey(rig.body.resident(), from, rig.goals()),
                    List.of(new Roamer(PersonBody.ID, Walking.INSTANCE, rig.body)), List.of(), List.of(), rig.level.getGameTime());
                helper.assertTrue(route instanceof Faring.Yes,
                    "the actual colony path graph must accept an exit its resident can swim: " + route + "; " + rig.describe());
            }
            movement.setOut(rig.level, rig.body, rig.goals());
            travelling[0] = true;
        }).thenWaitUntil(() -> helper.assertTrue(arrived[0] && !rig.body.isInWater() && rig.body.onGround(),
            "the actual resident reaches and stands on the dry bank: " + rig.describe()))
            .thenExecute(() -> {
                movement.release(rig.level, rig.body);
                helper.assertTrue(rig.body.walkDist > 0.5F, "native physical travel, rather than teleporting, reaches the bank");
                helper.assertTrue(rig.body.getHealth() == rig.body.getMaxHealth(), "the short real swim does not drown the resident");
                rig.body.discard();
            }).thenSucceed();
    }

    private static final class Rig {
        final ServerLevel level;
        final ResidentEntity body;
        final BlockPos bank;
        final BlockPos submergedStart;
        Rig(GameTestHelper helper, int depth) {
            level = helper.getLevel();
            BlockPos base = helper.absolutePos(new BlockPos(0, 1, 0));
            for (BlockPos at : BlockPos.betweenClosed(base, base.offset(9, depth + 4, 4))) {
                level.setBlock(at, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
            for (BlockPos at : BlockPos.betweenClosed(base, base.offset(9, 0, 4))) {
                level.setBlock(at, Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
            for (int y = 1; y <= depth; y++) for (int x = 0; x <= 9; x++) for (int z = 0; z <= 4; z++) {
                BlockPos at = base.offset(x, y, z);
                boolean pond = x >= 1 && x <= 5 && z >= 1 && z <= 3;
                level.setBlock(at, (pond ? Blocks.WATER : Blocks.STONE).defaultBlockState(), Block.UPDATE_CLIENTS);
            }
            bank = base.offset(7, depth + 1, 2);
            submergedStart = base.offset(2, 1, 2);
            body = PersonBody.RESIDENT.get().create(level);
            body.moveTo(Vec3.atBottomCenterOf(submergedStart));
            level.addFreshEntity(body);
            Walking.INSTANCE.fit(body);
        }
        Set<WorldPos> goals() { return Set.of(WorldPos.of(level, bank)); }
        String describe() {
            var path = body.getNavigation().getPath();
            return "feet=" + body.position() + ", bank=" + bank + ", water=" + body.isInWater()
                + ", ground=" + body.onGround() + ", nativeFloat=" + body.getNavigation().canFloat()
                + ", graphFloat=" + Walking.INSTANCE.newEvaluator(io.github.izakyl.folkways.core.api.resident.Search.from(body.blockPosition())).canFloat()
                + ", path=" + (path == null ? "none" : path.getNextNodeIndex() + "/" + path.getNodeCount());
        }
    }
}
