package io.github.izakyl.folkways.core.shell.ae2;

import appeng.api.networking.IGridNode;
import appeng.api.stacks.AEKey;
import io.github.izakyl.folkways.core.shell.crafting.TerminalPreferences;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.server.level.ServerPlayer;

public final class TerminalRecipes {

    private TerminalRecipes() {
    }

    public static Set<AEKey> filter(ServerPlayer player, IGridNode node, Set<AEKey> craftables) {
        if (TerminalPreferences.visible(player) || node == null) {
            return craftables;
        }
        Set<AEKey> visible = new HashSet<>(craftables);
        var crafting = node.getGrid().getCraftingService();
        visible.removeIf(key -> {
            var patterns = crafting.getCraftingFor(key);
            return !patterns.isEmpty() && patterns.stream().allMatch(ColonyPattern::isColonyPattern);
        });
        return visible;
    }
}
