package io.github.izakyl.folkways.front.api.notice;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.resident.body.Body;
import io.github.izakyl.folkways.front.api.ui.Pages;
import io.github.izakyl.folkways.front.engine.colony.ColonyLooks;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.netty.buffer.Unpooled;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;

@EventBusSubscriber(modid = FolkwaysMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class TextSyncGameTests {

    private static final String TEMPLATE = "empty";

    private TextSyncGameTests() {
    }

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(TextSyncGameTests.class);
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void goodsPicturesKeepComponentsThroughUiAndPacketSync(GameTestHelper helper) {
        var registries = helper.getLevel().registryAccess();
        var stack = new ItemStack(Items.PAPER);
        var data = new CompoundTag();
        data.putString("AmmoId", "tacz:45acp");
        stack.set(DataComponents.CUSTOM_DATA,
            CustomData.of(data));
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("Ammunition"));
        Sentence sent = Sentence.of(Sentence.Token.Ware.of(stack, 200, registries));
        Sentence synced = Sentence.load(sent.save());
        helper.assertTrue(sent.equals(synced) && ItemStack.isSameItemSameComponents(
            ((Sentence.Token.Ware) synced.tokens().getFirst()).stack(registries), stack),
            "UI sync retains custom names and material components, with a separate aggregate count");
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), registries);
        try {
            sent.encode(buffer);
            helper.assertTrue(Sentence.decode(buffer).equals(sent) && buffer.readableBytes() == 0,
                "component-bearing goods survive the information card packet");
        } finally {
            buffer.release();
        }
        var old = new CompoundTag();
        old.putString("ware", "minecraft:paper");
        old.putLong("count", 3);
        var legacy = new ListTag();
        legacy.add(old);
        helper.assertTrue(Sentence.load(legacy).equals(Sentence.of(Sentence.ware(
            ResourceLocation.withDefaultNamespace("paper"), 3))),
            "old goods pictures still load without component data");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void informationCardsKeepTranslatableNames(GameTestHelper helper) {
        Component name = Component.translatable("entity.folkways.resident")
            .append(Component.literal(" Ada")).withStyle(ChatFormatting.GOLD);
        List<Line> sent = List.of(Line.text(name), Line.literal("custom name"), Line.gauge(Meter.HEALTH, 4, 10));
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
        try {
            Line.encodeAll(buffer, sent, 8);
            List<Line> received = Line.decodeAll(buffer, 8);
            helper.assertTrue(sent.equals(received), "text, literal and gauge lines survive the packet together");
            Component decoded = ((Line.Text) received.getFirst()).component();
            helper.assertTrue(decoded.getContents() instanceof TranslatableContents,
                "the client's language must still be able to resolve the resident name");
            helper.assertTrue(buffer.readableBytes() == 0, "all packet fields were read");
        } finally {
            buffer.release();
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void residentUiSyncKeepsNamesAndNestedTranslations(GameTestHelper helper) {
        Component name = Component.translatable("folkways.sign.as_is",
            Component.translatable("entity.folkways.resident").withStyle(ChatFormatting.GOLD))
            .append(Component.literal(" 小明"));
        var registries = helper.getLevel().registryAccess();
        Component received = Pages.readText(registries, Pages.text(registries, name));
        helper.assertTrue(name.equals(received), "nested translations, custom text and styles survive UI sync");
        helper.assertTrue(received.getContents() instanceof TranslatableContents,
            "UI sync must not turn a translated name into server-language text");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void residentCardsSendTheNameWithoutResolvingIt(GameTestHelper helper) {
        Colony colony = Colonies.mint(helper.getLevel().getServer());
        Body body = PersonBody.RESIDENT.get().create(helper.getLevel());
        try {
            // An unnamed resident is called by a name drawn from the name sets, which is plain text already.
            body.mob().setCustomName(null);
            Line title = ColonyLooks.of(colony, body, List.of()).getFirst();
            helper.assertTrue(title instanceof Line.Text text && text.component().equals(body.mob().getDisplayName()),
                "an unnamed resident's card carries its display name as it stands");
            body.mob().setCustomName(Component.translatable("folkways.sign.as_is", Component.literal("小明")));
            Line custom = ColonyLooks.of(colony, body, List.of()).getFirst();
            helper.assertTrue(custom instanceof Line.Text text && text.component().equals(body.mob().getDisplayName())
                && text.component().getContents() instanceof TranslatableContents,
                "a translated custom name reaches the card unresolved");
        } finally {
            body.mob().discard();
            colony.raze();
        }
        helper.succeed();
    }
}
