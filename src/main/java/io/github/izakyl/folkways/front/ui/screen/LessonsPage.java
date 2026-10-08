package io.github.izakyl.folkways.front.ui.screen;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.lowdragmc.lowdraglib2.gui.texture.ItemStackTexture;
import io.github.izakyl.folkways.front.api.ui.Draw;
import io.github.izakyl.folkways.front.api.ui.Rows;
import io.github.izakyl.folkways.front.api.ui.Tokens;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;

// The shelf of lesson folders, one per plugin. The scenes are the client's to know and to play,
// so on the server this page is an empty shelf; it carries no synced value for the two to disagree on.
public final class LessonsPage implements Draw {

    public record Shelf(ResourceLocation id, String nameKey, ResourceLocation icon, List<Lesson> lessons) {
        public Shelf {
            lessons = List.copyOf(lessons);
        }
    }

    public record Lesson(ResourceLocation id, String headerKey) {
    }

    private static Supplier<List<Shelf>> shelves = List::of;
    private static Consumer<ResourceLocation> player = lesson -> { };

    private final List<UIElement> opened = new ArrayList<>();
    private UIElement folders;

    private LessonsPage() {
    }

    public static LessonsPage create() {
        return new LessonsPage();
    }

    public static void install(Supplier<List<Shelf>> shelved, Consumer<ResourceLocation> plays) {
        shelves = shelved;
        player = plays;
    }

    @Override
    public UIElement build(Player viewer) {
        folders = Rows.table();
        UIElement body = Rows.page().addChildren(
            Rows.text(Component.translatable("folkways.lessons.intro")), folders);
        for (Shelf shelf : shelves.get()) {
            folders.addChildren(folderRow(shelf));
            UIElement lessons = lessons(shelf);
            lessons.setDisplay(false);
            opened.add(lessons);
            body.addChildren(lessons);
        }
        ScrollerView scroller = Rows.fills(Rows.box());
        scroller.addScrollViewChild(body);
        return scroller;
    }

    private Button folderRow(Shelf shelf) {
        Button row = new Button()
            .setText(Component.translatable(shelf.nameKey()));
        row.addPreIcon(new ItemStackTexture(SettingsPage.iconOf(shelf.icon())));
        row.layout(layout -> layout.widthPercent(100).height(Rows.ROW_HEIGHT - 2));
        int at = opened.size();
        row.setOnClick(event -> show(at));
        Tokens.name(row, "folkways.lessons.folder." + Tokens.of(shelf.id()));
        return row;
    }

    private UIElement lessons(Shelf shelf) {
        Button back = new Button()
            .setText(Component.translatable("folkways.action.back"))
            .setOnClick(event -> show(-1));
        Tokens.name(back, "folkways.lessons.back");
        Button all = new Button()
            .setText(Component.translatable("folkways.lessons.play_all"))
            .setOnClick(event -> player.accept(shelf.id()));
        Tokens.name(all, "folkways.lessons.play." + Tokens.of(shelf.id()));
        UIElement table = Rows.table();
        for (Lesson lesson : shelf.lessons()) {
            Button row = new Button()
                .setText(Component.translatable(lesson.headerKey()))
                .setOnClick(event -> player.accept(lesson.id()));
            row.layout(layout -> layout.widthPercent(100).height(Rows.ROW_HEIGHT - 2));
            Tokens.name(row, "folkways.lessons.scene." + Tokens.of(lesson.id()));
            table.addChildren(row);
        }
        return Rows.page().addChildren(
            Rows.heading(Component.translatable(shelf.nameKey())),
            table,
            Rows.strip().addChildren(back, all));
    }

    private void show(int at) {
        folders.setDisplay(at < 0);
        for (int index = 0; index < opened.size(); index++) {
            opened.get(index).setDisplay(index == at);
        }
    }
}
