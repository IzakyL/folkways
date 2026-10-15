package io.github.izakyl.folkways.front.api.ui;

import io.github.izakyl.folkways.FolkwaysMod;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ChoicePickerGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) { event.register(ChoicePickerGameTests.class); }

    @GameTest(template = "empty")
    public static void nonRegistryChoicesRetainTranslationAndFallbackOnTheDedicatedServer(GameTestHelper helper) {
        var label = ChoicePicker.labelOf(ResourceLocation.fromNamespaceAndPath("folkways", "single"));
        helper.assertTrue(label.getContents() instanceof TranslatableContents, "choice text stays translatable until the client receives it");
        var text = (TranslatableContents) label.getContents();
        helper.assertValueEqual(text.getKey(), "folkways.choice.folkways.single", "the actual registered harvest choice key is preserved");
        helper.assertValueEqual(text.getFallback(), "single", "a custom untranslated option keeps its readable fallback");
        var registries = helper.getLevel().registryAccess();
        helper.assertTrue(Pages.readText(registries, Pages.text(registries, label)).equals(label),
            "the label crosses server UI synchronization without resolving the player's language");
        helper.assertTrue(ChoicePicker.labelOf(ResourceLocation.withDefaultNamespace("wheat")).getContents() instanceof TranslatableContents,
            "registered item choices still use their native descriptions");
        var variant = ChoicePicker.labelOf(ResourceLocation.withDefaultNamespace("wheat/square"));
        helper.assertTrue(variant.getContents() instanceof TranslatableContents
            && ((TranslatableContents) variant.getContents()).getKey().equals("folkways.choice.variant.square"),
            "registered variants keep their native item name argument");
        helper.succeed();
    }
}
