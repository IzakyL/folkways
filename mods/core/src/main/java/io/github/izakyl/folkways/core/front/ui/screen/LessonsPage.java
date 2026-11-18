package io.github.izakyl.folkways.core.front.ui.screen;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.data.Horizontal;
import com.lowdragmc.lowdraglib2.gui.ui.data.TextWrap;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import io.github.izakyl.folkways.core.front.api.ui.Draw;
import io.github.izakyl.folkways.core.front.api.ui.Rows;
import io.github.izakyl.folkways.core.front.api.ui.Tokens;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import org.appliedenergistics.yoga.YogaJustify;

// The lessons as a tree: categories hold folders, folders hold scenes. A branch folds open in place;
// a leaf plays. The scenes are the client's to know and to play, so on the server this page is an
// empty tree; it carries no synced value for the two to disagree on.
public final class LessonsPage implements Draw {

    private static final int INDENT = 10;
    private static final int ARROW_WIDTH = 9;
    private static final String CLOSED = "▸";
    private static final String OPENED = "▾";

    // A node names the lesson it plays, if any: a leaf plays it on a click, a branch from the button at its end.
    public record Node(String token, String nameKey, Optional<ResourceLocation> icon,
                       Optional<ResourceLocation> plays, String playToken, List<Node> children,
                       boolean openByDefault) {
        public Node {
            children = List.copyOf(children);
        }

        public boolean branch() {
            return !children.isEmpty();
        }
    }

    private static Supplier<List<Node>> roots = List::of;
    private static Consumer<ResourceLocation> player = lesson -> { };
    // What the player folded or unfolded stays so for the session, across reopening the book.
    private static final Map<String, Boolean> FOLDED = new HashMap<>();

    private LessonsPage() {
    }

    public static LessonsPage create() {
        return new LessonsPage();
    }

    public static void install(Supplier<List<Node>> tree, Consumer<ResourceLocation> plays) {
        roots = tree;
        player = plays;
    }

    @Override
    public UIElement build(Player viewer) {
        UIElement body = Rows.table();
        for (Node node : roots.get()) {
            body.addChildren(node(node, 0));
        }
        ScrollerView scroller = Rows.fills(Rows.box());
        scroller.addScrollViewChild(Rows.page().addChildren(body));
        return scroller;
    }

    private static UIElement node(Node node, int depth) {
        if (!node.branch()) {
            Button leaf = line(node, depth, new UIElement());
            node.plays().ifPresent(lesson -> leaf.setOnClick(event -> player.accept(lesson)));
            return leaf;
        }
        UIElement children = Rows.table();
        for (Node child : node.children()) {
            children.addChildren(node(child, depth + 1));
        }
        boolean open = FOLDED.getOrDefault(node.token(), node.openByDefault());
        children.setDisplay(open);
        Label arrow = new Label();
        arrow.setText(Component.literal(open ? OPENED : CLOSED));
        arrow.textStyle(style -> style.textWrap(TextWrap.NONE).textAlignHorizontal(Horizontal.CENTER));
        Button head = line(node, depth, arrow);
        head.setOnClick(event -> {
            boolean now = !children.isDisplayed();
            FOLDED.put(node.token(), now);
            children.setDisplay(now);
            arrow.setText(Component.literal(now ? OPENED : CLOSED));
        });
        UIElement top = head;
        if (node.plays().isPresent()) {
            ResourceLocation lesson = node.plays().get();
            Button all = Rows.act();
            all.layout(layout -> layout.height(Rows.ROW_HEIGHT - 2)
                .justifyContent(YogaJustify.CENTER).alignItems(AlignItems.CENTER));
            all.addChildren(Rows.worn(Component.literal("▶")));
            all.setOnClick(event -> player.accept(lesson));
            all.style(style -> style.tooltips(Component.translatable("folkways.lessons.play_all")));
            Tokens.name(all, node.playToken());
            top = Rows.strip().addChildren(head, all);
            top.layout(layout -> layout.gapAll(1));
        }
        return Rows.table().addChildren(top, children);
    }

    // One row: the fold arrow's column (empty on a leaf, so names line up), its icon if any, its name.
    private static Button line(Node node, int depth, UIElement arrow) {
        Button row = Rows.wearing();
        row.layout(layout -> layout.flexDirection(FlexDirection.ROW)
            .widthPercent(100).flexShrink(1).minWidth(0).height(Rows.ROW_HEIGHT - 2).minHeight(Rows.ROW_HEIGHT - 2)
            .paddingLeft(2 + depth * INDENT).gapAll(Rows.GAP - 1));
        row.setOverflowVisible(false);
        arrow.layout(layout -> layout.width(ARROW_WIDTH).flexShrink(0));
        row.addChildren(arrow);
        node.icon().ifPresent(icon -> row.addChildren(Rows.icon(SettingsPage.iconOf(icon))));
        row.addChildren(Rows.name(Component.translatable(node.nameKey())));
        Tokens.name(row, node.token());
        return row;
    }
}
