package io.github.izakyl.folkways.core.engine.labor;

import com.github.ysbbbbbb.kaleidoscopecookery.init.ModItems;
import com.github.ysbbbbbb.kaleidoscopecookery.init.tag.TagMod;
import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.Held;
import io.github.izakyl.folkways.core.api.resident.body.Body;
import io.github.izakyl.folkways.core.api.resident.body.Equipment;
import io.github.izakyl.folkways.core.api.terms.Containers;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemFilter;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.vocation.Vocations;
import io.github.izakyl.folkways.core.api.work.Delivery;
import io.github.izakyl.folkways.core.engine.colony.ColonyData;
import io.github.izakyl.folkways.core.engine.colony.ColonyWays;
import io.github.izakyl.folkways.core.engine.plan.Message;
import io.github.izakyl.folkways.front.engine.colony.ColonyFront;
import io.github.izakyl.folkways.front.engine.colony.ColonySettings;
import io.github.izakyl.folkways.cookery.CookeryContent;
import io.github.izakyl.folkways.plugins.farming.FarmingContent;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CookeryEquipmentSurveyGameTests {
    private static final ResourceLocation STORE = ResourceLocation.fromNamespaceAndPath("folkways", "test/clothing_store");

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) { event.register(CookeryEquipmentSurveyGameTests.class); }

    @GameTest(template = "empty")
    public static void togglingHatWearingRechecksAnIdleHandWithoutAnyInventoryOrLicenceChange(GameTestHelper helper) {
        try (var rig = new Rig(helper)) {
            rig.flag(false);
            rig.survey.read(rig.body.id()); rig.read(); rig.messages.clear();
            long licence = rig.body.licences().revision();
            rig.flag(true); rig.read();
            Delivery requested = rig.clothingDeliveries().getFirst();
            helper.assertValueEqual(rig.clothingDeliveries().size(), 1, "the new setting publishes exactly one real clothing delivery");
            helper.assertTrue(requested.spec().site() instanceof io.github.izakyl.folkways.core.api.work.WorkSite.AtEntity into
                    && into.entity().equals(rig.body.id()), "the actual receipt is addressed to this resident's physical pack");
            helper.assertValueEqual(rig.body.licences().revision(), licence, "the clothing setting did not secretly refresh a licence");
            helper.assertTrue(rig.body.getItemBySlot(EquipmentSlot.HEAD).isEmpty(), "publishing a delivery does not fabricate an equipped hat");
            helper.assertValueEqual(rig.hats(), 1L, "the one actual hat still waits in its original store");
            for (int pass = 0; pass < 10; pass++) rig.read();
            helper.assertValueEqual(rig.clothingDeliveries().size(), 1, "stable missing clothing does not publish repeated deliveries");
            rig.flag(false); rig.read();
            helper.assertTrue(rig.messages.stream().anyMatch(message -> message instanceof Message.Withdrawn withdrawn
                    && withdrawn.node().equals(requested.id())), "turning wearing off withdraws the old real delivery");
            helper.assertValueEqual(rig.hats(), 1L, "withdrawing an unspent delivery preserves the physical store item");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void changingTheClothingAllowlistReplacesTheMissingHatDeliveryWithoutDuplicatingIt(GameTestHelper helper) {
        try (var rig = new Rig(helper)) {
            rig.allow(ModItems.STRAW_HAT.get());
            rig.survey.read(rig.body.id()); rig.read();
            Delivery old = rig.clothingDeliveries().getFirst(); rig.messages.clear();
            rig.allow(ModItems.STRAW_HAT_FLOWER.get()); rig.read();
            helper.assertValueEqual(rig.clothingDeliveries().size(), 1, "the changed allowlist publishes one replacement demand");
            Delivery next = rig.clothingDeliveries().getFirst();
            helper.assertTrue(!next.id().equals(old.id())
                    && Goods.matches(ModItems.STRAW_HAT_FLOWER.get().getDefaultInstance(), next.goods().spec())
                    && !Goods.matches(ModItems.STRAW_HAT.get().getDefaultInstance(), next.goods().spec()),
                "the replacement actually requires the newly allowed flower hat");
            helper.assertTrue(rig.messages.stream().anyMatch(message -> message instanceof Message.Withdrawn withdrawn
                    && withdrawn.node().equals(old.id())), "the disallowed original hat receipt is withdrawn");
            for (int pass = 0; pass < 10; pass++) rig.read();
            helper.assertValueEqual(rig.clothingDeliveries().size(), 1, "a stable selector does not repeat its replacement demand");
            helper.assertTrue(rig.body.getItemBySlot(EquipmentSlot.HEAD).isEmpty(), "an unavailable selected hat is never synthesized");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 600)
    public static void anIdleResidentFetchesAndWearsTheSameNamedHatAfterTheRealSettingIsEnabled(GameTestHelper helper) {
        var rig = new Rig(helper);
        rig.flag(false);
        rig.allow(ModItems.STRAW_HAT.get());
        for (var vocation : Vocations.all()) {
            if (!vocation.equals(FarmingContent.trade()) && !vocation.id().equals(Vocations.HAULING)) {
                rig.body.licences().trade(vocation).ifPresent(trade -> rig.body.licences().allow(trade, false));
            }
        }
        rig.body.pack().setItem(0, new ItemStack(Items.COOKED_BEEF, 12));
        rig.body.pack().setItem(1, new ItemStack(Items.WOODEN_HOE));
        rig.level.addFreshEntity(rig.body);
        helper.assertTrue(rig.colony.hold(PersonBody.ID, Held.Entity.of(rig.body)).isPresent(), "the real resident joins the actual colony");
        ColonyLabor labor = new ColonyLabor(rig.data, rig.level.dimension());
        int[] tick = {0}, phase = {0}, stable = {0};
        boolean[] done = {false};
        Runnable close = () -> { if (!done[0]) { done[0] = true; labor.close(); rig.close(); } };
        helper.onEachTick(() -> {
            if (done[0]) return;
            try {
                tick[0]++;
                ColonyWays.advance(rig.data, rig.level);
                labor.run(rig.level, Lanes.INLINE);
                helper.assertValueEqual(rig.hats(), 1L, "actual chest, pack and native slot retain exactly one physical hat");
                if (phase[0] == 0 && tick[0] >= 20) {
                    helper.assertTrue(rig.body.getItemBySlot(EquipmentSlot.HEAD).isEmpty(), "disabled idle wearing leaves HEAD empty");
                    rig.flag(true); phase[0] = 1;
                } else if (phase[0] == 1 && rig.body.getItemBySlot(EquipmentSlot.HEAD).is(TagMod.STRAW_HAT)) {
                    helper.assertTrue(Equipment.managed(rig.body, EquipmentSlot.HEAD), "the real delivery becomes a managed native HEAD item");
                    helper.assertTrue(ItemStack.isSameItemSameComponents(rig.expected, rig.body.getItemBySlot(EquipmentSlot.HEAD)),
                        "the actual named hat and its components survived logistics");
                    helper.assertValueEqual(Goods.countIn(rig.store, ItemSpec.of(TagMod.STRAW_HAT)), 0L, "the original physical store slot was spent exactly once");
                    rig.flag(false); phase[0] = 2;
                } else if (phase[0] == 2 && rig.body.getItemBySlot(EquipmentSlot.HEAD).isEmpty()) {
                    helper.assertFalse(Equipment.managed(rig.body, EquipmentSlot.HEAD), "turning wearing off releases native slot ownership");
                    rig.flag(true); phase[0] = 3;
                } else if (phase[0] == 3 && rig.body.getItemBySlot(EquipmentSlot.HEAD).is(TagMod.STRAW_HAT) && ++stable[0] >= 30) {
                    helper.assertTrue(Equipment.managed(rig.body, EquipmentSlot.HEAD), "stable wearing keeps the true native slot owner");
                    helper.assertValueEqual(Goods.countIn(rig.body.pack(), ItemSpec.of(TagMod.STRAW_HAT)), 0L, "no spare hat accumulates in the real pack");
                    close.run(); helper.succeed();
                }
                if (tick[0] >= 550) {
                    String detail = labor.heartbeat() + "; phase=" + phase[0] + "; HEAD=" + rig.body.getItemBySlot(EquipmentSlot.HEAD);
                    close.run(); helper.fail("the real logistics must fetch and equip the existing hat: " + detail);
                }
            } catch (RuntimeException | AssertionError broken) { close.run(); throw broken; }
        });
    }

    private static final class Rig implements AutoCloseable {
        final ServerLevel level;
        final Colony colony;
        final ColonyData data;
        final ResidentEntity body;
        final Container store;
        final ItemStack expected;
        final Survey survey = new Survey();
        final Kits kits = new Kits();
        final List<Message> messages = new ArrayList<>();
        final GameTestHelper helper;
        Rig(GameTestHelper helper) {
            this.helper = helper; level = helper.getLevel();
            for (BlockPos cell : BlockPos.betweenClosed(helper.absolutePos(BlockPos.ZERO), helper.absolutePos(new BlockPos(6, 0, 6)))) {
                level.setBlockAndUpdate(cell, Blocks.STONE.defaultBlockState());
            }
            BlockPos chest = helper.absolutePos(new BlockPos(2, 1, 2));
            level.setBlockAndUpdate(chest, Blocks.CHEST.defaultBlockState()); store = Containers.at(level, chest).orElseThrow();
            colony = Colonies.mint(level.getServer()); data = ColonyData.find(level.getServer(), colony.id()).orElseThrow();
            colony.stores(STORE, level.dimension(), List.of(WorldPos.of(level, chest)));
            expected = new ItemStack(ModItems.STRAW_HAT.get()); expected.set(DataComponents.CUSTOM_NAME, Component.literal("the physical field hat"));
            store.setItem(0, expected.copy());
            body = PersonBody.RESIDENT.get().create(level);
            body.moveTo(chest.getX() + 1.5, chest.getY(), chest.getZ() + 1.5); body.setOnGround(true);
        }
        void flag(boolean enabled) {
            helper.assertTrue(ColonyFront.of(colony).setSetting(CookeryContent.ID, "wear_hat", new ColonySettings.Value.Flag(enabled)),
                "the real setting changes the wearing flag");
        }
        void allow(Item item) {
            ColonyFront.of(colony).setSetting(CookeryContent.ID, "clothing", new ColonySettings.Value.Items(List.of(ItemFilter.item(BuiltInRegistries.ITEM.getKey(item)))));
        }
        void read() { survey.answer(level, data, Map.<UUID, Body>of(body.id(), body), seen -> kits.read(colony, seen, messages::add)); }
        List<Delivery> clothingDeliveries() {
            return messages.stream().filter(message -> message instanceof Message.Submitted).map(message -> (Message.Submitted) message)
                .flatMap(message -> message.work().nodes().stream()).filter(node -> node instanceof Delivery).map(node -> (Delivery) node)
                .filter(delivery -> Goods.overlap(delivery.goods().spec(), ItemSpec.of(TagMod.STRAW_HAT))).toList();
        }
        long hats() { return Goods.countIn(store, ItemSpec.of(TagMod.STRAW_HAT)) + Goods.countIn(body.pack(), ItemSpec.of(TagMod.STRAW_HAT))
            + Equipment.worn(body, ItemSpec.of(TagMod.STRAW_HAT)); }
        public void close() { body.discard(); colony.raze(); ColonyWays.forget(colony.id()); }
    }
}
