package io.github.izakyl.folkways.core.command.client;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;
import io.github.izakyl.folkways.core.combat.api.Portraits;
import java.util.UUID;
import io.github.izakyl.folkways.core.command.combat.net.ClientUnits;
import io.github.izakyl.folkways.core.command.combat.net.UnitSnapshot;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** A resident's live portrait or a vehicle's icon inside a unit window; an empty frame while out of sight. */
final class UnitFace extends UIElement {
    private static final int FRAME = 0xFFC4AD7E;
    private static final int BED = 0xFFEADCB6;
    private static final int WAITING = 0x889A3324;
    private final UUID resident;
    private final int size;
    private final boolean waiting;

    UnitFace(UUID resident, int size, boolean waiting) {
        this.resident = resident;
        this.size = size;
        this.waiting = waiting;
        layout(layout -> layout.width(size).height(size).flexShrink(0));
    }

    @Override
    public void drawBackgroundAdditional(GUIContext context) {
        super.drawBackgroundAdditional(context);
        int x = Math.round(getPositionX()), y = Math.round(getPositionY());
        var graphics = context.graphics;
        graphics.fill(x, y, x + size, y + size, FRAME);
        graphics.fill(x + 1, y + 1, x + size - 1, y + size - 1, BED);
        Entity seen = resident == null ? null : CombatController.seen(resident);
        if (seen instanceof LivingEntity body && ClientUnits.unit(resident)
            .filter(unit -> unit.kind() == UnitSnapshot.Kind.VEHICLE).isEmpty()) {
            graphics.flush();
            Portraits.draw(graphics, body, x + 1, y + 1, size - 2);
            graphics.flush();
        } else if (seen != null) {
            // A vehicle wears its mod's own icon where the pack has one, else the item it is picked as.
            graphics.flush();
            var picture = CombatCards.picture(BuiltInRegistries.ENTITY_TYPE.getKey(seen.getType()));
            if (picture.isPresent()) {
                graphics.blit(picture.get(), x + 1, y + 1, 0, 0, size - 2, size - 2, size - 2, size - 2);
            } else {
                ItemStack icon = seen.getPickResult();
                if (icon == null || icon.isEmpty()) {
                    icon = new ItemStack(Items.MINECART);
                }
                float scale = (size - 4) / 16F;
                graphics.pose().pushPose();
                graphics.pose().translate(x + 2, y + 2, 0);
                graphics.pose().scale(scale, scale, 1);
                graphics.renderItem(icon, 0, 0);
                graphics.pose().popPose();
            }
            graphics.flush();
        }
        if (waiting) {
            // Reserved, not yet seated: a red wash with a walking dot row along the foot.
            graphics.fill(x + 1, y + 1, x + size - 1, y + size - 1, WAITING);
            long phase = (System.currentTimeMillis() / 250) % 3;
            for (int dot = 0; dot < 3; dot++) {
                int left = x + size / 2 - 5 + dot * 4;
                graphics.fill(left, y + size - 4, left + 2, y + size - 2, dot == phase ? 0xFFFFF4D6 : 0xFF9A3324);
            }
        }
    }
}
