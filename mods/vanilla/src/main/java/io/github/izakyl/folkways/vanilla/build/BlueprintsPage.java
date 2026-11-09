package io.github.izakyl.folkways.vanilla.build;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Dialog;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Scene;
import com.lowdragmc.lowdraglib2.utils.data.BlockInfo;
import com.lowdragmc.lowdraglib2.utils.virtuallevel.TrackedDummyWorld;
import java.util.Set;
import dev.vfyjxf.taffy.style.FlexDirection;
import io.github.izakyl.folkways.core.front.api.ui.Draw;
import io.github.izakyl.folkways.core.front.api.ui.Picker;
import io.github.izakyl.folkways.core.front.api.ui.Rows;
import io.github.izakyl.folkways.core.front.api.ui.Tokens;
import io.github.izakyl.folkways.core.library.Shelves;
import io.github.izakyl.folkways.vanilla.build.draft.Templates;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

// The blueprints there are to set down: the server's shelf and the player's own folder, each filed in its folders,
// beside how big the one the pointer is on stands. Which there are and how one is held are the client's to know, so
// on the server this page is an empty shelf, with no synced value to disagree on.
public final class BlueprintsPage implements Draw {

    static final String TOKEN = "folkways.blueprints";

    private static final float SIDE_WIDTH = 92f;
    // A blueprint of more blocks than this is shown by its first ones only, so a hover never stalls the screen.
    private static final int MOST_PREVIEWED = 32_768;
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
            boolean ours = id.getNamespace().equals("folkways_vanilla") || id.getNamespace().equals(Shelves.NAMESPACE);
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
        Button choose = new Button().setText(Component.translatable(TOKEN + ".choose"));
        Tokens.name(choose, TOKEN + ".choose");
        choose.setOnClick(event -> open(choose, shelf.get(), taking));
        return Rows.page().addChildren(Rows.text(Component.translatable(TOKEN + ".explain")), choose);
    }

    static Dialog open(UIElement host, List<Entry> entries, Consumer<Entry> takes) {
        Map<String, Entry> byKey = new LinkedHashMap<>();
        entries.forEach(entry -> byKey.put(entry.key(), entry));
        Side side = new Side();
        Picker picker = new Picker(TOKEN, false).openRoots().immediate(true).size(300, 230)
            .emptySaying(Component.translatable(TOKEN + ".empty"))
            .side(side.element).hovered(key -> side.show(byKey.get(key)));
        picker.fill(entries.stream().map(entry -> new Picker.Leaf(entry.key(), entry.filed(),
            entry.name(), TOKEN + ".entry." + Tokens.of(entry.id()), Optional.empty(),
            new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.PAPER),
            entry.id().getNamespace(), List.of())).toList());
        if (!entries.isEmpty()) {
            side.show(entries.getFirst());
        }
        return picker.open(host, Component.translatable(TOKEN + ".choose"), Set.of(), picked -> {
            if (!picked.isEmpty()) {
                takes.accept(byKey.get(picked.getFirst()));
            }
        });
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

        // The blueprint itself, built in a world of its own and turned to be seen from a corner above.
        private final UIElement box = new UIElement();
        private final Scene model = new Scene();
        private final TrackedDummyWorld world = new TrackedDummyWorld();

        private Side() {
            element.layout(layout -> layout.flexDirection(FlexDirection.COLUMN)
                .width(SIDE_WIDTH).flexShrink(0).gapAll(3));
            box.layout(layout -> layout.widthPercent(100).height(SIDE_WIDTH).flexShrink(0)).addClass("preview_bg");
            box.setOverflowVisible(false);
            model.layout(layout -> layout.widthPercent(100).heightPercent(100));
            model.createScene(world);
            model.useOrtho(false).setDraggable(true).setScalable(true).setIntractable(false)
                .setRenderFacing(false).setRenderSelect(false).setShowHoverBlockTips(false);
            box.addChildren(model);
            Tokens.name(box, TOKEN + ".preview");
            Tokens.name(named, TOKEN + ".previewed");
            Tokens.name(size, TOKEN + ".size");
            Tokens.name(blocks, TOKEN + ".blocks");
            element.addChildren(box, named, size, blocks, whose, materials);
        }

        // Builds the shown blueprint in the preview's own world; one too big to build here leaves it empty.
        private void build(@Nullable CompoundTag file) {
            world.clear();
            var level = net.minecraft.client.Minecraft.getInstance().level;
            Map<BlockPos, BlockInfo> laid = new LinkedHashMap<>();
            if (file != null && level != null) {
                var lookup = level.registryAccess().lookupOrThrow(Registries.BLOCK);
                ListTag palette = file.getList("palette", Tag.TAG_COMPOUND);
                List<Optional<BlockState>> states = new ArrayList<>();
                for (int at = 0; at < palette.size(); at++) {
                    states.add(BlueprintBlock.read(lookup, palette.getCompound(at)));
                }
                ListTag cells = file.getList("blocks", Tag.TAG_COMPOUND);
                for (int at = 0; at < cells.size() && laid.size() < MOST_PREVIEWED; at++) {
                    CompoundTag cell = cells.getCompound(at);
                    int index = cell.getInt("state");
                    ListTag pos = cell.getList("pos", Tag.TAG_INT);
                    if (index < 0 || index >= states.size() || pos.size() != 3) {
                        continue;
                    }
                    states.get(index).filter(state -> !state.isAir()).ifPresent(state -> laid.put(
                        new BlockPos(pos.getInt(0), pos.getInt(1), pos.getInt(2)), BlockInfo.fromBlockState(state)));
                }
            }
            world.addBlocks(laid);
            model.setRenderedCore(laid.keySet());
            int[] sides = Optional.ofNullable(file).flatMap(Side::sizeOf).orElse(new int[] {1, 1, 1});
            model.setCenter(new Vector3f(sides[0] / 2f, sides[1] / 2f, sides[2] / 2f));
            // Seen from a corner above, from far enough off that the whole of it fits the box.
            model.setZoom(Math.max(sides[0], Math.max(sides[1], sides[2])) * 1.25f + 0.5f);
            model.setCameraYawAndPitch(135, 30);
        }

        private void show(@Nullable Entry entry) {
            if (entry == null || entry.equals(shown)) {
                return;
            }
            shown = entry;
            build(entry.file());
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
            if (level == null || entry.file() == null) {
                return Component.empty();
            }
            try {
                var text = Component.empty();
                for (Component line : BlueprintMaterials.describe(entry.file(), level.registryAccess())) {
                    if (!text.getSiblings().isEmpty()) {
                        text.append("\n");
                    }
                    text.append(line);
                }
                return text;
            } catch (io.github.izakyl.folkways.core.work.api.StructureCompat.Failure failed) {
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
