package io.github.izakyl.folkways.core.command.client;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Dialog;
import io.github.izakyl.folkways.core.card.api.Card;
import io.github.izakyl.folkways.core.card.client.CardView;
import io.github.izakyl.folkways.core.card.net.CardStatus;
import io.github.izakyl.folkways.core.command.combat.net.ClientUnits;
import io.github.izakyl.folkways.core.command.combat.net.UnitSnapshot;
import io.github.izakyl.folkways.core.front.client.Overhead;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.client.KeyMapping;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * The selected facility's card in the command view: the card as every window draws it, with the command view's
 * hotkeys on its commands and targets picked in the world.
 */
final class FacilityCard implements UnitWindow.Panel, CardView.Host {
    static final ItemStack ICON = new ItemStack(Items.LEVER);
    private static final float FRAME_WIDTH = 34;
    private static final float TITLE_AND_FRAME = 22 + 22;
    private static final float WAITING = 12 + 5 + 12;

    private final UnitWindow owner;
    private UnitSnapshot.Unit unit;
    private CardView view;

    FacilityCard(UnitWindow owner, List<UnitSnapshot.Unit> facilities) {
        this.owner = owner;
        this.unit = facilities.size() == 1 && facilities.getFirst().facility().isPresent() ? facilities.getFirst() : null;
        this.view = unit == null ? null : CardView.of(ClientUnits.facilityStatus(unit.id()).orElseGet(this::known),
            this);
    }

    boolean refresh(List<UnitSnapshot.Unit> facilities) {
        if (unit == null || facilities.size() != 1 || facilities.getFirst().facility().isEmpty()
            || !unit.id().equals(facilities.getFirst().id())) {
            return false;
        }
        unit = facilities.getFirst();
        // The window also refreshes on camera mode changes, which change the keys with the same status.
        view = view.redraw(ClientUnits.facilityStatus(unit.id()).orElseGet(this::known));
        return true;
    }

    /** Until the server's status comes, what the selection already says about the facility. */
    private CardStatus known() {
        UnitSnapshot.FacilityUnit facility = unit.facility().orElseThrow();
        return new CardStatus(unit.owner().orElse(new UUID(0, 0)), unit.id(), unit.name(), facility.card(),
            facility.parameters(), facility.triggers(), List.of(), Map.of(), List.of(), Optional.empty(), List.of(),
            Map.of(), List.of());
    }

    @Override
    public Component title() {
        return unit != null ? unit.name() : Component.translatable(UnitPanel.KEY + "selected", 0);
    }

    @Override
    public UIElement commands() {
        return view == null ? CardView.waiting(Component.translatable("folkways.rts.facility.waiting")) : view.body();
    }

    @Override
    public float preferredHeight() {
        return (view == null ? WAITING : view.height()) + TITLE_AND_FRAME;
    }

    @Override
    public float preferredWidth() {
        return (view == null ? 130 : view.width()) + FRAME_WIDTH;
    }

    @Override
    public boolean press(String token) {
        if (view == null || !token.startsWith("facility.")) {
            return false;
        }
        try {
            return view.press(Integer.parseInt(token.substring("facility.".length())) - 1);
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    // ---------------------------------------------------------------- what the command view does for the card

    @Override
    public KeyMapping key(int slot) {
        return CombatKeys.facilityKey(slot);
    }

    @Override
    public boolean picks() {
        return true;
    }

    @Override
    public void pick(int index, Card.Element.Target target) {
        owner.pickFacilityTarget(unit.id(), index, target);
    }

    @Override
    public void ping(BlockPos at) {
        Overhead.lookAt(Vec3.atCenterOf(at));
    }

    @Override
    public UIElement desk(UIElement asking) {
        return owner.desk();
    }

    @Override
    public void popup(Dialog dialog) {
        owner.popup(dialog);
    }

    @Override
    public void refused(Component reason) {
        owner.host().failed(reason);
    }
}
