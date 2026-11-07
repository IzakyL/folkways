package io.github.izakyl.folkways.core.resident.internal;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.effect.Stats;
import io.github.izakyl.folkways.effect.perk.GlobalPerks;
import io.github.izakyl.folkways.effect.perk.PerkPool;
import io.github.izakyl.folkways.effect.perk.Perks;
import io.github.izakyl.folkways.plugins.build.BuildContent;
import io.github.izakyl.folkways.plugins.farming.FarmingContent;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.RandomSource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PerkGrowthGameTests {
    @SubscribeEvent public static void register(RegisterGameTestsEvent event) { event.register(PerkGrowthGameTests.class); }

    @GameTest(template = "empty")
    public static void malformedTracksFailInsteadOfRepairingProgressOrRanks(GameTestHelper helper) {
        BodyPerks grown = new BodyPerks();
        grown.addXp(FarmingContent.VOCATION, BodyPerks.xpForLevel(3), RandomSource.create(9));
        List<Consumer<CompoundTag>> corruptions = List.of(
            tag -> tag.getCompound("global").putInt("xp", -1),
            tag -> tag.getCompound("global").putInt("labor_remainder", -1),
            tag -> tag.getCompound("global").putInt("labor_remainder", 20),
            tag -> tag.getCompound("vocations").putString(FarmingContent.VOCATION.toString(), "bad track"),
            tag -> {
                var owned = tag.getCompound("global").getList("owned", Tag.TAG_COMPOUND);
                owned.add(owned.getCompound(0).copy());
            },
            tag -> farmTrack(tag).getList("owned", Tag.TAG_COMPOUND).getCompound(0)
                .putString("id", GlobalPerks.BRISK),
            tag -> farmTrack(tag).getList("owned", Tag.TAG_COMPOUND).getCompound(0)
                .putString("id", BuildContent.QUICK_HANDS),
            tag -> tag.getCompound("global").getList("owned", Tag.TAG_COMPOUND).getCompound(0)
                .putString("id", FarmingContent.QUICK_HANDS));
        for (Consumer<CompoundTag> corrupt : corruptions) {
            CompoundTag saved = grown.save();
            corrupt.accept(saved);
            boolean rejected = false;
            try {
                new BodyPerks().load(saved);
            } catch (IllegalArgumentException expected) {
                rejected = true;
            }
            helper.assertTrue(rejected, "malformed perk tracks must fail loading");
        }
        CompoundTag saved = grown.save();
        var owned = farmTrack(saved).getList("owned", Tag.TAG_COMPOUND);
        owned.getCompound(0).putInt("rank", Integer.MAX_VALUE);
        String known = owned.getCompound(0).getString("id");
        CompoundTag unknown = owned.getCompound(0).copy();
        unknown.putString("id", "missing_plugin_perk");
        owned.add(unknown);
        BodyPerks restored = new BodyPerks();
        restored.load(saved);
        helper.assertValueEqual(restored.rank(known), PerkPool.maxRank(known), "dynamic rank cap still applies");
        helper.assertValueEqual(restored.rank("missing_plugin_perk"), 0, "unknown perks are still skipped");
        helper.succeed();
    }

    private static CompoundTag farmTrack(CompoundTag tag) {
        return tag.getCompound("vocations").getCompound(FarmingContent.VOCATION.toString());
    }

    @GameTest(template = "empty")
    public static void tracksExposeExperienceAndKeepSharedRanksSeparate(GameTestHelper helper) {
        BodyPerks grown = new BodyPerks();
        RandomSource random = RandomSource.create(29);
        grown.addXp(FarmingContent.VOCATION, 1120, random);
        grown.addXp(BuildContent.VOCATION, 200, random);
        BodyPerks restored = new BodyPerks(); restored.load(grown.save());
        Perks perks = restored;
        helper.assertValueEqual(perks.xp(FarmingContent.VOCATION), 1120, "cumulative trade experience survives reload");
        helper.assertValueEqual(perks.nextLevelXp(FarmingContent.VOCATION), 1500, "next level's cumulative threshold");
        helper.assertValueEqual(perks.maxLevel(FarmingContent.VOCATION), 10, "level cap is independent of pool depth");
        helper.assertValueEqual(perks.ranks(FarmingContent.VOCATION).values().stream().mapToInt(Integer::intValue).sum(), 3, "farm draws only");
        helper.assertValueEqual(perks.ranks(BuildContent.VOCATION).values().stream().mapToInt(Integer::intValue).sum(), 1, "building draws only");
        helper.assertValueEqual(perks.globalRanks().values().stream().mapToInt(Integer::intValue).sum(), 1, "shared milestone");
        helper.assertFalse(perks.ranks(FarmingContent.VOCATION).keySet().stream().anyMatch(PerkPool::global), "no shared ranks in trade");
        Map<String, Integer> snapshot = perks.ranks(FarmingContent.VOCATION);
        restored.addXp(FarmingContent.VOCATION, BodyPerks.xpForLevel(10), random);
        helper.assertValueEqual(snapshot.values().stream().mapToInt(Integer::intValue).sum(), 3, "exposure is a snapshot");
        helper.assertValueEqual(perks.nextLevelXp(FarmingContent.VOCATION), 0, "capped trade has no next level");
        Perks fixed = Perks.fixed(Map.of(FarmingContent.QUICK_HANDS, 2, BuildContent.QUICK_HANDS, 1, GlobalPerks.BRISK, 3));
        helper.assertValueEqual(fixed.ranks(FarmingContent.VOCATION), Map.of(FarmingContent.QUICK_HANDS, 2), "fixed farm ranks follow their pool");
        helper.assertValueEqual(fixed.globalRanks(), Map.of(GlobalPerks.BRISK, 3), "fixed shared ranks");
        helper.assertTrue(fixed.xp(FarmingContent.VOCATION) == 0 && fixed.nextLevelXp(FarmingContent.VOCATION) == 0
            && fixed.maxLevel(FarmingContent.VOCATION) == 0, "fixed ranks do not grow");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void tenOccupationalDrawsAndThreeSharedMilestones(GameTestHelper helper) {
        BodyPerks perks = new BodyPerks();
        perks.addXp(FarmingContent.VOCATION, BodyPerks.xpForLevel(10), RandomSource.create(17));
        helper.assertValueEqual(perks.level(FarmingContent.VOCATION), 10, "ten levels");
        helper.assertValueEqual(perks.ranks().entrySet().stream().filter(entry -> !PerkPool.global(entry.getKey()))
            .mapToInt(Map.Entry::getValue).sum(), 10, "ten occupational ranks");
        helper.assertValueEqual(perks.ranks().entrySet().stream().filter(entry -> PerkPool.global(entry.getKey()))
            .mapToInt(Map.Entry::getValue).sum(), 3, "shared draws at three, six and ten");
        helper.assertValueEqual(perks.rank(BuildContent.QUICK_HANDS), 0, "another occupation never appears");
        perks.ranks().forEach((id, rank) -> helper.assertTrue(rank >= 1 && rank <= PerkPool.maxRank(id), "bounded rank " + id));
        helper.assertFalse(PerkPool.vocationCandidates(FarmingContent.VOCATION, Map.of(FarmingContent.QUICK_HANDS, 3))
            .contains(FarmingContent.QUICK_HANDS), "max rank leaves the pool");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void everyResidentDiscoversThreeSpecialitiesByLevelFive(GameTestHelper helper) {
        for (int seed = 0; seed < 100; seed++) {
            BodyPerks perks = new BodyPerks();
            perks.addXp(FarmingContent.VOCATION, BodyPerks.xpForLevel(5), RandomSource.create(seed));
            long known = perks.ranks().keySet().stream().filter(id -> !PerkPool.global(id)).count();
            helper.assertTrue(known >= 3, "three specialities, seed " + seed);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void shortJobsAccumulateLaborSecondsAcrossSaveAndLoad(GameTestHelper helper) {
        BodyPerks perks = new BodyPerks();
        RandomSource random = RandomSource.create(1);
        perks.worked(BuildContent.VOCATION, 3599, random);
        helper.assertValueEqual(perks.level(BuildContent.VOCATION), 0, "three minutes needed for first draw");
        BodyPerks restored = new BodyPerks(); restored.load(perks.save());
        restored.worked(BuildContent.VOCATION, 1, random);
        helper.assertValueEqual(restored.level(BuildContent.VOCATION), 1, "fractional second survives save");
        helper.assertValueEqual(restored.ranks().values().stream().mapToInt(Integer::intValue).sum(), 1, "exactly one draw");
        restored.addXp(BuildContent.VOCATION, -1000, random);
        helper.assertValueEqual(restored.level(BuildContent.VOCATION), 1, "negative rewards cannot remove growth");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void sharedMilestonesSurviveReloadAndCarryBetweenTrades(GameTestHelper helper) {
        BodyPerks perks = new BodyPerks();
        RandomSource random = RandomSource.create(31);
        perks.addXp(FarmingContent.VOCATION, BodyPerks.xpForLevel(3), random);
        BodyPerks restored = new BodyPerks(); restored.load(perks.save());
        restored.addXp(FarmingContent.VOCATION, 1, random);
        helper.assertValueEqual(restored.ranks().values().stream().mapToInt(Integer::intValue).sum(), 4, "reload does not repeat milestone");
        restored.addXp(BuildContent.VOCATION, BodyPerks.xpForLevel(3), random);
        helper.assertValueEqual(restored.ranks().values().stream().mapToInt(Integer::intValue).sum(), 8, "each trade earns its own shared milestone");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void currentTracksPreserveLevelsXpAndGlobalRanks(GameTestHelper helper) {
        BodyPerks perks = new BodyPerks();
        perks.addXp(FarmingContent.VOCATION, BodyPerks.xpForLevel(3), RandomSource.create(9));
        CompoundTag saved = perks.save();
        BodyPerks restored = new BodyPerks();
        restored.load(saved);
        helper.assertValueEqual(restored.level(FarmingContent.VOCATION), perks.level(FarmingContent.VOCATION),
            "vocation level survives reload");
        CompoundTag loaded = restored.save();
        helper.assertValueEqual(loaded.getCompound("vocations").getCompound(FarmingContent.VOCATION.toString()),
            saved.getCompound("vocations").getCompound(FarmingContent.VOCATION.toString()),
            "vocation XP and ranks survive reload");
        helper.assertValueEqual(loaded.getCompound("global"), saved.getCompound("global"),
            "global track survives reload");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void occupationalSpeedDoesNotBundleUnrelatedAbilities(GameTestHelper helper) {
        helper.assertFalse(PerkPool.effects(Map.of(FarmingContent.QUICK_HANDS, 3)).has(FarmingContent.REPLANTS),
            "speed and replanting are independent");
        helper.assertTrue(PerkPool.effects(Map.of(BuildContent.QUICK_HANDS, 3)).value(Stats.TOOL_WEAR, BuildContent.VOCATION) == 1.0,
            "construction speed does not grant tool care");
        helper.assertTrue(PerkPool.effects(Map.of(GlobalPerks.LONGARM, 3)).value(Stats.REACH) == 1.5,
            "longarm has substantial reach");
        helper.assertFalse(PerkPool.globalCandidates(Map.of()).contains(GlobalPerks.GOLEM_PACK_ROOM),
            "fixed golem capacity is not drawn");
        helper.succeed();
    }
}
