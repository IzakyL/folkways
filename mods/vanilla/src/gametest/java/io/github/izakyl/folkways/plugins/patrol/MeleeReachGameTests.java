package io.github.izakyl.folkways.plugins.patrol;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.colony.Held;
import io.github.izakyl.folkways.core.api.terms.Reach;
import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
import io.github.izakyl.folkways.core.api.work.WorkProgress;
import io.github.izakyl.folkways.core.engine.labor.LaborFixture;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaMod;
import io.github.izakyl.folkways.plugins.combat.command.ResidentCommands;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.person.PersonContent;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class MeleeReachGameTests {
    @SubscribeEvent public static void register(RegisterGameTestsEvent event) { event.register(MeleeReachGameTests.class); }

    @GameTest(template = "empty_large", timeoutTicks = 220)
    public static void aGuardAtTheOldArrivalBoundaryWalksCloserAndKeepsActuallyHitting(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        for (int x = 0; x < 20; x++) {
            for (int z = 0; z < 12; z++) { helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE); }
        }
        var colony = Colonies.mint(level.getServer());
        var guard = PersonBody.RESIDENT.get().create(level);
        var enemy = EntityType.ZOMBIE.create(level);
        BlockPos origin = helper.absolutePos(new BlockPos(0, 1, 0));
        // These fractions reproduce the real endurance failure after translating its world origin.
        guard.moveTo(origin.getX() + 7.81659611336061, origin.getY(), origin.getZ() + 5.50324124898353);
        enemy.moveTo(origin.getX() + 12.9597065223133, origin.getY(), origin.getZ() + 6.23120922598678);
        enemy.setNoAi(true); enemy.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.CARVED_PUMPKIN));
        enemy.getAttribute(Attributes.MAX_HEALTH).setBaseValue(60); enemy.setHealth(60);
        guard.pack().insert(new ItemStack(Items.IRON_SWORD));
        level.addFreshEntity(guard);
        colony.hold(PersonContent.ID, Held.Entity.of(guard)).orElseThrow(); guard.joinColony(colony.id());
        level.addFreshEntity(enemy);
        BlockPos oldStance = helper.absolutePos(new BlockPos(8, 1, 5));
        helper.assertTrue(Reach.standingAt(guard.position(), oldStance)
                && Reach.workableCells(level, enemy.blockPosition()).contains(oldStance),
            "the old block-based melee stance already considered this real body to have arrived");
        helper.assertTrue(guard.distanceTo(enemy) > 4.5 + enemy.getBbWidth(),
            "the actual 5.194-block body-to-enemy distance still exceeds the unchanged native melee limit");
        double startX = guard.getX();
        var commands = ResidentCommands.of(colony);
        var order = commands.guard(guard, WorldSpaces.at(guard), new Vec3(1, 0, 0));
        LaborFixture simulation = new LaborFixture(colony, level);
        int[] completions = {0};
        Consumer<WorkProgress> progress = event -> {
            if (event.colony().equals(colony.id()) && event.node() instanceof StrikeNode
                    && event.step() == WorkProgress.Step.FINISHED) { completions[0]++; }
        };
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, WorkProgress.class, progress);
        AtomicBoolean closed = new AtomicBoolean();
        Runnable close = () -> {
            if (!closed.compareAndSet(false, true)) { return; }
            NeoForge.EVENT_BUS.unregister(progress); simulation.close(); guard.discard(); enemy.discard(); colony.raze();
        };
        helper.testInfo.addListener(new GameTestListener() {
            public void testStructureLoaded(GameTestInfo info) { }
            public void testPassed(GameTestInfo info, GameTestRunner runner) { close.run(); }
            public void testFailed(GameTestInfo info, GameTestRunner runner) { close.run(); }
            public void testAddedForRerun(GameTestInfo original, GameTestInfo rerun, GameTestRunner runner) { }
        });
        helper.onEachTick(() -> {
            if (closed.get()) { return; }
            simulation.tick();
            helper.assertTrue(completions[0] <= 12,
                "the runner must make finite attacks instead of endlessly completing refused strikes; completed=" + completions[0]);
        });
        helper.startSequence()
            .thenWaitUntil(() -> helper.assertTrue(enemy.getHealth() < enemy.getMaxHealth(),
                "the normal guard runner walks closer and delivers real damage; body=" + guard.position()
                    + ", distance=" + guard.distanceTo(enemy) + ", completed=" + completions[0]))
            .thenIdle(30)
            .thenExecute(() -> {
                helper.assertTrue(guard.getX() > startX + .5, "the real body advances past the old prematurely arrived position");
                helper.assertTrue(enemy.getHealth() <= enemy.getMaxHealth() - 9, "successive native strikes continue making progress at the safe stance");
                helper.assertTrue(enemy.getLastDamageSource() != null && enemy.getLastDamageSource().getEntity() == guard,
                    "damage comes from this guard's native melee attacks");
                int count = guard.pack().count(Items.IRON_SWORD)
                    + (guard.hasWeaponSession() && guard.getMainHandItem().is(Items.IRON_SWORD) ? guard.getMainHandItem().getCount() : 0);
                int wear = guard.pack().contents().stream().filter(stack -> stack.is(Items.IRON_SWORD)).mapToInt(ItemStack::getDamageValue).sum()
                    + (guard.hasWeaponSession() && guard.getMainHandItem().is(Items.IRON_SWORD) ? guard.getMainHandItem().getDamageValue() : 0);
                helper.assertValueEqual(count, 1, "normal runner ownership retains the unique real sword");
                helper.assertTrue(wear > 0, "native attacks wear that actual sword");
                helper.assertTrue(commands.current(guard.id()).orElseThrow() == order, "the same guard order remains in control");
            }).thenSucceed();
    }
}
