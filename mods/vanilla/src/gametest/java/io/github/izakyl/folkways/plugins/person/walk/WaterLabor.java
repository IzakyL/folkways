package io.github.izakyl.folkways.plugins.person.walk;

import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.colony.Held;
import io.github.izakyl.folkways.core.engine.labor.LaborFixture;
import io.github.izakyl.folkways.plugins.person.PersonContent;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import net.minecraft.gametest.framework.GameTestHelper;

final class WaterLabor {
    private WaterLabor() {
    }

    static void enroll(GameTestHelper helper, ResidentEntity body) {
        var colony = Colonies.mint(helper.getLevel().getServer());
        colony.hold(PersonContent.ID, Held.Entity.of(body)).orElseThrow();
        body.joinColony(colony.id());
        colony.entered(body);
        LaborFixture labor = new LaborFixture(colony, helper.getLevel());
        boolean[] closed = {false};
        helper.onEachTick(() -> {
            if (closed[0]) {
                return;
            }
            if (body.isRemoved()) {
                closed[0] = true;
                labor.close();
                colony.raze();
            } else {
                labor.tick();
            }
        });
    }

    static boolean escaping(ResidentEntity body) {
        return body.doing().filter(doing -> doing.toward().orElse(doing.what()).equals(BreathingPresence.ID))
            .isPresent();
    }
}
