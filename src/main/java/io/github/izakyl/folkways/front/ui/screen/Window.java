package io.github.izakyl.folkways.front.ui.screen;

import com.lowdragmc.lowdraglib2.LDLib2;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.data.TextWrap;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.util.WindowDragHelper;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import dev.vfyjxf.taffy.style.TaffyPosition;
import io.github.izakyl.folkways.front.api.ui.Pane;
import io.github.izakyl.folkways.front.api.ui.Rows;
import io.github.izakyl.folkways.front.api.ui.Tokens;
import net.minecraft.network.chat.Component;
import org.joml.Vector2f;

public final class Window implements Pane {

    static final float TITLE_HEIGHT = 22f;

    private static final float RESIZE_BORDER = 3f;

    private static final float CLOSE_SIZE = 18f;

    private static final float BESIDE_GAP = 6f;

    private static int raising = 1;

    private final String id;
    private final WindowGeometry.Box byDefault;
    private final float minWidth;
    private final float minHeight;

    private final UIElement frame = new UIElement();
    private final UIElement titleBar = new UIElement();
    private final UIElement body = new UIElement();
    private final Label caption = new Label();
    private final Button closeButton = new Button();

    private Runnable onClose = () -> {
    };
    private Runnable dismissed = () -> {
    };
    private boolean open;
    private int order;
    private boolean centring = true;
    private boolean automaticPosition = true;
    private float lastDeskWidth;
    private float lastDeskHeight;
    private float wantedWidth;
    private float wantedHeight;
    private Window beside;

    private Window(String id, Component title, WindowGeometry.Box byDefault,
            float minWidth, float minHeight) {
        this.id = id;
        this.byDefault = byDefault;
        this.minWidth = minWidth;
        this.minHeight = minHeight;
        wantedWidth = byDefault.width();
        wantedHeight = byDefault.height();

        caption.setText(title);
        caption.layout(layout -> layout.flexGrow(1).flexShrink(1).minWidth(0));
        caption.textStyle(style -> style.textWrap(TextWrap.HIDE));

        closeButton.setText(Component.literal("x"));
        closeButton.layout(layout ->
            layout.width(CLOSE_SIZE).height(CLOSE_SIZE).flexShrink(0).paddingAll(0));
        closeButton.setOnClick(event -> {
            close();
            dismissed.run();
        });
        closeButton.style(style -> style.tooltips(Component.translatable("folkways.ui.close")));
        Tokens.name(closeButton, "folkways.window.close." + id);

        titleBar.layout(layout -> layout.flexDirection(FlexDirection.ROW)
            .paddingHorizontal(4).height(TITLE_HEIGHT).alignItems(AlignItems.CENTER).gapAll(Rows.GAP).flexShrink(0));
        titleBar.setOverflowVisible(false);
        titleBar.addChildren(caption, closeButton);
        titleBar.addClass("__dialog_title__");

        body.layout(layout -> layout.flexDirection(FlexDirection.COLUMN)
            .flexGrow(1).flexShrink(1).minHeight(0).gapAll(5));
        body.setOverflowVisible(false);

        frame.layout(layout -> layout.positionType(TaffyPosition.ABSOLUTE)
            .flexDirection(FlexDirection.COLUMN)
            .left(byDefault.x()).top(byDefault.y())
            .width(byDefault.width()).height(byDefault.height()));
        frame.addClass("panel_bg");
        frame.setOverflowVisible(false);
        frame.addChildren(titleBar, body);
        frame.setDisplay(false);
        Tokens.name(frame, "folkways.window." + id);

        frame.addEventListener(UIEvents.MOUSE_DOWN, event -> raise());
        if (LDLib2.isRemote()) {
            draggable();
        }
    }

    public static Window of(String id, Component title, float x, float y, float width, float height,
            float minWidth, float minHeight) {
        return new Window(id, title, new WindowGeometry.Box(x, y, width, height),
            minWidth, minHeight);
    }

    private void draggable() {
        WindowDragHelper.setDragMove(titleBar, frame, event -> true, event -> remember());
        WindowDragHelper.setBorderResize(frame, frame, RESIZE_BORDER,
            new Vector2f(minWidth, minHeight), new Vector2f(Float.MAX_VALUE, Float.MAX_VALUE),
            event -> true, (event, handle) -> true, event -> remember());
        titleBar.addEventListener(UIEvents.DOUBLE_CLICK, event -> {
            WindowGeometry.forget(id);
            automaticPosition = true;
            centring = true;
        });
    }

