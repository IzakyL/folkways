package io.github.izakyl.folkways.plugins.build;

/**
 * The switch an optional add-on flips to build an order with the site raising itself instead of the residents
 * placing one block at a time.
 *
 * <p>Nothing here is on by default: a world without the add-on installed never calls {@link #activate}, so every
 * site is the ordinary {@link BuildSite} and the build behaves exactly as it always has.
 */
public final class EfficientConstruction {

    /**
     * What the add-on asks of a site. Read when the add-on activates, and again whenever its config is reloaded.
     *
     * <p>{@code stacksPerTrip} says how much material one hand is asked to bring. A run of the plan is cut short once
     * what it spends reaches that many stacks, so a hand arrives with everything it will lay and does not come back
     * halfway through. Wide runs mean fewer journeys; the colony still has to hold the goods for the asking to be met,
     * and a list too wide to fill leaves the node waiting for goods instead of sending a hand.
     */
    public record Settings(int ticksPerBlock, int workersPerSite, int stacksPerTrip, boolean stowOnSite) {

        public static final Settings DEFAULTS = new Settings(16, 8, 18, true);

        public Settings {
            if (ticksPerBlock < 1) {
                throw new IllegalArgumentException("a block cannot be laid faster than once a tick");
            }
            if (workersPerSite < 1) {
                throw new IllegalArgumentException("a site needs at least one worker to start");
            }
            if (stacksPerTrip < 1) {
                throw new IllegalArgumentException("a hand must be able to bring something to build with");
            }
        }

        /** The most items one run may spend: whole stacks, as a resident's pack holds them. */
        public long itemsPerTrip() {
            return (long) stacksPerTrip * 64L;
        }
    }

    private static volatile Settings settings = Settings.DEFAULTS;

    private static volatile boolean active;

    private EfficientConstruction() {
    }

    /** Turns the efficient site on for every order from now on. Called by the add-on's mod constructor. */
    public static void activate(Settings requested) {
        settings = requested == null ? Settings.DEFAULTS : requested;
        active = true;
    }

    public static boolean active() {
        return active;
    }

    public static Settings settings() {
        return settings;
    }
}
