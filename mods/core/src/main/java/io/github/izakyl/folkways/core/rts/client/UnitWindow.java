package io.github.izakyl.folkways.core.rts.client;

import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.lowdragmc.lowdraglib2.gui.ui.style.StylesheetManager;
import io.github.izakyl.folkways.core.front.ui.screen.DeskTextures;
import io.github.izakyl.folkways.core.front.ui.screen.Window;
import io.github.izakyl.folkways.core.rts.combat.net.ClientUnits;
import io.github.izakyl.folkways.core.rts.combat.net.UnitSnapshot;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * The selection's command card at the bottom right, and the details window (seats, weapons, stock) it opens on
 * demand. Both are LDLib2 windows laid over the overhead view; their widget is one of the overhead screen's
 * children, so LDLib2 adapters find them, and pointer input over them is routed to them before the RTS tool.
 */
final class UnitWindow {
    static final String ID = "folkways_rts_unit";
    static final String DETAILS = "folkways_rts_unit_details";
    static final float WIDTH = 186;
    static final float DETAILS_WIDTH = 212;
    private static final float TOP = 28;
    private static final float RIGHT = 2;
    private static final float BOTTOM = 2;
    private static final float GAP = 4;

    /** What the windows need from the RTS tool that owns selection and command input. */
    interface Host {
        /** The single vehicle or squad the selection is, if it is one. */
        Optional<UnitSnapshot.Unit> focus();

        Set<UUID> selectedResidents();

        Set<UUID> selectedVehicles();

        void failed(Component reason);

        void selectResidents(Set<UUID> residents);

        void selectVehicle(UUID vehicle);

        CombatController.Command armed();

        void arm(CombatController.Command command);

        void stop();

        /** Whether a plain right-click on the ground attack-moves rather than moves. */
        boolean attackMoveDefault();

        void attackMoveDefault(boolean attack);

        /** The squad order's state line, for the selection strip's tooltip. */
        Component orders();
    }

    private final Host host;
    private Screen screen;
    private ModularUI ui;
    private Window window;
    private Window details;
    private UnitPanel panel;
    private UnitPanel detailPanel;
    private Object drawn;
    private Object detailDrawn;
    private Set<UUID> dismissed;
    private boolean hiding;
    private boolean detailsWanted;
    private final Set<Integer> captured = new HashSet<>();
    private final Map<Long, UUID> pending = new LinkedHashMap<>();
    private int laidWidth = -1, laidHeight = -1, laidDetails = -1;
    private UIElement desk;
    private com.lowdragmc.lowdraglib2.gui.ui.elements.Dialog popup;

    UnitWindow(Host host) {
        this.host = host;
    }

    /** Called for every (re)initialisation of the overhead screen; the widget must be re-added each time. */
    void attach(Screen target, Consumer<GuiEventListener> add) {
        if (screen != target) {
            detach();
            screen = target;
            UIElement root = new UIElement()
                .layout(layout -> layout.widthPercent(100).heightPercent(100));
            root.addClass("folkways_desk");
            root.setAllowHitTest(false);
            DeskTextures.installPopups(root);
            window = Window.of(ID, Component.translatable("folkways.rts.unit.title"), 0, 0, WIDTH, 120, 120, 60);
            window.setOnClose(() -> {
                if (!hiding) {
                    dismissed = selection();
                    detailsWanted = false;
                }
            });
            details = Window.of(DETAILS, Component.translatable("folkways.rts.unit.details"), 0, 0, DETAILS_WIDTH,
                160, 160, 80);
            details.setOnClose(() -> {
                if (!hiding) {
                    detailsWanted = false;
                    drawn = null;
                }
            });
            root.addChildren(window.frame(), details.frame());
            desk = root;
            ui = new ModularUI(UI.of(root, List.of(
                StylesheetManager.INSTANCE.getStylesheetSafe(StylesheetManager.MC),
                StylesheetManager.INSTANCE.getStylesheetSafe(
                    ResourceLocation.fromNamespaceAndPath("folkways_core", "lss/desk.lss")))),
                Minecraft.getInstance().player);
            drawn = null;
            detailDrawn = null;
        }
        ui.setScreenAndInit(target);
        add.accept(ui.getWidget());
        laidWidth = laidHeight = laidDetails = -1;
    }

