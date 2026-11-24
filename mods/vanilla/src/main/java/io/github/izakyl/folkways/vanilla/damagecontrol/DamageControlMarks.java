package io.github.izakyl.folkways.vanilla.damagecontrol;

import io.github.izakyl.folkways.core.front.api.ui.OverheadTools;
import io.github.izakyl.folkways.core.front.engine.item.HeldBook;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * What damage control has found in the colony's protected areas, marked in the world while the book is bound: fires,
 * missing and changed blocks, and the workers answering them. The areas themselves are ordinary zones, drawn with
 * the book and set up on their cards.
 */
@OnlyIn(Dist.CLIENT)
public final class DamageControlMarks implements OverheadTools.Marks {
    private static final DamageControlMarks MARKS = new DamageControlMarks();
    private static final int POLL_TICKS = 20;
    private static final long STALE_MS = 3000L;

    private DamageControlState state;
    private UUID colony;
    private int cooldown;
    private long receivedAt;

    private DamageControlMarks() {
    }

    public static void install(FMLClientSetupEvent event) {
        event.enqueueWork(() -> OverheadTools.registerMarks(MARKS));
    }

    public static void heard(DamageControlState packet) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null
            || !minecraft.level.dimension().location().equals(packet.dimension())
            || HeldBook.bound(minecraft.player).map(HeldBook::colonyId).filter(packet.colony()::equals).isEmpty()) {
            return;
        }
        MARKS.state = packet;
        MARKS.receivedAt = Util.getMillis();
    }

    @Override
    public void tick(Optional<UUID> bound) {
        if (bound.isEmpty()) {
            reset();
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (!bound.get().equals(colony) || (state != null && minecraft.level != null
            && !state.dimension().equals(minecraft.level.dimension().location()))) {
            reset();
            colony = bound.get();
        }
        if (state != null && Util.getMillis() - receivedAt > STALE_MS) {
            state = null;
        }
        if (cooldown-- <= 0) {
            PacketDistributor.sendToServer(new DamageControlCommand(colony));
            cooldown = POLL_TICKS;
        }
    }

    @Override
    public void reset() {
        state = null;
        colony = null;
        cooldown = 0;
    }

    @Override
    public List<OverheadTools.Mark> marks() {
        if (state == null) {
            return List.of();
        }
        List<OverheadTools.Mark> marks = new ArrayList<>();
        for (var mark : state.marks()) {
            marks.add(new OverheadTools.Mark(mark.area(), mark.at(), mark.at(), mark.color(), mark.lines()));
        }
        return List.copyOf(marks);
    }
}
