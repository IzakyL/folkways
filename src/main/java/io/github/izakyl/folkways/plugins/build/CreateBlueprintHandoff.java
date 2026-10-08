package io.github.izakyl.folkways.plugins.build;

import com.simibubi.create.CreateClient;
import com.simibubi.create.content.schematics.client.SchematicHandler;
import com.simibubi.create.content.schematics.client.SchematicTransformation;
import com.simibubi.create.foundation.utility.CreatePaths;
import io.github.izakyl.folkways.core.api.colony.Books;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.neoforged.neoforge.network.PacketDistributor;

public final class CreateBlueprintHandoff {
    private CreateBlueprintHandoff() {
    }

    public static void handOff(boolean excavate, String siteName) {
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
        SchematicHandler handler = CreateClient.SCHEMATIC_HANDLER;
        String name = handler.getCurrentSchematicName();
        Optional<byte[]> file = read(CreatePaths.SCHEMATICS_DIR.resolve(name));
        if (file.isEmpty()) {
            say(player, BlueprintHandoffPanel.TOKEN + ".unreadable");
            return;
        }
        SchematicTransformation transformation = handler.getTransformation();
        StructurePlaceSettings settings = transformation.toSettings();
        BlockPos anchor = transformation.getAnchor();
        PacketDistributor.sendToServer(new UploadBlueprintPacket(book.get(), name, siteName, file.get(),
            anchor, excavate, settings.getRotation(), settings.getMirror(), false));
    }

    private static Optional<byte[]> read(Path path) {
        try {
            byte[] bytes = Files.readAllBytes(path);
            return bytes.length > UploadBlueprintPacket.MAX_BYTES ? Optional.empty() : Optional.of(bytes);
        } catch (IOException | RuntimeException missing) {
            return Optional.empty();
        }
    }

    private static void say(Player player, String key) {
        player.displayClientMessage(Component.translatable(key), true);
    }
}
