package io.github.izakyl.folkways.front.client;

import io.github.izakyl.folkways.front.engine.item.HeldBook;
import io.github.izakyl.folkways.front.ui.screen.Drawing;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
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
    Sender<D> sender,
    Function<ResourceLocation, Optional<Consumer<D>>> offer
) implements Drawing.Held {

    @Override
    public Optional<Component> measure() {
        return drawn.get().map(size);
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
    public void take(ResourceLocation id) {
        Player player = Minecraft.getInstance().player;
        Optional<D> shape = drawn.get();
        Optional<Consumer<D>> taker = offer.apply(id);
        if (player == null || shape.isEmpty() || taker.isEmpty()) {
            return;
        }
        dropped.run();
        player.closeContainer();
        taker.get().accept(shape.get());
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
