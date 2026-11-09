package io.github.izakyl.folkways.vanilla.groundwork;

import io.github.izakyl.folkways.core.colony.api.Claimed;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.resident.api.Bodies;
import io.github.izakyl.folkways.core.resident.api.Body;
import io.github.izakyl.folkways.core.runtime.LaborFixture;
import io.github.izakyl.folkways.core.testing.Cleanup;
import io.github.izakyl.folkways.vanilla.person.PersonContent;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ChunkPos;

/** GameTestServer suppresses Labor.work, so this drives a zone's usual colony pipeline with inline planning lanes. */
final class ColonyStorageRun {
    private ColonyStorageRun() {
    }

    // Enrolls the person beside the work at {@code at}, then ticks groundwork and labor until {@code stored} holds.
    static void until(GameTestHelper helper, Colony colony, Mob person, BlockPos at, Runnable release, Runnable stored) {
        ServerLevel level = helper.getLevel();
        person.moveTo(at.getX() + 2.5, at.getY(), at.getZ() + 1.5);
        level.addFreshEntity(person);
        colony.hold(PersonContent.ID, Claimed.Entity.of(person)).orElseThrow();
        Body body = Bodies.of(person).orElseThrow();
        body.joinColony(colony.id());
        colony.entered(body);
        var labor = new LaborFixture(colony, level);
        Set<Long> forced = new HashSet<>();
        for (BlockPos corner : List.of(at.offset(-3, 0, -3), at.offset(4, 0, -3), at.offset(-3, 0, 3), at.offset(4, 0, 3))) {
            ChunkPos chunk = new ChunkPos(corner);
            if (!level.getForcedChunks().contains(chunk.toLong()) && level.setChunkForced(chunk.x, chunk.z, true)) {
                forced.add(chunk.toLong());
            }
        }
        boolean[] closed = {false};
        Cleanup.on(helper, () -> {
            closed[0] = true;
            labor.close();
            release.run();
            forced.forEach(chunk -> level.setChunkForced(ChunkPos.getX(chunk), ChunkPos.getZ(chunk), false));
        });
        helper.onEachTick(() -> {
            if (closed[0]) {
                return;
            }
            colony.service(GroundworkContent.ID, GroundPresence.class).orElseThrow().tick(level.getServer());
            labor.tick();
        });
        helper.startSequence().thenWaitUntil(stored).thenSucceed();
    }
}
