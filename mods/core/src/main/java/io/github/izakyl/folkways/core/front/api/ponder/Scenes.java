package io.github.izakyl.folkways.core.front.api.ponder;

import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.core.base.Ledger;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import net.createmod.ponder.api.registration.StoryBoardEntry;
import net.createmod.ponder.api.scene.PonderStoryBoard;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

// A plugin's lessons live in one folder, named after the plugin's enrollment so the page it
// enrolls opens the same folder when its tab is pondered.
@OnlyIn(Dist.CLIENT)
public final class Scenes {

    public static final ResourceLocation BASICS =
        ResourceLocation.fromNamespaceAndPath(FolkwaysCoreMod.MOD_ID, "basics");

    // The id is the one the board gives scene.title(...); it names the header the index shows.
    public record Scene(ResourceLocation id, PonderStoryBoard board, BooleanSupplier available)
        implements PonderStoryBoard {
        public Scene(ResourceLocation id, PonderStoryBoard board) {
            this(id, board, () -> true);
        }

        public Scene when(BooleanSupplier condition) {
            return new Scene(id, board, condition);
        }

        @Override
        public void program(SceneBuilder scene, SceneBuildingUtil util) {
            board.program(scene, util);
        }

        public String headerKey() {
            return id.getNamespace() + ".ponder." + id.getPath() + ".header";
        }
    }

    public record Folder(ResourceLocation id, String nameKey, ResourceLocation icon, List<Scene> scenes) {
        public Folder {
            scenes = List.copyOf(scenes);
            if (scenes.isEmpty()) {
                throw new IllegalArgumentException(id + " is a lesson folder with nothing in it");
            }
        }
    }

    private static final java.util.Map<ResourceLocation, List<ResourceLocation>> ITEMS = new java.util.LinkedHashMap<>();

    // A lesson one mod sets in a folder another owns, after the folder's own; dropped if that folder never comes.
    public record Addition(ResourceLocation folder, Scene scene) {
    }

    private static final Ledger<Folder> FOLDERS = Ledger.sealedOnceRead("a lesson folder", Folder::id);
    private static final Ledger<Addition> ADDITIONS =
        Ledger.sealedOnceRead("a lesson added to a folder", addition -> addition.scene().id());

    private Scenes() {
    }

    public static Scene scene(String namespace, String title, PonderStoryBoard board) {
        return new Scene(ResourceLocation.fromNamespaceAndPath(namespace, title), board);
    }

    public static Scene scene(String namespace, String title, PonderStoryBoard board, ResourceLocation... items) {
        Scene scene = scene(namespace, title, board);
        ITEMS.put(scene.id(), java.util.Arrays.stream(items).distinct().toList());
        return scene;
    }

    public static List<ResourceLocation> itemsFor(Scene scene) {
        return ITEMS.getOrDefault(scene.id(), List.of());
    }

    public static boolean available(StoryBoardEntry entry) {
        return !(entry.getBoard() instanceof Scene scene) || scene.available().getAsBoolean();
    }

    public static void folder(ResourceLocation id, String nameKey, ResourceLocation icon, Scene... scenes) {
        FOLDERS.claim(new Folder(id, nameKey, icon, List.of(scenes)));
    }

    public static void add(ResourceLocation folder, Scene scene) {
        ADDITIONS.claim(new Addition(folder, scene));
    }

    public static List<Folder> declared() {
        List<Addition> additions = ADDITIONS.all();
        return FOLDERS.all().stream().map(folder -> {
            List<Scene> scenes = new ArrayList<>(folder.scenes());
            additions.stream().filter(addition -> addition.folder().equals(folder.id()))
                .forEach(addition -> scenes.add(addition.scene()));
            return new Folder(folder.id(), folder.nameKey(), folder.icon(), scenes);
        }).toList();
    }
}
