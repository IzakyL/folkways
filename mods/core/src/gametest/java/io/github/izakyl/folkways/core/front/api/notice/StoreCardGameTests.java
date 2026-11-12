package io.github.izakyl.folkways.core.front.api.notice;

import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.core.inventory.api.InventorySnapshot;
import io.github.izakyl.folkways.core.inventory.api.SlotSnapshot;
import io.github.izakyl.folkways.core.inventory.api.Stash;
import io.github.izakyl.folkways.core.item.api.BlockNames;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.vanilla.wares.StoreContents;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysCoreMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysCoreMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class StoreCardGameTests {

    private static final String TEMPLATE = "empty";

    private StoreCardGameTests() {
    }

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(StoreCardGameTests.class);
    }

    private static InventorySnapshot.Store store(GameTestHelper helper, BlockPos cell, InventorySnapshot.State state,
            long contents, List<ItemStack> goods, int size) {
        WorldPos at = WorldPos.of(helper.getLevel(), helper.absolutePos(cell));
        List<SlotSnapshot> slots = new ArrayList<>();
        for (int slot = 0; slot < size; slot++) {
            slots.add(new SlotSnapshot(slot, slot, slot < goods.size() ? goods.get(slot) : ItemStack.EMPTY, 64));
        }
        UUID epoch = UUID.randomUUID();
        return new InventorySnapshot.Store(Stash.at(at), Set.of(at), helper.getLevel().dimension(), 1,
            new InventorySnapshot.Version(epoch, contents, 0, 0, 0, 0), state, true, List.of(), slots, Set.of(),
            new InventorySnapshot.Approach(0, state, Set.of()));
    }

    @GameTest(template = TEMPLATE, templateNamespace = "folkways_core")
    public static void storeCardsSumTheMostPlentifulGoodsAndCountTheRest(GameTestHelper helper) {
        List<Item> kinds = List.of(Items.STONE, Items.DIRT, Items.SAND, Items.GRAVEL, Items.COAL, Items.IRON_INGOT,
            Items.GOLD_INGOT, Items.REDSTONE, Items.BONE, Items.STRING, Items.FEATHER);
        List<ItemStack> goods = new ArrayList<>();
        for (int at = 0; at < kinds.size(); at++) {
            goods.add(new ItemStack(kinds.get(at), at + 1));
        }
        // A second stack of the least plentiful kind makes it the most plentiful once summed.
        goods.add(new ItemStack(Items.STONE, 64));
        var barrel = store(helper, new BlockPos(1, 1, 1), InventorySnapshot.State.OBSERVED, 1, goods, 27);
        Line.Contents contents = StoreContents.of(List.of(barrel));
        helper.assertTrue(contents.stacks().size() == Line.Contents.MOST_SHOWN, "only the most plentiful are shown");
        helper.assertTrue(contents.stacks().getFirst().item().equals(BuiltInRegistries.ITEM.getKey(Items.STONE))
            && contents.stacks().getFirst().count() == 65, "equal goods are summed across slots: " + contents);
        for (int at = 1; at < contents.stacks().size(); at++) {
            helper.assertTrue(contents.stacks().get(at - 1).count() >= contents.stacks().get(at).count(),
                "goods are shown most plentiful first: " + contents);
        }
        helper.assertTrue(contents.more() == kinds.size() - Line.Contents.MOST_SHOWN,
            "the kinds left out are counted: " + contents);
        helper.assertTrue(contents.used() == goods.size() && contents.total() == 27,
            "a container tells its taken slots out of all: " + contents);

        var empty = store(helper, new BlockPos(2, 1, 1), InventorySnapshot.State.OBSERVED, 1, List.of(), 27);
        Line.Contents nothing = StoreContents.of(List.of(empty));
        helper.assertTrue(nothing.empty() && nothing.total() == 27, "an empty store reads as empty: " + nothing);

        Line.Contents shared = StoreContents.of(List.of(barrel, empty));
        helper.assertTrue(shared.stacks().getFirst().count() == 65 && shared.total() == 54,
            "several stores sum together, as an access point's members do: " + shared);
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, templateNamespace = "folkways_core")
    public static void storeCardsKeepLastReadGoodsWhileAStoreIsReadAgain(GameTestHelper helper) {
        List<ItemStack> goods = List.of(new ItemStack(Items.BREAD, 3));
        helper.assertTrue(StoreContents.known(store(helper, new BlockPos(1, 1, 1),
            InventorySnapshot.State.DIRTY, 2, goods, 9)), "a store read before shows its goods while marked dirty");
        helper.assertTrue(!StoreContents.known(store(helper, new BlockPos(1, 1, 1),
            InventorySnapshot.State.DIRTY, 0, List.of(), 9)), "a store never read is still being checked");
        helper.assertTrue(!StoreContents.known(store(helper, new BlockPos(1, 1, 1),
            InventorySnapshot.State.QUEUED, 0, List.of(), 9)), "a queued store is still being checked");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, templateNamespace = "folkways_core")
    public static void storeContentsSurviveThePacketAndRefuseTooManyGoods(GameTestHelper helper) {
        var registries = helper.getLevel().registryAccess();
        List<Line.Stack> stacks = List.of(new Line.Stack(ResourceLocation.withDefaultNamespace("stone"), 12_000L),
            new Line.Stack(ResourceLocation.withDefaultNamespace("dirt"), 5L));
        List<Line> sent = List.of(Line.contents(stacks, 3, 7, 27), Line.contents(List.of(), 0, 0, 0));
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), registries);
        try {
            Line.encodeAll(buffer, sent, 8);
            helper.assertTrue(Line.decodeAll(buffer, 8).equals(sent) && buffer.readableBytes() == 0,
                "store contents survive the card packet");
        } finally {
            buffer.release();
        }
        var flooded = new RegistryFriendlyByteBuf(Unpooled.buffer(), registries);
        try {
            flooded.writeVarInt(1);
            flooded.writeVarInt(9);
            flooded.writeVarInt(Line.Contents.MOST_SHOWN + 1);
            boolean rejected = false;
            try {
                Line.decodeAll(flooded, 8);
            } catch (DecoderException expected) {
                rejected = true;
            }
            helper.assertTrue(rejected, "a card cannot carry more goods than it shows");
        } finally {
            flooded.release();
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, templateNamespace = "folkways_core")
    public static void blocksAreNamedByTheirOwnTranslation(GameTestHelper helper) {
        helper.assertTrue(BlockNames.of(Blocks.CHEST).getContents() instanceof TranslatableContents translatable
            && translatable.getKey().equals(Blocks.CHEST.getDescriptionId()), "a translated block keeps its own key");
        helper.succeed();
    }
}
