package io.github.izakyl.folkways.plugins.build;

import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import io.github.izakyl.folkways.core.api.colony.Books;
import io.github.izakyl.folkways.front.api.ui.BoxOffers;
import io.github.izakyl.folkways.front.api.ui.Rows;
import io.github.izakyl.folkways.front.api.ui.Tokens;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.network.PacketDistributor;

@OnlyIn(Dist.CLIENT)
public final class BlueprintCaptureOffer {
    private BlueprintCaptureOffer() {
    }

    public static void register(FMLClientSetupEvent event) {
        event.enqueueWork(() -> BoxOffers.register(new BoxOffers.Offer(
            ResourceLocation.fromNamespaceAndPath("folkways", "capture_blueprint"), "folkways.blueprints.capture",
            ResourceLocation.withDefaultNamespace("map"), (from, to, taking) -> {
                TextField name = new TextField();
                name.layout(layout -> layout.flexGrow(1).flexShrink(1).minWidth(0));
                name.setTextRegexValidator("[a-z0-9_./-]{0,128}");
                name.setValue("blueprint", false);
                Tokens.name(name, "folkways.blueprints.capture.name");
                Button save = new Button().setText(Component.translatable("folkways.blueprints.capture"))
                    .setOnClick(click -> {
                        var player = Minecraft.getInstance().player;
                        if (player != null) Books.heldColony(player).ifPresent(colony -> {
                            PacketDistributor.sendToServer(new CaptureBlueprintPacket(colony, from, to, name.getValue()));
                            taking.done();
                        });
                    });
                Tokens.name(save, "folkways.blueprints.capture.confirm");
                return Rows.page().addChildren(Rows.text(Component.translatable("folkways.blueprints.capture.hint")),
                    Rows.row().addChildren(Rows.name(Component.translatable("folkways.blueprints.capture.name")), name),
                    Rows.actions(save));
            })));
    }
}
