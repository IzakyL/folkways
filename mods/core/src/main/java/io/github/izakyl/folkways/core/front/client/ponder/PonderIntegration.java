package io.github.izakyl.folkways.core.front.client.ponder;

import io.github.izakyl.folkways.core.front.api.ponder.PonderScenery;
import io.github.izakyl.folkways.core.front.api.ponder.Scenes;
import io.github.izakyl.folkways.core.front.api.ui.Tokens;
import io.github.izakyl.folkways.core.front.client.Lessons;
import io.github.izakyl.folkways.core.front.client.ponder.scenes.BookModesScene;
import io.github.izakyl.folkways.core.front.client.ponder.scenes.ColonyBookScene;
import io.github.izakyl.folkways.core.front.client.ponder.scenes.ColonyPanelScene;
import io.github.izakyl.folkways.core.front.client.ponder.scenes.SceneBooks;
import io.github.izakyl.folkways.core.front.engine.registry.FolkwaysItems;
import io.github.izakyl.folkways.core.front.ui.screen.LessonsPage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.createmod.ponder.foundation.PonderIndex;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public final class PonderIntegration {

    private PonderIntegration() {
    }

    public static void install() {
        PonderScenery.installBooks(SceneBooks::book);
        Scenes.folder(Scenes.BASICS, "folkways.lessons.basics", FolkwaysItems.COLONY_BOOK.getId(),
            Scenes.scene("folkways_core", "colony_book", ColonyBookScene::program),
            Scenes.scene("folkways_core", "book_modes", BookModesScene::program),
            Scenes.scene("folkways_core", "colony_panel", ColonyPanelScene::program));
        LessonsPage.install(PonderIntegration::tree, PonderHint::play);
        for (String namespace : List.of("folkways_core", "folkways_vanilla", "folkways_airline")) {
            PonderIndex.addPlugin(new FolkwaysPonderPlugin(namespace));
        }
    }

    // Basics first and open, then each category holding its folders, then any folder under none.
    private static List<LessonsPage.Node> tree() {
        List<Scenes.Folder> folders = Lessons.folders();
        Map<Scenes.Category, List<LessonsPage.Node>> shelved = new LinkedHashMap<>();
        Scenes.Category.BUILT_IN.forEach(category -> shelved.put(category, new ArrayList<>()));
        List<LessonsPage.Node> loose = new ArrayList<>();
        for (Scenes.Folder folder : folders) {
            LessonsPage.Node node = folder(folder);
            folder.category().ifPresentOrElse(
                category -> shelved.computeIfAbsent(category, key -> new ArrayList<>()).add(node),
                () -> loose.add(node));
        }
        List<LessonsPage.Node> roots = new ArrayList<>();
        loose.stream().filter(node -> node.token().equals(folderToken(Scenes.BASICS))).forEach(roots::add);
        shelved.forEach((category, held) -> {
            if (!held.isEmpty()) {
                roots.add(new LessonsPage.Node("folkways.lessons.category." + Tokens.of(category.id()),
                    category.nameKey(), Optional.of(category.icon()), Optional.empty(), Tokens.NONE, held, true));
            }
        });
        loose.stream().filter(node -> !node.token().equals(folderToken(Scenes.BASICS))).forEach(roots::add);
        return roots;
    }

    // A folder of one scene is that scene, named as the folder: nothing to unfold.
    private static LessonsPage.Node folder(Scenes.Folder folder) {
        if (folder.scenes().size() == 1) {
            Scenes.Scene only = folder.scenes().getFirst();
            return new LessonsPage.Node(sceneToken(only.id()), folder.nameKey(), Optional.of(folder.icon()),
                Optional.of(only.id()), Tokens.NONE, List.of(), false);
        }
        List<LessonsPage.Node> scenes = folder.scenes().stream()
            .map(scene -> new LessonsPage.Node(sceneToken(scene.id()), scene.headerKey(), Optional.empty(),
                Optional.of(scene.id()), Tokens.NONE, List.of(), false))
            .toList();
        return new LessonsPage.Node(folderToken(folder.id()), folder.nameKey(), Optional.of(folder.icon()),
            Optional.of(folder.id()), "folkways.lessons.play." + Tokens.of(folder.id()), scenes,
            folder.id().equals(Scenes.BASICS));
    }

    private static String folderToken(ResourceLocation id) {
        return "folkways.lessons.folder." + Tokens.of(id);
    }

    private static String sceneToken(ResourceLocation id) {
        return "folkways.lessons.scene." + Tokens.of(id);
    }
}
