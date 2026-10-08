package io.github.izakyl.folkways.core.api.resident.body;

import io.github.izakyl.folkways.core.api.perk.Perks;
import io.github.izakyl.folkways.core.api.resident.ResidentKind;
import io.github.izakyl.folkways.core.api.resident.ResidentKinds;
import io.github.izakyl.folkways.core.api.vocation.Vocation;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;

public final class Bodies {

    private static final Map<ResidentKind, Perks> BUILT = new ConcurrentHashMap<>();

    private Bodies() {
    }

    public static Optional<Body> of(Entity entity) {
        return ResidentKinds.of(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()))
            .flatMap(kind -> kind.read(entity));
    }

    public static void credit(Body body, Vocation vocation, int amount, RandomSource random) {
        if (!(body.kind().growth() instanceof ResidentKind.Growth.Climbs)) {
            return;
        }
        body.mob().getData(FolkwaysAttachments.BODY_STATE)
            .credit(vocation.id(), amount, random);
        body.refreshPerkEffects();
    }

    static Perks perksOf(ResidentKind kind, BodyState state) {
        if (kind.growth() instanceof ResidentKind.Growth.Built(Map<String, Integer> ranks)) {
            return BUILT.computeIfAbsent(kind, sort -> Perks.fixed(ranks));
        }
        return state.perks();
    }
}
