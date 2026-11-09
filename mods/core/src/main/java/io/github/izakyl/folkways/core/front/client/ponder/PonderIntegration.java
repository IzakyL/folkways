package io.github.izakyl.folkways.core.front.client.ponder;

import io.github.izakyl.folkways.core.front.api.ponder.PonderScenery;
import io.github.izakyl.folkways.core.front.api.ponder.Scenes;
import io.github.izakyl.folkways.core.front.client.Lessons;
import io.github.izakyl.folkways.core.front.client.ponder.scenes.BookModesScene;
import io.github.izakyl.folkways.core.front.client.ponder.scenes.ColonyBookScene;
import io.github.izakyl.folkways.core.front.client.ponder.scenes.ColonyPanelScene;
import io.github.izakyl.folkways.core.front.client.ponder.scenes.SceneBooks;
import io.github.izakyl.folkways.core.front.engine.registry.FolkwaysItems;
import io.github.izakyl.folkways.core.front.ui.screen.LessonsPage;
import java.util.List;
import net.createmod.ponder.foundation.PonderIndex;
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
        LessonsPage.install(PonderIntegration::shelves, PonderHint::play);
        for (String namespace : List.of("folkways_core", "folkways_vanilla", "folkways_create", "folkways_cookery",
                "folkways_firearms", "folkways_carts", "folkways_carving", "folkways_golems",
                "folkways_epicfight")) {
            PonderIndex.addPlugin(new FolkwaysPonderPlugin(namespace));
        }
    }

    private static List<LessonsPage.Shelf> shelves() {
        return Lessons.folders().stream()
            .map(folder -> new LessonsPage.Shelf(folder.id(), folder.nameKey(), folder.icon(),
                folder.scenes().stream()
                    .map(scene -> new LessonsPage.Lesson(scene.id(), scene.headerKey()))
                    .toList()))
            .toList();
    }
}
