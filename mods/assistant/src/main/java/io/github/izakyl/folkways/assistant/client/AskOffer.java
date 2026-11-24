package io.github.izakyl.folkways.assistant.client;

import io.github.izakyl.folkways.assistant.AssistantMod;
import io.github.izakyl.folkways.core.front.api.Category;
import io.github.izakyl.folkways.core.front.api.Shape;
import io.github.izakyl.folkways.core.front.api.ui.BoxOffers;
import io.github.izakyl.folkways.core.front.client.BookTools;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * Offered over a marked box: the box goes to the assistant, as the next turn's marks, and the chat opens with the
 * player's sentence about what stands in it begun, for them to finish. Nothing is asked in between.
 */
final class AskOffer {

    static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(AssistantMod.ID, "ask");

    private static final String TOKEN = "folkways.assistant.ask";

    private AskOffer() {
    }

    static void register() {
        BoxOffers.register(new BoxOffers.Offer(ID, TOKEN, ResourceLocation.withDefaultNamespace("writable_book"),
                BoxOffers.acting(AskOffer::take)).in(Category.TAKING));
    }

    private static void take(BlockPos min, BlockPos max) {
        var level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        AssistantSession.current().attach(new BookTools.Marks("offer:" + ID, level.dimension(), level.getGameTime(),
                Shape.Gesture.BOX, List.of(min, max)));
        AssistantPanel.ask(Component.translatable(TOKEN + ".prefill").getString());
    }
}
