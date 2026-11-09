package io.github.izakyl.folkways.core.combat.api;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.entity.LivingEntity;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Client-only: the small square face command cards draw for a resident of any kind. */
public final class Portraits {
    /** Draws the resident if it knows how; false passes it to the next portrait. */
    @FunctionalInterface
    public interface Portrait {
        boolean draw(GuiGraphics graphics, LivingEntity resident, int x, int y, int size);
    }

    private static final List<Portrait> PORTRAITS = new CopyOnWriteArrayList<>();

    private Portraits() {
    }

    public static void register(Portrait portrait) {
        PORTRAITS.add(portrait);
    }

    public static void draw(GuiGraphics graphics, LivingEntity resident, int x, int y, int size) {
        for (Portrait portrait : PORTRAITS) {
            if (portrait.draw(graphics, resident, x, y, size)) {
                return;
            }
        }
        model(graphics, resident, x, y, size);
    }

    // Any kind without a face of its own: its live model's upper body, turned to the viewer for this one draw.
    private static void model(GuiGraphics graphics, LivingEntity resident, int x, int y, int size) {
        float body = resident.yBodyRot, bodyO = resident.yBodyRotO, yaw = resident.getYRot(), yawO = resident.yRotO;
        float head = resident.yHeadRot, headO = resident.yHeadRotO, pitch = resident.getXRot(), pitchO = resident.xRotO;
        resident.yBodyRot = resident.yBodyRotO = 180f;
        resident.setYRot(180f);
        resident.yRotO = resident.yHeadRot = resident.yHeadRotO = 180f;
        resident.setXRot(0f);
        resident.xRotO = 0f;
        try {
            float shown = Math.max(resident.getBbWidth(), resident.getBbHeight() / 2f);
            graphics.enableScissor(x, y, x + size, y + size);
            InventoryScreen.renderEntityInInventory(graphics, x + size / 2f, y + size / 2f, size / Math.max(.1f, shown),
                new Vector3f(0f, resident.getBbHeight() - shown / 2f, 0f),
                new Quaternionf().rotateZ((float) Math.PI), null, resident);
            graphics.disableScissor();
        } finally {
            resident.yBodyRot = body;
            resident.yBodyRotO = bodyO;
            resident.setYRot(yaw);
            resident.yRotO = yawO;
            resident.yHeadRot = head;
            resident.yHeadRotO = headO;
            resident.setXRot(pitch);
            resident.xRotO = pitchO;
        }
    }
}
