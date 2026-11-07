package io.github.izakyl.folkways.plugins.person.living;

import com.mojang.authlib.GameProfile;
import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.base.persist.Writer;
import io.github.izakyl.folkways.core.colony.api.Claimed;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.resident.api.Body;
import io.github.izakyl.folkways.core.testing.TestWorker;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.effect.perk.PerkPool;
import io.github.izakyl.folkways.front.engine.charter.Charter;
import io.github.izakyl.folkways.front.engine.charter.Charters;
import io.github.izakyl.folkways.front.engine.registry.FolkwaysItems;
import io.github.izakyl.folkways.front.engine.registry.FolkwaysPlayerAttachments;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.person.PersonContent;
import io.github.izakyl.folkways.rts.FolkwaysRtsMod;
import io.github.izakyl.folkways.rts.combat.command.ResidentCommands;
import io.github.izakyl.folkways.rts.combat.control.CombatSelectionOwnership;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysRtsMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RtsLivingGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(RtsLivingGameTests.class);
    }

    @GameTest(template = "empty")
    public static void selectionShortcutsAndCommandsDeferSleepButDoNotEraseRestDebt(GameTestHelper helper) {
        var level = helper.getLevel();
        Colony colony = Colonies.mint(level.getServer());
        Body body = PersonBody.RESIDENT.get().create(level);
        var player = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "rest_commander"));
        try {
            BlockPos feet = helper.absolutePos(new BlockPos(2, 1, 2));
            helper.setBlock(new BlockPos(2, 0, 2), Blocks.STONE);
            body.mob().moveTo(feet.getX() + .5, feet.getY(), feet.getZ() + .5);
            level.addFreshEntity(body.mob());
            colony.hold(PersonContent.ID, Claimed.Entity.of(body.mob())).orElseThrow();
            body.joinColony(colony.id());
            player.moveTo(body.mob().position());
            Charters.keep(colony, Charter.founded("rest control test", player.getUUID()));
            player.setData(FolkwaysPlayerAttachments.CURRENT_COLONY, Optional.of(colony.id()));
            player.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(FolkwaysItems.COLONY_BOOK.get()));
            WorldPos bed = WorldPos.of(level, feet);
            colony.keep(
                LivingContent.ID,
                Writer.of(new LivingPresence(colony).save())
                .children(
                "hunger",
                List.of(body.id()),
                id -> Writer.of().uuid("resident", id).integer("FoodLevel", 20).decimal("Saturation", 0).decimal("Exhaustion", 0).tag())
                .children(
                "rest",
                List.of(body.id()),
                id -> Writer.of().uuid("resident", id).decimal("Tired", Rest.SPENT).tag())
                .children(
                "claims",
                List.of(body.id()),
                id -> Writer.of().uuid("resident", id).blob("bed", bed.save()).tag())
                .tag());
            LivingPresence living = new LivingPresence(colony);
            Worker worker = worker(body, 0);
            var ownership = CombatSelectionOwnership.of(colony);
            helper.assertTrue(
                living.urges(worker, colony.view(level)).stream()
                .anyMatch(urge -> urge.id().equals(LivingPresence.SLEEP)),
                "a fully exhausted uncontrolled resident still requests its own bed");
            helper.assertTrue(
                ownership.select(player, List.of(body.id())), "the actual commander acquires"
                + " selection ownership");
            helper.assertFalse(
                living.urges(worker, colony.view(level)).stream()
                .anyMatch(urge -> urge.id().equals(LivingPresence.SLEEP)),
                "selection alone postpones sleep even without a movement order");
            ownership.bindGroup(player, 0);
            ownership.select(player, List.of());
            helper.assertFalse(
                living.urges(worker, colony.view(level)).stream()
                .anyMatch(urge -> urge.id().equals(LivingPresence.SLEEP)),
                "a retained shortcut continues postponing sleep after the visible selection"
                + " clears");
            ownership.recallGroup(player, 0);
            ownership.releaseSelected(player, List.of(body.id()));
            helper.assertTrue(
                living.urges(worker, colony.view(level)).stream()
                .anyMatch(urge -> urge.id().equals(LivingPresence.SLEEP)),
                "releasing the last references restores the unchanged sleep need");
            var hold = ResidentCommands.of(colony).hold(body, bed);
            helper.assertFalse(
                living.urges(worker, colony.view(level)).stream()
                .anyMatch(urge -> urge.id().equals(LivingPresence.SLEEP)),
                "a valid standalone manual order also postpones sleep");
            hold.close();
            Mood mood = new Mood();
            mood.step(level.getGameTime(), 20, living.alertnessOf(body.id()).orElseThrow(), 0, false, false);
            helper.assertValueEqual(
                mood.reasons.get("rest"),
                -10,
                "postponing sleep preserves exhaustion and its existing mood penalty instead of"
                + " granting free rest");
        } finally {
            body.mob().discard();
            player.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            colony.raze();
        }
        helper.succeed();
    }

    private static Worker worker(Body body, int stoic) {
        Container pack = new SimpleContainer(new ItemStack(Items.BREAD));
        return TestWorker.of(body).pack(pack).effects(() -> PerkPool.effects(Map.of(PerkPool.STOIC, stoic)));
    }
}
