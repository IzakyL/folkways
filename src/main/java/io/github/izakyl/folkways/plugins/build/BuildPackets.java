package io.github.izakyl.folkways.plugins.build;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.colony.Books;
import io.github.izakyl.folkways.plugins.build.draft.Chosen;
import io.github.izakyl.folkways.plugins.build.draft.DraftRefusal;
import io.github.izakyl.folkways.plugins.build.draft.Hint;
import io.github.izakyl.folkways.plugins.build.draft.Pattern;
import io.github.izakyl.folkways.plugins.build.draft.Patterns;
import io.github.izakyl.folkways.plugins.build.draft.Round;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public final class BuildPackets {

    static final int MOST_RAISED_ROUNDS = 64;

    private BuildPackets() {
    }
    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        event.registrar(FolkwaysMod.NETWORK_VERSION)
            .playToServer(UploadBlueprintPacket.TYPE, UploadBlueprintPacket.STREAM_CODEC,
                BuildPackets::handleUpload)
            .playToServer(CommissionPacket.TYPE, CommissionPacket.STREAM_CODEC, BuildPackets::handleCommission);
    }

    private static void handleUpload(UploadBlueprintPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            Optional<BuildPresence> site = Books.ofHeldBook(player, packet.colonyId())
                .flatMap(colony -> BuildContent.presenceIn(colony.service(BuildContent.ID, Object.class)));
            if (site.isEmpty()) {
                return;
            }
            boolean raise = packet.raiseInCreative() && player.isCreative();
            ServerLevel level = player.serverLevel();
            Optional<String> name = LibraryPath.of(packet.name()).map(LibraryPath::name);
            Optional<CompoundTag> file = name.flatMap(ignored -> readStructure(packet.file()));
            if (file.isEmpty()) {
                refuse(player, Component.translatable("folkways.blueprint.unreadable"));
                return;
            }
            StructureNbt.AirMeaning airMeaning = packet.airIsEmpty()
                ? StructureNbt.AirMeaning.EXCAVATE
                : StructureNbt.AirMeaning.UNCONSTRAINED;
            Optional<Blueprint> shape = read(file.get(), level, name.get(), airMeaning,
                raise ? Integer.MAX_VALUE : BlueprintBook.maxCaptureBlocks());
            if (shape.isEmpty()) {
                refuse(player, whyNot(file.get(), level, name.get(), airMeaning));
                return;
            }
            Optional<Blueprint> blueprint = shape.get().transformed(packet.rotation(), packet.mirror());
            if (blueprint.isEmpty()) {
                refuse(player, Component.translatable("folkways.blueprint.unreadable"));
                return;
            }
            if (raise) {
                raise(level, player, blueprint.get(), packet.anchor());
                player.displayClientMessage(
                    Component.translatable("folkways.blueprint.raised", blueprint.get().name()), true);
                return;
            }
            Optional<BlueprintBuildOrder> filed = site.get().file(level, blueprint.get(), packet.anchor(),
                packet.siteName(), Optional.of(player.getUUID()), player.getGameProfile().getName());
            if (filed.isEmpty()) {
                refuse(player, Component.translatable("folkways.blueprint.too_many",
                    BlueprintBook.MAX_BUILD_ORDERS));
                return;
            }
            player.displayClientMessage(
                Component.translatable("folkways.blueprint.placed", filed.get().name()), true);
        });
    }

    private static void handleCommission(CommissionPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            Optional<BuildPresence> site = Books.ofHeldBook(player, packet.colonyId())
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
            Chosen settings = Chosen.load(pattern.get().knobs(), packet.settings());
            if (packet.raiseInCreative() && player.isCreative()) {
                if (pattern.get().grows()) {
                    raiseRounds(level, player, Commissions.begun(level, pattern.get(), hint.get(), settings,
                        packet.facing(), packet.siteName(), Optional.of(player.getUUID()),
                        player.getGameProfile().getName()));
                } else {
                    raiseOnce(level, player, pattern.get(), hint.get(), settings, packet.facing());
                }
                return;
            }
            Commissions.Said said = Commissions.file(level, site.get(), pattern.get(), hint.get(), settings,
                packet.facing(), packet.siteName(), Optional.of(player.getUUID()), player.getGameProfile().getName());
            player.displayClientMessage(said.message(), true);
        });
    }

    private static void raiseOnce(ServerLevel level, ServerPlayer player, Pattern pattern, Hint hint,
            Chosen settings, Direction facing) {
        Rounds.Once once = Rounds.drawOnce(level, pattern, hint, settings, facing);
        if (!(once instanceof Rounds.Once.Drafted drafted)) {
            refuse(player, ((Rounds.Once.Refused) once).why());
            return;
        }
        raise(level, player, drafted.blueprint(), drafted.corner());
        player.displayClientMessage(
            Component.translatable("folkways.blueprint.raised", drafted.blueprint().name()), true);
    }

    private static void raiseRounds(ServerLevel level, ServerPlayer player, Growing growing) {
        Growing one = growing;
        for (int raised = 0; raised < MOST_RAISED_ROUNDS; raised++) {
            Round round = Rounds.draw(level, one);
            if (!(round instanceof Round.Grew grew)) {
                if (raised == 0) {
                    refuse(player, round instanceof Round.Stuck stuck
                        ? Component.translatable(stuck.refused().why().translationKey(), stuck.refused().detail())
                        : Component.translatable(DraftRefusal.NOTHING_DRAWN.translationKey(), ""));
                    return;
                }
                break;
            }
            Optional<Blueprint> blueprint = Rounds.blueprintOf(level, one, grew.ready().draft());
            if (blueprint.isEmpty()) {
                refuse(player, Component.translatable(DraftRefusal.SITE_TOO_LARGE.translationKey(), ""));
                return;
            }
            raise(level, player, blueprint.get(), grew.ready().corner());
            if (grew.last()) {
                break;
            }
            one = one.building(blueprint.get().id(), one.growth().next(grew.kept(), grew.ready().corner(),
                grew.ready().draft(), one.hint().anchor()), false);
        }
        player.displayClientMessage(
            Component.translatable("folkways.draft.grow.raised", growing.pattern().getPath(), one.growth().round()),
            true);
    }

    private static void raise(ServerLevel level, ServerPlayer player, Blueprint blueprint, BlockPos anchor) {
        Map<BlockPos, BlockState> before = new LinkedHashMap<>();
        Map<BlockPos, BlockState> after = new LinkedHashMap<>();
        for (BlueprintBlock block : blueprint.blocks()) {
            BlockPos at = block.worldPos(anchor);
            before.put(at, level.getBlockState(at));
            after.put(at, block.state());
        }
        Rubble.swap(level, player, before, after, ItemStack.EMPTY, true);
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
                NbtAccounter.create(UploadBlueprintPacket.MAX_BYTES * 64L)));
        } catch (IOException | RuntimeException failure) {
            return Optional.empty();
        }
    }

    private static void refuse(ServerPlayer player, Component why) {
        player.displayClientMessage(why, true);
    }
}
