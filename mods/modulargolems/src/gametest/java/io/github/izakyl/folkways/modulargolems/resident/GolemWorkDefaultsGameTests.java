package io.github.izakyl.folkways.modulargolems.resident;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.colony.Held;
import io.github.izakyl.folkways.core.api.resident.body.Bodies;
import io.github.izakyl.folkways.core.api.resident.body.Keenness;
import io.github.izakyl.folkways.core.api.vocation.Vocations;
import io.github.izakyl.folkways.core.engine.colony.WorkDefaults;
import io.github.izakyl.folkways.modulargolems.domain.GolemContent;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Mob;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = "folkways_modulargolems", bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class GolemWorkDefaultsGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(GolemWorkDefaultsGameTests.class);
    }

    @GameTest(template = "empty")
    public static void humanDefaultsDoNotChangeAnyGolemKind(GameTestHelper helper) {
        var level = helper.getLevel();
        var colony = Colonies.mint(level.getServer());
        try {
            for (var trade : Vocations.ids()) {
                WorkDefaults.set(colony, PersonBody.ID, trade, false, 1);
            }
            for (var kind : GolemKinds.ALL) {
                Mob mob = (Mob) BuiltInRegistries.ENTITY_TYPE.get(kind.id()).create(level);
                mob.moveTo(helper.absolutePos(new net.minecraft.core.BlockPos(1, 2, 1)).getCenter());
                level.addFreshEntity(mob);
                try {
                    var body = Bodies.of(mob).orElseThrow();
                    var trade = Vocations.all().stream().filter(vocation -> body.licences().trade(vocation).isPresent())
                        .findFirst().orElseThrow();
                    var known = body.licences().trade(trade).orElseThrow();
                    body.licences().setKeenness(known, Keenness.SECOND);
                    helper.assertTrue(colony.hold(GolemContent.BODY, Held.Entity.of(mob)).isPresent(), "the golem joins");
                    helper.assertTrue(body.licences().allows(known) && body.licences().keennessOf(known) == Keenness.SECOND,
                        "joining keeps golem work settings");
                    for (var vocation : Vocations.all()) {
                        if (vocation.equals(trade)) { continue; }
                        body.licences().trade(vocation).ifPresent(other -> helper.assertTrue(
                            body.licences().allows(other) && body.licences().keennessOf(other) == Keenness.DEFAULT,
                            "other golem work stays allowed at three"));
                    }
                    helper.assertFalse(WorkDefaults.set(colony, kind.id(), trade.id(), false, 1), "golems have no template");
                } finally {
                    mob.discard();
                }
            }
        } finally {
            colony.raze();
        }
        helper.succeed();
    }
}
