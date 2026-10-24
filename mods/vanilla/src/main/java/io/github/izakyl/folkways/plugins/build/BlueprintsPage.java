package io.github.izakyl.folkways.plugins.build;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import dev.vfyjxf.taffy.style.FlexDirection;
import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.front.api.ui.Draw;
import io.github.izakyl.folkways.front.api.ui.FolderPicker;
import io.github.izakyl.folkways.front.api.ui.Rows;
import io.github.izakyl.folkways.front.api.ui.Tokens;
import io.github.izakyl.folkways.library.Shelves;
import io.github.izakyl.folkways.plugins.build.draft.Templates;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

// The blueprints there are to set down: the server's shelf and the player's own folder, each filed in its folders,
// beside how big the one the pointer is on stands. Which there are and how one is held are the client's to know, so
// on the server this page is an empty shelf, with no synced value to disagree on.
public final class BlueprintsPage implements Draw {

    static final String TOKEN = "folkways.blueprints";

    private static final float SIDE_WIDTH = 92f;
    private static final String AIR = "minecraft:air";

    // file is the structure as it was read, when it was.
    public record Entry(ResourceLocation id, Component name, boolean own, @Nullable CompoundTag file) {

        String key() {
            return (own ? "own:" : "shared:") + id;
        }

        // Kept under the server's or the player's own, then the folders after blueprint/; a pack's blueprints
        // stand in a folder of their namespace.
        String filed() {
            String path = id.getPath().substring(Templates.BLUEPRINT.length());
            boolean ours = id.getNamespace().equals(FolkwaysMod.MOD_ID) || id.getNamespace().equals(Shelves.NAMESPACE);
            return (own ? "own:" : "shared:") + (ours ? "" : id.getNamespace() + "/") + path;
        }
    }

    private static Supplier<List<Entry>> shelf = List::of;
    private static Consumer<Entry> taking = entry -> { };

    public static void install(Supplier<List<Entry>> shelved, Consumer<Entry> takes) {
        shelf = shelved;
        taking = takes;
    }

    @Override
    public UIElement build(Player viewer) {
        return viewer.level().isClientSide() ? shelfOf(shelf.get(), taking) : shelfOf(List.of(), entry -> { });
    }

    // The folders and what stands beside them, for this page and for the picker the view from above opens on its own.
    static UIElement shelfOf(List<Entry> entries, Consumer<Entry> takes) {
        if (entries.isEmpty()) {
            return Rows.page().addChildren(Rows.text(Component.translatable(TOKEN + ".empty")));
        }
        Map<String, Entry> byKey = new LinkedHashMap<>();
        entries.forEach(entry -> byKey.put(entry.key(), entry));
        Side side = new Side();
        FolderPicker picker = new FolderPicker(TOKEN, false, (word, key) -> {
            Entry entry = byKey.get(key);
            if (entry != null) {
                takes.accept(entry);
            }
        }).openRoots().hovered(key -> side.show(byKey.get(key)));
        picker.fill(entries.stream().map(entry -> new FolderPicker.Leaf(entry.key(), entry.filed(),
            entry.name(), TOKEN + ".entry." + Tokens.of(entry.id()))).toList());
        side.show(entries.getFirst());
        UIElement whole = new UIElement()
            .layout(layout -> layout.flexDirection(FlexDirection.ROW)
                .widthPercent(100).flexGrow(1).flexShrink(1).minHeight(0).gapAll(Rows.GAP))
            .addChildren(picker.list(), side.element);
        whole.setOverflowVisible(false);
        return whole;
    }

    // The blueprint the pointer is on: its name, how big it stands and how many blocks it takes, and whose it is.
    private static final class Side {
        private Entry shown;
        private final UIElement element = new UIElement();
        private final Label named = Rows.text(Component.empty());
        private final Label size = Rows.note(Component.empty());
        private final Label blocks = Rows.note(Component.empty());
        private final Label whose = Rows.note(Component.empty());
        private final Label materials = Rows.note(Component.empty());

        private Side() {
            element.layout(layout -> layout.flexDirection(FlexDirection.COLUMN)
                .width(SIDE_WIDTH).flexShrink(0).gapAll(3));
            Tokens.name(named, TOKEN + ".previewed");
            Tokens.name(size, TOKEN + ".size");
            Tokens.name(blocks, TOKEN + ".blocks");
            element.addChildren(named, size, blocks, whose, materials);
        }

        private void show(@Nullable Entry entry) {
            if (entry == null || entry.equals(shown)) {
                return;
            }
            shown = entry;
            named.setText(entry.name());
            Optional<CompoundTag> file = Optional.ofNullable(entry.file());
            Optional<int[]> stands = file.flatMap(Side::sizeOf);
            size.setText(stands.<Component>map(sides -> Component.translatable(TOKEN + ".size",
                sides[0], sides[1], sides[2])).orElse(Component.empty()));
            blocks.setText(file.<Component>map(tag -> Component.translatable(TOKEN + ".blocks", blocksOf(tag)))
                .orElse(Component.empty()));
            whose.setText(Component.translatable("folkways.picker.root." + (entry.own() ? "own" : "shared")));
            materials.setText(describe(entry));
        }

        private static Component describe(Entry entry) {
            var level = net.minecraft.client.Minecraft.getInstance().level;
            if (level == null || entry.file() == null) return Component.empty();
            try {
                var text = Component.empty();
                for (Component line : BlueprintMaterials.describe(entry.file(), level.registryAccess())) {
                    if (!text.getSiblings().isEmpty()) text.append("\n");
                    text.append(line);
                }
                return text;
            } catch (io.github.izakyl.folkways.core.api.structure.StructureCompat.Failure failed) {
                return failed.component();
            } catch (RuntimeException failed) {
                return Component.translatable("folkways.refusal.build.micro_data_invalid");
            }
        }

        private static Optional<int[]> sizeOf(CompoundTag file) {
            ListTag size = file.getList("size", Tag.TAG_INT);
            return size.size() == 3
                ? Optional.of(new int[] {size.getInt(0), size.getInt(1), size.getInt(2)})
                : Optional.empty();
        }

        // The blocks it sets, not the air it clears.
        private static int blocksOf(CompoundTag file) {
            ListTag palette = file.getList("palette", Tag.TAG_COMPOUND);
            ListTag cells = file.getList("blocks", Tag.TAG_COMPOUND);
            int set = 0;
            for (int at = 0; at < cells.size(); at++) {
                int state = cells.getCompound(at).getInt("state");
                if (state < 0 || state >= palette.size() || !palette.getCompound(state).getString("Name").equals(AIR)) {
                    set++;
                }
            }
            return set;
        }
    }
}
