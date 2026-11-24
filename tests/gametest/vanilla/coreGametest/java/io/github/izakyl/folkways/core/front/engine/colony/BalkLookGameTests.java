package io.github.izakyl.folkways.core.front.engine.colony;

import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.resident.api.Balk;
import io.github.izakyl.folkways.core.resident.api.Body;
import io.github.izakyl.folkways.core.resident.api.Doing;
import io.github.izakyl.folkways.core.resident.api.Doings;
import io.github.izakyl.folkways.core.card.api.notice.Line;
import io.github.izakyl.folkways.vanilla.person.PersonBody;
import io.github.izakyl.folkways.vanilla.person.living.LivingContent;
import io.github.izakyl.folkways.vanilla.person.living.LivingRefusal;
import java.util.List;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysCoreMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysCoreMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class BalkLookGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(BalkLookGameTests.class);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 20)
    public static void residentCardShowsCurrentWorkWithoutRefusals(GameTestHelper helper) {
        Colony colony = Colonies.mint(helper.getLevel().getServer());
        Body body = PersonBody.RESIDENT.get().create(helper.getLevel());
        long now = helper.getLevel().getGameTime();
        try {
            var doing = Doing.open(Doings.OPERATING);
            body.setDoing(doing);
            var refusal = new Balk(Doing.open(LivingContent.EATING), LivingRefusal.NOTHING_TO_EAT, now);
            body.balked(refusal);

            List<Line> lines = ColonyLooks.of(colony, body, List.of());
            helper.assertTrue(lines.contains(Line.busy(doing)), "the card shows the resident's current work");
            helper.assertTrue(lines.stream().noneMatch(Line.Blocked.class::isInstance), "the card omits the refusal");
            helper.assertTrue(body.balk().filter(refusal::equals).isPresent(), "the refusal is still recorded");
        } finally {
            body.mob().discard();
            colony.raze();
        }
        helper.succeed();
    }
}
