package io.github.izakyl.folkways.front.api.notice;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

// A vital a card shows as an icon beside a bar: the icon is a GUI sprite, the bar is drawn in the colour.
public record Meter(ResourceLocation icon, int color) {

    // Every body has health, whoever supplies it.
    public static final Meter HEALTH =
        new Meter(ResourceLocation.withDefaultNamespace("hud/heart/full"), 0xFFE0383E);

    void encode(FriendlyByteBuf buffer) {
        buffer.writeResourceLocation(icon);
        buffer.writeInt(color);
    }

    static Meter decode(FriendlyByteBuf buffer) {
        return new Meter(buffer.readResourceLocation(), buffer.readInt());
    }
}
