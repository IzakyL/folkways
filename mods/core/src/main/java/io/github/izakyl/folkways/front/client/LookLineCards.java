package io.github.izakyl.folkways.front.client;

import io.github.izakyl.folkways.front.api.ui.OverheadTools;
import io.github.izakyl.folkways.front.api.notice.Line;
import io.github.izakyl.folkways.front.client.ColonyLookCard.Row;
import io.github.izakyl.folkways.front.engine.net.LookLines;
import java.util.List;
import net.minecraft.client.Minecraft;
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
        if (looks.isEmpty() || !Layer.RESIDENTS.cards() || minecraft.level == null || minecraft.player == null) {
            return;
        }

        boolean overhead = Overhead.on();
        OverheadTools.Handler handler = overhead ? OverheadTools.selectedHandler().orElse(null) : null;
        for (LookLines look : looks) {
            Entity resident = minecraft.level.getEntity(look.entityId());
            if (resident == null || overhead && handler != null && !handler.showResidentCard(resident.getUUID())) {
                continue;
            }
            queue(event, resident, look.lines());
        }
    }

    /** Queues an extension's entity card before the normal world-card flush, with the book's layer and range. */
    public static boolean queue(RenderLevelStageEvent event, Entity entity, List<Line> lines) {
        Minecraft minecraft = Minecraft.getInstance();
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS || ShadowPass.active()
                || minecraft.player == null || ColonyHighlightState.heldColonyId(minecraft.player) == null
                || !Layer.RESIDENTS.cards() || !event.getFrustum().isVisible(entity.getBoundingBox())) {
            return false;
        }
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Vec3 camera = event.getCamera().getPosition();
        Vec3 anchor = entity.getPosition(partialTick).add(0.0D, entity.getBbHeight() + HEAD_ROOM, 0.0D);
        List<Row> rows = ColonyLookCard.rows(lines);
        if (anchor.distanceToSqr(camera) > RANGE * RANGE || rows.isEmpty()) {
            return false;
        }
        WorldCards.queue(anchor, rows, SCALE, WorldCards.Look.PLATE, camera);
        return true;
    }
}
