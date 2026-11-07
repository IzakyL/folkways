package io.github.izakyl.folkways.base.persist.upgrade;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import java.util.function.Consumer;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = "folkways_core", bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways_persistence")
@PrefixGameTestTemplate(false)
public final class UpgradesGameTests {
    private UpgradesGameTests() {
    }

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(UpgradesGameTests.class);
    }

    private static Step step(Surface surface, int target, Consumer<CompoundTag> change) {
        return new Step() {
            @Override
            public int target() {
                return target;
            }

            @Override
            public Surface surface() {
                return surface;
            }

            @Override
            public void apply(CompoundTag raw) {
                change.accept(raw);
            }
        };
    }

    @GameTest(template = "empty")
    public static void chainSkipsUnchangedVersionsAndOtherSurfaces(GameTestHelper helper) {
        Upgrades upgrades = new Upgrades(2, 6);
        upgrades.add(step(Surface.ENTITY, 6, tag -> tag.putInt("value", tag.getInt("value") * 10)));
        upgrades.add(step(Surface.ENTITY, 3, tag -> tag.putInt("value", tag.getInt("value") + 2)));
        upgrades.add(step(Surface.CROP_CARE, 4, tag -> { throw new AssertionError("wrong surface"); }));
        upgrades.seal();
        CompoundTag raw = new CompoundTag();
        raw.putInt("value", 1);
        upgrades.run(Surface.ENTITY, raw, 2);
        helper.assertTrue(raw.getInt("value") == 30, "steps run in target order with gaps");
        upgrades.run(Surface.ENTITY, raw, 6);
        helper.assertTrue(raw.getInt("value") == 30, "current data is unchanged");
        upgrades.run(Surface.ENTITY, raw, 3);
        helper.assertTrue(raw.getInt("value") == 300, "stored target is excluded");
        helper.succeed();
    }

    private static void rejected(GameTestHelper helper, Runnable operation) {
        boolean rejected = false;
        try {
            operation.run();
        } catch (IllegalArgumentException | IllegalStateException expected) {
            rejected = true;
        }
        helper.assertTrue(rejected, "invalid registration/version must be rejected");
    }

    @GameTest(template = "empty")
    public static void registrationAndVersionBoundsAreEnforced(GameTestHelper helper) {
        Upgrades upgrades = new Upgrades(2, 4);
        Step valid = step(Surface.ENTITY, 3, tag -> tag.putBoolean("changed", true));
        upgrades.add(valid);
        rejected(helper, () -> upgrades.add(valid));
        upgrades.add(step(Surface.BLOCK_ENTITY, 3, tag -> { }));
        rejected(helper, () -> upgrades.add(step(Surface.ENTITY, 2, tag -> { })));
        rejected(helper, () -> upgrades.add(step(Surface.ENTITY, 5, tag -> { })));
        CompoundTag raw = new CompoundTag();
        rejected(helper, () -> upgrades.run(Surface.ENTITY, raw, 1));
        rejected(helper, () -> upgrades.run(Surface.ENTITY, raw, 5));
        helper.assertTrue(raw.isEmpty(), "unsupported input is never mutated");
        upgrades.seal();
        rejected(helper, () -> upgrades.add(step(Surface.ENTITY, 4, tag -> { })));
        rejected(helper, () -> upgrades.addBag("folkways:test", 4, tag -> { }));
        rejected(helper, () -> Upgrades.register(valid));
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void keepsComposeAcrossOwnersAndLeaveAbsentBagsAbsent(GameTestHelper helper) {
        Upgrades upgrades = new Upgrades(2, 4);
        upgrades.addBag("folkways:a", 3, bag -> bag.putInt("value", 3));
        upgrades.addBag("folkways:b", 3, bag -> bag.putInt("value", 4));
        upgrades.addBag("folkways:a", 4, bag -> bag.putInt("value", bag.getInt("value") + 1));
        rejected(helper, () -> upgrades.addBag("folkways:a", 3, bag -> { }));
        rejected(helper, () -> upgrades.addBag("folkways:a", 2, bag -> { }));
        rejected(helper, () -> upgrades.addBag("folkways:a", 5, bag -> { }));
        CompoundTag keeps = new CompoundTag();
        keeps.put("folkways:a", new CompoundTag());
        keeps.put("folkways:b", new CompoundTag());
        CompoundTag colony = new CompoundTag();
        colony.put("keeps", keeps);
        ListTag colonies = new ListTag();
        colonies.add(colony);
        colonies.add(new CompoundTag());
        CompoundTag raw = new CompoundTag();
        raw.put("colonies", colonies);
        upgrades.run(Surface.COLONY_REGISTRY, raw, 2);
        helper.assertTrue(keeps.getCompound("folkways:a").getInt("value") == 4, "owner a chains");
        helper.assertTrue(keeps.getCompound("folkways:b").getInt("value") == 4, "owner b migrates independently");
        helper.assertTrue(colonies.getCompound(1).isEmpty(), "absent bags are not created");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void modulesComposeWithinOneSurfaceAndVersion(GameTestHelper helper) {
        Upgrades upgrades = new Upgrades(2, 4);
        upgrades.addPart("vanilla", Surface.ENTITY, 3, tag -> tag.putInt("value", tag.getInt("value") * 10));
        upgrades.addPart("core", Surface.ENTITY, 3, tag -> tag.putInt("value", tag.getInt("value") + 2));
        upgrades.add(step(Surface.ENTITY, 3, tag -> tag.putInt("value", 1)));
        upgrades.addPart("vanilla", Surface.BLOCK_ENTITY, 3, tag -> { throw new AssertionError("wrong surface"); });
        rejected(helper, () -> upgrades.addPart("core", Surface.ENTITY, 3, tag -> { }));
        rejected(helper, () -> upgrades.addPart("core", Surface.ENTITY, 2, tag -> { }));
        rejected(helper, () -> upgrades.addPart("core", Surface.ENTITY, 5, tag -> { }));
        upgrades.seal();
        rejected(helper, () -> upgrades.addPart("cookery", Surface.ENTITY, 3, tag -> { }));
        CompoundTag raw = new CompoundTag();
        upgrades.run(Surface.ENTITY, raw, 2);
        helper.assertTrue(raw.getInt("value") == 30, "root runs first, module fragments follow in name order");
        upgrades.run(Surface.ENTITY, raw, 3);
        helper.assertTrue(raw.getInt("value") == 30, "already applied fragments never run again");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void codecPrefersCurrentFallsBackAndOnlyWritesCurrent(GameTestHelper helper) {
        Codec<Integer> current = Codec.INT.fieldOf("value").codec();
        Codec<Integer> legacy = Codec.STRING.comapFlatMap(value -> {
            try {
                return DataResult.success(Integer.parseInt(value));
            } catch (NumberFormatException exception) {
                return DataResult.error(() -> "invalid old integer");
            }
        }, Object::toString);
        Codec<Integer> codec = Upgrades.codec(current, legacy);
        helper.assertTrue(codec.parse(NbtOps.INSTANCE, StringTag.valueOf("12")).getOrThrow() == 12,
            "legacy shape converts");
        CompoundTag raw = new CompoundTag();
        raw.putInt("value", 7);
        helper.assertTrue(codec.parse(NbtOps.INSTANCE, raw).getOrThrow() == 7, "current shape decodes");
        helper.assertValueEqual(codec.encodeStart(NbtOps.INSTANCE, 8).getOrThrow(),
            current.encodeStart(NbtOps.INSTANCE, 8).getOrThrow(), "only current is encoded");
        Codec<Integer> both = Upgrades.codec(Codec.INT, Codec.INT.xmap(value -> -value, value -> -value));
        helper.assertTrue(both.parse(NbtOps.INSTANCE, net.minecraft.nbt.IntTag.valueOf(9)).getOrThrow() == 9,
            "current wins when both accept the input");
        helper.assertTrue(codec.parse(NbtOps.INSTANCE, new ListTag()).result().isEmpty(), "all failures propagate");
        helper.assertTrue(codec.parse(NbtOps.INSTANCE, StringTag.valueOf("bad")).result().isEmpty(),
            "failed legacy conversion stays an error");
        helper.succeed();
    }
}
