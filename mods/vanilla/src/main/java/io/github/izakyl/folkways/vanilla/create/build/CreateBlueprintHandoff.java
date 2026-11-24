package io.github.izakyl.folkways.vanilla.create.build;

import io.github.izakyl.folkways.core.front.api.Books;
import io.github.izakyl.folkways.core.shell.create.build.CreateSchematicDraft;
import io.github.izakyl.folkways.vanilla.build.BlueprintHandoffPanel;
import io.github.izakyl.folkways.vanilla.build.BlueprintUploads;
import io.github.izakyl.folkways.vanilla.build.UploadBlueprintPacket;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.PacketDistributor;

public final class CreateBlueprintHandoff {
    private CreateBlueprintHandoff() {
    }

    public static void handOff(boolean excavate, String siteName, boolean raise) {
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        if (player == null) {
            return;
        }
        Optional<UUID> book = Books.heldColony(player);
        if (book.isEmpty()) {
            say(player, BlueprintHandoffPanel.TOKEN + ".no_book");
            return;
        }
        var draft = CreateSchematicDraft.read(BlueprintUploads::fits);
        if (draft.isEmpty()) {
            say(player, BlueprintHandoffPanel.TOKEN + ".unreadable");
            return;
        }
        var schematic = draft.get();
        BlueprintUploads.send(new UploadBlueprintPacket(book.get(), schematic.name(), siteName, schematic.bytes(),
            schematic.anchor(), excavate, schematic.settings().getRotation(), schematic.settings().getMirror(), raise),
            PacketDistributor::sendToServer);
    }

    private static void say(Player player, String key) {
        player.displayClientMessage(Component.translatable(key), true);
    }
}
