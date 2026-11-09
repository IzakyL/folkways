package io.github.izakyl.folkways.core.rts.client;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.izakyl.folkways.core.front.api.ui.OverheadTools;
import io.github.izakyl.folkways.core.front.client.Overhead;
import io.github.izakyl.folkways.core.rts.combat.ui.CombatContent;
import net.minecraft.client.KeyMapping;
import net.neoforged.neoforge.client.settings.IKeyConflictContext;
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

    // Keep the persisted mapping ID so existing player rebindings survive the semantic change.
    public static final KeyMapping MOVE_MODE = mapping("attack_move", GLFW.GLFW_KEY_G);
    public static final KeyMapping STOP = mapping("stop", GLFW.GLFW_KEY_H);
    public static final KeyMapping RELEASE = mapping("release_control", GLFW.GLFW_KEY_J);

    private CombatKeys() {
    }

    private static KeyMapping mapping(String command, int key) {
        return new KeyMapping("key.folkways.rts." + command, CONTEXT, InputConstants.Type.KEYSYM.getOrCreate(key),
            "key.categories.folkways_core.rts");
    }
}
