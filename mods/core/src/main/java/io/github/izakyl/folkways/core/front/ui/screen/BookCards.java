package io.github.izakyl.folkways.core.front.ui.screen;

import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.core.card.client.CardPane;
import io.github.izakyl.folkways.core.card.client.CardView;
import io.github.izakyl.folkways.core.card.client.CardWindow;
import io.github.izakyl.folkways.core.card.client.ClientCards;
import io.github.izakyl.folkways.core.front.client.ColonyHighlightState;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * The cards the open colony book shows, kept watched while they are in sight: a page's cards while the page is shown,
 * the book's card window's while it is open. Every watch ends when the book closes. Client side; one book is open at
 * a time.
 */
public final class BookCards {
    // The server keeps a player told about this many cards at most; the card window's is asked for first.
    private static final int MOST = 16;
    private static final Map<UIElement, Wanted> WANTED = new WeakHashMap<>();
    private static final Map<UUID, UUID> WATCHING = new LinkedHashMap<>();
    private static UIElement root;
    private static Window window;
    private static CardPane pane;

    /** How a card on a book page is drawn: no run state, scrolled with its page, its rows pinging and opening here. */
    static final CardView.Host INLINE = new CardView.Host() {
        @Override
        public void ping(BlockPos at) {
            ColonyHighlightState.pingBlock(at);
        }

        @Override
        public void open(UUID colony, UUID facility) {
            BookCards.open(colony, facility);
        }

        @Override
        public UIElement desk(UIElement asking) {
            return root != null ? root : asking;
        }

        @Override
        public boolean states() {
            return false;
        }

        @Override
        public boolean scrolls() {
            return false;
        }
    };

    private record Wanted(UUID colony, List<UUID> facilities) {
    }

    private BookCards() {
    }

    /**
     * Serves a newly built book: its desk, and the window a facility's card opens in. The desk that ticks is the one
     * on screen, so it takes over from whatever book was served before the first time it ticks.
     */
    static void attach(UIElement desk, Window cards) {
        desk.addEventListener(UIEvents.TICK, event -> {
            if (root != desk) {
                // What a page of this book asked for before it first ticked stays asked for.
                unwatch();
                root = desk;
                window = cards;
                pane = new CardPane(cards, desk) {
                    @Override
                    public void open(UUID colony, UUID facility) {
                        BookCards.open(colony, facility);
                    }
                };
            }
            tick();
        });
    }

    /** Asks for these cards while {@code shownBy} is in sight; an empty list asks for none. */
    static void want(UIElement shownBy, UUID colony, List<UUID> facilities) {
        WANTED.put(shownBy, new Wanted(colony, List.copyOf(facilities)));
    }

    /** Shows the facility's card in the book's card window, or in a window of its own outside the book. */
    public static void open(UUID colony, UUID facility) {
        if (pane == null) {
            CardWindow.open(colony, facility);
            return;
        }
        pane.show(colony, facility);
        window.open();
    }

    private static void tick() {
        Map<UUID, UUID> wanted = new LinkedHashMap<>();
        if (window.isOpen()) {
            pane.refresh();
            pane.facility().ifPresent(facility -> wanted.put(facility, pane.colony().orElseThrow()));
        }
        WANTED.forEach((element, asked) -> {
            if (shown(element)) {
                for (UUID facility : asked.facilities()) {
                    if (wanted.size() < MOST) {
                        wanted.putIfAbsent(facility, asked.colony());
                    }
                }
            }
        });
        WATCHING.entrySet().removeIf(watched -> {
            if (watched.getValue().equals(wanted.get(watched.getKey()))) {
                return false;
            }
            ClientCards.watch(watched.getValue(), watched.getKey(), false);
            return true;
        });
        wanted.forEach((facility, colony) -> {
            if (WATCHING.putIfAbsent(facility, colony) == null) {
                ClientCards.watch(colony, facility, true);
            }
        });
    }

    // In sight: it and every element above it up to the desk displayed.
    private static boolean shown(UIElement element) {
        UIElement at = element;
        while (at != null) {
            if (!at.isDisplayed()) {
                return false;
            }
            if (at == root) {
                return true;
            }
            at = at.getParent();
        }
        return false;
    }

    private static void unwatch() {
        WATCHING.forEach((facility, colony) -> ClientCards.watch(colony, facility, false));
        WATCHING.clear();
    }

    private static void close() {
        unwatch();
        WANTED.clear();
        root = null;
        window = null;
        pane = null;
    }

    @EventBusSubscriber(modid = FolkwaysCoreMod.CORE_ID, value = Dist.CLIENT)
    public static final class Closing {
        private Closing() {
        }

        @SubscribeEvent
        public static void closing(ScreenEvent.Closing event) {
            ModularUI ui = ModularUI.of(event.getScreen());
            if (root != null && ui != null && ui.ui.getRootElement() == root) {
                close();
            }
        }
    }
}
