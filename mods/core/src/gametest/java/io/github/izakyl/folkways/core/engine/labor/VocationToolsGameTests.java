package io.github.izakyl.folkways.core.engine.labor;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.base.persist.Reader;
import io.github.izakyl.folkways.core.api.terms.ItemFilter;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.vocation.KitRules;
import io.github.izakyl.folkways.core.api.vocation.Vocation;
import io.github.izakyl.folkways.core.api.vocation.Vocations;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Placement;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.ToolNeed;
import io.github.izakyl.folkways.core.api.work.ToolUse;
import io.github.izakyl.folkways.core.api.work.Tools;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.core.engine.colony.ColonyData;
import io.github.izakyl.folkways.front.api.CoreSettings;
import io.github.izakyl.folkways.front.engine.colony.ColonyFront;
import io.github.izakyl.folkways.front.engine.colony.ColonySettings;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.ItemAbilities;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class VocationToolsGameTests {

    private static final ResourceLocation BUILDING = id("building");
    private static final ResourceLocation FARMING = id("farming");
    private static final ToolNeed AXE = new ToolNeed.Ability(ItemAbilities.AXE_DIG);

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(VocationToolsGameTests.class);
    }

    @GameTest(template = "empty")
    public static void multiVocationSelectionAndWearUseTheWorkVocation(GameTestHelper helper) {
        var body = PersonBody.RESIDENT.get().create(helper.getLevel());
        var colony = new ColonyData().works();
        var front = ColonyFront.of(colony);
        Vocation building = Vocations.required(BUILDING);
        Vocation patrol = Vocations.required(id("patrolling"));
        helper.assertTrue(body.licences().of(building).isPresent() && body.licences().of(patrol).isPresent(),
            "the same resident is licensed for both vocations");
        front.setSetting(BUILDING, "tool", rules("stone_axe"));
        front.setSetting(id("patrolling"), "tool", rules("iron_axe"));
        ItemStack stone = new ItemStack(Items.STONE_AXE);
        ItemStack iron = new ItemStack(Items.IRON_AXE);
        body.pack().setItem(0, stone);
        body.pack().setItem(1, iron);
        NodeSpec spec = work(helper, patrol);
        var snapshot = KitRules.snapshot(colony);
        helper.assertFalse(Tools.canPrepare(body.kind().id(), spec, List.of(stone), snapshot),
            "a building tool cannot qualify the resident for patrol");
        helper.assertTrue(Tools.canPrepare(body.kind().id(), spec, List.of(stone, iron), snapshot),
            "planning can use the patrol tool even when it follows the building tool");
        var kit = Kit.of(colony, body);
        helper.assertTrue(kit.holds(stone) && kit.holds(iron), "the carried kit is the union of each licensed kit's allowed tools");
        Hands hands = new Hands(colony, spec.vocation(), body, null, Placement.NOWHERE, drop -> { }, List::of);
        helper.assertTrue(hands.held(AXE) == iron, "actual selection skips the other vocation's first matching axe");
        Node node = new Node() {
            public NodeSpec spec() { return spec; }
            public Outcome commit(ServerLevel level, Worker who) {
                helper.assertTrue(who.held(AXE) == iron, "commit receives the same physical tool");
                return Outcome.done();
            }
        };
        helper.assertTrue(Commit.run(helper.getLevel(), node, hands) instanceof Outcome.Done, "allowed work commits");
        Wear.from(helper.getLevel(), body, spec, hands, item -> { });
        helper.assertValueEqual(stone.getDamageValue(), 0, "the building axe is not worn");
        helper.assertValueEqual(iron.getDamageValue(), 1, "wear follows the actual patrol selection");
        front.setSetting(id("patrolling"), "tool", rules());
        helper.assertFalse(Kit.of(colony, body).holds(iron), "the disabled vocation's axe becomes cargo");
        helper.assertTrue(Kit.of(colony, body).holds(stone), "the other vocation keeps its own allowed axe");
        Hands forbidden = new Hands(colony, spec.vocation(), body, null, Placement.NOWHERE, drop -> { }, List::of);
        helper.assertTrue(Commit.run(helper.getLevel(), node, forbidden) instanceof Outcome.Failed,
            "execution rechecks a list changed after planning");
        helper.assertTrue(snapshot.allows(Optional.of(patrol), Items.IRON_AXE), "an earlier planning snapshot remains immutable");
        helper.assertFalse(KitRules.snapshot(colony).allows(Optional.of(patrol), Items.IRON_AXE), "a new snapshot sees the edit");
        body.mob().discard();
        ColonyFront.forget(colony.id());
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void defaultsEmptyAndResetSurviveReload(GameTestHelper helper) {
        var colony = new ColonyData().works();
        Vocation building = Vocations.required(BUILDING);
        var front = ColonyFront.of(colony);
        helper.assertTrue(front.settings(BUILDING).valueOf("tool").isEmpty(), "defaults are inherited, not explicitly configured");
        helper.assertTrue(KitRules.allows(colony, building, Items.IRON_PICKAXE), "an unset list uses its vocation kit");
        front.setSetting(BUILDING, "tool", rules());
        ColonyFront.forget(colony.id());
        front = ColonyFront.of(colony);
        helper.assertTrue(front.settings(BUILDING).valueOf("tool").isPresent(), "an explicitly empty list persists");
        helper.assertFalse(KitRules.allows(colony, building, Items.IRON_PICKAXE), "empty forbids every tool after reload");
        front.resetTool(building);
        ColonyFront.forget(colony.id());
        front = ColonyFront.of(colony);
        helper.assertTrue(front.settings(BUILDING).valueOf("tool").isEmpty(), "reset restores inherited defaults across reload");
        helper.assertTrue(KitRules.allows(colony, building, Items.IRON_PICKAXE), "reset restores this vocation's kit");
        ColonyFront.forget(colony.id());
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void legacyGlobalRulesMigrateOnceWithoutOverwritingVocations(GameTestHelper helper) {
        migrate(helper, rules("stone_axe"));
    }

    @GameTest(template = "empty")
    public static void legacyExplicitEmptyMigratesAsForbidden(GameTestHelper helper) {
        migrate(helper, rules());
    }

    private static void migrate(GameTestHelper helper, ColonySettings.Value.Items legacy) {
        var colony = new ColonyData().works();
        CompoundTag saved = new CompoundTag();
        CompoundTag scopes = new CompoundTag();
        scopes.put(CoreSettings.CORE.toString(), ColonySettings.empty().with("tool", legacy).save());
        scopes.put(BUILDING.toString(), ColonySettings.empty().with("tool", rules("iron_pickaxe")).save());
        saved.put("settings", scopes);
        colony.keep(ColonyFront.OWNER, saved);
        var front = ColonyFront.of(colony);
        for (Vocation vocation : Vocations.all()) {
            helper.assertTrue(front.settings(vocation.id()).valueOf("tool").orElseThrow().equals(
                vocation.id().equals(BUILDING) ? rules("iron_pickaxe") : legacy),
                "legacy rules are copied exactly to each unconfigured vocation, including empty lists");
        }
        helper.assertValueEqual(colony.kept(ColonyFront.OWNER).getInt("toolVersion"), 1, "migration is versioned in the save");
        front.resetTool(Vocations.required(FARMING));
        ColonyFront.forget(colony.id());
        front = ColonyFront.of(colony);
        helper.assertTrue(front.settings(FARMING).valueOf("tool").isEmpty(), "the old seed is never applied a second time");
        ColonyFront.forget(colony.id());
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void removedPluginToolDataSurvivesSavingOtherSettings(GameTestHelper helper) {
        var colony = new ColonyData().works();
        ResourceLocation unknown = ResourceLocation.fromNamespaceAndPath("missing_plugin", "trade");
        var rules = new ColonySettings.Value.Items(List.of(ItemFilter.tag(ResourceLocation.withDefaultNamespace("axes")),
            ItemFilter.query("axe").orElseThrow()));
        var missing = ColonySettings.empty().with("tool", rules).with("other", new ColonySettings.Value.Count(7));
        CompoundTag scopes = new CompoundTag();
        scopes.put(unknown.toString(), missing.save());
        CompoundTag saved = new CompoundTag();
        saved.putInt("toolVersion", 1);
        saved.put("settings", scopes);
        colony.keep(ColonyFront.OWNER, saved);
        ColonyFront.of(colony).setSetting(BUILDING, "tool", rules());
        ColonyFront.forget(colony.id());
        var reloaded = ColonyFront.of(colony);
        helper.assertTrue(reloaded.settings(unknown).equals(missing), "unknown vocation and other plugin settings are retained");
        var raw = ColonySettings.load(Reader.of(colony.kept(ColonyFront.OWNER).getCompound("settings").getCompound(unknown.toString())));
        helper.assertTrue(raw.equals(missing), "the unknown scope remains in persisted data for plugin reinstallation");
        ColonyFront.forget(colony.id());
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void settingsChangesRefreshSurveyWithoutAChangedPack(GameTestHelper helper) {
        var body = PersonBody.RESIDENT.get().create(helper.getLevel());
        var colony = new ColonyData();
        body.pack().setItem(0, new ItemStack(Items.IRON_HOE));
        var survey = new Survey();
        survey.read(body.id());
        survey.answer(helper.getLevel(), colony, Map.of(body.id(), body), seen -> { });
        var before = survey.hands().getFirst();
        ColonyFront.of(colony.works()).setSetting(FARMING, "tool", rules());
        helper.assertTrue(survey.answer(helper.getLevel(), colony, Map.of(body.id(), body), seen -> { }),
            "a tool list edit refreshes the hand without a pack or licence change");
        var after = survey.hands().getFirst();
        helper.assertFalse(before.toolRules().equals(after.toolRules()), "the next planner input has fresh vocation rules");
        helper.assertTrue(before.toolRules().allows(Optional.of(Vocations.required(FARMING)), Items.IRON_HOE),
            "the old async input retains its previous rules");
        body.mob().discard();
        ColonyFront.forget(colony.colonyId());
        helper.succeed();
    }

    private static NodeSpec work(GameTestHelper helper, Vocation vocation) {
        var at = WorldPos.of(helper.getLevel(), helper.absolutePos(BlockPos.ZERO));
        return NodeSpec.of(UUID.randomUUID(), id("tool_test"), new WorkSite.AtBlock(at),
            Stances.at(helper.getLevel(), at.cell()), Workload.Once.of(1))
            .vocation(vocation).tools(new ToolUse(AXE, 1)).done();
    }

    private static ColonySettings.Value.Items rules(String... tools) {
        return new ColonySettings.Value.Items(Arrays.stream(tools)
            .map(tool -> ItemFilter.item(ResourceLocation.withDefaultNamespace(tool))).toList());
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("folkways", path);
    }
}
