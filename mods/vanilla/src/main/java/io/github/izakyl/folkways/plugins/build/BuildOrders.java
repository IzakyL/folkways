package io.github.izakyl.folkways.plugins.build;

import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import java.util.List;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;

/** Already confirmed drawings from other colony work, with no reusable blueprint to keep afterwards. */
public final class BuildOrders {

    private BuildOrders() {
    }

    public static boolean fileOnce(Colony colony, ServerLevel level, Blueprint blueprint, WorldPos anchor) {
        return colony.service(BuildContent.ID, BuildPresence.class)
            .map(presence -> presence.fileOnce(level, blueprint, anchor.block(level)).isPresent())
            .orElse(false);
    }

    /** Every order a colony is building, in the order they were taken up. */
    public static List<BlueprintBuildOrder> orders(Colony colony) {
        return colony.service(BuildContent.ID, BuildPresence.class)
            .map(BuildPresence::orders)
            .orElse(List.of());
    }

    /**
     * Starts an order's site again: the order keeps its place and its blueprint, and the site is planned afresh
     * against the world as it now stands. What is already built counts as built, so this costs only what was stuck.
     */
    public static boolean restart(Colony colony, UUID order) {
        return colony.service(BuildContent.ID, BuildPresence.class)
            .map(presence -> presence.restart(order))
            .orElse(false);
    }
}
