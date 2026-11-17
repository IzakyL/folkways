package io.github.izakyl.folkways.core.rts.client;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.izakyl.folkways.core.front.api.ui.OverheadTools;
import io.github.izakyl.folkways.core.front.client.Overhead;
import io.github.izakyl.folkways.core.rts.combat.ui.CombatContent;
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

    /** Each command-card button's key, by the button's token; a key presses its button when the card shows it. */
    public static final Map<String, KeyMapping> CARD = cards();
    public static final List<KeyMapping> ALL = List.of(ATTACK, MOVE, STOP, BOARD, DISEMBARK, LAND, LOITER,
        DECOY, BOOST, DETAILS, RELEASE);

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

    private static KeyMapping mapping(String command, int key) {
        return new KeyMapping("key.folkways.rts." + command, CONTEXT, InputConstants.Type.KEYSYM.getOrCreate(key),
            "key.categories.folkways_core.rts");
    }
}
