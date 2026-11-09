package io.github.izakyl.folkways.epicfight;

import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.Stances;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;

/** Test-only access to the upstream node without changing visibility or splitting its module package. */
public final class EpicFightNodeFixture {
    private EpicFightNodeFixture() { }
    public static Node strike(ServerLevel level, Mob target) {
        try {
            var constructor = Class.forName("io.github.izakyl.folkways.vanilla.patrol.StrikeNode")
                .getDeclaredConstructor(ServerLevel.class, Mob.class, Stances.class);
            constructor.setAccessible(true);
            return (Node) constructor.newInstance(level, target, Stances.WHEREVER);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("cannot construct actual upstream StrikeNode fixture", failure);
        }
    }
}
