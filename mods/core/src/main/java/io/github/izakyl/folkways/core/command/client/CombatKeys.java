package io.github.izakyl.folkways.core.command.client;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.izakyl.folkways.core.front.api.ui.OverheadTools;
import io.github.izakyl.folkways.core.front.client.Overhead;
import io.github.izakyl.folkways.core.command.combat.ui.CombatContent;
import net.minecraft.client.KeyMapping;
import net.neoforged.neoforge.client.settings.IKeyConflictContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.lwjgl.glfw.GLFW;

/** Command shortcuts belong only to the active RTS screen, including in Controls' conflict checks. */
public final class CombatKeys {
    private static final IKeyConflictContext CONTEXT = new IKeyConflictContext() {
        @Override
        public boolean isActive() {
            return Overhead.activeScreen()
                && OverheadTools.selected().filter(tool -> tool.id().equals(CombatContent.ID)).isPresent();
        }

        @Override
        public boolean conflicts(IKeyConflictContext other) {
            return true;
        }
    };

    /** Slots for one camera mode: live only in RTS with that mode on, and clashing only with RTS keys and each other. */
    private static IKeyConflictContext slots(boolean edge) {
        return new IKeyConflictContext() {
            @Override
            public boolean isActive() {
                return CONTEXT.isActive() && Overhead.edgeScroll() == edge;
            }

            @Override
            public boolean conflicts(IKeyConflictContext other) {
                return other == this || other == CONTEXT;
            }
        };
    }

    private static final IKeyConflictContext WASD_SLOTS = slots(false);
    private static final IKeyConflictContext GRID_SLOTS = slots(true);

    // Attack keeps the old toggle's mapping ID, so a player's rebinding of G carries over.
    public static final KeyMapping ATTACK = mapping("attack_move", GLFW.GLFW_KEY_G);
    public static final KeyMapping MOVE = mapping("move", GLFW.GLFW_KEY_M);
    public static final KeyMapping STOP = mapping("stop", GLFW.GLFW_KEY_H);
    public static final KeyMapping BOARD = mapping("board", GLFW.GLFW_KEY_B);
    public static final KeyMapping DISEMBARK = mapping("disembark", GLFW.GLFW_KEY_N);
    public static final KeyMapping LAND = mapping("land", GLFW.GLFW_KEY_L);
    public static final KeyMapping LOITER = mapping("loiter", GLFW.GLFW_KEY_O);
    public static final KeyMapping DECOY = mapping("decoy", GLFW.GLFW_KEY_C);
    public static final KeyMapping BOOST = mapping("boost", GLFW.GLFW_KEY_X);
    public static final KeyMapping DETAILS = mapping("details", GLFW.GLFW_KEY_I);
    public static final KeyMapping RELEASE = mapping("release_control", GLFW.GLFW_KEY_J);
    /**
     * A facility card's buttons and toggles take these in card order while the camera flies on W, A, S and D: clear of
     * the camera, of Q and E beside it, and of every other RTS key.
     */
    public static final List<KeyMapping> FACILITY = slots("facility_", WASD_SLOTS, GLFW.GLFW_KEY_R, GLFW.GLFW_KEY_T,
        GLFW.GLFW_KEY_Y, GLFW.GLFW_KEY_U, GLFW.GLFW_KEY_F, GLFW.GLFW_KEY_K);
    /** The same slots with the edge-scroll camera, which frees W, A, S and D: the strategy-game grid. */
    public static final List<KeyMapping> FACILITY_GRID = slots("facility_grid_", GRID_SLOTS, GLFW.GLFW_KEY_Q,
        GLFW.GLFW_KEY_W, GLFW.GLFW_KEY_E, GLFW.GLFW_KEY_R, GLFW.GLFW_KEY_A, GLFW.GLFW_KEY_S, GLFW.GLFW_KEY_D,
        GLFW.GLFW_KEY_F);

    /** Each command-card button's key, by the button's token; a key presses its button when the card shows it. */
    public static final Map<String, KeyMapping> CARD = cards();
    public static final List<KeyMapping> ALL = java.util.stream.Stream.of(java.util.stream.Stream.of(ATTACK,
        MOVE, STOP, BOARD, DISEMBARK, LAND, LOITER, DECOY, BOOST, DETAILS, RELEASE), FACILITY.stream(),
        FACILITY_GRID.stream()).flatMap(each -> each).toList();

    private CombatKeys() {
    }

    private static Map<String, KeyMapping> cards() {
        Map<String, KeyMapping> keys = new LinkedHashMap<>();
        keys.put("board", BOARD);
        keys.put("disembark", DISEMBARK);
        keys.put("move", MOVE);
        keys.put("attack", ATTACK);
        keys.put("stop", STOP);
        keys.put("op.land", LAND);
        keys.put("op.loiter", LOITER);
        keys.put("op.decoy", DECOY);
        keys.put("op.sprint", BOOST);
        keys.put("details", DETAILS);
        return java.util.Collections.unmodifiableMap(keys);
    }

    /** The card token of a facility card's slot, counted from zero. */
    static String facility(int slot) {
        return "facility." + (slot + 1);
    }

    /** The facility card's slot keys for the camera mode in use. */
    static List<KeyMapping> facilitySlots() {
        return Overhead.edgeScroll() ? FACILITY_GRID : FACILITY;
    }

    /** The key of a facility card's slot in the camera mode in use, or null past the last slot. */
    static KeyMapping facilityKey(int slot) {
        List<KeyMapping> slots = facilitySlots();
        return slot < slots.size() ? slots.get(slot) : null;
    }

    /** The slot a facility key presses, counted from zero, or -1 when it is no facility key. */
    static int facilitySlot(KeyMapping mapping) {
        int slot = FACILITY.indexOf(mapping);
        return slot >= 0 ? slot : FACILITY_GRID.indexOf(mapping);
    }

    private static List<KeyMapping> slots(String prefix, IKeyConflictContext context, int... keys) {
        List<KeyMapping> slots = new java.util.ArrayList<>();
        for (int slot = 0; slot < keys.length; slot++) {
            slots.add(new KeyMapping("key.folkways.rts." + prefix + (slot + 1), context,
                InputConstants.Type.KEYSYM.getOrCreate(keys[slot]), "key.categories.folkways_core.rts"));
        }
        return List.copyOf(slots);
    }

    private static KeyMapping mapping(String command, int key) {
        return new KeyMapping("key.folkways.rts." + command, CONTEXT, InputConstants.Type.KEYSYM.getOrCreate(key),
            "key.categories.folkways_core.rts");
    }
}
