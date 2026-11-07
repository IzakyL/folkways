package io.github.izakyl.folkways.modulargolems.domain;

import dev.xkmc.modulargolems.content.config.GolemMaterial;
import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.colony.api.ColonyView;
import io.github.izakyl.folkways.core.inventory.api.InventorySnapshot;
import io.github.izakyl.folkways.core.work.api.Urge;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.front.api.Facing;
import io.github.izakyl.folkways.front.api.notice.Line;
import io.github.izakyl.folkways.front.api.notice.Notice;
import io.github.izakyl.folkways.front.api.notice.Sentence;
import java.util.List;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;

final class GolemPresence implements Facing {

    static final ResourceLocation MEND = ResourceLocation.fromNamespaceAndPath("folkways", "mend");

    private static final double MEND_BELOW = 0.6;

    private final Colony colony;

    GolemPresence(Colony colony) {
        this.colony = colony;
    }

    public List<Urge> urges(Worker who, ColonyView colony) {
        Mob body = who.body();
        if (!(body instanceof AbstractGolemEntity<?, ?> golem)
            || golem.getHealth() >= golem.getMaxHealth() * MEND_BELOW
            || !carriesMetal(who.resident().id())) {
            return List.of();
        }
        ServerLevel level = colony.level();
        return List.of(new Urge(MEND, Urge.SPARE, false,
            () -> new MendAction(level, who.resident().id(), body.blockPosition())));
    }

    @Override
    public List<Line> lookLines(UUID resident, ColonyView colony) {
        Entity found = colony.level().getEntity(resident);
        if (!(found instanceof AbstractGolemEntity<?, ?> golem) || golem.getReforgeCount() <= 0) {
            return List.of();
        }
        return List.of(Line.told(Sentence.of(Sentence.doing(GolemContent.MENDING),
            Sentence.word(Notice.count(golem.getReforgeCount())))));
    }

    private boolean carriesMetal(UUID resident) {
        InventorySnapshot.Resident observed = colony.inventory().snapshot().residents().get(resident);
        if (observed == null || observed.state() != InventorySnapshot.State.OBSERVED) {
            colony.inventory().ensureObservedResident(resident);
            return false;
        }
        return observed.pack().stream().map(slot -> slot.stackCopy())
                .anyMatch(stack -> !stack.isEmpty() && GolemMaterial.getRepairMaterial(stack).isPresent());
    }
}
