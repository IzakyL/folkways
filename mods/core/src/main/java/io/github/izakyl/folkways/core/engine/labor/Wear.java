package io.github.izakyl.folkways.core.engine.labor;

import io.github.izakyl.folkways.core.api.resident.ResidentKinds;
import io.github.izakyl.folkways.core.api.resident.body.Body;
import io.github.izakyl.folkways.core.api.resident.body.Pack;
import io.github.izakyl.folkways.core.api.vocation.Vocation;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.ToolUse;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.effect.Stats;
import java.util.function.Consumer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;

final class Wear {

    private Wear() {
    }

    static void from(ServerLevel level, Body body, NodeSpec spec, Worker who,
            Consumer<String> broke) {
        if (ResidentKinds.barehanded(body.kind().id(), spec.vocation())) {
            return;
        }
        Pack pack = body.pack();
        for (ToolUse use : spec.tools()) {
            int points = points(use.wearFor(who) * who.effects().value(Stats.TOOL_WEAR,
                spec.vocation().map(Vocation::id)), level.getRandom());
            if (points <= 0) {
                continue;
            }
            ItemStack stack = who.held(use.need());
            if (!stack.isEmpty() && stack.isDamageableItem()) {
                stack.hurtAndBreak(points, level, body.mob(),
                    gone -> broke.accept(BuiltInRegistries.ITEM.getKey(gone).toString()));
                pack.setChanged();
            }
        }
    }

    private static int points(double scaled, RandomSource random) {
        if (scaled <= 0.0) {
            return 0;
        }
        int whole = (int) Math.floor(scaled);
        double part = scaled - whole;
        if (part > 0.0 && random.nextDouble() < part) {
            whole++;
        }
        return Math.max(0, whole);
    }
}
