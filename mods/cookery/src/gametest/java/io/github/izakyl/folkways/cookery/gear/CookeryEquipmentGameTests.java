package io.github.izakyl.folkways.cookery.gear;

import com.github.ysbbbbbb.kaleidoscopecookery.init.ModItems;
import com.github.ysbbbbbb.kaleidoscopecookery.init.tag.TagMod;
import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.resident.body.Equipment;
import io.github.izakyl.folkways.core.api.terms.Containers;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemDrops;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.front.engine.colony.ColonyFront;
import io.github.izakyl.folkways.front.engine.colony.ColonySettings;
import io.github.izakyl.folkways.cookery.CookeryContent;
import io.github.izakyl.folkways.plugins.farming.FarmingContent;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = "folkways_cookery", bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CookeryEquipmentGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) { event.register(CookeryEquipmentGameTests.class); }

    @GameTest(template = "empty")
    public static void equippingMovesOneRealHatAndPreservesItsComponents(GameTestHelper helper) {
        try (var rig = new Rig(helper)) {
            ItemStack hat = new ItemStack(ModItems.STRAW_HAT_FLOWER.get());
            hat.set(DataComponents.CUSTOM_NAME, Component.literal("field hat"));
            ItemStack expected = hat.copy();
            rig.body.pack().setItem(0, hat);
            helper.assertTrue(Equipment.reconcile(rig.colony, rig.body), "the actual hat moves into its native slot");
            helper.assertTrue(rig.body.pack().isEmpty(), "there is no shadow hat in the pack");
            helper.assertTrue(ItemStack.matches(expected, rig.body.getItemBySlot(EquipmentSlot.HEAD)), "actual components survive equipping");
            helper.assertTrue(Equipment.managed(rig.body, EquipmentSlot.HEAD), "ownership belongs to the real slot");
            helper.assertValueEqual(Equipment.carried(rig.body, new Equipment.Demand(EquipmentSlot.HEAD,
                ItemSpec.of(TagMod.STRAW_HAT))), 1L, "worn equipment satisfies the delivery without a spare");
            helper.assertFalse(Equipment.reconcile(rig.colony, rig.body), "repeating reconciliation does not clone or replace it");
            rig.flag("wear_hat", false);
            Equipment.reconcile(rig.colony, rig.body);
            helper.assertTrue(rig.body.getItemBySlot(EquipmentSlot.HEAD).isEmpty(), "turning off wearing removes the managed hat");
            helper.assertTrue(ItemStack.matches(expected, rig.body.pack().getItem(0)), "the same named hat returns to the pack");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void unrelatedPlayerGivenEquipmentIsNeverOverwrittenOrUnworn(GameTestHelper helper) {
        try (var rig = new Rig(helper)) {
            rig.body.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.DIAMOND_HELMET));
            rig.body.pack().setItem(0, new ItemStack(ModItems.STRAW_HAT.get()));
            Equipment.reconcile(rig.colony, rig.body);
            helper.assertTrue(rig.body.getItemBySlot(EquipmentSlot.HEAD).is(Items.DIAMOND_HELMET), "a foreign helmet remains equipped");
            helper.assertTrue(Equipment.wanted(rig.colony, rig.body).stream().noneMatch(demand -> demand.slot() == EquipmentSlot.HEAD),
                "a blocked clothing slot cannot trigger endless spare-hat deliveries");
            Equipment.release(rig.body);
            helper.assertTrue(rig.body.getItemBySlot(EquipmentSlot.HEAD).is(Items.DIAMOND_HELMET), "release only touches managed slots");
            helper.assertValueEqual(Goods.countIn(rig.body.pack(), ItemSpec.of(TagMod.STRAW_HAT)), 1L, "the unneeded hat stays physical cargo");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void disablingTheFarmingLicenceReturnsItsActualHat(GameTestHelper helper) {
        try (var rig = new Rig(helper)) {
            rig.body.pack().setItem(0, new ItemStack(ModItems.STRAW_HAT.get()));
            Equipment.reconcile(rig.colony, rig.body);
            rig.body.licences().allow(rig.body.licences().trade(FarmingContent.trade()).orElseThrow(), false);
            Equipment.reconcile(rig.colony, rig.body);
            helper.assertTrue(rig.body.getItemBySlot(EquipmentSlot.HEAD).isEmpty(), "a disabled farmer stops wearing managed clothing");
            helper.assertValueEqual(Goods.countIn(rig.body.pack(), ItemSpec.of(TagMod.STRAW_HAT)), 1L, "its one hat returns for normal storage");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void fullPackUnwearingDropsOneActualHatInsteadOfDestroyingIt(GameTestHelper helper) {
        try (var rig = new Rig(helper)) {
            rig.body.pack().setItem(0, new ItemStack(ModItems.STRAW_HAT.get()));
            Equipment.reconcile(rig.colony, rig.body);
            for (int slot = 0; slot < rig.body.pack().getContainerSize(); slot++) rig.body.pack().setItem(slot, new ItemStack(Items.DIRT, 64));
            var released = ItemDrops.capture(helper.getLevel(), rig.body.getBoundingBox().inflate(2), () -> Equipment.release(rig.body));
            helper.assertValueEqual(released.items().stream().filter(stack -> stack.is(TagMod.STRAW_HAT)).mapToInt(ItemStack::getCount).sum(), 1,
                "one displaced physical hat is safely dropped");
            helper.assertTrue(rig.body.getItemBySlot(EquipmentSlot.HEAD).isEmpty(), "no second hat remains in the slot");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void nativeSaveAndReloadKeepSlotOwnershipWithoutCreatingAnotherHat(GameTestHelper helper) {
        try (var rig = new Rig(helper)) {
            rig.body.pack().setItem(0, new ItemStack(ModItems.STRAW_HAT.get()));
            Equipment.reconcile(rig.colony, rig.body);
            CompoundTag saved = new CompoundTag();
            rig.body.saveWithoutId(saved);
            var restored = PersonBody.RESIDENT.get().create(helper.getLevel());
            try {
                restored.load(saved);
                helper.assertTrue(Equipment.managed(restored, EquipmentSlot.HEAD), "native NBT restores managed-slot ownership");
                helper.assertFalse(Equipment.reconcile(rig.colony, restored), "restoring an already worn hat needs no new item");
                helper.assertValueEqual(Equipment.worn(restored, ItemSpec.of(TagMod.STRAW_HAT)), 1L, "one hat stays in the native slot");
                helper.assertTrue(restored.pack().isEmpty(), "reloading has no hidden pack copy");
            } finally { restored.discard(); }
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void actualHeadEquipmentEnablesTheNativeShortGrassSeedLoot(GameTestHelper helper) {
        try (var rig = new Rig(helper)) {
            var level = helper.getLevel();
            var grass = Blocks.SHORT_GRASS.defaultBlockState();
            var table = level.getServer().reloadableRegistries().getLootTable(Blocks.SHORT_GRASS.getLootTable());
            var params = new LootParams.Builder(level).withParameter(LootContextParams.BLOCK_STATE, grass)
                .withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(helper.absolutePos(BlockPos.ZERO)))
                .withParameter(LootContextParams.TOOL, ItemStack.EMPTY).withOptionalParameter(LootContextParams.THIS_ENTITY, rig.body)
                .create(LootContextParamSets.BLOCK);
            rig.body.pack().setItem(0, new ItemStack(ModItems.STRAW_HAT.get()));
            for (long seed = 1; seed <= 32; seed++) {
                helper.assertTrue(table.getRandomItems(params, seed).stream().noneMatch(CookeryEquipmentGameTests::modSeed),
                    "a hat merely carried in the pack cannot enable the native HEAD loot condition");
            }
            Equipment.reconcile(rig.colony, rig.body);
            boolean found = false;
            for (long seed = 1; seed <= 128; seed++) found |= table.getRandomItems(params, seed).stream().anyMatch(CookeryEquipmentGameTests::modSeed);
            helper.assertTrue(found, "fixed real loot seeds with a real resident HEAD produce native Cookery seeds");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void theActualFarmerSetReceivesItsNativeWaterEffect(GameTestHelper helper) {
        var rig = new Rig(helper);
        rig.flag("wear_farmer_armor", true);
        for (var item : java.util.List.of(ModItems.STRAW_HAT.get(), ModItems.FARMER_CHEST_PLATE.get(),
                ModItems.FARMER_LEGGINGS.get(), ModItems.FARMER_BOOTS.get())) {
            helper.assertTrue(Containers.insert(rig.body.pack(), new ItemStack(item)).isEmpty(), "the physical set fits before equipping");
        }
        Equipment.reconcile(rig.colony, rig.body);
        BlockPos water = helper.absolutePos(new BlockPos(2, 1, 2));
        for (BlockPos cell : BlockPos.betweenClosed(water.offset(-1, 0, -1), water.offset(1, 2, 1))) {
            helper.getLevel().setBlockAndUpdate(cell, Blocks.WATER.defaultBlockState());
        }
        for (BlockPos floor : BlockPos.betweenClosed(water.offset(-1, -1, -1), water.offset(1, -1, 1))) {
            helper.getLevel().setBlockAndUpdate(floor, Blocks.STONE.defaultBlockState());
        }
        rig.body.setNoAi(true);
        rig.body.moveTo(water.getX() + 0.5, water.getY(), water.getZ() + 0.5);
        helper.getLevel().addFreshEntity(rig.body);
        helper.runAtTickTime(30, () -> {
            try (rig) {
                helper.assertTrue(rig.body.isInWater(), "the actual resident is submerged");
                helper.assertTrue(rig.body.hasEffect(MobEffects.DOLPHINS_GRACE), "Cookery's real LivingEntity tick handler grants its full-set effect");
                helper.succeed();
            }
        });
    }

    @GameTest(template = "empty")
    public static void nativeDeathDropsTheOneRealManagedHat(GameTestHelper helper) {
        try (var rig = new Rig(helper)) {
            rig.body.pack().setItem(0, new ItemStack(ModItems.STRAW_HAT.get()));
            Equipment.reconcile(rig.colony, rig.body);
            var died = ItemDrops.capture(helper.getLevel(), rig.body.getBoundingBox().inflate(3), () ->
                rig.body.hurt(helper.getLevel().damageSources().generic(), Float.MAX_VALUE));
            helper.assertTrue(rig.body.isDeadOrDying(), "the real resident died");
            helper.assertValueEqual(died.items().stream().filter(stack -> stack.is(TagMod.STRAW_HAT))
                .mapToInt(ItemStack::getCount).sum(), 1, "native armor death loot drops exactly its one managed hat");
            helper.assertTrue(rig.body.pack().isEmpty(), "there was never a pack duplicate to drop");
        }
        helper.succeed();
    }

    private static boolean modSeed(ItemStack stack) {
        return stack.is(ModItems.TOMATO_SEED.get()) || stack.is(ModItems.CHILI_SEED.get()) || stack.is(ModItems.LETTUCE_SEED.get())
            || stack.is(ModItems.WILD_RICE_SEED.get()) || stack.is(ModItems.TEA_SEED.get());
    }

    private static final class Rig implements AutoCloseable {
        final GameTestHelper helper;
        final Colony colony;
        final ResidentEntity body;
        Rig(GameTestHelper helper) {
            this.helper = helper;
            colony = Colonies.mint(helper.getLevel().getServer());
            body = PersonBody.RESIDENT.get().create(helper.getLevel());
            body.moveTo(helper.absolutePos(new BlockPos(2, 1, 2)).getCenter());
        }
        void flag(String key, boolean value) {
            helper.assertTrue(ColonyFront.of(colony).setSetting(CookeryContent.ID, key, new ColonySettings.Value.Flag(value)),
                "the actual kitchen setting accepts " + key);
        }
        public void close() { body.discard(); colony.raze(); }
    }
}
