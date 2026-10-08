package io.github.izakyl.folkways.front.api.ponder;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.Ledger;
import java.util.List;
import net.createmod.ponder.api.scene.PonderStoryBoard;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

// A plugin's lessons live in one folder, named after the plugin's enrollment so the page it
// enrolls opens the same folder when its tab is pondered.
@OnlyIn(Dist.CLIENT)
public final class Scenes {

    public static final ResourceLocation BASICS =
        ResourceLocation.fromNamespaceAndPath(FolkwaysMod.MOD_ID, "basics");

    // The id is the one the board gives scene.title(...); it names the header the index shows.
    public record Scene(ResourceLocation id, PonderStoryBoard board) {
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

    private static final Ledger<Folder> FOLDERS = Ledger.sealedOnceRead("a lesson folder", Folder::id);

    private Scenes() {
    }

    public static Scene scene(String title, PonderStoryBoard board) {
        return new Scene(ResourceLocation.fromNamespaceAndPath(FolkwaysMod.MOD_ID, title), board);
    }

    public static void folder(ResourceLocation id, String nameKey, ResourceLocation icon, Scene... scenes) {
        FOLDERS.claim(new Folder(id, nameKey, icon, List.of(scenes)));
    }

    public static List<Folder> declared() {
        return FOLDERS.all();
    }
}
