package io.github.izakyl.folkways.core.front.ui.screen;

import com.lowdragmc.lowdraglib2.LDLib2;
import com.lowdragmc.lowdraglib2.gui.texture.SpriteTexture;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Dialog;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import dev.vfyjxf.taffy.style.TaffyPosition;
import io.github.izakyl.folkways.core.front.api.ui.DeskLaunchers;
import io.github.izakyl.folkways.core.front.api.ui.Pane;
import io.github.izakyl.folkways.core.front.api.ui.Rows;
import io.github.izakyl.folkways.core.front.api.ui.Tokens;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.function.Function;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

public final class Desk {

    private static final Map<UIElement, WeakReference<Desk>> DESKS = new WeakHashMap<>();

    private final List<Window> windows = new ArrayList<>();

    private final UIElement root = new UIElement() {
        @Override
        public void appendExtraAreas(List<net.minecraft.client.renderer.Rect2i> areas) {
            for (Box box : occupiedBoxes()) {
                areas.add(new net.minecraft.client.renderer.Rect2i(
                    box.x(), box.y(), box.width(), box.height()));
            }
        }
    };

    private Window panel;

    private UIElement launcherStrip;

    private Runnable onEmpty = () -> { };

    private final Map<String, Pane> extensions = new HashMap<>();

    private final DeskLaunchers.Context context = this::extension;

    private Pane extension(String id, Component title, float width, float height, Function<Pane, UIElement> body) {
        return extensions.computeIfAbsent(id, key -> {
            Window window = add(Window.of(id, title, 0, 0, width, height, 160, 80));
            if (panel != null) {
                window.beside(panel);
            }
            window.body().addChildren(body.apply(window));
            return window;
        });
    }

    static boolean available(UIElement root, Window self, WindowGeometry.Box box) {
        WeakReference<Desk> reference = DESKS.get(root);
        Desk desk = reference == null ? null : reference.get();
        if (desk == null) {
            return true;
        }
        for (Window window : desk.windows) {
            if (window == self || !window.isOpen()) {
                continue;
            }
            if (overlaps(box, window.frame())) {
                return false;
            }
        }
        // A window set beside another keeps clear of the launchers too, which stand above every window.
        UIElement strip = desk.launcherStrip;
        return strip == null || !strip.isDisplayed() || strip.getChildren().isEmpty() || !overlaps(box, strip);
    }

    private static boolean overlaps(WindowGeometry.Box box, UIElement element) {
        return box.x() < element.getLayoutX() + element.getSizeWidth()
            && box.x() + box.width() > element.getLayoutX()
            && box.y() < element.getLayoutY() + element.getSizeHeight()
            && box.y() + box.height() > element.getLayoutY();
    }

    public void launchers() {
        if (!LDLib2.isRemote()) {
            return;
        }
        UIElement strip = Rows.strip();
        launcherStrip = strip;
        strip.layout(layout -> layout.positionType(TaffyPosition.ABSOLUTE).left(4).top(30).widthAuto());
        strip.style(style -> style.zIndex(Window.aboveWindows()));
        for (DeskLaunchers.Launcher launcher : DeskLaunchers.all()) {
            Button button = Rows.act().setOnClick(event -> launcher.action().accept(context));
            button.layout(layout -> layout.width(24).height(24));
            button.addChildren(new UIElement().layout(layout -> layout.widthPercent(100).heightPercent(100))
                .style(style -> style.background(SpriteTexture.of(launcher.icon().toString()))));
            button.style(style -> style.tooltips(Component.translatable(launcher.tooltip())));
            Tokens.name(button, launcher.id());
            strip.addChildren(button);
        }
        root.addChildren(strip);
    }

    static void raiseLaunchers(UIElement root) {
        WeakReference<Desk> reference = DESKS.get(root);
        Desk desk = reference == null ? null : reference.get();
        if (desk != null && desk.launcherStrip != null) {
            desk.launcherStrip.style(style -> style.zIndex(Window.aboveWindows()));
        }
    }

    public void onEmpty(Runnable action) {
        onEmpty = action;
    }

    static void windowClosed(UIElement root) {
        WeakReference<Desk> reference = DESKS.get(root);
        Desk desk = reference == null ? null : reference.get();
        if (desk != null && desk.windows.stream().noneMatch(Window::isOpen)) {
            desk.onEmpty.run();
        }
    }

