package io.github.izakyl.folkways.vanilla.carts;

import com.jusipat.astikorcartsredux.entity.AbstractCargoCart;
import io.github.izakyl.folkways.core.front.client.ColonyHighlightState;
import io.github.izakyl.folkways.core.front.client.LookLineCards;
import io.github.izakyl.folkways.core.front.client.ShadowPass;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

final class CartsCardRenderer {
    private static List<Integer> shown = List.of();

    private CartsCardRenderer() {
    }

    static List<Integer> shown() {
        return shown;
    }

    static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS || ShadowPass.active()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        List<Integer> cards = new ArrayList<>();
        if (minecraft.level != null && minecraft.player != null && ColonyHighlightState.shownColonyId(minecraft.player) != null) {
            for (var entity : minecraft.level.entitiesForRendering()) {
                if (entity instanceof AbstractCargoCart cart && CartLinks.admissible(cart)) {
                    if (LookLineCards.queue(event, cart, CartsCards.lines(cart))) {
                        cards.add(cart.getId());
                    }
                }
            }
        }
        shown = List.copyOf(cards);
    }
}
