package io.github.izakyl.folkways.plugins.person.look;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Dialog;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import dev.vfyjxf.taffy.style.FlexDirection;
import io.github.izakyl.folkways.front.api.ui.Picker;
import io.github.izakyl.folkways.front.api.ui.IdTree;
import io.github.izakyl.folkways.front.api.ui.Rows;
import io.github.izakyl.folkways.front.api.ui.Tokens;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;

// Every look there is, filed in folders with a search over them, beside a preview of the one the pointer is on.
// Either each look and each folder has a box to tick, or a look is taken by clicking it. Its rows are the client's
// alone: the client knows every look and supplies the preview of the one being considered.
public final class LookPicker {

    private static final float SIDE_WIDTH = 92f;
    private static final float PREVIEW_HEIGHT = 124f;

    private static final float DIALOG_WIDTH = 300f;
    private static final float DIALOG_HEIGHT = 230f;

    private final String token;
    private final Picker picker;
    private final UIElement side = new UIElement();
    private final Label named = Rows.text(Component.empty());
    private final Label filed = Rows.note(Component.empty());
    private Consumer<ResourceLocation> preview = look -> {
    };

    @Nullable
    private ResourceLocation previewed;

    // ticked: looks and folders are switched on and off by their boxes; otherwise a click on a look takes it.
    // token heads the names of its elements, so two pickers on one screen are told apart.
    private LookPicker(String token, boolean ticked) {
        this.token = token;
        this.picker = new Picker(token, ticked, (word, what) -> {
            if (word.equals(Picker.PICK)) {
                show(ResourceLocation.tryParse(what));
            }
        }).naming(LookPicker::folderName).hovered(look -> show(ResourceLocation.tryParse(look)));
        UIElement box = new UIElement()
            .layout(layout -> layout.widthPercent(100).height(PREVIEW_HEIGHT).flexShrink(0))
            .addClass("preview_bg");
        box.setOverflowVisible(false);
        Tokens.name(box, token + ".preview");
        UIElement drawn = new UIElement().layout(layout -> layout.widthPercent(100).heightPercent(100));
        box.addChildren(drawn);
        preview = LookPreview.in(drawn);
        Tokens.name(named, token + ".previewed");
        side.layout(layout -> layout.flexDirection(FlexDirection.COLUMN)
            .width(SIDE_WIDTH).flexShrink(0).gapAll(3));
        side.addChildren(box, named, filed);
    }

    public static Optional<Component> disabledReason(List<ResourceLocation> looks) {
        return looks.stream().flatMap(look -> LookPreview.disabledReason(look).stream()).findFirst();
    }

    // Client only. Lays the folders out afresh when the looks there are have changed; open folders stay open.
    public void fill() {
        List<ResourceLocation> sorted = LookPreview.known().stream().sorted().toList();
        picker.fill(sorted.stream().map(look -> new Picker.Leaf(look.toString(), look.toString(),
            nameOf(look), token + ".look." + Tokens.of(look), LookPreview.disabledReason(look),
            new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.PLAYER_HEAD),
            look.getNamespace(), List.of())).toList());
        if (previewed == null || !sorted.contains(previewed)) {
            show(sorted.isEmpty() ? null : sorted.getFirst());
        }
    }

    // Client only. In a ticked picker these are the looks switched on; otherwise the one look worn.
    public void mark(Set<ResourceLocation> on) {
        picker.mark(on.stream().map(ResourceLocation::toString).collect(Collectors.toSet()));
    }

    // Opens every folder on the way down to a look, and shows it in the preview.
    public void reveal(ResourceLocation look) {
        picker.reveal(look.toString());
        show(look);
    }

    private void show(@Nullable ResourceLocation look) {
        previewed = look;
        preview.accept(look);
        named.setText(look == null ? Component.empty() : nameOf(look));
        filed.setText(look == null ? Component.empty() : Component.literal(look.toString()));
    }

    // What a look is called: its own translation where a pack gives one, a maid's name where it is a maid.
    public static Component nameOf(ResourceLocation look) {
        String path = look.getPath();
        String last = path.substring(path.lastIndexOf('/') + 1);
        String fallback = MaidLooks.nameKey(look)
            .map(key -> Component.translatableWithFallback(key, last).getString())
            .orElse(last);
        return Component.translatableWithFallback("folkways.looks.name." + Tokens.of(look), fallback);
    }

    private static Component folderName(IdTree.Folder<Picker.Leaf> folder) {
        String fallback = folder.name().contains("/") ? folder.name() : modName(folder.name());
        return Component.translatableWithFallback("folkways.looks.folder." + folder.dotted(), fallback);
    }

    private static String modName(String namespace) {
        ModList mods = ModList.get();
        return mods != null && mods.isLoaded(namespace)
            ? mods.getModContainerById(namespace).map(mod -> mod.getModInfo().getDisplayName()).orElse(namespace)
            : namespace;
    }

    // Opens the picker as a window of its own beside whatever asked for it; a click on a look is told to taken.
    public static Open open(UIElement host, @Nullable ResourceLocation worn, Consumer<ResourceLocation> taken) {
        LookPicker picker = new LookPicker("folkways.looks", false);
        picker.fill();
        Dialog dialog = picker.picker.immediate(false).side(picker.side).size(DIALOG_WIDTH, DIALOG_HEIGHT)
            .open(host, Component.translatable("folkways.looks.pick"),
                worn == null ? Set.of() : Set.of(worn.toString()),
                picked -> taken.accept(ResourceLocation.parse(picked.getFirst())));
        Open open = new Open(dialog, picker);
        open.wears(worn);
        if (worn != null) {
            picker.reveal(worn);
        }
        return open;
    }

    public static Dialog pool(UIElement host, Set<ResourceLocation> held, Consumer<List<ResourceLocation>> confirmed) {
        LookPicker looks = new LookPicker("folkways.pool.looks", true);
        looks.fill();
        return looks.picker.side(looks.side).size(DIALOG_WIDTH, DIALOG_HEIGHT)
            .open(host, Component.translatable("folkways.pool.looks.choose"),
                held.stream().map(ResourceLocation::toString).collect(Collectors.toSet()),
                picked -> confirmed.accept(picked.stream().map(ResourceLocation::parse).toList()));
    }

    public static final class Open {
        private final Dialog dialog;
        private final LookPicker picker;
        private boolean closed;

        private Open(Dialog dialog, LookPicker picker) {
            this.dialog = dialog;
            this.picker = picker;
            dialog.setOnClose(() -> closed = true);
        }

        public boolean isOpen() {
            return !closed;
        }

        public void wears(@Nullable ResourceLocation worn) {
            picker.mark(worn == null ? Set.of() : Set.of(worn));
        }

        public void close() {
            if (!closed) {
                dialog.close();
            }
        }
    }

}