    public boolean launch(String id) {
        return DeskLaunchers.all().stream().filter(launcher -> launcher.id().equals(id)).findFirst().map(launcher -> {
            launcher.action().accept(context);
            return true;
        }).orElse(false);
    }

    public static boolean launchCurrent(String id) {
        var screen = Minecraft.getInstance().screen;
        return screen != null && of(ModularUI.of(screen)).map(desk -> desk.launch(id)).orElse(false);
    }

    public Desk() {
        root.addClass("folkways_desk");
        DeskTextures.installPopups(root);
        root.addEventListener(UIEvents.LAYOUT_CHANGED, event -> clamp());
        if (LDLib2.isRemote()) {
            DESKS.put(root, new WeakReference<>(this));
        }
    }

    public UIElement root() {
        return root;
    }

    public Window panel(Window window) {
        panel = window;
        return add(window);
    }

    public Window add(Window window) {
        windows.add(window);
        root.addChildren(window.frame());
        window.nextTo(panel);
        return window;
    }

    private void clamp() {
        float width = root.getSizeWidth();
        float height = Math.max(0f, root.getSizeHeight() - Rows.FOOT);
        for (Window window : windows) {
            window.clampInto(width, height);
        }
    }

    static void resetLayout() {
        WindowGeometry.reset();
        var screen = Minecraft.getInstance().screen;
        if (screen != null) {
            of(ModularUI.of(screen)).ifPresent(desk -> desk.windows.forEach(Window::resetLayout));
        }
    }

    public record Box(int x, int y, int width, int height) {
    }

    public static List<Box> occupied(ModularUI ui) {
        return of(ui).map(Desk::occupiedBoxes).orElseGet(List::of);
    }

    private List<Box> occupiedBoxes() {
        List<Box> boxes = new ArrayList<>();
        for (Window window : windows) {
            if (!window.isOpen()) {
                continue;
            }
            Optional<Box> drawn = boxOf(window.frame());
            if (drawn.isEmpty()) {
                continue;
            }
            Box box = drawn.get();
            if (window == panel) {
                boxes.add(0, box);
            } else {
                boxes.add(box);
            }
        }
        if (launcherStrip != null && launcherStrip.isDisplayed() && !launcherStrip.getChildren().isEmpty()) {
            boxOf(launcherStrip).ifPresent(boxes::add);
        }
        // Pickers open as dialogs on the desk too: the dialog itself spans the screen, its overlay is the box drawn.
        for (UIElement child : root.getChildren()) {
            if (child instanceof Dialog dialog && dialog.isDisplayed()) {
                boxOf(dialog.overlay).ifPresent(boxes::add);
            }
        }
        return List.copyOf(boxes);
    }

    private static Optional<Box> boxOf(UIElement element) {
        Box box = new Box(Math.round(element.getPositionX()), Math.round(element.getPositionY()),
            Math.round(element.getSizeWidth()), Math.round(element.getSizeHeight()));
        return box.width() > 0 && box.height() > 0 ? Optional.of(box) : Optional.empty();
    }

    private static Optional<Desk> of(ModularUI ui) {
        if (ui == null) {
            return Optional.empty();
        }
        WeakReference<Desk> found = DESKS.get(ui.ui.getRootElement());
        Desk desk = found == null ? null : found.get();
        return desk != null && desk.root.getModularUI() == ui ? Optional.of(desk) : Optional.empty();
    }

    public static boolean on(ModularUI ui) {
        return of(ui).isPresent();
    }

    /** Whether a window stands open over the desk's own panel, for Escape to close before the desk itself. */
    public static boolean floating(ModularUI ui) {
        return of(ui).flatMap(Desk::topmost).isPresent();
    }

    public static boolean closeTopmost(ModularUI ui) {
        Optional<Window> top = of(ui).flatMap(Desk::topmost);
        top.ifPresent(Window::close);
        return top.isPresent();
    }

    private Optional<Window> topmost() {
        return windows.stream()
            .filter(window -> window != panel)
            .filter(Window::isOpen)
            .max(java.util.Comparator.comparingInt(Window::order));
    }
}
