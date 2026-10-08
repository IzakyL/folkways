package io.github.izakyl.folkways.plugins.build.draft.client;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.colony.Books;
import io.github.izakyl.folkways.front.api.ui.BoxOffers;
import io.github.izakyl.folkways.front.api.ui.PathOffers;
import io.github.izakyl.folkways.plugins.build.CommissionPacket;
import io.github.izakyl.folkways.plugins.build.UploadBlueprintPacket;
import io.github.izakyl.folkways.plugins.build.draft.Chosen;
import io.github.izakyl.folkways.plugins.build.draft.Hint;
import io.github.izakyl.folkways.plugins.build.draft.Pattern;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class DraftOffer {

    private static final Logger LOGGER = LoggerFactory.getLogger("folkways-patterns");

    private static final ResourceLocation ID =
        ResourceLocation.fromNamespaceAndPath(FolkwaysMod.MOD_ID, "draft");
    private static final ResourceLocation ALONG =
        ResourceLocation.fromNamespaceAndPath(FolkwaysMod.MOD_ID, "draft_path");

    private DraftOffer() {
    }
    public static void register(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            BoxOffers.register(new BoxOffers.Offer(ID, DraftPanel.TOKEN, ResourceLocation.withDefaultNamespace("paper"),
                (min, max) -> DraftPanel.open(new Hint.Zone(min, max), DraftOffer::send)));
            PathOffers.register(new PathOffers.Offer(ALONG, DraftPanel.TOKEN,
                ResourceLocation.withDefaultNamespace("paper"),
                points -> DraftPanel.open(new Hint.Path(points), DraftOffer::send)));
        });
    }

    private static void send(CompoundTag file, BlockPos anchor, String siteName) {
        Optional<UUID> colony = colonyInHand();
        if (colony.isEmpty()) {
            LOGGER.warn("no bound colony book in hand; the drawing was not sent");
            return;
        }
        bytesOf(file).ifPresent(bytes -> PacketDistributor.sendToServer(new UploadBlueprintPacket(
            colony.get(), ID.getPath(), siteName, bytes, anchor, true, Rotation.NONE, Mirror.NONE, true)));
    }

    static boolean fits(CompoundTag file) {
        return bytesOf(file).isPresent();
    }

    static void commission(Pattern pattern, Hint hint, Chosen settings, Direction facing, String siteName) {
        Optional<UUID> colony = colonyInHand();
        if (colony.isEmpty()) {
            LOGGER.warn("no bound colony book in hand; the drawing was not sent");
            return;
        }
        PacketDistributor.sendToServer(new CommissionPacket(colony.get(), pattern.id(), hint.save(),
            settings.save(), facing, siteName, true));
    }

    private static Optional<UUID> colonyInHand() {
        Player player = Minecraft.getInstance().player;
        return player == null ? Optional.empty() : Books.heldColony(player);
    }

    private static Optional<byte[]> bytesOf(CompoundTag file) {
        try (ByteArrayOutputStream written = new ByteArrayOutputStream()) {
            NbtIo.writeCompressed(file, written);
            byte[] bytes = written.toByteArray();
            if (bytes.length > UploadBlueprintPacket.MAX_BYTES) {
                LOGGER.warn("the drawing came to {} bytes, over the {} one packet carries",
                    bytes.length, UploadBlueprintPacket.MAX_BYTES);
                return Optional.empty();
            }
            return Optional.of(bytes);
        } catch (IOException unwritable) {
            LOGGER.error("cannot write the drawing: {}", unwritable.getMessage());
            return Optional.empty();
        }
    }
}