    void detach() {
        if (ui != null) {
            ui.onRemoved();
        }
        ui = null;
        screen = null;
        window = null;
        details = null;
        panel = null;
        detailPanel = null;
        drawn = null;
        detailDrawn = null;
        captured.clear();
    }

    boolean attachedTo(Screen target) {
        return ui != null && screen == target;
    }

    void track(long request, UUID unit) {
        pending.put(request, unit);
    }

    private Set<UUID> selection() {
        Set<UUID> all = new HashSet<>(host.selectedResidents());
        all.addAll(host.selectedVehicles());
        return all;
    }

    void tick() {
        receipts();
        if (ui == null) {
            return;
        }
        if (Minecraft.getInstance().screen != screen) {
            detach();
            return;
        }
        ui.tick();
        Set<UUID> selection = selection();
        if (selection.isEmpty() || selection.equals(dismissed)) {
            if (selection.isEmpty()) {
                dismissed = null;
                detailsWanted = false;
            }
            hide();
            return;
        }
        dismissed = null;
        Optional<UnitSnapshot.Unit> focus = host.focus();
        List<UnitSnapshot.Unit> vehicles = host.selectedVehicles().stream().map(ClientUnits::unit)
            .flatMap(Optional::stream).toList();
        // Only what the buttons say: health and energy live in the world, so a meter tick does not rebuild the card
        // (and close a picker opened from it).
        Object signature = List.of(selection, vehicles.stream().map(unit -> List.of(unit.id(), unit.name().getString(),
                unit.capabilities(), unit.seats().stream().map(seat -> seat.occupant().map(Object::toString).orElse(""))
                    .toList(), unit.releaseWhenIdle(), unit.altitude(),
                ClientUnits.vehicleProfile(unit.id()).map(profile -> profile.id().toString()).orElse(""))).toList(),
            focus.map(UnitSnapshot.Unit::id).map(Object::toString).orElse(""), host.armed(), host.attackMoveDefault(),
            ClientUnits.vehicleProfiles(), detailsWanted, host.orders().getString());
        if (!Objects.equals(signature, drawn)) {
            drawn = signature;
            panel = new UnitPanel(this, focus, vehicles, host.selectedResidents());
            window.title(panel.title());
            window.body().clearAllChildren();
            window.body().addChildren(panel.commands());
            if (!window.isOpen()) {
                window.open();
            }
            laidWidth = laidHeight = -1;
        }
        if (detailsWanted && focus.isPresent()) {
            Object detailSignature = detailSignature(focus.get());
            if (!Objects.equals(detailSignature, detailDrawn)) {
                float scroll = detailPanel != null && detailDrawn != null ? detailPanel.scroll() : 0;
                detailDrawn = detailSignature;
                detailPanel = new UnitPanel(this, focus, vehicles, host.selectedResidents());
                details.title(detailPanel.title());
                details.body().clearAllChildren();
                details.body().addChildren(detailPanel.details());
                detailPanel.scroll(scroll);
                if (!details.isOpen()) {
                    details.open();
                }
                laidDetails = -1;
            }
        } else {
            closeDetails();
        }
        place();
    }

    private Object detailSignature(UnitSnapshot.Unit unit) {
        // Live faces and meters draw themselves; only what the rows say needs a rebuild.
        var residents = ClientUnits.snapshot().map(UnitSnapshot::residents).orElse(List.of());
        var crew = new HashSet<UUID>(unit.members());
        unit.seats().forEach(seat -> seat.occupant().ifPresent(crew::add));
        return List.of(unit, residents.stream().filter(resident -> crew.contains(resident.id())).toList(),
            ClientUnits.vehicleProfiles(), ClientUnits.vehicleInventory(unit.id()), CombatController.aboard(crew));
    }

    private void hide() {
        hiding = true;
        if (window != null && window.isOpen()) {
            window.close();
        }
        hiding = false;
        closeDetails();
        drawn = null;
        panel = null;
    }

    private void closeDetails() {
        if (details != null && details.isOpen()) {
            hiding = true;
            details.close();
            hiding = false;
        }
        detailDrawn = null;
        detailPanel = null;
    }

