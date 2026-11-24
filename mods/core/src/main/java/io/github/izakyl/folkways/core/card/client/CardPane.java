package io.github.izakyl.folkways.core.card.client;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import io.github.izakyl.folkways.core.card.net.CardStatus;
import io.github.izakyl.folkways.core.front.client.ColonyHighlightState;
import io.github.izakyl.folkways.core.front.ui.screen.Window;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/**
 * A facility's card in a window's body, its name in the window's caption. It is drawn from the status the server last
 * sent and again whenever a new one comes. Until a status comes it says it is waiting. Whoever shows the pane
 * watches the card.
 */
public class CardPane implements CardView.Host {
    private final Window window;
    private final UIElement desk;
    private UUID colony;
    private UUID facility;
    private Object drawn = this;
    private CardView view;

    public CardPane(Window window, UIElement desk) {
        this.window = window;
        this.desk = desk;
    }

    /** Shows this facility's card in place of whatever the pane showed. */
    public void show(UUID colony, UUID facility) {
        this.colony = colony;
        this.facility = facility;
        drawn = this;
        view = null;
        window.title(Component.translatable(CardView.ID + "window"));
        refresh();
    }

    public Optional<UUID> colony() {
        return Optional.ofNullable(colony);
    }

    public Optional<UUID> facility() {
        return Optional.ofNullable(facility);
    }

    /** Draws the card again if the server told something new since. */
    public void refresh() {
        if (facility == null) {
            return;
        }
        CardStatus status = ClientCards.status(facility).filter(each -> each.colony().equals(colony)).orElse(null);
        if (status == drawn) {
            return;
        }
        drawn = status;
        if (status != null && view != null) {
            view = view.refresh(status);
        } else {
            view = status == null ? null : CardView.of(status, this);
            window.body().clearAllChildren();
            window.body().addChildren(view == null
                ? CardView.waiting(Component.translatable(CardView.KEY + "waiting"))
                : view.body());
        }
        if (status != null) {
            window.title(status.title());
        }
    }

    @Override
    public void ping(BlockPos at) {
        ColonyHighlightState.pingBlock(at);
    }

    @Override
    public UIElement desk(UIElement asking) {
        return desk;
    }
}
