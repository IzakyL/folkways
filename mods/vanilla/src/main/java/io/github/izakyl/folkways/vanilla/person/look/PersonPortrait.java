package io.github.izakyl.folkways.vanilla.person.look;

import io.github.izakyl.folkways.core.combat.api.Portraits;
import io.github.izakyl.folkways.vanilla.person.ResidentEntity;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

/** A person's command card shows the face of its skin, as a player's would. */
@OnlyIn(Dist.CLIENT)
public final class PersonPortrait {
    private PersonPortrait() {
    }

    public static void install(FMLClientSetupEvent event) {
        Portraits.register((graphics, resident, x, y, size) -> {
            if (!(resident instanceof ResidentEntity person)) {
                return false;
            }
            PlayerFaceRenderer.draw(graphics, ResidentLooks.of(person).appearance().skin().texture(), x, y, size);
            return true;
        });
    }
}
