package io.github.izakyl.folkways.plugins.golem.domain;

import dev.xkmc.modulargolems.content.config.GolemMaterial;
import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import io.github.izakyl.folkways.core.api.colony.ColonyView;
import io.github.izakyl.folkways.core.api.work.Urge;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.front.api.Facing;
import io.github.izakyl.folkways.front.api.notice.Line;
import io.github.izakyl.folkways.front.api.notice.Notice;
import io.github.izakyl.folkways.front.api.notice.Sentence;
import java.util.List;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;

final class GolemPresence implements Facing {

    static final ResourceLocation MEND = ResourceLocation.fromNamespaceAndPath("folkways", "mend");

    private static final double MEND_BELOW = 0.6;

    public List<Urge> urges(Worker who, ColonyView colony) {
        Mob body = who.body();
        if (!(body instanceof AbstractGolemEntity<?, ?> golem)
            || golem.getHealth() >= golem.getMaxHealth() * MEND_BELOW
            || !carriesMetal(who.pack())) {
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

    private static boolean carriesMetal(Container pack) {
        for (int slot = 0; slot < pack.getContainerSize(); slot++) {
            ItemStack stack = pack.getItem(slot);
            if (!stack.isEmpty() && GolemMaterial.getRepairMaterial(stack).isPresent()) {
                return true;
            }
        }
        return false;
    }
}
