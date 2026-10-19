package io.github.izakyl.folkways.front.ui.screen;

import com.lowdragmc.lowdraglib2.LDLib2;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Dialog;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import io.github.izakyl.folkways.front.api.ui.Rows;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class Desk {

    private static Desk shown;

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

    public Desk() {
        root.addClass("folkways_desk");
        root.addEventListener(UIEvents.LAYOUT_CHANGED, event -> clamp());
        if (LDLib2.isRemote()) {
            shown = this;
        }
    }

    public static void forget() {
        shown = null;
    }

    public UIElement root() {
        return root;
    }

    public Window panel(Window window) {
        panel = window;
        return add(window);
    }

    Window add(Window window) {
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
        if (shown != null) {
            shown.windows.forEach(Window::resetLayout);
        }
    }

    public record Box(int x, int y, int width, int height) {
    }

    public static List<Box> occupied(com.lowdragmc.lowdraglib2.gui.ui.ModularUI ui) {
        Desk desk = shown;
        if (desk == null || ui == null || desk.root.getModularUI() != ui) {
            return List.of();
        }
        return desk.occupiedBoxes();
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

    public static boolean on(com.lowdragmc.lowdraglib2.gui.ui.ModularUI ui) {
        return shown != null && ui != null && shown.root.getModularUI() == ui;
    }

    /** Whether a window stands open over the desk's own panel, for Escape to close before the desk itself. */
    public static boolean floating(com.lowdragmc.lowdraglib2.gui.ui.ModularUI ui) {
        return on(ui) && shown.topmost().isPresent();
    }

    public static boolean closeTopmost(com.lowdragmc.lowdraglib2.gui.ui.ModularUI ui) {
        if (!on(ui)) {
            return false;
        }
        Optional<Window> top = shown.topmost();
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
