package io.github.izakyl.folkways.front.client;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import io.github.izakyl.folkways.front.engine.item.HeldBook;
import io.github.izakyl.folkways.front.ui.screen.Drawing;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.PacketDistributor;

record Draft<D>(
    Supplier<Optional<D>> drawn,
    Runnable dropped,
    Function<D, Component> size,
    ToIntFunction<D> counted,
    Sender<D> sender,
    Function<ResourceLocation, Optional<BiFunction<D, Runnable, UIElement>>> offer
) implements Drawing.Held {

    @Override
    public Optional<Component> measure() {
        return drawn.get().map(size);
    }

    @Override
    public int extent() {
        return drawn.get().map(counted::applyAsInt).orElse(0);
    }

    @Override
    public void drop() {
        dropped.run();
    }

    @Override
    public void hand(ResourceLocation delegation, CompoundTag settings) {
        Player player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        drawn.get().ifPresent(shape -> HeldBook.bound(player).ifPresent(book -> {
            PacketDistributor.sendToServer(sender.packet(book.colonyId(), shape, delegation, settings));
            dropped.run();
        }));
    }

    @Override
    public Optional<UIElement> offered(ResourceLocation id) {
        Player player = Minecraft.getInstance().player;
        if (player == null) {
            return Optional.empty();
        }
        // The shape stays drawn until the offer has been taken, so backing out of it loses nothing.
        return drawn.get().flatMap(shape -> offer.apply(id).map(body -> body.apply(shape, () -> {
            dropped.run();
            player.closeContainer();
        })));
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
