package io.github.izakyl.folkways.front.client;

import io.github.izakyl.folkways.front.engine.item.HeldBook;
import io.github.izakyl.folkways.front.engine.net.OpenBookPagePacket;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;

/** Client bridge for a tool opening its book page through the ordinary held-book authority check. */
public final class BookPages {
    private BookPages() { }

    public static void open(ResourceLocation page) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.getConnection() == null) return;
        HeldBook.bound(minecraft.player).ifPresent(book ->
            PacketDistributor.sendToServer(new OpenBookPagePacket(book.colonyId(), page)));
    }
}