    private void remember() {
        if (!open || frame.getSizeWidth() <= 0f || frame.getSizeHeight() <= 0f) {
            return;
        }
        automaticPosition = false;
        centring = false;
        wantedWidth = frame.getSizeWidth();
        wantedHeight = frame.getSizeHeight();
        WindowGeometry.put(id, new WindowGeometry.Box(
            frame.getLayoutX(), frame.getLayoutY(), frame.getSizeWidth(), frame.getSizeHeight()));
    }

    private void apply(WindowGeometry.Box box) {
        wantedWidth = Math.max(minWidth, box.width());
        wantedHeight = Math.max(minHeight, box.height());
        frame.layout(layout -> layout.left(box.x()).top(box.y())
            .width(wantedWidth).height(wantedHeight));
    }

    void clampInto(float deskWidth, float deskHeight) {
        if (!open || deskWidth <= 0f || deskHeight <= 0f) {
            return;
        }
        if (automaticPosition && (deskWidth != lastDeskWidth || deskHeight != lastDeskHeight)) {
            centring = true;
        }
        lastDeskWidth = deskWidth;
        lastDeskHeight = deskHeight;
        if (centring) {
            centring = false;
            apply(spotIn(deskWidth, deskHeight));
            wantedWidth = byDefault.width();
            wantedHeight = byDefault.height();
            return;
        }
        if (frame.getSizeWidth() <= 0f || frame.getSizeHeight() <= 0f) {
            return;
        }
        float width = Math.min(wantedWidth, deskWidth);
        float height = Math.min(wantedHeight, deskHeight);
        float x = Math.max(0f, Math.min(frame.getLayoutX(), deskWidth - width));
        float y = Math.max(0f, Math.min(frame.getLayoutY(), deskHeight - height));
        if (x == frame.getLayoutX() && y == frame.getLayoutY()
            && width == frame.getSizeWidth() && height == frame.getSizeHeight()) {
            return;
        }
        frame.layout(layout -> layout.left(x).top(y).width(width).height(height));
    }

    void resetLayout() {
        automaticPosition = true;
        centring = true;
        wantedWidth = byDefault.width();
        wantedHeight = byDefault.height();
        settle();
    }

    void nextTo(Window neighbour) {
        beside = neighbour == this ? null : neighbour;
    }

    private WindowGeometry.Box spotIn(float deskWidth, float deskHeight) {
        float width = Math.min(byDefault.width(), deskWidth);
        float height = Math.min(byDefault.height(), deskHeight);
        WindowGeometry.Box middle = new WindowGeometry.Box(
            Math.max(0f, (deskWidth - width) / 2f),
            Math.max(0f, (deskHeight - height) / 2f), width, height);
        if (beside == null || !beside.open || beside.frame.getSizeWidth() <= 0f) {
            return middle;
        }
        float hostLeft = beside.frame.getLayoutX();
        float hostRight = hostLeft + beside.frame.getSizeWidth();
        float top = Math.max(0f, Math.min(deskHeight - height, beside.frame.getLayoutY()));
        if (hostLeft - BESIDE_GAP - width >= 0f) {
            return new WindowGeometry.Box(hostLeft - BESIDE_GAP - width, top, width, height);
        }
        if (hostRight + BESIDE_GAP + width <= deskWidth) {
            return new WindowGeometry.Box(hostRight + BESIDE_GAP, top, width, height);
        }
        return middle;
    }

    @Override
    public void open() {
        if (open) {
            raise();
            return;
        }
        open = true;
        if (LDLib2.isRemote()) {
            WindowGeometry.Box remembered = WindowGeometry.of(id, byDefault);
            centring = remembered == byDefault;
            automaticPosition = centring;
            apply(remembered);
        }
        frame.setDisplay(true);
        settle();
        raise();
    }

    private void settle() {
        UIElement desk = frame.getParent();
        if (centring && desk != null) {
            clampInto(desk.getSizeWidth(), Math.max(0f, desk.getSizeHeight() - 24f));
        }
    }

    @Override
    public void close() {
        if (!open) {
            return;
        }
        open = false;
        frame.setDisplay(false);
        if (LDLib2.isRemote()) {
            WindowGeometry.flush();
        }
        onClose.run();
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    public void raise() {
        order = raising++;
        int drawn = order;
        frame.style(style -> style.zIndex(drawn));
    }

    int order() {
        return order;
    }

    public static int aboveWindows() {
        return raising;
    }

    @Override
    public void title(Component text) {
        caption.setText(text);
    }

    public Window setOnClose(Runnable closed) {
        onClose = closed;
        return this;
    }

    @Override
    public void onDismiss(Runnable both) {
        dismissed = both;
        closeButton.setOnServerClick(event -> both.run());
    }

    public UIElement frame() {
        return frame;
    }

    public UIElement body() {
        return body;
    }

    public String id() {
        return id;
    }
}
