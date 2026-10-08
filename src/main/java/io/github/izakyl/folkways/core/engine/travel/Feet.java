package io.github.izakyl.folkways.core.engine.travel;

import io.github.izakyl.folkways.core.api.terms.Realm;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;

public final class Feet {

    private Feet() {
    }

    public static WorldPos of(Entity body) {
        WorldPos at = WorldSpaces.at(body);
        if (!body.onGround()) {
            return at;
        }
        if (at.realm() instanceof Realm.Dimension) {
            return WorldSpaces.at(body.level(), BlockPos.containing(body.getX(), body.getY() + 0.5D, body.getZ()));
        }
        return WorldSpaces.relative(body.level(), at.realm(), body.position())
            .map(local -> new WorldPos(at.realm(), BlockPos.containing(local.x, local.y + 0.5D, local.z)))
            .orElse(at);
    }
}
