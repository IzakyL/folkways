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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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
    public static void heldToolAllowedOnlyForBuildingCanDoFarmingWork(GameTestHelper helper) {
        var body = PersonBody.RESIDENT.get().create(helper.getLevel());
        var colony = new ColonyData().works();
        var front = ColonyFront.of(colony);
        Vocation building = Vocations.required(BUILDING);
        Vocation farming = Vocations.required(FARMING);
        helper.assertTrue(body.licences().of(building).isPresent() && body.licences().of(farming).isPresent(),
            "the same resident is licensed for both vocations");
        for (Vocation vocation : Vocations.all()) {
            front.setSetting(vocation.id(), "tool", rules());
        }
        front.setSetting(BUILDING, "tool", rules("stone_axe"));
        front.setSetting(FARMING, "tool", rules("iron_hoe"));
        ItemStack stone = new ItemStack(Items.STONE_AXE);
        body.pack().setItem(0, stone);
        NodeSpec spec = work(helper, farming);
        var snapshot = KitRules.snapshot(colony);
        helper.assertTrue(snapshot.allows(Optional.of(building), Items.STONE_AXE)
            && !snapshot.allows(Optional.of(farming), Items.STONE_AXE), "only building may acquire the stone axe");
        helper.assertTrue(Tools.canPrepare(body.kind().id(), spec, List.of(stone), snapshot),
            "a held building tool qualifies the resident for farming work");
        helper.assertTrue(Kit.of(colony, body).holds(stone), "building keeps its own allowed axe");
        Hands hands = new Hands(body, null, Placement.NOWHERE, drop -> { }, List::of);
        helper.assertTrue(hands.held(AXE).is(Items.STONE_AXE), "selection ignores the vocation lists");
        Node node = new Node() {
            public NodeSpec spec() { return spec; }
            public Outcome commit(ServerLevel level, Worker who) {
                helper.assertTrue(who.held(AXE).is(Items.STONE_AXE), "farming work uses the building axe");
                return Outcome.done();
            }
        };
        helper.assertTrue(Commit.run(helper.getLevel(), node, hands) instanceof Outcome.Done, "cross-vocation work commits");
        Wear.from(helper.getLevel(), body, spec, hands, item -> { });
        helper.assertValueEqual(stone.getDamageValue(), 1, "wear reaches the pack stack");
        front.setSetting(BUILDING, "tool", rules());
        helper.assertFalse(Kit.of(colony, body).holds(stone), "a tool removed from every kit becomes cargo");
        helper.assertTrue(Commit.run(helper.getLevel(), node, hands) instanceof Outcome.Done,
            "a list edit cannot reject a tool already in the pack");
        ItemStack iron = new ItemStack(Items.IRON_AXE);
        body.pack().setItem(0, iron);
        helper.assertTrue(hands.held(AXE).is(Items.IRON_AXE), "selection follows the pack after a stack is replaced");
        helper.assertTrue(snapshot.allows(Optional.of(building), Items.STONE_AXE), "an earlier planning snapshot remains immutable");
        helper.assertFalse(KitRules.snapshot(colony).allows(Optional.of(building), Items.STONE_AXE), "a new snapshot sees the edit");
        body.mob().discard();
        ColonyFront.forget(colony.id());
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void defaultsAndEmptySurviveReload(GameTestHelper helper) {
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

    @GameTest(template = "empty")
    public static void legacyDefaultKeepsVocationDefaultsUnconfigured(GameTestHelper helper) {
        var colony = new ColonyData().works();
        var filters = new ArrayList<>(CoreSettings.legacyKit());
        Collections.reverse(filters);
        CompoundTag scopes = new CompoundTag();
        scopes.put(CoreSettings.CORE.toString(), ColonySettings.empty().with("tool",
            new ColonySettings.Value.Items(filters)).save());
        CompoundTag saved = new CompoundTag();
        saved.put("settings", scopes);
        colony.keep(ColonyFront.OWNER, saved);
        for (int load = 0; load < 2; load++) {
            var front = ColonyFront.of(colony);
            var defaults = KitRules.defaults();
            for (Vocation vocation : Vocations.all()) {
                helper.assertTrue(front.settings(vocation.id()).valueOf("tool").isEmpty(),
                    "the old global default leaves each vocation unconfigured");
                helper.assertTrue(front.toolRules().tools().get(vocation.id()).equals(defaults.tools().get(vocation.id())),
                    "each vocation inherits only its own default tools");
            }
            helper.assertValueEqual(colony.kept(ColonyFront.OWNER).getInt("toolVersion"), 1, "migration is versioned in the save");
            ColonyFront.forget(colony.id());
        }
        helper.succeed();
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
        saved = colony.kept(ColonyFront.OWNER);
        saved.getCompound("settings").getCompound(FARMING.toString()).remove("tool");
        colony.keep(ColonyFront.OWNER, saved);
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
