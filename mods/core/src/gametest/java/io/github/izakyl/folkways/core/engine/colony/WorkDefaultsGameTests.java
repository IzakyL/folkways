package io.github.izakyl.folkways.core.engine.colony;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.base.persist.Reader;
import io.github.izakyl.folkways.core.api.Declaring;
import io.github.izakyl.folkways.core.api.participation.Participation;
import io.github.izakyl.folkways.core.api.participation.Stake;
import io.github.izakyl.folkways.core.api.resident.Locomotions;
import io.github.izakyl.folkways.core.api.resident.ResidentKind;
import io.github.izakyl.folkways.core.api.resident.ResidentKinds;
import io.github.izakyl.folkways.core.api.resident.body.Body;
import io.github.izakyl.folkways.core.api.resident.body.FolkwaysAttachments;
import io.github.izakyl.folkways.core.api.resident.body.Keenness;
import io.github.izakyl.folkways.core.api.resident.body.Licences;
import io.github.izakyl.folkways.core.api.resident.body.WorkPriorities;
import io.github.izakyl.folkways.core.api.vocation.Vocations;
import java.util.Map;
import java.util.Set;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class WorkDefaultsGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(WorkDefaultsGameTests.class);
    }

    private static final ResourceLocation FIRST = Vocations.HAULING;
    private static final ResourceLocation SECOND = ResourceLocation.fromNamespaceAndPath("folkways", "building");

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void declare(Declaring event) {
        for (String name : new String[]{"saved_defaults", "missing_defaults", "participation_defaults", "copied_defaults", "other_defaults"}) {
            Map<Stake, Participation> rows = name.equals("participation_defaults")
                ? Map.of(Stake.vocation(FIRST), Participation.ALWAYS)
                : Map.of(Stake.vocation(FIRST), Participation.OPTIONAL, Stake.vocation(SECOND), Participation.OPTIONAL);
            var kind = ResidentKind.growing(id(name), Locomotions.named(Locomotions.WALKING)).taking(rows);
            event.residentKind(name.equals("other_defaults") ? kind : kind.withWorkDefaults());
        }
    }

    @GameTest(template = "empty")
    public static void sparsePrioritiesRoundTripWithUnknownTrades(GameTestHelper helper) {
        var trade = Vocations.ids().getFirst();
        var missing = id("missing_plugin/trade");
        var priorities = new WorkPriorities(Map.of(trade, WorkPriorities.DEFAULT,
            missing, new WorkPriorities.Rule(false, Keenness.FIRST)));
        helper.assertFalse(priorities.save().contains(trade.toString()), "usual settings take no entry");
        helper.assertValueEqual(WorkPriorities.from(priorities.save()), priorities, "unknown trades round trip too");
        helper.assertValueEqual(priorities.of(id("new_trade")), WorkPriorities.DEFAULT, "new trades use the usual settings");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void invalidSavedLevelsFallBackWithoutLosingTheAllowedFlag(GameTestHelper helper) {
        for (int number : new int[]{-1, 0, 5, Integer.MAX_VALUE}) {
            CompoundTag entry = new CompoundTag();
            entry.putBoolean("allowed", false);
            entry.putInt("priority", number);
            CompoundTag trades = new CompoundTag();
            trades.put(id("trade").toString(), entry);
            var rule = WorkPriorities.from(trades).of(id("trade"));
            helper.assertTrue(!rule.allowed() && rule.priority() == Keenness.DEFAULT, "bad ranks retain the work switch");
        }
        helper.assertValueEqual(WorkPriorities.from(new CompoundTag()).of(id("trade")), WorkPriorities.DEFAULT,
            "a missing entry allows work at three");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void colonySaveKeepsDefaultsAndMissingPluginsThroughEdits(GameTestHelper helper) {
        var trades = java.util.List.of(FIRST, SECOND);
        var kind = kind("saved_defaults");
        var data = new ColonyData();
        var colony = data.works();
        CompoundTag kinds = new CompoundTag();
        CompoundTag settings = new CompoundTag();
        settings.put("trades", new WorkPriorities(Map.of(id("absent/trade"),
            new WorkPriorities.Rule(false, Keenness.SECOND))).save());
        kinds.put(kind.id().toString(), settings);
        kinds.put("absent:kind", settings.copy());
        CompoundTag saved = new CompoundTag();
        saved.putInt("version", 1);
        saved.put("kinds", kinds);
        colony.keep(WorkDefaults.ID, saved);
        helper.assertTrue(WorkDefaults.set(colony, kind.id(), trades.get(0), false, 1), "edit one entry");
        helper.assertTrue(WorkDefaults.set(colony, kind.id(), trades.get(1), true, 4), "edit another from the latest table");
        var restored = ColonyData.load(Reader.of(data.save(new CompoundTag()))).orElseThrow().works();
        helper.assertValueEqual(restored.kept(WorkDefaults.ID), colony.kept(WorkDefaults.ID), "colony NBT round trips");
        helper.assertFalse(WorkDefaults.of(restored, kind.id()).of(trades.get(0)).allowed(), "the first edit stays");
        helper.assertTrue(WorkDefaults.of(restored, kind.id()).of(trades.get(1)).priority() == Keenness.FOURTH,
            "the second edit stays too");
        helper.assertFalse(WorkDefaults.of(restored, kind.id()).of(id("absent/trade")).allowed(), "edits keep absent trades");
        helper.assertValueEqual(restored.kept(WorkDefaults.ID).getCompound("kinds").getCompound("absent:kind"), settings,
            "editing one kind keeps unknown kinds");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void missingTemplateKeepsOldWorldBehaviour(GameTestHelper helper) {
        var trade = Vocations.of(FIRST).orElseThrow();
        var kind = kind("missing_defaults");
        Body body = body(helper, kind);
        var known = body.licences().trade(trade).orElseThrow();
        WorkDefaults.apply(new CompoundTag(), body);
        helper.assertTrue(body.licences().allows(known) && body.licences().keennessOf(known) == Keenness.DEFAULT,
            "an old colony's new residents still allow work at three");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void editsAndApplicationRespectParticipationAndRejectBadInput(GameTestHelper helper) {
        var trades = java.util.List.of(Vocations.of(FIRST).orElseThrow(), Vocations.of(SECOND).orElseThrow());
        var always = trades.get(0);
        var never = trades.get(1);
        var kind = kind("participation_defaults");
        var colony = new ColonyData().works();
        CompoundTag before = colony.kept(WorkDefaults.ID);
        for (int level : new int[]{0, -1, 5, Integer.MAX_VALUE}) {
            helper.assertFalse(WorkDefaults.set(colony, kind.id(), always.id(), true, level), "reject bad incoming levels");
        }
        helper.assertFalse(WorkDefaults.set(colony, kind.id(), id("absent/trade"), false, 1), "no unknown incoming trade");
        helper.assertFalse(WorkDefaults.set(colony, kind.id(), never.id(), true, 1), "no unsupported incoming trade");
        helper.assertValueEqual(colony.kept(WorkDefaults.ID), before, "refused changes do not write");
        helper.assertTrue(WorkDefaults.set(colony, kind.id(), always.id(), false, 1), "mandatory trade can be ranked");
        Body body = body(helper, kind);
        WorkDefaults.apply(colony.kept(WorkDefaults.ID), body);
        var known = body.licences().trade(always).orElseThrow();
        helper.assertTrue(body.licences().allows(known) && body.licences().keennessOf(known) == Keenness.FIRST,
            "mandatory work stays allowed with the chosen rank");
        helper.assertTrue(body.licences().trade(never).isEmpty(), "unsupported work gains no licence");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void optInSurvivesKindCopiesAndOtherKindsKeepPersonalSettings(GameTestHelper helper) {
        var trade = Vocations.of(FIRST).orElseThrow();
        var kind = kind("copied_defaults");
        helper.assertTrue(kind.embodiedBy(ResidentKind.Embodiment.ITSELF).taking(kind.participations())
            .barehanded(Set.of(trade.id())).workDefaults(), "copy methods keep the opt in");
        var other = ResidentKinds.of(id("other_defaults")).orElseThrow();
        Body body = body(helper, other);
        var known = body.licences().trade(trade).orElseThrow();
        body.licences().allow(known, false);
        body.licences().setKeenness(known, Keenness.SECOND);
        WorkDefaults.apply(new CompoundTag(), body);
        helper.assertTrue(!body.licences().allows(known) && body.licences().keennessOf(known) == Keenness.SECOND,
            "a kind that did not opt in keeps its settings");
        helper.assertFalse(WorkDefaults.set(new ColonyData().works(), other.id(), trade.id(), false, 1),
            "a kind that did not opt in has no editable template");
        helper.succeed();
    }

    private static ResidentKind kind(String name) {
        return ResidentKinds.of(id(name)).orElseThrow();
    }

    private static Body body(GameTestHelper helper, ResidentKind kind) {
        Mob mob = EntityType.VILLAGER.create(helper.getLevel());
        mob.setData(FolkwaysAttachments.LICENCES, new Licences(kind.id()));
        return new Body() {
            public Mob mob() { return mob; }
            public ResidentKind kind() { return kind; }
        };
    }

    private static ResourceLocation id(String name) {
        return ResourceLocation.fromNamespaceAndPath("folkways", "test/" + name);
    }
}
