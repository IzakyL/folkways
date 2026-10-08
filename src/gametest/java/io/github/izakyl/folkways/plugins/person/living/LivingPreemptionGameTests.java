package io.github.izakyl.folkways.plugins.person.living;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.Held;
import io.github.izakyl.folkways.core.api.perk.PerkPool;
import io.github.izakyl.folkways.core.api.persist.Writer;
import io.github.izakyl.folkways.core.api.resident.Resident;
import io.github.izakyl.folkways.core.api.resident.body.Body;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.Placement;
import io.github.izakyl.folkways.core.api.work.ToolNeed;
import io.github.izakyl.folkways.core.api.work.Urge;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.WorkExertion;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.person.PersonContent;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
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
        colony.hold(PersonContent.ID, Held.Entity.of(body.mob())).orElseThrow();
        body.joinColony(colony.id());
        colony.entered(body);
        try {
            WorkExertion.performed(body.mob(), 2, 1, 3);
            colony.service(LivingContent.ID, LivingPresence.class).orElseThrow()
                .refresh(helper.getLevel().getServer());
            var saved = colony.kept(LivingContent.ID);
            helper.assertTrue(Math.abs(saved.getList("hunger", 10).getCompound(0).getFloat("Exhaustion")
                - 0.4F) < 0.00001F, "food consumes standard work plus distance, independent of labor ticks");
            var rest = saved.getList("rest", 10).getCompound(0);
            helper.assertTrue(Math.abs(rest.getFloat("Tired") - (1.0F / 15 + 0.3F)) < 0.00001F,
                "fatigue consumes elapsed labor ticks plus distance, preserving fractional effort");
            Rest loaded = new Rest();
            loaded.load(io.github.izakyl.folkways.core.api.persist.Reader.of(rest));
            helper.assertTrue(Math.abs(loaded.save().getFloat("Tired") - rest.getFloat("Tired")) < 0.00001F,
                "fractional fatigue survives a save/load");
            loaded.load(io.github.izakyl.folkways.core.api.persist.Reader.of(
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

    private static Worker worker(Body body, int stoic) {
        Container pack = new SimpleContainer(new ItemStack(Items.BREAD));
        return new Worker() {
            public Resident resident() { return body.resident(); }
            public Mob body() { return body.mob(); }
            public Container pack() { return pack; }
            public List<Store> within() { return List.of(); }
            public ItemStack held(ToolNeed need) { return ItemStack.EMPTY; }
            public void spill(ItemStack stack) { throw new AssertionError("offering an urge must not spill items"); }
            public int rankOf(String perk) { return PerkPool.STOIC.equals(perk) ? stoic : 0; }
            public List<Node> route() { return List.of(); }
            public Placement placement() { return Placement.NOWHERE; }
        };
    }
}
