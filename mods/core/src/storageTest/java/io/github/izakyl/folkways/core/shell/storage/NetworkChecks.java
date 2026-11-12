package io.github.izakyl.folkways.core.shell.storage;

import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.inventory.api.port.StorePorts;
import io.github.izakyl.folkways.core.inventory.api.port.Usability;
import io.github.izakyl.folkways.core.runtime.LaborFixture;
import io.github.izakyl.folkways.core.testing.Cleanup;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;

public final class NetworkChecks {

    private NetworkChecks() {
    }

    public static void refused(GameTestHelper helper, BlockPos pos, String reason) {
        PortChecks.unusable(helper, pos);
        var verdict = (Usability.Unusable) PortChecks.at(helper, pos).usability();
        helper.assertTrue(verdict.reason().getContents() instanceof TranslatableContents text
            && text.getKey().equals("folkways.storage.unusable." + reason), "specific translated refusal: " + verdict);
        helper.assertTrue(StorePorts.candidate(helper.getLevel(), helper.absolutePos(pos)),
            "refused block remains an enrollment/frame candidate");
    }

    public static void observed(GameTestHelper helper, List<BlockPos> endpoints, int stores, long count) {
        var level = helper.getLevel();
        var colony = Colonies.mint(level.getServer());
        ResourceLocation owner = ResourceLocation.fromNamespaceAndPath("folkways_core", "native_network_test");
        List<WorldPos> offered = new ArrayList<>();
        for (BlockPos endpoint : endpoints) {
            offered.add(WorldPos.of(level, helper.absolutePos(endpoint)));
            PortChecks.at(helper, endpoint).proxyMembers().ifPresent(offered::addAll);
        }
        colony.stores(owner, level.dimension(), offered.stream().distinct().toList());
        LaborFixture labor = new LaborFixture(colony, level);
        AtomicBoolean closed = new AtomicBoolean();
        Cleanup.on(helper, () -> {
            closed.set(true);
            labor.close();
            colony.raze();
        });
        helper.onEachTick(() -> {
            if (!closed.get()) {
                colony.inventory().ensureObserved();
                labor.tick();
            }
        });
        helper.succeedWhen(() -> {
            var snapshot = colony.inventory().snapshot();
            helper.assertTrue(snapshot.coverage().complete() && snapshot.stores().size() == stores,
                "complete observation counts physical inventories once: " + snapshot.stores());
            helper.assertTrue(snapshot.stores().values().stream().flatMap(store -> store.entries().stream())
                .mapToLong(entry -> entry.count()).sum() == count, "access points do not duplicate stock");
        });
    }
}
