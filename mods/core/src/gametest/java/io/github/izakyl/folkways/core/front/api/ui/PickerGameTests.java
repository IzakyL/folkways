package io.github.izakyl.folkways.core.front.api.ui;

import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Toggle;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.network.chat.Component;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysCoreMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysCoreMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PickerGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) { event.register(PickerGameTests.class); }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void nonRegistryChoicesRetainTranslationAndFallbackOnTheDedicatedServer(GameTestHelper helper) {
        var label = Picker.labelOf(ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "single"));
        helper.assertTrue(label.getContents() instanceof TranslatableContents, "choice text stays translatable until the client receives it");
        var text = (TranslatableContents) label.getContents();
        helper.assertValueEqual(text.getKey(), "folkways.choice.folkways_vanilla.single", "the actual registered harvest choice key is preserved");
        helper.assertValueEqual(text.getFallback(), "single", "a custom untranslated option keeps its readable fallback");
        var registries = helper.getLevel().registryAccess();
        helper.assertTrue(Pages.readText(registries, Pages.text(registries, label)).equals(label),
            "the label crosses server UI synchronization without resolving the player's language");
        helper.assertTrue(Picker.labelOf(ResourceLocation.withDefaultNamespace("wheat")).getContents() instanceof TranslatableContents,
            "registered item choices still use their native descriptions");
        var variant = Picker.labelOf(ResourceLocation.withDefaultNamespace("wheat/square"));
        helper.assertTrue(variant.getContents() instanceof TranslatableContents
            && ((TranslatableContents) variant.getContents()).getKey().equals("folkways.choice.variant.square"),
            "registered variants keep their native item name argument");
        helper.succeed();
    }
    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void heldOptionsBeyondTheFirstFolderPageAreRevealed(GameTestHelper helper) {
        List<Picker.Leaf> options = new ArrayList<>();
        for (int at = 0; at < 180; at++) {
            options.add(new Picker.Leaf("key_" + at, "test:folder_" + String.format("%03d", at) + "/leaf",
                Component.literal("Option " + at), "option_" + at));
        }
        Picker picker = new Picker("test", false).compactFolders(false);
        picker.fill(options);
        // One root holding 180 folders: it opens, and lays only the first page of its folders.
        helper.assertValueEqual(count(picker.tree(), "test.folder."), 1 + 40,
            "an opened folder lays its first forty folders, not all of them");
        helper.assertTrue(element(picker.tree(), "test.more") != null, "the folders beyond the page wait behind more");
        helper.assertTrue(element(picker.tree(), "option_179") == null, "the full list is not laid out on opening");
        picker.mark(Set.of("key_179"));
        picker.reveal("key_179");
        helper.assertTrue(element(picker.tree(), "option_179") != null, "reveal reaches past the folder page");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void aQueryNotYetWholeFindsNothingAndAListShowsWhatItFinds(GameTestHelper helper) {
        Picker picker = new Picker("test", false);
        picker.fill(List.of(new Picker.Leaf("minecraft:bread", "", Component.literal("Bread"), "bread")));
        UIElement list = picker.list();
        ((TextField) picker.search()).setText("#", true);
        helper.assertTrue(element(picker.results(), "bread") == null, "a bare # finds nothing");
        ((TextField) picker.search()).setText("bre", true);
        helper.assertTrue(element(list, "bread") != null, "an embedded list shows what its search finds");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void oneOptionMayBeFiledTwiceAndBothRowsStayMarked(GameTestHelper helper) {
        Picker picker = new Picker("test", true).openRoots();
        picker.fill(List.of(
            new Picker.Leaf("same", "first:leaf", Component.literal("Same"), "first"),
            new Picker.Leaf("same", "second:leaf", Component.literal("Same"), "second")));
        picker.mark(Set.of("same"));
        helper.assertTrue(((Toggle) element(picker.tree(), "first")).isOn(), "the first filing is ticked");
        helper.assertTrue(((Toggle) element(picker.tree(), "second")).isOn(), "the second filing is ticked");
        picker.mark(Set.of());
        helper.assertFalse(((Toggle) element(picker.tree(), "first")).isOn(), "the first filing clears");
        helper.assertFalse(((Toggle) element(picker.tree(), "second")).isOn(), "the second filing clears");
        helper.succeed();
    }

    private static int count(UIElement root, String prefix) {
        int found = root.getId() != null && root.getId().startsWith(prefix) ? 1 : 0;
        for (UIElement child : root.getChildren()) {
            found += count(child, prefix);
        }
        return found;
    }

    private static UIElement element(UIElement root, String id) {
        if (id.equals(root.getId())) {
            return root;
        }
        for (UIElement child : root.getChildren()) {
            UIElement found = element(child, id);
            if (found != null) {
                return found;
            }
        }
        return null;
    }
}
