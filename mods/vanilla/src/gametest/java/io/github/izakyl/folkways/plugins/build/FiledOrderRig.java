package io.github.izakyl.folkways.plugins.build;

import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.engine.labor.Commit;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;

/** Answers a filed site's work by hand, through the same commits and completion path as colony labour. */
public final class FiledOrderRig {

    private FiledOrderRig() {
    }

    private static BuildPresence presence(Colony colony) {
        return colony.service(BuildContent.ID, BuildPresence.class).orElseThrow();
    }

    public static void work(GameTestHelper helper, Colony colony, UUID order, Worker worker) {
        BuildPresence presence = presence(colony);
        ServerLevel level = helper.getLevel();
        presence.settle(level.getServer());
        BuildSite site = presence.site(order).orElseThrow();
        for (int round = 0; round < 64 && presence.site(order).isPresent(); round++) {
            for (BuildSite.Piece piece : site.pieces()) {
                helper.assertTrue(Commit.run(level, piece.node(), worker) instanceof Outcome.Done,
                    "the ordinary build work can be committed");
                site.ended(piece.node().id(), Ending.DONE);
            }
            presence.settle(level.getServer());
        }
        helper.assertValueEqual(site.left(), 0, "ordinary build work meets every target cell");
        helper.assertTrue(presence.site(order).isEmpty(), "the completed site is retired");
    }

    public static void reload(Colony colony, ServerLevel level) {
        BuildPresence restored = new BuildPresence(colony, level.getServer());
        // The restored book must remember to discard the synthetic blueprint too.
        for (BlueprintBuildOrder order : BlueprintBook.load(
                io.github.izakyl.folkways.base.persist.Reader.of(colony.kept(BuildContent.ID).getCompound("book")),
                level.registryAccess()).allBuildOrders()) {
            restored.act(BuildContent.PAGE.id(), BuildAct.cancelling(order.id()), Optional.empty(), colony.view(level));
        }
        restored.settle(level.getServer());
    }
}
