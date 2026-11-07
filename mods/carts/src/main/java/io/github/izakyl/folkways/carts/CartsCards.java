package io.github.izakyl.folkways.carts;

import com.jusipat.astikorcartsredux.entity.AbstractCargoCart;
import io.github.izakyl.folkways.front.client.ColonyHighlightState;
import io.github.izakyl.folkways.front.client.LookLineCards;
import io.github.izakyl.folkways.front.client.ShadowPass;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/** The book shows a cart's synchronized stock even when no resident walks alongside it. */
@EventBusSubscriber(modid = FolkwaysCartsMod.ID, value = Dist.CLIENT)
public final class CartsCards {
    private static List<Integer> shown = List.of();

    private CartsCards() {
    }

    /** Entity ids queued by the last world frame, for read-only capture inspection. */
    public static List<Integer> shown() {
        return shown;
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS || ShadowPass.active()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        List<Integer> cards = new ArrayList<>();
        if (minecraft.level != null && minecraft.player != null && ColonyHighlightState.shownColonyId(minecraft.player) != null) {
            for (var entity : minecraft.level.entitiesForRendering()) {
                if (entity instanceof AbstractCargoCart cart && CartLinks.admissible(cart)) {
                    if (LookLineCards.queue(event, cart, CartContents.lines(cart))) {
                        cards.add(cart.getId());
                    }
                }
            }
        }
        shown = List.copyOf(cards);
    }
}