    private void place() {
        var mc = Minecraft.getInstance().getWindow();
        int width = mc.getGuiScaledWidth(), height = mc.getGuiScaledHeight();
        float h = Math.min(panel.preferredHeight(), height - TOP - BOTTOM);
        float w = Math.min(panel.preferredWidth(), width - 2 * RIGHT);
        float top = height - BOTTOM - h;
        int key = Math.round(h) * 4096 + Math.round(w);
        if (laidWidth != width || laidHeight != key) {
            laidWidth = width;
            laidHeight = key;
            float left = Math.max(0, width - w - RIGHT);
            window.frame().layout(layout -> layout.left(left).top(top).width(w).height(h));
            laidDetails = -1;
        }
        if (detailPanel != null && details.isOpen()) {
            float room = Math.max(60, top - GAP - TOP);
            float dh = Math.min(detailPanel.preferredHeight(), room);
            int detailKey = Math.round(dh) * 31 + Math.round(top);
            if (laidDetails != detailKey) {
                laidDetails = detailKey;
                float left = Math.max(0, width - DETAILS_WIDTH - RIGHT);
                details.frame().layout(layout -> layout.left(left).top(top - GAP - dh).width(DETAILS_WIDTH)
                    .height(dh));
            }
        }
    }

    private void receipts() {
        var iterator = pending.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            var reply = ClientUnits.receipt(entry.getKey());
            if (reply.isEmpty()) {
                continue;
            }
            iterator.remove();
            if (!reply.get().result().accepted()) {
                host.failed(Component.translatable(reply.get().result().key()));
            }
        }
        if (pending.size() > 64) {
            pending.remove(pending.keySet().iterator().next());
        }
    }

    /**
     * Where the card's pickers open: the whole overlay, so a picker stands centred on the screen rather than
     * squeezed into (and drawn under the icons of) the small card that asked for it.
     */
    UIElement desk() {
        return desk;
    }

    /** Remembers the card's open picker, so Esc closes it before it clears the selection. */
    void popup(com.lowdragmc.lowdraglib2.gui.ui.elements.Dialog dialog) {
        popup = dialog;
    }

    /** Closes the card's open picker; false when none is open. */
    boolean closePopup() {
        if (popup == null || popup.getParent() == null) {
            popup = null;
            return false;
        }
        popup.close();
        popup = null;
        return true;
    }

    /** Presses the command card's button with this token, as a hotkey does; false when the card has none. */
    boolean press(String token) {
        return panel != null && window != null && window.isOpen() && panel.press(token);
    }

    /** Opens or closes the details window for the focused unit. */
    void toggleDetails() {
        detailsWanted = !detailsWanted;
        drawn = null;
    }

    boolean detailsOpen() {
        return detailsWanted;
    }

    Host host() {
        return host;
    }

    UIElement root() {
        return window == null ? null : window.frame();
    }

    // --- pointer routing: a press that lands on a window belongs to it for its drag and release ---

    private boolean over(double x, double y) {
        if (ui == null || window == null || !window.isOpen() && !details.isOpen()) {
            return false;
        }
        ui.refreshHoveredElementAtScreen((float) x, (float) y);
        return ui.getLastHoveredElement() != null;
    }

    boolean pressed(double x, double y, int button) {
        if (!over(x, y)) {
            return false;
        }
        captured.add(button);
        ui.getWidget().mouseClicked(x, y, button);
        return true;
    }

    boolean dragged(double x, double y, int button, double dx, double dy) {
        if (!captured.contains(button)) {
            return false;
        }
        ui.getWidget().mouseDragged(x, y, button, dx, dy);
        return true;
    }

    boolean released(double x, double y, int button) {
        if (!captured.remove(button)) {
            return false;
        }
        if (ui != null) {
            ui.getWidget().mouseReleased(x, y, button);
        }
        return true;
    }

    boolean scrolled(double x, double y, double dx, double dy) {
        if (!over(x, y)) {
            return false;
        }
        ui.getWidget().mouseScrolled(x, y, dx, dy);
        return true;
    }

    /** The scroll view gets the remaining height; the window body lays it out. */
    static ScrollerView scroller() {
        return io.github.izakyl.folkways.core.front.api.ui.Rows.fills(
            io.github.izakyl.folkways.core.front.api.ui.Rows.box());
    }
}
