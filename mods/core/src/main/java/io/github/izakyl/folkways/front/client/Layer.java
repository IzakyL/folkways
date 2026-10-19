package io.github.izakyl.folkways.front.client;

/**
 * A part of what the book draws over the world, which the view from above can put away to see the rest: its
 * cards alone, leaving what it marks on the ground, or all of it. Out of the player's own eyes all of it is drawn.
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
        return Overhead.on() ? showing : Showing.ALL;
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
