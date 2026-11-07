package io.github.izakyl.folkways.front.client;

import io.github.izakyl.folkways.FolkwaysClientConfig;
import io.github.izakyl.folkways.front.engine.item.HeldBook;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;

/**
 * A part of what the book draws over the world, which the view from above can put away to see the rest: its
 * cards alone, leaving what it marks on the ground, or all of it. Out of the player's own eyes, with the book in
 * hand all of it is drawn; without it, all of it if the player's own setting asks for it, and by default nothing.
 */
enum Layer {
    ZONES("folkways.overhead.layer.zones", true),
    PATHS("folkways.overhead.layer.paths", true),
    WORKS("folkways.overhead.layer.works", true),
    RESIDENTS("folkways.overhead.layer.residents", false);

    /** How much of a layer is drawn. */
    enum Showing {
        ALL("folkways.overhead.layer.all"),
        MARKS("folkways.overhead.layer.marks"),
        NONE("folkways.overhead.layer.none");

        private final String nameKey;

        Showing(String nameKey) {
            this.nameKey = nameKey;
        }

        String nameKey() {
            return nameKey;
        }
    }

    private final String nameKey;
    /** Whether it marks the ground at all; a layer that does not is its cards and nothing else. */
    private final boolean marks;
    private Showing showing = Showing.ALL;

    Layer(String nameKey, boolean marks) {
        this.nameKey = nameKey;
        this.marks = marks;
    }

    String nameKey() {
        return nameKey;
    }

    Showing showing() {
        if (Overhead.on()) {
            return showing;
        }
        Player player = Minecraft.getInstance().player;
        return player != null && HeldBook.bound(player).isPresent() ? Showing.ALL : withoutBook();
    }

    /**
     * How much of every layer is drawn while no book is in hand: all of it once the player has asked to keep the
     * colony in sight, nothing otherwise. Whatever else is in hand, or nothing, makes no difference.
     */
    static Showing withoutBook() {
        return FolkwaysClientConfig.overlaysWithoutBook() ? Showing.ALL : Showing.NONE;
    }

    /** Whether what it marks is drawn, and can be pointed at. */
    boolean shown() {
        return showing() != Showing.NONE;
    }

    /** Whether its cards are drawn. */
    boolean cards() {
        return showing() == Showing.ALL;
    }

    /** One press puts the cards away, the next the markings too, the third brings both back. */
    void toggle() {
        showing = switch (showing) {
            case ALL -> marks ? Showing.MARKS : Showing.NONE;
            case MARKS -> Showing.NONE;
            case NONE -> Showing.ALL;
        };
    }
}
