package io.github.izakyl.folkways.core.front.client.ponder;

import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.core.front.api.ponder.Scenes;
import io.github.izakyl.folkways.core.front.client.Lessons;
import io.github.izakyl.folkways.core.front.engine.Enrollments;
import io.github.izakyl.folkways.core.front.engine.registry.FolkwaysItems;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
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

    private final String namespace;

    public FolkwaysPonderPlugin(String namespace) {
        this.namespace = namespace;
    }

    @Override
    public String getModId() {
        return namespace;
    }

    // Every scene hangs off the colony book, folder after folder, so pondering the book is the whole course.
    @Override
    public void registerScenes(PonderSceneRegistrationHelper<ResourceLocation> helper) {
        if (namespace.equals(FolkwaysCoreMod.MOD_ID)) {
            Lessons.clear();
        }
        List<Scenes.Folder> folders = Lessons.declared();
        ResourceLocation colonyBook = FolkwaysItems.COLONY_BOOK.getId();

        for (Scenes.Folder folder : folders) {
            for (Scenes.Scene scene : folder.scenes()) {
                if (!scene.id().getNamespace().equals(namespace)) {
                    continue;
                }
                String schematic = folder.id().getPath() + "/" + scene.id().getPath();
                // Ponder invokes the callback once per component. Only the book's entry belongs in our lesson list.
                helper.forComponents(colonyBook).addStoryBoard(schematic, scene, entry -> {
                    Lessons.bind(folder.id(), entry);
                    Lessons.bind(scene.id(), entry);
                });
                List<ResourceLocation> components = Scenes.itemsFor(scene).stream()
                    .filter(component -> !component.equals(colonyBook)).distinct().toList();
                if (!components.isEmpty()) {
                    helper.forComponents(components.toArray(ResourceLocation[]::new)).addStoryBoard(schematic, scene);
                }
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
