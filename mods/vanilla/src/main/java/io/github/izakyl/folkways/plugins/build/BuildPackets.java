package io.github.izakyl.folkways.plugins.build;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.work.api.StructureCompat;
import io.github.izakyl.folkways.front.api.Books;
import io.github.izakyl.folkways.library.Shelves;
import io.github.izakyl.folkways.plugins.build.draft.Chosen;
import io.github.izakyl.folkways.plugins.build.draft.Drafting;
import io.github.izakyl.folkways.plugins.build.draft.Hint;
import io.github.izakyl.folkways.plugins.build.draft.Joint;
import io.github.izakyl.folkways.plugins.build.draft.Pattern;
import io.github.izakyl.folkways.plugins.build.draft.PatternPackets;
import io.github.izakyl.folkways.plugins.build.draft.Patterns;
import io.github.izakyl.folkways.plugins.build.draft.Templates;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public final class BuildPackets {

    private BuildPackets() {
    }

    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        event.registrar(FolkwaysMod.NETWORK_VERSION)
            .playToServer(CaptureBlueprintPacket.TYPE, CaptureBlueprintPacket.STREAM_CODEC, BuildPackets::handleCapture)
            .playToServer(BlueprintChunkPacket.TYPE, BlueprintChunkPacket.STREAM_CODEC,
                BuildPackets::handleChunk)
            .playToServer(UploadBlueprintPacket.TYPE, UploadBlueprintPacket.STREAM_CODEC,
                BuildPackets::handleUpload)
            .playToServer(PlaceBlueprintPacket.TYPE, PlaceBlueprintPacket.STREAM_CODEC,
                BuildPackets::handlePlace)
            .playToServer(CommissionPacket.TYPE, CommissionPacket.STREAM_CODEC, BuildPackets::handleCommission);
    }

    private static void handleCapture(CaptureBlueprintPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)
                || Books.actingFor(player, packet.colony()).isEmpty()) return;
            Optional<LibraryPath> name = LibraryPath.of(packet.name());
            if (name.isEmpty() || ResourceLocation.tryBuild(Shelves.NAMESPACE, packet.name()) == null) {
                refuse(player, Component.translatable("folkways.blueprints.capture.name_invalid"));
                return;
            }
            ServerLevel level = player.serverLevel();
            try {
                var box = net.minecraft.world.level.levelgen.structure.BoundingBox.fromCorners(packet.from(), packet.to());
                CompoundTag file = BlueprintCapture.read(level, box, pos -> level.mayInteract(player, pos));
                saveCapture(packet.name(), file);
                Templates.reload(level.getServer().getResourceManager());
                PatternPackets.offerToEveryone(level.getServer());
                player.displayClientMessage(Component.translatable("folkways.blueprints.capture.saved", packet.name()), true);
            } catch (StructureCompat.Failure failed) {
                refuse(player, failed.component());
            } catch (IllegalArgumentException | IOException failed) {
                refuse(player, Component.translatable("folkways.blueprints.capture.failed"));
            }
        });
    }

    private static void saveCapture(String name, CompoundTag file) throws IOException {
        byte[] bytes;
        try (ByteArrayOutputStream written = new ByteArrayOutputStream()) {
            NbtIo.writeCompressed(file, written);
            bytes = written.toByteArray();
        }
        if (!BlueprintUploads.fits(bytes)) {
            throw new IllegalArgumentException("Capture exceeds the upload limit");
        }
        Path blueprint = Templates.BLUEPRINTS.path().resolve(name + ".nbt");
        Path piece = Templates.SHELF.path().resolve(name + ".nbt");
        if (Files.exists(blueprint) || Files.exists(piece)) {
            throw new IOException("Capture name already used");
        }
        Files.createDirectories(blueprint.getParent());
        Files.createDirectories(piece.getParent());
        Files.write(blueprint, bytes, StandardOpenOption.CREATE_NEW);
        try {
            Files.write(piece, bytes, StandardOpenOption.CREATE_NEW);
        } catch (IOException failed) {
            Files.deleteIfExists(blueprint);
            throw failed;
        }
    }

    private static void handleChunk(BlueprintChunkPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> BlueprintUploads.accept(context.player().getUUID(), packet));
    }

    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        BlueprintUploads.forget(event.getEntity().getUUID());
    }

    private static void handleUpload(UploadBlueprintPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            byte[] bytes = packet.file().length > 0 ? packet.file()
                : BlueprintUploads.assembled(player.getUUID()).orElse(packet.file());
            Optional<String> name = LibraryPath.of(packet.name()).map(LibraryPath::name);
            Drafting.prepare(() -> name.flatMap(ignored -> readStructure(bytes)))
                .thenAccept(file -> player.server.execute(() -> {
                    if (!player.isRemoved()) {
                        setDown(player, packet.colonyId(), name.orElse(""), file, packet.siteName(), packet.anchor(),
                            packet.airIsEmpty(), packet.rotation(), packet.mirror(), packet.raiseInCreative());
                    }
                }));
        });
    }

    private static void handlePlace(PlaceBlueprintPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            String path = packet.blueprint().getPath();
            setDown(player, packet.colonyId(), path.substring(path.lastIndexOf('/') + 1),
                Templates.blueprint(packet.blueprint()), packet.siteName(), packet.anchor(), packet.airIsEmpty(),
                packet.rotation(), packet.mirror(), packet.raiseInCreative());
        });
    }

    // A structure file set down whole at anchor, turned and flipped so: filed with the colony, or raised at once.
    private static void setDown(ServerPlayer player, UUID colonyId, String name, Optional<CompoundTag> file,
            String siteName, BlockPos anchor, boolean airIsEmpty, Rotation rotation, Mirror mirror,
            boolean raiseInCreative) {
        Optional<BuildPresence> site = Books.actingFor(player, colonyId)
            .flatMap(colony -> BuildContent.presenceIn(colony.service(BuildContent.ID, Object.class)));
        if (site.isEmpty()) {
            return;
        }
        boolean raise = raiseInCreative && player.isCreative();
        ServerLevel level = player.serverLevel();
        if (file.isEmpty()) {
            refuse(player, Component.translatable("folkways.blueprint.unreadable"));
            return;
        }
        StructureNbt.AirMeaning airMeaning = airIsEmpty
            ? StructureNbt.AirMeaning.EXCAVATE
            : StructureNbt.AirMeaning.UNCONSTRAINED;
        Optional<Blueprint> shape = read(file.get(), level, name, airMeaning, BlueprintBook.maxCaptureBlocks());
        if (shape.isEmpty()) {
            refuse(player, whyNot(file.get(), level, name, airMeaning));
            return;
        }
        Drafting.prepare(() ->
            shape.get().transformed(rotation, mirror).orElseThrow(StructureCompat::invalid))
            .thenCompose(Blueprint::prepare).whenComplete((prepared, failure) -> level.getServer().execute(() -> {
                if (player.isRemoved()) {
                    return;
                }
                if (failure != null) {
                    Throwable cause = failure;
                    while (cause.getCause() != null) {
                        cause = cause.getCause();
                    }
                    refuse(player, cause instanceof StructureCompat.Failure invalid ? invalid.component()
                        : Component.translatable("folkways.refusal.build.micro_data_invalid"));
                    return;
                }
                filePrepared(player, colonyId, prepared, anchor, siteName, raise);
            }));
    }

    private static void filePrepared(ServerPlayer player, UUID colonyId, Blueprint prepared, BlockPos anchor,
            String siteName, boolean raise) {
        ServerLevel level = player.serverLevel();
        Optional<BuildPresence> site = Books.actingFor(player, colonyId)
            .flatMap(colony -> BuildContent.presenceIn(colony.service(BuildContent.ID, Object.class)));
        if (site.isEmpty()) {
            return;
        }
        if (raise) {
            raise(player, prepared, anchor);
            return;
        }
        Optional<BlueprintBuildOrder> filed;
        try {
            filed = site.get().file(level, prepared, anchor,
                siteName, Optional.of(player.getUUID()), player.getGameProfile().getName());
        } catch (StructureCompat.Failure failed) {
            refuse(player, failed.component());
            return;
        }
        if (filed.isEmpty()) {
            refuse(player, Component.translatable("folkways.blueprint.too_many",
                BlueprintBook.MAX_BUILD_ORDERS));
            return;
        }
        player.displayClientMessage(
            Component.translatable("folkways.blueprint.placed", filed.get().name()), true);
    }

    private static void handleCommission(CommissionPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            Optional<BuildPresence> site = Books.actingFor(player, packet.colonyId())
                .flatMap(colony -> BuildContent.presenceIn(colony.service(BuildContent.ID, Object.class)));
            if (site.isEmpty()) {
                return;
            }
            Optional<Pattern> pattern = Patterns.find(packet.pattern());
            Optional<Hint> hint = Hint.load(packet.hint());
            if (pattern.isEmpty() || hint.isEmpty() || !pattern.get().accepts(hint.get())) {
                refuse(player, Component.translatable("folkways.draft.unknown", packet.pattern().toString()));
                return;
            }
            ServerLevel level = player.serverLevel();
            Chosen settings;
            try {
                settings = Chosen.load(pattern.get().knobs(), packet.settings());
            } catch (IllegalArgumentException | java.util.NoSuchElementException stale) {
                // A /reload can change the pattern's knobs while the player's panel still holds the old ones.
                refuse(player, Component.translatable("folkways.draft.stale", packet.pattern().toString()));
                return;
            }
            if (packet.raiseInCreative() && player.isCreative()) {
                raiseOnce(level, player, pattern.get(), hint.get(), settings, packet.facing());
                return;
            }
            Rounds.logged(Commissions.file(level, site.get(), pattern.get(), hint.get(), settings,
                    packet.facing(), packet.siteName(), Optional.of(player.getUUID()),
                    player.getGameProfile().getName())
                .thenAccept(said -> player.displayClientMessage(said.message(), true)));
        });
    }

    private static void raiseOnce(ServerLevel level, ServerPlayer player, Pattern pattern, Hint hint,
            Chosen settings, Direction facing) {
        Rounds.logged(Rounds.drawOnce(level, pattern, hint, settings, facing).thenAccept(once -> {
            if (!(once instanceof Rounds.Once.Drafted drafted)) {
                refuse(player, ((Rounds.Once.Refused) once).why());
                return;
            }
            raise(player, drafted.blueprint(), drafted.corner());
        }));
    }

    private static void raise(ServerPlayer player, Blueprint blueprint, BlockPos anchor) {
        ServerLevel level = player.serverLevel();
        try {
            Blueprint confirmed = blueprint.confirm(level);
            Rounds.logged(SchematicPlacement.print(level, confirmed, anchor).handle((done, failure) -> {
                if (player.isRemoved()) {
                    return null;
                }
                if (failure != null) {
                    if (failure instanceof StructureCompat.Failure refused) {
                        refuse(player, refused.component());
                        return null;
                    }
                    throw new java.util.concurrent.CompletionException(failure);
                }
                for (Joint joint : confirmed.joints()) {
                    Joint between = joint.moved(anchor);
                    Joints.joiner().cost(level, between.from(), between.to())
                        .ifPresent(cost -> Joints.joiner().join(level, between.from(), between.to(), cost));
                }
                player.displayClientMessage(Component.translatable("folkways.blueprint.raised", confirmed.name()), true);
                return null;
            }));
        } catch (StructureCompat.Failure failed) {
            refuse(player, failed.component());
        }
    }

    private static Component whyNot(CompoundTag file, ServerLevel level, String name,
            StructureNbt.AirMeaning airMeaning) {
        return read(file, level, name, airMeaning, Integer.MAX_VALUE).isPresent()
            ? Component.translatable("folkways.blueprint.too_large", BlueprintBook.maxCaptureBlocks())
            : Component.translatable("folkways.blueprint.unreadable");
    }

    private static Optional<Blueprint> read(CompoundTag file, ServerLevel level, String name,
            StructureNbt.AirMeaning airMeaning, int maxCells) {
        return StructureNbt.toBlueprint(file, level.registryAccess(), UUID.randomUUID(), name,
            level.getGameTime(), airMeaning, maxCells);
    }

    private static Optional<CompoundTag> readStructure(byte[] bytes) {
        try (ByteArrayInputStream in = new ByteArrayInputStream(bytes)) {
            return Optional.of(NbtIo.readCompressed(in,
                NbtAccounter.create(BlueprintUploads.MOST_BYTES * 32L)));
        } catch (IOException | RuntimeException failure) {
            return Optional.empty();
        }
    }

    private static void refuse(ServerPlayer player, Component why) {
        player.displayClientMessage(why, true);
    }
}
