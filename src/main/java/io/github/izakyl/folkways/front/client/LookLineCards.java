package io.github.izakyl.folkways.front.client;

import io.github.izakyl.folkways.front.client.ColonyLookCard.Row;
import io.github.izakyl.folkways.front.engine.net.LookLines;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

public final class LookLineCards {
    private static final double RANGE = 32.0D;
    private static final float SCALE = 0.025F;
    private static final double HEAD_ROOM = 0.5D;

    private LookLineCards() {
    }

    /** Queues a card over each resident in range; {@link WorldCards#flush} draws them with the zone cards. */
    public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        List<LookLines> looks = ColonyLookState.residents();
        Minecraft minecraft = Minecraft.getInstance();
        if (looks.isEmpty() || minecraft.level == null || minecraft.player == null) {
            return;
        }

        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Vec3 camera = event.getCamera().getPosition();
        Frustum frustum = event.getFrustum();
        for (LookLines look : looks) {
            Entity resident = minecraft.level.getEntity(look.entityId());
            if (resident == null || !frustum.isVisible(resident.getBoundingBox())) {
                continue;
            }
            Vec3 anchor = resident.getPosition(partialTick)
                .add(0.0D, resident.getBbHeight() + HEAD_ROOM, 0.0D);
            if (anchor.distanceToSqr(camera) > RANGE * RANGE) {
                continue;
            }
            List<Row> rows = ColonyLookCard.rows(look.lines());
            if (!rows.isEmpty()) {
                WorldCards.queue(anchor, rows, SCALE, WorldCards.Look.PLATE, camera);
            }
        }
    }
}
