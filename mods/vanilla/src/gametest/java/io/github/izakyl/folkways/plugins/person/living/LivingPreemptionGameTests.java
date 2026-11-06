package io.github.izakyl.folkways.plugins.person.living;

import com.mojang.authlib.GameProfile;
import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.Held;
import io.github.izakyl.folkways.core.engine.inventory.InventoryObservations;
import io.github.izakyl.folkways.core.testing.TestWorker;
import io.github.izakyl.folkways.effect.perk.PerkPool;
import io.github.izakyl.folkways.base.persist.Writer;
import io.github.izakyl.folkways.core.api.resident.body.Body;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Urge;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.WorkExertion;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaMod;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.person.PersonContent;
import io.github.izakyl.folkways.front.engine.charter.Charter;
import io.github.izakyl.folkways.front.engine.charter.Charters;
import io.github.izakyl.folkways.front.engine.registry.FolkwaysItems;
import io.github.izakyl.folkways.front.engine.registry.FolkwaysPlayerAttachments;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.common.util.FakePlayerFactory;

@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class LivingPreemptionGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(LivingPreemptionGameTests.class);
    }

    @GameTest(template = "empty")
    public static void abstractEffortSeparatesFoodFromTimeAndPersistsFractions(GameTestHelper helper) {
        Colony colony = Colonies.mint(helper.getLevel().getServer());
        Body body = PersonBody.RESIDENT.get().create(helper.getLevel());
        body.mob().moveTo(helper.absolutePos(new BlockPos(1, 2, 1)).getCenter());
        helper.getLevel().addFreshEntity(body.mob());
        colony.hold(PersonContent.ID, Held.Entity.of(body.mob())).orElseThrow();
        try {
            WorkExertion.performed(body.mob(), 2, 1, 3);
            colony.service(LivingContent.ID, LivingPresence.class).orElseThrow()
                .refresh(helper.getLevel().getServer());
            var saved = colony.kept(LivingContent.ID);
            helper.assertTrue(Math.abs(saved.getList("hunger", 10).getCompound(0).getFloat("Exhaustion")
                - 0.19F) < 0.00001F, "food consumes standard work plus distance, independent of labor ticks");
            var rest = saved.getList("rest", 10).getCompound(0);
            helper.assertTrue(
                Math.abs(rest.getFloat("Tired") - 0.9F) < 0.00001F,
                "settled effort adds distance fatigue once; elapsed time belongs exclusively to"
                + " the live awake clock");
            Rest loaded = new Rest();
            loaded.load(io.github.izakyl.folkways.base.persist.Reader.of(rest));
            helper.assertTrue(Math.abs(loaded.save().getFloat("Tired") - rest.getFloat("Tired")) < 0.00001F,
                "fractional fatigue survives a save/load");
            loaded.load(io.github.izakyl.folkways.base.persist.Reader.of(
                Writer.of().integer("Tired", 800).tag()));
            helper.assertValueEqual(loaded.tiredness(), 800, "old integer fatigue saves remain readable");
        } finally {
            body.mob().discard();
            colony.raze();
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void ordinaryNeedsPreemptButStoicNeedsWaitUntilUrgent(GameTestHelper helper) {
        Colony colony = Colonies.mint(helper.getLevel().getServer());
        Body body = PersonBody.RESIDENT.get().create(helper.getLevel());
        UUID resident = body.resident().id();
        WorldPos bed = WorldPos.of(helper.getLevel(), helper.absolutePos(BlockPos.ZERO));
        try {
            body.pack().setItem(0, new ItemStack(Items.BREAD));
            InventoryObservations.publish(colony, helper.getLevel(), Map.of(resident, body));
            for (boolean urgent : List.of(false, true)) {
                colony.keep(LivingContent.ID, Writer.of()
                    .children("hunger", List.of(resident), id -> Writer.of().uuid("resident", id)
                        .integer("FoodLevel", urgent ? 4 : 14).tag())
                    .children("rest", List.of(resident), id -> Writer.of().uuid("resident", id)
                        .integer("Tired", urgent ? Rest.SPENT : Rest.SLEEPY).tag())
                    .children("claims", List.of(resident), id -> Writer.of().uuid("resident", id)
                        .blob("bed", bed.save()).tag()).tag());
                for (int stoic : List.of(0, 1)) {
                    LivingPresence living = new LivingPresence(colony);
                    List<Urge> offers = living.urges(worker(body, stoic), colony.view(helper.getLevel()));
                    for (var id : List.of(LivingPresence.EAT, LivingPresence.SLEEP)) {
                        Urge urge = offers.stream().filter(offer -> offer.id().equals(id)).findFirst().orElseThrow();
                        helper.assertTrue(urge.preempts() == (stoic == 0 || urgent),
                            id + " preemption policy for stoic=" + stoic + ", urgent=" + urgent);
                        helper.assertTrue(urge.weight() == (urgent ? Urge.NOW : Urge.SPARE),
                            "stoic affects preemption permission, not the weight of " + id);
                    }
                }
            }
        } finally {
            body.mob().discard();
            colony.raze();
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void onlyConsecutiveLoadedAwakeTicksAddTimeFatigue(GameTestHelper helper) {
        AwakeTime clock = new AwakeTime();
        Rest rest = new Rest();
        helper.assertValueEqual(clock.observe(100, true), 0, "the first loaded observation has no"
            + " elapsed interval");
        for (long tick = 101; tick <= 130; tick++) {
            rest.awake(clock.observe(tick, true));
        }
        helper.assertValueEqual(rest.tiredness(), 1, "thirty actual awake ticks charge one"
            + " time-fatigue point");
        helper.assertValueEqual(
            clock.observe(130, true), 0, "duplicate observation in the same tick never double"
            + " charges");
        helper.assertValueEqual(clock.observe(131, false), 0, "sleeping does not count as awake"
            + " time");
        helper.assertValueEqual(
            clock.observe(5000, true), 0, "an unloaded or offline gap is not retroactive awake"
            + " time");
        helper.assertValueEqual(
            new AwakeTime().observe(5001, true), 0, "a reloaded clock starts without catch-up"
            + " debt");
        Rest day = new Rest();
        day.awake(24000);
        helper.assertValueEqual(
            day.tiredness(), Rest.SLEEPY, "one loaded awake day reaches the existing sleepy"
            + " threshold");
        helper.succeed();
    }

    private static Worker worker(Body body, int stoic) {
        return TestWorker.of(body).effects(() -> PerkPool.effects(Map.of(PerkPool.STOIC, stoic)));
    }
}
