package io.github.izakyl.folkways.front.api.ui;

import io.github.izakyl.folkways.front.api.Ghost;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * A whole shape held over the world with the book, to be set down where it is aimed: it stands at the crosshair,
 * turns and flips with the wheel, and a click puts it down there. It is let go when the book leaves the hand.
 */
@OnlyIn(Dist.CLIENT)
public final class Placing {

    private static Hand hand = new Hand() {
        @Override
        public void hold(Held held) {
        }

        @Override
        public void drop() {
        }

        @Override
        public boolean holding() {
            return false;
        }
    };

    private static final List<Shelf> SHELVES = new CopyOnWriteArrayList<>();

    private Placing() {
    }

    /** Somewhere a shape to hold is picked from, offered as a button of its own in the view from above. */
    public static void shelve(Shelf shelf) {
        SHELVES.add(shelf);
    }

    public static List<Shelf> shelves() {
        return List.copyOf(SHELVES);
    }

    public static void install(Hand installed) {
        hand = installed;
    }

    public static void hold(Held held) {
        hand.hold(held);
    }

    public static void drop() {
        hand.drop();
    }

    public static boolean holding() {
        return hand.holding();
    }

    /** {@code open} shows what there is to pick; picking one holds it, and the view it was opened from stays. */
    public record Shelf(String nameKey, ResourceLocation icon, Runnable open) {
    }

    public interface Held {

        Component name();

        /** Its blocks turned and flipped so, from the lowest corner of the box they make. */
        List<Ghost> blocks(Rotation rotation, Mirror mirror);

        /** Clicked down with its lowest corner at {@code corner}; it stays held until it calls {@link #drop}. */
        void place(BlockPos corner, Rotation rotation, Mirror mirror);
    }

    public interface Hand {

        void hold(Held held);

        void drop();

        boolean holding();
    }
}
