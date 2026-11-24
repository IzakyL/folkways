package io.github.izakyl.folkways.core.front.client;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import io.github.izakyl.folkways.core.front.api.Ghost;
import io.github.izakyl.folkways.core.front.api.ui.LonePanel;
import io.github.izakyl.folkways.core.front.api.ui.Taking;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.PacketDistributor;

/** A shape drawn with the book, a box or a line, and what it can be handed to once it is whole. */
record Draft<D>(
    Supplier<Optional<D>> drawn,
    Runnable dropped,
    Function<D, Component> size,
    ToIntFunction<D> counted,
    Sender<D> sender,
    Function<ResourceLocation, Optional<BiFunction<D, Taking, UIElement>>> offer,
    Function<ResourceLocation, Optional<Consumer<D>>> act
) {
    private static final String OFFER_PANEL = "folkways_offer";
    private static final float OFFER_WIDTH = 300f;
    private static final float OFFER_HEIGHT = 240f;

    boolean ready() {
        return drawn.get().isPresent();
    }

    Optional<Component> measure() {
        return drawn.get().map(size);
    }

    /** How much was drawn, in what a shape's limit counts: the cells of a box, the points of a line. */
    int extent() {
        return drawn.get().map(counted::applyAsInt).orElse(0);
    }

    void drop() {
        ShapePreview.clear();
        dropped.run();
    }

    /** Sends the shape to become a holding of the delegation, at its defaults; its card opens once it stands. */
    void send(UUID colony, ResourceLocation delegation) {
        drawn.get().ifPresent(shape -> {
            PacketDistributor.sendToServer(sender.packet(colony, shape, delegation, new CompoundTag()));
            drop();
        });
    }

    /**
     * Opens what an offer asks before it takes the shape, in a panel of its own, or hands the shape straight to an
     * offer that asks nothing; false if it cannot be offered.
     */
    boolean offer(ResourceLocation id, Component title) {
        Optional<D> shape = drawn.get();
        Optional<Consumer<D>> now = act.apply(id);
        if (shape.isPresent() && now.isPresent()) {
            now.get().accept(shape.get());
            return true;
        }
        Optional<BiFunction<D, Taking, UIElement>> body = offer.apply(id);
        if (shape.isEmpty() || body.isEmpty()) {
            return false;
        }
        // The shape stays drawn until the offer has been taken, so backing out of it, or off to look at a
        // preview, loses nothing.
        LonePanel.open(OFFER_PANEL, title, OFFER_WIDTH, OFFER_HEIGHT, pane -> body.get().apply(shape.get(),
            new Taking() {
                @Override
                public void done() {
                    drop();
                    back();
                }

                @Override
                public void preview(List<Ghost> ghosts) {
                    ShapePreview.show(id, shape.get(), drawn, ghosts);
                    back();
                    say(Component.translatable("folkways.draft.previewing"));
                }

                @Override
                public boolean previewed() {
                    return ShapePreview.of(id, shape.get());
                }
            }));
        return true;
    }

    // Back to whatever the panel was opened over: the world, or the view from above.
    private static void back() {
        Screen open = Minecraft.getInstance().screen;
        if (open != null && !(open instanceof OverheadScreen)) {
            open.onClose();
        }
    }

    static void say(Component message) {
        Player player = Minecraft.getInstance().player;
        if (player != null) {
            player.displayClientMessage(message, true);
        }
    }

    @FunctionalInterface
    interface Sender<D> {

        CustomPacketPayload packet(UUID colony, D drawn, ResourceLocation delegation, CompoundTag settings);
    }
}
