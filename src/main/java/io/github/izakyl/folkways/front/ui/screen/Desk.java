package io.github.izakyl.folkways.front.ui.screen;

import com.lowdragmc.lowdraglib2.LDLib2;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
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
    private UIElement beside;

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

    void beside(UIElement element) {
        beside = element;
        root.addChildren(element);
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
        float height = Math.max(0f, root.getSizeHeight() - 24f);
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
        if (beside != null && beside.isDisplayed()) {
            boxes.add(new Box(Math.round(beside.getPositionX()), Math.round(beside.getPositionY()),
                Math.round(beside.getSizeWidth()), Math.round(beside.getSizeHeight())));
        }
        for (Window window : windows) {
            if (!window.isOpen()) {
                continue;
            }
            UIElement frame = window.frame();
            Box box = new Box(Math.round(frame.getPositionX()), Math.round(frame.getPositionY()),
                Math.round(frame.getSizeWidth()), Math.round(frame.getSizeHeight()));
            if (box.width() <= 0 || box.height() <= 0) {
                continue;
            }
            if (window == panel) {
                boxes.add(0, box);
            } else {
                boxes.add(box);
            }
        }
        return List.copyOf(boxes);
    }

    public static boolean closeTopmost(com.lowdragmc.lowdraglib2.gui.ui.ModularUI ui) {
        Desk desk = shown;
        if (desk == null || ui == null || desk.root.getModularUI() != ui) {
            return false;
        }
        Optional<Window> top = desk.windows.stream()
            .filter(window -> window != desk.panel)
            .filter(Window::isOpen)
            .max(java.util.Comparator.comparingInt(Window::order));
        top.ifPresent(Window::close);
        return top.isPresent();
    }
}
