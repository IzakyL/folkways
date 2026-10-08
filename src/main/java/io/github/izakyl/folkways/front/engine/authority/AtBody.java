package io.github.izakyl.folkways.front.engine.authority;

import io.github.izakyl.folkways.core.api.resident.body.Bodies;
import io.github.izakyl.folkways.core.api.resident.body.Body;
import java.util.Optional;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

public final class AtBody {

    private static final double AT_BODY_SQR = 64.0D;

    private final Body body;

    private AtBody(Body body) {
        this.body = body;
    }

    public static Optional<AtBody> of(ServerPlayer player, int entityId) {
        Entity found = player.serverLevel().getEntity(entityId);
        if (found == null || player.distanceToSqr(found) > AT_BODY_SQR) {
            return Optional.empty();
        }
        return Bodies.of(found).map(AtBody::new);
    }

    public Body body() {
        return body;
    }
}
