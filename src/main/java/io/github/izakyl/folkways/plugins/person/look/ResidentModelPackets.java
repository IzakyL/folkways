package io.github.izakyl.folkways.plugins.person.look;

import io.github.izakyl.folkways.FolkwaysMod;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public final class ResidentModelPackets {
    private static final String CLIENT_CLASS = "io.github.izakyl.folkways.plugins.person.look.ClientResidentModels";

    private ResidentModelPackets() {
    }

    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        event.registrar(FolkwaysMod.NETWORK_VERSION)
            .playToClient(ModelLibraryPacket.TYPE, ModelLibraryPacket.STREAM_CODEC, ResidentModelPackets::handleLibrary)
            .playToClient(ModelAssetPacket.TYPE, ModelAssetPacket.STREAM_CODEC, ResidentModelPackets::handleAsset)
            .playToServer(RequestModelAssetsPacket.TYPE, RequestModelAssetsPacket.STREAM_CODEC,
                ResidentModelPackets::handleRequest);
    }

    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            offerTo(player, ModelLibraryPacket.of(ResidentModels.current()));
        }
    }

    public static void offerToEveryone(MinecraftServer server, ModelLibrary library) {
        ModelLibraryPacket offer = ModelLibraryPacket.of(library);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            offerTo(player, offer);
        }
    }

    private static void offerTo(ServerPlayer player, ModelLibraryPacket offer) {
        if (player.connection.hasChannel(ModelLibraryPacket.TYPE)) {
            PacketDistributor.sendToPlayer(player, offer);
        }
    }

    private static void handleRequest(RequestModelAssetsPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            ModelLibrary library = ResidentModels.current();
            if (!library.fingerprint().equals(packet.fingerprint())) {
                return;
            }
            for (Map.Entry<String, byte[]> asset : library.assets().entrySet()) {
                sendAsset(player, library.fingerprint(), asset.getKey(), asset.getValue());
            }
        });
    }

    private static void sendAsset(ServerPlayer player, String fingerprint, String path, byte[] bytes) {
        if (!player.connection.hasChannel(ModelAssetPacket.TYPE)) {
            return;
        }
        int count = Math.max(1, (bytes.length + ModelAssetPacket.CHUNK_BYTES - 1) / ModelAssetPacket.CHUNK_BYTES);
        for (int index = 0; index < count; index++) {
            int from = index * ModelAssetPacket.CHUNK_BYTES;
            int to = Math.min(bytes.length, from + ModelAssetPacket.CHUNK_BYTES);
            PacketDistributor.sendToPlayer(player,
                new ModelAssetPacket(fingerprint, path, index, count, Arrays.copyOfRange(bytes, from, to)));
        }
    }

    private static void handleLibrary(ModelLibraryPacket packet, IPayloadContext context) {
        if (FMLEnvironment.dist != Dist.CLIENT) {
            return;
        }
        context.enqueueWork(() -> deliver("handleLibrary", ModelLibraryPacket.class, packet));
    }

    private static void handleAsset(ModelAssetPacket packet, IPayloadContext context) {
        if (FMLEnvironment.dist != Dist.CLIENT) {
            return;
        }
        context.enqueueWork(() -> deliver("handleAsset", ModelAssetPacket.class, packet));
    }

    private static void deliver(String methodName, Class<?> packetClass, Object packet) {
        try {
            Method method = Class.forName(CLIENT_CLASS).getMethod(methodName, packetClass);
            method.invoke(null, packet);
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException exception) {
            throw new IllegalStateException("Unable to deliver a Folkways model library to the client", exception);
        } catch (InvocationTargetException exception) {
            throw new IllegalStateException("Folkways client rejected a model library", exception.getCause());
        }
    }
}
