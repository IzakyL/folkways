package io.github.izakyl.folkways.front.client;

import io.github.izakyl.folkways.front.api.ponder.Scenes;
import io.github.izakyl.folkways.front.engine.Enrollments;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.createmod.ponder.api.registration.StoryBoardEntry;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

// A lesson is either a folder, which plays every scene in it, or one scene by its own id.
@OnlyIn(Dist.CLIENT)
public final class Lessons {

    private static final Map<ResourceLocation, List<StoryBoardEntry>> ENTRIES = new LinkedHashMap<>();

    private Lessons() {
    }

    // The basics first, then the folders in the order their plugins enrolled, as the panel lists its tabs.
    public static List<Scenes.Folder> folders() {
        List<ResourceLocation> enrolled = new ArrayList<>(Enrollments.all().keySet());
        return Scenes.declared().stream()
            .sorted(Comparator.comparingInt((Scenes.Folder folder) -> rank(enrolled, folder.id()))
                .thenComparing(folder -> folder.id().toString()))
            .toList();
    }

    private static int rank(List<ResourceLocation> enrolled, ResourceLocation id) {
        if (id.equals(Scenes.BASICS)) {
            return -1;
        }
        int at = enrolled.indexOf(id);
        return at < 0 ? enrolled.size() : at;
    }

    public static void bind(ResourceLocation lesson, StoryBoardEntry entry) {
        ENTRIES.computeIfAbsent(lesson, key -> new ArrayList<>()).add(entry);
    }

    public static List<StoryBoardEntry> scenesFor(ResourceLocation lesson) {
        return ENTRIES.getOrDefault(lesson, List.of());
    }

    public static boolean has(ResourceLocation lesson) {
        return ENTRIES.containsKey(lesson);
    }
}
