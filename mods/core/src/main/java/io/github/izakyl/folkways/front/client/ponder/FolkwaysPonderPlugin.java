package io.github.izakyl.folkways.front.client.ponder;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.front.api.ponder.Scenes;
import io.github.izakyl.folkways.front.client.Lessons;
import io.github.izakyl.folkways.front.engine.Enrollments;
import io.github.izakyl.folkways.front.engine.registry.FolkwaysItems;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import net.createmod.ponder.api.registration.MultiSceneBuilder;
import net.createmod.ponder.api.registration.PonderPlugin;
import net.createmod.ponder.api.registration.PonderSceneRegistrationHelper;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@OnlyIn(Dist.CLIENT)
public final class FolkwaysPonderPlugin implements PonderPlugin {

    private static final Logger LOGGER = LoggerFactory.getLogger("folkways-lessons");

    @Override
    public String getModId() {
        return FolkwaysMod.MOD_ID;
    }

    // Every scene hangs off the colony book, folder after folder, so pondering the book is the whole course.
    @Override
    public void registerScenes(PonderSceneRegistrationHelper<ResourceLocation> helper) {
        List<Scenes.Folder> folders = Lessons.folders();
        MultiSceneBuilder book = helper.forComponents(FolkwaysItems.COLONY_BOOK.getId());
        for (Scenes.Folder folder : folders) {
            for (Scenes.Scene scene : folder.scenes()) {
                book.addStoryBoard(folder.id().getPath() + "/" + scene.id().getPath(), scene.board(), entry -> {
                    Lessons.bind(folder.id(), entry);
                    Lessons.bind(scene.id(), entry);
                });
            }
        }
        unlessoned(folders);
    }

    // A plugin that enrolls with the colony but brings no folder has nothing to show when its tab is pondered.
    private static void unlessoned(List<Scenes.Folder> folders) {
        Set<ResourceLocation> taught = folders.stream().map(Scenes.Folder::id).collect(Collectors.toSet());
        List<ResourceLocation> missing = Enrollments.all().keySet().stream()
            .filter(owner -> !taught.contains(owner))
            .toList();
        if (!missing.isEmpty()) {
            LOGGER.warn("Enrolled with no lesson folder: {}", missing);
        }
    }
}
