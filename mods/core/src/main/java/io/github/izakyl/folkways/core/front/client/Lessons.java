package io.github.izakyl.folkways.core.front.client;

import io.github.izakyl.folkways.core.front.api.ponder.Scenes;
import io.github.izakyl.folkways.core.front.engine.Enrollments;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
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

    public static List<Scenes.Folder> folders() {
        return declared().stream().flatMap(folder -> {
            var scenes = folder.scenes().stream().filter(scene -> scene.available().getAsBoolean()).toList();
            return scenes.isEmpty() ? Stream.empty()
                : Stream.of(new Scenes.Folder(folder.id(), folder.nameKey(), folder.icon(), scenes));
        }).toList();
    }

    // The basics first, then the folders in the order their plugins enrolled, as the panel lists its tabs.
    // Registration precedes world tags; keep every board so later joins and reloads can reveal it.
    public static List<Scenes.Folder> declared() {
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

    // Ponder rebuilds its registry on a resource reload; rebuild our matching playback entries as well.
    public static void clear() {
        ENTRIES.clear();
    }

    public static List<StoryBoardEntry> scenesFor(ResourceLocation lesson) {
        return ENTRIES.getOrDefault(lesson, List.of()).stream().filter(Scenes::available).toList();
    }

    public static boolean has(ResourceLocation lesson) {
        return !scenesFor(lesson).isEmpty();
    }
}
