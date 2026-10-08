package io.github.izakyl.folkways.front.client.ponder;

import io.github.izakyl.folkways.front.api.ponder.Scenes;
import io.github.izakyl.folkways.front.client.Lessons;
import io.github.izakyl.folkways.front.client.ponder.scenes.BookModesScene;
import io.github.izakyl.folkways.front.client.ponder.scenes.ColonyBookScene;
import io.github.izakyl.folkways.front.client.ponder.scenes.ColonyPanelScene;
import io.github.izakyl.folkways.front.engine.registry.FolkwaysItems;
import io.github.izakyl.folkways.front.ui.screen.LessonsPage;
import java.util.List;
import net.createmod.ponder.foundation.PonderIndex;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public final class PonderIntegration {

    private PonderIntegration() {
    }

    public static void install() {
        Scenes.folder(Scenes.BASICS, "folkways.lessons.basics", FolkwaysItems.COLONY_BOOK.getId(),
            Scenes.scene("colony_book", ColonyBookScene::program),
            Scenes.scene("book_modes", BookModesScene::program),
            Scenes.scene("colony_panel", ColonyPanelScene::program));
        LessonsPage.install(PonderIntegration::shelves, PonderHint::play);
        PonderIndex.addPlugin(new FolkwaysPonderPlugin());
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
