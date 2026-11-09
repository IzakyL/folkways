package io.github.izakyl.folkways.vanilla.person.walk;

import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.core.colony.api.Claimed;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.runtime.LaborFixture;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.vanilla.person.PersonBody;
import io.github.izakyl.folkways.vanilla.person.PersonContent;
import io.github.izakyl.folkways.vanilla.FolkwaysVanillaMod;
import io.github.izakyl.folkways.core.rts.combat.command.ResidentCommands;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways_vanilla")
@PrefixGameTestTemplate(false)
public final class DrowningCommandGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(DrowningCommandGameTests.class);
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 400)
    public static void drowningPreemptsEvenARetreatCommand(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos base = helper.absolutePos(new BlockPos(4, 1, 4));
        for (int x = 0; x <= 6; x++) {
            for (int z = 0; z <= 4; z++) {
                for (int y = 0; y <= 3; y++) {
                    boolean wall = y == 0 || x == 0 || x == 6 || z == 0 || z == 4;
                    level.setBlock(base.offset(x, y, z),
                        (wall ? Blocks.STONE : y <= 2 ? Blocks.WATER : Blocks.AIR).defaultBlockState(), 2);
                }
            }
        }
        level.setBlock(base.offset(4, 3, 2), Blocks.STONE.defaultBlockState(), 2);
        level.setBlock(base.offset(5, 3, 2), Blocks.STONE.defaultBlockState(), 2);
        var body = PersonBody.RESIDENT.get().create(level);
        body.moveTo(Vec3.atBottomCenterOf(base.offset(5, 4, 2)));
        level.addFreshEntity(body);
        var colony = Colonies.mint(level.getServer());
        colony.hold(PersonContent.ID, Claimed.Entity.of(body)).orElseThrow();
        body.joinColony(colony.id());
        colony.entered(body);
        var command = ResidentCommands.of(colony).retreat(body, WorldPos.of(level, base.offset(4, 4, 2)));
        LaborFixture labor = new LaborFixture(colony, level);
        boolean[] closed = {false};
        boolean[] escaped = {false};
        helper.onEachTick(() -> {
            if (!closed[0]) {
                labor.tick();
                escaped[0] |= body.doing().filter(doing ->
                    doing.toward().orElse(doing.what()).equals(BreathingPresence.ID)).isPresent();
            }
        });
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(command.status() == ResidentCommands.Status.HOLDING
                && body.doing().isPresent(),
                "the real RTS command starts"))
            .thenExecute(() -> {
                helper.assertTrue(ResidentCommands.of(colony).current(body.id()).orElseThrow() == command,
                    "the live retreat order owns this resident");
                body.moveTo(Vec3.atBottomCenterOf(base.offset(2, 1, 2)));
                body.setAirSupply(100);
            }).thenWaitUntil(() -> helper.assertTrue(escaped[0], "escape preempts the CONTROL retreat"))
            .thenExecuteAfter(120, () -> {
                helper.assertTrue(!body.isUnderWater() && body.getHealth() == body.getMaxHealth(),
                    "the commanded resident reaches air unharmed");
                closed[0] = true;
                labor.close();
                command.close();
                body.discard();
                colony.raze();
            }).thenSucceed();
    }
}
