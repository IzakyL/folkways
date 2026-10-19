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
    public static void togglingTaskClothingDoesNotCreateAPermanentIdleResidentDelivery(GameTestHelper helper) {
        try (var rig = new Rig(helper)) {
            rig.flag(false); rig.survey.read(rig.body.id()); rig.read(); rig.messages.clear();
            long licence = rig.body.licences().revision();
            rig.flag(true);
            for (int pass = 0; pass < 10; pass++) rig.read();
            helper.assertTrue(rig.clothingDeliveries().isEmpty(), "allowing task borrowing creates no standing per-resident delivery");
            helper.assertValueEqual(rig.body.licences().revision(), licence, "changing permission does not refresh unrelated licences");
            helper.assertTrue(rig.body.getItemBySlot(EquipmentSlot.HEAD).isEmpty(), "an idle hand remains bare-headed");
            rig.flag(false); rig.read();
            helper.assertTrue(rig.clothingDeliveries().isEmpty(), "neither permission transition delivers personal clothing");
            helper.assertValueEqual(rig.hats(), 1L, "the original shared item remains real store stock");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void changingTheClothingAllowlistDoesNotQueueUnusedPersonalEquipment(GameTestHelper helper) {
        try (var rig = new Rig(helper)) {
            rig.allow(ModItems.STRAW_HAT.get()); rig.survey.read(rig.body.id()); rig.read();
            rig.allow(ModItems.STRAW_HAT_FLOWER.get());
            for (int pass = 0; pass < 10; pass++) rig.read();
            helper.assertTrue(rig.clothingDeliveries().isEmpty(), "selecting allowed task clothing does not order one hat for every farmer");
            helper.assertTrue(Equipment.wanted(rig.colony, rig.body).isEmpty(), "the kit cache exposes no permanent Cookery armor demand");
            helper.assertTrue(rig.body.getItemBySlot(EquipmentSlot.HEAD).isEmpty(), "unused selected clothing is never synthesized or equipped");
            helper.assertValueEqual(rig.hats(), 1L, "the allowlist does not consume the one physical stored hat");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 220)
    public static void realIdleColonyLaborNeverFetchesAStandingHatAcrossPermissionChanges(GameTestHelper helper) {
        var rig = new Rig(helper);
        rig.flag(false); rig.allow(ModItems.STRAW_HAT.get());
        for (var vocation : Vocations.all()) {
            if (!vocation.equals(FarmingContent.trade()) && !vocation.id().equals(Vocations.HAULING)) {
                rig.body.licences().trade(vocation).ifPresent(trade -> rig.body.licences().allow(trade, false));
            }
        }
        rig.body.pack().setItem(0, new ItemStack(Items.COOKED_BEEF, 12));
        rig.body.pack().setItem(1, new ItemStack(Items.WOODEN_HOE));
        rig.level.addFreshEntity(rig.body);
        helper.assertTrue(rig.colony.hold(PersonBody.ID, Held.Entity.of(rig.body)).isPresent(), "the real farmer joins the actual colony");
        ColonyLabor labor = new ColonyLabor(rig.data, rig.level.dimension());
        int[] tick = {0}; boolean[] done = {false};
        Runnable close = () -> { if (!done[0]) { done[0] = true; labor.close(); rig.close(); } };
        helper.onEachTick(() -> {
            if (done[0]) return;
            try {
                tick[0]++;
                if (tick[0] == 20 || tick[0] == 80) rig.flag(true);
                if (tick[0] == 60) rig.flag(false);
                ColonyWays.advance(rig.data, rig.level); labor.run(rig.level, Lanes.INLINE);
                helper.assertValueEqual(rig.hats(), 1L, "actual store, cargo and armor conserve one physical hat");
                helper.assertTrue(rig.body.getItemBySlot(EquipmentSlot.HEAD).isEmpty(), "idle native HEAD stays empty through real labor surveys");
                helper.assertTrue(Equipment.wanted(rig.colony, rig.body).isEmpty(), "no per-resident Cookery clothing kit is published");
                if (tick[0] >= 140) {
                    helper.assertValueEqual(Goods.countIn(rig.store, ItemSpec.of(TagMod.STRAW_HAT)), 1L,
                        "without a beneficial Cookery task the hat remains in the shared store");
                    close.run(); helper.succeed();
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
