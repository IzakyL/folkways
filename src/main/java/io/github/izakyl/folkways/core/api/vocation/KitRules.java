package io.github.izakyl.folkways.core.api.vocation;

import io.github.izakyl.folkways.core.api.colony.Colony;
import java.util.Objects;
import net.minecraft.world.item.Item;

// Which of the tools a trade's kit names a colony lets its residents take up. Core only asks;
// whoever keeps the colony's settings answers, and until someone does every tool is allowed.
public final class KitRules {

    @FunctionalInterface
    public interface Rule {
        boolean allows(Colony colony, Item tool);
    }

    private static volatile Rule rule = (colony, tool) -> true;

    private KitRules() {
    }

    public static void install(Rule installed) {
        rule = Objects.requireNonNull(installed);
    }

    public static boolean allows(Colony colony, Item tool) {
        return rule.allows(colony, tool);
    }
}
