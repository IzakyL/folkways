package io.github.izakyl.folkways.plugins.person.walk;

import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.Held;
import io.github.izakyl.folkways.core.api.terms.RefusalKind;
import io.github.izakyl.folkways.core.engine.labor.LaborFixture;
import io.github.izakyl.folkways.core.testing.Cleanup;
import io.github.izakyl.folkways.plugins.person.PersonContent;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

final class WaterLabor {
    static final RefusalKind LOST = () -> "folkways.refusal.labor.no_way_there";

    private WaterLabor() {
    }

    record Enlisted(Colony colony, LaborFixture labor) {
    }

    // A colony of one whose labour runs each tick until the body is removed or the test ends.
    static Enlisted enlist(GameTestHelper helper, ResidentEntity body) {
        var colony = Colonies.mint(helper.getLevel().getServer());
        colony.hold(PersonContent.ID, Held.Entity.of(body)).orElseThrow();
        body.joinColony(colony.id());
        colony.entered(body);
        LaborFixture labor = new LaborFixture(colony, helper.getLevel());
        boolean[] closed = {false};
        Runnable close = () -> {
            if (!closed[0]) {
                closed[0] = true;
                labor.close();
                colony.raze();
            }
        };
        helper.onEachTick(() -> {
            if (closed[0]) {
                return;
            }
            if (body.isRemoved()) {
                close.run();
            } else {
                labor.tick();
            }
        });
        Cleanup.on(helper, close);
        return new Enlisted(colony, labor);
    }

    // A one-wide, two-high tunnel along x, flooded from x=2 through lastWater, with dry ends.
    static void tunnel(ServerLevel level, BlockPos base, int lastWater) {
        for (int x = 0; x <= lastWater + 2; x++) {
            for (int z = 0; z <= 2; z++) {
                level.setBlock(base.offset(x, 0, z), Blocks.STONE.defaultBlockState(), 2);
                for (int y = 1; y <= 3; y++) {
                    boolean wall = z != 1 || y == 3;
                    boolean flooded = x >= 2 && x <= lastWater;
                    level.setBlock(base.offset(x, y, z), (wall ? Blocks.STONE
                        : flooded ? Blocks.WATER : Blocks.AIR).defaultBlockState(), 2);
                }
            }
        }
    }

    static boolean escaping(ResidentEntity body) {
        return body.doing().filter(doing -> doing.toward().orElse(doing.what()).equals(BreathingPresence.ID))
            .isPresent();
    }
}
