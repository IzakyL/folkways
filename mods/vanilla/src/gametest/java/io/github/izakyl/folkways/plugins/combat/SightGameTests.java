package io.github.izakyl.folkways.plugins.combat;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.colony.api.Claimed;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.testing.Cleanup;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaConfig;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaMod;
import io.github.izakyl.folkways.plugins.combat.weapon.LoadoutProfile;
import io.github.izakyl.folkways.plugins.combat.weapon.LoadoutProfiles;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.person.PersonContent;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SightGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(SightGameTests.class);
    }

    @GameTest(template = "sight_range")
    public static void defaultSightMatchesFlatWallOpticsAndScanBounds(GameTestHelper helper) {
        Scene f = new Scene(helper);
        Zombie target = f.zombie(42, 1, 2);
        target.setGlowingTag(true);
        helper.assertTrue(SpottedTargets.sight(f.observer, target) == 32, "bright flat-ground default is 32 blocks");
        helper.assertTrue(SpottedTargets.maximumSight(f.observer) == 56,
            "the unassisted scan bound includes capped height");
        f.observer.setPos(f.observer.getX(), target.getEyeY() + 8 - f.observer.getEyeHeight(), f.observer.getZ());
        helper.assertTrue(Math.abs(SpottedTargets.sight(f.observer, target) - 44) < .001,
            "eight blocks of eye-height advantage give 44 blocks before optics");
        var profiles = LoadoutProfiles.of(f.colony);
        var profile = profiles.create("Wall observer");
        profiles.add(profile.id(), LoadoutProfile.Entry.supply(ItemSpec.of(Items.SPYGLASS), 1, 1));
        profiles.assign(List.of(f.observer.id()), Optional.of(profile.id()));
        f.observer.pack().insert(new ItemStack(Items.SPYGLASS));
        helper.assertTrue(Math.abs(SpottedTargets.sight(f.observer, target) - 88) < .001,
            "a real profile spyglass gives 88 blocks on the same wall");
        helper.assertTrue(SpottedTargets.maximumSight(f.observer) == 112,
            "the optical scan and sentry ring bound stays below the 128-block hard cap");
        f.observer.pack().clearContent();
        helper.assertTrue(SpottedTargets.maximumSight(f.observer) == 112, "assigned optics retain the bound");
        helper.succeed();
    }

    @GameTest(template = "sight_range")
    public static void elevatedObserverFindsTheSameDistantTargetAndCoarseScanDoesNotMissIt(GameTestHelper helper) {
        Scene f = new Scene(helper);
        Zombie target = f.zombie(42, 1, 2);
        target.setGlowingTag(true);
        ResidentEntity lookout = f.resident(2, 9, 2);
        helper.assertFalse(SpottedTargets.sees(f.observer, target), "ground-level sight cannot reach forty blocks");
        helper.assertTrue(SpottedTargets.sees(lookout, target),
            "eight blocks of height allow the same distant sighting");
        helper.assertTrue(SpottedTargets.of(f.colony).observe(lookout).contains(target),
            "the scan upper bound includes a hostile that only the height bonus can reveal");
        helper.assertTrue(SpottedTargets.of(f.colony).get(f.observer, target.getUUID()).isPresent(),
            "the elevated observer publishes an ordinary shared sighting");
        helper.setBlock(new BlockPos(22, 6, 2), Blocks.STONE);
        helper.setBlock(new BlockPos(22, 7, 2), Blocks.STONE);
        helper.assertFalse(SpottedTargets.sees(lookout, target),
            "height and Glowing never bypass a blocked sight line");
        helper.setBlock(new BlockPos(22, 6, 2), Blocks.AIR);
        helper.setBlock(new BlockPos(22, 7, 2), Blocks.AIR);
        f.move(lookout, 2, 41, 2);
        double capped = SpottedTargets.sight(lookout, target);
        f.move(lookout, 2, 49, 2);
        helper.assertTrue(SpottedTargets.sight(lookout, target) == capped,
            "further eye height cannot exceed the configured height contribution");
        f.move(lookout, 2, 0, 2);
        helper.assertTrue(SpottedTargets.sight(lookout, target) == FolkwaysVanillaConfig.defenderSightRadius(),
            "looking up grants no height bonus or additional low-ground penalty");
        helper.assertTrue(capped <= SpottedTargets.maximumSight(lookout), "the coarse bound covers every pair");
        helper.succeed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 100)
    public static void effectiveDayNightAndWeatherLightRespectFireGlowAndNightVision(GameTestHelper helper) {
        Scene f = new Scene(helper);
        Zombie target = f.zombie(12, 1, 2);
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(
            f.level.getBrightness(LightLayer.SKY, target.blockPosition()) >= 12,
            "the actual open-sky lighting has propagated"))
            .thenExecute(() -> {
                long day = f.level.getDayTime();
                float rain = f.level.getRainLevel(1);
                float thunder = f.level.getThunderLevel(1);
                try {
                    f.level.setDayTime(6000);
                    f.level.setRainLevel(0);
                    f.level.setThunderLevel(0);
                    f.level.updateSkyBrightness();
                    double bright = SpottedTargets.sight(f.observer, target);
                    helper.assertTrue(SpottedTargets.sees(f.observer, target),
                        "the bright target is visible ten blocks away");
                    f.level.setRainLevel(1);
                    f.level.setThunderLevel(1);
                    f.level.updateSkyBrightness();
                    helper.assertTrue(SpottedTargets.sight(f.observer, target) < bright,
                        "vanilla rain and thunder reduce effective skylight without an extra ray");
                    f.level.setDayTime(18000);
                    f.level.setRainLevel(0);
                    f.level.setThunderLevel(0);
                    f.level.updateSkyBrightness();
                    double dark = SpottedTargets.sight(f.observer, target);
                    helper.assertTrue(dark == 8 && dark < bright && !SpottedTargets.sees(f.observer, target),
                        "real night skylight shortens discovery distance");
                    target.igniteForSeconds(5);
                    helper.assertTrue(SpottedTargets.sight(f.observer, target) == bright,
                        "a burning target receives the full-light distance immediately");
                    target.clearFire();
                    target.addEffect(new MobEffectInstance(MobEffects.GLOWING, 100));
                    helper.assertTrue(SpottedTargets.sees(f.observer, target), "the Glowing effect bypasses darkness");
                    target.removeEffect(MobEffects.GLOWING);
                    helper.assertTrue(SpottedTargets.sight(f.observer, target) == dark,
                        "removing Glowing restores darkness in the same tick");
                    f.observer.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, 100));
                    helper.assertTrue(SpottedTargets.sees(f.observer, target),
                        "observer night vision bypasses darkness");
                    f.observer.removeEffect(MobEffects.NIGHT_VISION);
                    Mob luminous = EntityType.GLOW_SQUID.create(f.level);
                    f.move(luminous, 12, 1, 2);
                    f.entities.add(luminous);
                    helper.assertTrue(SpottedTargets.sight(f.observer, luminous) >= bright,
                        "the data-pack luminous-target tag recognizes native glow squids");
                } finally {
                    f.level.setDayTime(day);
                    f.level.setRainLevel(rain);
                    f.level.setThunderLevel(thunder);
                    f.level.updateSkyBrightness();
                }
            }).thenSucceed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 100)
    public static void realCaveLightChangesDetectionAndCloseRangeStillRequiresSight(GameTestHelper helper) {
        Scene f = new Scene(helper);
        for (BlockPos cell : BlockPos.betweenClosed(0, 0, 0, 18, 5, 6)) {
            if (cell.getX() == 0 || cell.getX() == 18 || cell.getY() == 0 || cell.getY() == 5
                || cell.getZ() == 0 || cell.getZ() == 6) {
                helper.setBlock(cell, Blocks.STONE);
            }
        }
        Zombie target = f.zombie(12, 1, 2);
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(
            f.level.getMaxLocalRawBrightness(target.blockPosition()) == 0, "the enclosed room is actually dark"))
            .thenExecute(() -> {
                helper.assertTrue(SpottedTargets.sight(f.observer, target) == 8,
                    "zero light retains the eight-block dark-distance floor");
                helper.assertFalse(SpottedTargets.sees(f.observer, target),
                    "darkness hides a target ten blocks away");
                f.move(target, 4, 1, 2);
                helper.assertTrue(SpottedTargets.sees(f.observer, target),
                    "close enemies remain visible in total darkness");
                helper.setBlock(new BlockPos(3, 1, 2), Blocks.STONE);
                helper.setBlock(new BlockPos(3, 2, 2), Blocks.STONE);
                helper.assertFalse(SpottedTargets.sees(f.observer, target),
                    "the close-range floor never sees through walls");
                helper.setBlock(new BlockPos(3, 1, 2), Blocks.AIR);
                helper.setBlock(new BlockPos(3, 2, 2), Blocks.AIR);
                f.move(target, 12, 1, 2);
                helper.setBlock(new BlockPos(12, 1, 4), Blocks.SOUL_TORCH);
            }).thenWaitUntil(() -> helper.assertTrue(
                f.level.getMaxLocalRawBrightness(target.blockPosition()) == 8,
                "the real soul torch supplies intermediate target light"))
            .thenExecute(() -> {
                helper.assertTrue(SpottedTargets.sight(f.observer, target) == 20,
                    "effective light eight interpolates the default distance to twenty");
                helper.setBlock(new BlockPos(12, 1, 4), Blocks.SEA_LANTERN);
            }).thenWaitUntil(() -> helper.assertTrue(
                f.level.getMaxLocalRawBrightness(target.blockPosition()) >= FolkwaysVanillaConfig.fullSightLightLevel(),
                "the actual block light reaches the target"))
            .thenExecute(() -> helper.assertTrue(SpottedTargets.sees(f.observer, target),
                "lighting the target restores discovery without lighting the observer"))
            .thenSucceed();
    }

    @GameTest(template = "empty_large")
    public static void globalCapNormalizesMinimumAndReportsKeepTheirExistingLifetime(GameTestHelper helper) {
        Scene f = new Scene(helper);
        Zombie target = f.zombie(4, 1, 2);
        target.setGlowingTag(true);
        SpottedTargets spots = SpottedTargets.of(f.colony);
        helper.assertTrue(spots.report(f.observer, target), "the observer makes a real report");
        target.setGlowingTag(false);
        helper.assertTrue(spots.get(f.observer, target.getUUID()).isPresent(),
            "changing visibility does not delete a genuine report before its normal expiry");
        ModConfigSpec.IntValue maximum = FolkwaysVanillaConfig.SPEC.getValues().get("sentries.maximumSightRadius");
        ModConfigSpec.IntValue minimum = FolkwaysVanillaConfig.SPEC.getValues().get("sentries.minimumSightRadius");
        int oldMaximum = maximum.get();
        int oldMinimum = minimum.get();
        ModConfigSpec.IntValue dark = FolkwaysVanillaConfig.SPEC.getValues().get("sentries.darkSightLightLevel");
        ModConfigSpec.IntValue full = FolkwaysVanillaConfig.SPEC.getValues().get("sentries.fullSightLightLevel");
        int oldDark = dark.get();
        int oldFull = full.get();
        try {
            dark.set(14);
            full.set(1);
            helper.assertTrue(Double.isFinite(SpottedTargets.sight(f.observer, target)),
                "overlapping light thresholds normalize without division by zero");
            maximum.set(8);
            minimum.set(16);
            helper.assertTrue(SpottedTargets.maximumSight(f.observer) == 8,
                "the hard cap wins even when minimum is configured above it");
            helper.assertTrue(SpottedTargets.sight(f.observer, target) == 8,
                "pairwise sight uses the same normalized clamp");
            f.move(target, 12, 1, 2);
            helper.assertFalse(SpottedTargets.sees(f.observer, target), "the hard cap also bounds physical detection");
        } finally {
            maximum.set(oldMaximum);
            minimum.set(oldMinimum);
            dark.set(oldDark);
            full.set(oldFull);
        }
        helper.succeed();
    }

    private static final class Scene {
        private final GameTestHelper helper;
        private final ServerLevel level;
        private final Colony colony;
        private final List<Entity> entities = new ArrayList<>();
        private final ResidentEntity observer;

        private Scene(GameTestHelper helper) {
            this.helper = helper;
            level = helper.getLevel();
            colony = Colonies.mint(level.getServer());
            for (int x = 0; x <= 24; x++) {
                for (int z = 0; z <= 6; z++) {
                    helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
                }
            }
            observer = resident(2, 1, 2);
            Cleanup.on(helper, this::close);
        }

        private void move(Entity entity, int x, int y, int z) {
            BlockPos at = helper.absolutePos(new BlockPos(x, y, z));
            entity.moveTo(at.getX() + .5, at.getY(), at.getZ() + .5);
        }

        private ResidentEntity resident(int x, int y, int z) {
            ResidentEntity body = PersonBody.RESIDENT.get().create(level);
            move(body, x, y, z);
            body.setNoAi(true);
            body.setNoGravity(true);
            level.addFreshEntity(body);
            colony.hold(PersonContent.ID, Claimed.Entity.of(body)).orElseThrow();
            body.joinColony(colony.id());
            entities.add(body);
            return body;
        }

        private Zombie zombie(int x, int y, int z) {
            Zombie body = EntityType.ZOMBIE.create(level);
            move(body, x, y, z);
            body.setNoAi(true);
            body.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.CARVED_PUMPKIN));
            level.addFreshEntity(body);
            entities.add(body);
            return body;
        }

        private void close() {
            entities.forEach(Entity::discard);
            colony.raze();
        }
    }
}
