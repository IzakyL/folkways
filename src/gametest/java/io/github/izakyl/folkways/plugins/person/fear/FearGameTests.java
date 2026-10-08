package io.github.izakyl.folkways.plugins.person.fear;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.resident.Resident;
import io.github.izakyl.folkways.core.api.resident.body.Body;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.Placement;
import io.github.izakyl.folkways.core.api.work.ToolNeed;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class FearGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(FearGameTests.class);
    }

    @GameTest(template = "empty")
    public static void aCampfireBurnSendsTheResidentAway(GameTestHelper helper) {
        fleesAfterABurn(helper, PersonBody.RESIDENT.get().create(helper.getLevel()));
    }

    private static void fleesAfterABurn(GameTestHelper helper, Body body) {
        Colony colony = Colonies.mint(helper.getLevel().getServer());
        BlockPos at = helper.absolutePos(new BlockPos(1, 1, 1));
        body.mob().moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        helper.getLevel().addFreshEntity(body.mob());
        try {
            FearPresence fear = new FearPresence();
            helper.assertTrue(fear.urges(worker(body), colony.view(helper.getLevel())).isEmpty(),
                "an unhurt " + body.kind().id() + " has nothing to flee");
            body.mob().hurt(helper.getLevel().damageSources().campfire(), 1.0F);
            helper.assertTrue(fear.urges(worker(body), colony.view(helper.getLevel())).stream()
                    .anyMatch(urge -> urge.id().equals(FearPresence.FLEE)),
                "a campfire burn must make " + body.kind().id() + " flee");
        } finally {
            body.mob().discard();
            colony.raze();
        }
        helper.succeed();
    }

    private static Worker worker(Body body) {
        Container pack = new SimpleContainer(1);
        return new Worker() {
            public Resident resident() { return body.resident(); }
            public Mob body() { return body.mob(); }
            public Container pack() { return pack; }
            public List<Store> within() { return List.of(); }
            public ItemStack held(ToolNeed need) { return ItemStack.EMPTY; }
            public void spill(ItemStack stack) { throw new AssertionError("offering an urge must not spill items"); }
            public int rankOf(String perk) { return 0; }
            public List<Node> route() { return List.of(); }
            public Placement placement() { return Placement.NOWHERE; }
        };
    }
}
