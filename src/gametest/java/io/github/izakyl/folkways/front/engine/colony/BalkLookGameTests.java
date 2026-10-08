package io.github.izakyl.folkways.front.engine.colony;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.resident.body.Body;
import io.github.izakyl.folkways.core.api.work.Balk;
import io.github.izakyl.folkways.core.api.work.Doing;
import io.github.izakyl.folkways.core.api.work.Doings;
import io.github.izakyl.folkways.core.engine.plan.haul.HaulRefusal;
import io.github.izakyl.folkways.front.api.notice.Line;
import io.github.izakyl.folkways.front.api.notice.Sentence;
import io.github.izakyl.folkways.plugins.farming.FarmRefusal;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.person.living.LivingContent;
import io.github.izakyl.folkways.plugins.person.living.LivingRefusal;
import java.util.List;
import java.util.Optional;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class BalkLookGameTests {
    private static final ResourceLocation COBBLESTONE = ResourceLocation.withDefaultNamespace("cobblestone");

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(BalkLookGameTests.class);
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void workGivenUpOnHangsUnderIdleUntilItIsOldNews(GameTestHelper helper) {
        Colony colony = Colonies.mint(helper.getLevel().getServer());
        Body body = PersonBody.RESIDENT.get().create(helper.getLevel());
        long now = helper.getLevel().getGameTime();
        try {
            body.balked(new Balk(Doing.open(LivingContent.EATING), LivingRefusal.NOTHING_TO_EAT, now));
            Line.Blocked eat = blockedAfterIdle(helper, colony, body);
            helper.assertTrue(eat.notice().key().equals("folkways.look.lacking"), "nothing to eat reads as missing");
            helper.assertTrue(eat.told().equals(Optional.of(Sentence.of(Sentence.doing(LivingContent.EATING),
                Sentence.glyph("lacks")))), "then the eating glyph and a cross, got " + eat.told());

            Doing.Wares stone = new Doing.Wares(List.of(new Doing.Ware(COBBLESTONE, 8L)), List.of());
            body.balked(new Balk(Doing.open(Doings.STOWING).with(stone), HaulRefusal.NO_ROOM, now));
            Line.Blocked full = blockedAfterIdle(helper, colony, body);
            helper.assertTrue(full.notice().key().equals("folkways.look.no_room"), "a full chest reads as no room");
            helper.assertTrue(full.told().equals(Optional.of(Sentence.of(Sentence.doing(Doings.STOWING),
                Sentence.glyph("refuses"), Sentence.ware(COBBLESTONE, 8L)))), "then what would not go in, got " + full.told());

            body.balked(new Balk(Doing.open(Doings.WORKING).about(Optional.of("block.minecraft.wheat")),
                FarmRefusal.NOTHING_TO_WORK, now));
            Line.Blocked other = blockedAfterIdle(helper, colony, body);
            helper.assertTrue(other.notice().key().equals(FarmRefusal.NOTHING_TO_WORK.translationKey())
                && other.told().isEmpty(), "any other refusal says its own words alone");

            body.balked(new Balk(Doing.open(LivingContent.EATING), LivingRefusal.NOTHING_TO_EAT, now - Balk.FRESH_TICKS));
            helper.assertTrue(ColonyLooks.of(colony, body, List.of()).stream().noneMatch(Line.Blocked.class::isInstance),
                "a balk that is old news is not shown");
        } finally {
            body.mob().discard();
            colony.raze();
        }
        helper.succeed();
    }

    private static Line.Blocked blockedAfterIdle(GameTestHelper helper, Colony colony, Body body) {
        List<Line> lines = ColonyLooks.of(colony, body, List.of());
        int idle = lines.indexOf(Line.busy(Doing.open(Doings.IDLE)));
        helper.assertTrue(idle >= 0 && idle + 1 < lines.size() && lines.get(idle + 1) instanceof Line.Blocked,
            "the balk sits right under Idle, got " + lines);
        return (Line.Blocked) lines.get(idle + 1);
    }
}
