package io.github.izakyl.folkways.front.engine.net;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.ColonyView;
import io.github.izakyl.folkways.core.api.persist.Reader;
import io.github.izakyl.folkways.core.api.resident.body.Body;
import io.github.izakyl.folkways.front.api.Facing;
import io.github.izakyl.folkways.front.api.Ghost;
import io.github.izakyl.folkways.front.api.Placard;
import io.github.izakyl.folkways.front.api.notice.Attempt;
import io.github.izakyl.folkways.front.api.notice.Line;
import io.github.izakyl.folkways.front.client.ColonyHighlightState;
import io.github.izakyl.folkways.front.client.ColonyLookState;
import io.github.izakyl.folkways.front.client.SiteEntryInjector;
import io.github.izakyl.folkways.front.engine.authority.AtBlock;
import io.github.izakyl.folkways.front.engine.authority.AtBody;
import io.github.izakyl.folkways.front.engine.authority.ColonyAuthority;
import io.github.izakyl.folkways.front.engine.colony.ColonyBoards;
import io.github.izakyl.folkways.front.engine.colony.ColonyFront;
import io.github.izakyl.folkways.front.engine.colony.ColonyGhosts;
import io.github.izakyl.folkways.front.engine.colony.ColonyGround;
import io.github.izakyl.folkways.front.engine.colony.ColonyLooks;
import io.github.izakyl.folkways.front.engine.colony.ColonyPath;
import io.github.izakyl.folkways.front.engine.colony.ColonySettings;
import io.github.izakyl.folkways.front.engine.colony.ColonyViews;
import io.github.izakyl.folkways.front.engine.colony.ColonyZone;
import io.github.izakyl.folkways.front.engine.colony.Endorsements;
import io.github.izakyl.folkways.front.engine.colony.MemberToggle;
import io.github.izakyl.folkways.front.engine.colony.ZoneDrafts;
import io.github.izakyl.folkways.front.engine.item.ColonyBookItem;
import io.github.izakyl.folkways.front.ui.menu.FilterSlot;
import io.github.izakyl.folkways.front.ui.screen.ColonyShell;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.handling.IPayloadHandler;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

@EventBusSubscriber(modid = FolkwaysMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
public final class FolkwaysUiNetworkEvents {
    private FolkwaysUiNetworkEvents() {
    }

    @SubscribeEvent
    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(FolkwaysMod.NETWORK_VERSION);
        registrar.playToServer(FilterActionPacket.TYPE, FilterActionPacket.STREAM_CODEC,
            asPlayer(FolkwaysUiNetworkEvents::filterAction));
        registrar.playToServer(RequestColonyOverviewPacket.TYPE, RequestColonyOverviewPacket.STREAM_CODEC,
            asPlayer((packet, player) -> ColonyAuthority.ofHeldBook(player, packet.colonyId())
                .ifPresent(authority -> PacketDistributor.sendToPlayer(player,
                    ColonyOverviews.of(player, authority.colony())))));
        registrar.playToServer(RequestSitePacket.TYPE, RequestSitePacket.STREAM_CODEC,
            asPlayer((packet, player) -> PacketDistributor.sendToPlayer(player, siteState(player, packet.pos()))));
        registrar.playToServer(ToggleColonyMemberPacket.TYPE, ToggleColonyMemberPacket.STREAM_CODEC,
            asPlayer((packet, player) -> ColonyAuthority
                .ofHeldBook(player, packet.colonyId(), Optional.of(packet.pos()))
                .ifPresent(authority -> toggleColonyMember(player, authority))));
        registrar.playToServer(ToggleColonyResidentPacket.TYPE, ToggleColonyResidentPacket.STREAM_CODEC,
            asPlayer((packet, player) -> ColonyAuthority.ofHeldBook(player, packet.colonyId())
                .ifPresent(authority -> AtBody.of(player, packet.entityId())
                    .ifPresent(aimed -> toggleColonyResident(player, authority, aimed.body())))));
        registrar.playToServer(SetBookGesturePacket.TYPE, SetBookGesturePacket.STREAM_CODEC,
            asPlayer(FolkwaysUiNetworkEvents::setBookGesture));
        registrar.playToServer(CreateZonePacket.TYPE, CreateZonePacket.STREAM_CODEC,
            asPlayer(FolkwaysUiNetworkEvents::createZone));
        registrar.playToServer(CreatePathPacket.TYPE, CreatePathPacket.STREAM_CODEC,
            asPlayer(FolkwaysUiNetworkEvents::createPath));
        registrar.playToServer(RequestLookAtPacket.TYPE, RequestLookAtPacket.STREAM_CODEC,
            asPlayer(FolkwaysUiNetworkEvents::requestLookAt));

        registrar.playToClient(LookAtSnapshotPacket.TYPE, LookAtSnapshotPacket.STREAM_CODEC,
            (packet, context) -> onClient(context, () -> ColonyLookState.handleLookAtSnapshot(packet)));
        registrar.playToClient(ColonyOverviewPacket.TYPE, ColonyOverviewPacket.STREAM_CODEC,
            (packet, context) -> onClient(context, () -> ColonyHighlightState.handleColonyOverview(packet)));
        registrar.playToClient(SiteSyncPacket.TYPE, SiteSyncPacket.STREAM_CODEC,
            (packet, context) -> onClient(context, () -> SiteEntryInjector.accept(packet)));
    }

    private static <T extends CustomPacketPayload> IPayloadHandler<T> asPlayer(BiConsumer<T, ServerPlayer> work) {
        return (packet, context) -> {
            if (context.player() instanceof ServerPlayer player) {
                context.enqueueWork(() -> work.accept(packet, player));
            }
        };
    }

    private static void onClient(IPayloadContext context, Runnable work) {
        if (FMLEnvironment.dist == Dist.CLIENT) {
            context.enqueueWork(work);
        }
    }

    private static void filterAction(FilterActionPacket packet, ServerPlayer player) {
        AbstractContainerMenu menu = player.containerMenu;
        if (packet.slotIndex() < 0 || packet.slotIndex() >= menu.slots.size()) {
            return;
        }
        if (!(menu.getSlot(packet.slotIndex()) instanceof FilterSlot slot)) {
            return;
        }
        switch (packet.action()) {
            case STEP_UP -> slot.increase(menu.getCarried());
            case STEP_DOWN -> slot.decrease(menu.getCarried());
            case DROP -> slot.set(packet.stack());
        }
        menu.broadcastChanges();
    }

    private static void setBookGesture(SetBookGesturePacket packet, ServerPlayer player) {
        ItemStack stack = player.getItemInHand(packet.hand());
        if (stack.getItem() instanceof ColonyBookItem) {
            ColonyBookItem.setGesture(stack, packet.gesture());
            player.displayClientMessage(Component.translatable(
                "folkways.book.gesture." + packet.gesture().getSerializedName()), true);
        }
    }

    static SiteSyncPacket siteState(ServerPlayer player, BlockPos at) {
        ServerLevel level = player.serverLevel();
        return ColonyAuthority.of(player, Optional.of(at))
            .map(authority -> new SiteSyncPacket(at,
                ColonyGround.holds(level, authority.colony(), at),
                ColonyBoards.firstAt(level, authority.colony(), at).isPresent()))
            .orElseGet(() -> new SiteSyncPacket(at, false, false));
    }

    static void toggleColonyResident(ServerPlayer player, ColonyAuthority authority, Body body) {
        Colony colony = authority.colony();
        Optional<Attempt> answered =
            Endorsements.pointed(player.serverLevel(), colony, player, body);
        if (answered.isEmpty()) {
            return;
        }
        answered.get().refusal().ifPresent(why -> player.displayClientMessage(why.component(), true));
        PacketDistributor.sendToPlayer(player, ColonyOverviews.of(player, colony));
    }

    static void toggleColonyMember(ServerPlayer player, ColonyAuthority authority) {
        ServerLevel level = player.serverLevel();
        BlockPos pos = authority.at().orElseThrow().pos();
        Colony colony = authority.colony();
        if (MemberToggle.toggleAt(level, colony, pos, player).refused()) {
            return;
        }
        PacketDistributor.sendToPlayer(player, siteState(player, pos));
        PacketDistributor.sendToPlayer(player, ColonyOverviews.of(player, colony));
    }

    private static void createZone(CreateZonePacket packet, ServerPlayer player) {
        ColonyAuthority.ofHeldBook(player, packet.colonyId()).ifPresent(authority -> ZoneDrafts
            .zone(player.serverLevel(), authority.colony(), packet.delegation(), packet.min(), packet.max(),
                ColonySettings.load(Reader.of(packet.settings())))
            .ifPresent(zone -> opened(player, authority.colony(), zone.id())));
    }

    private static void createPath(CreatePathPacket packet, ServerPlayer player) {
        ColonyAuthority.ofHeldBook(player, packet.colonyId()).ifPresent(authority -> ZoneDrafts
            .path(player.serverLevel(), authority.colony(), packet.delegation(), packet.points(),
                ColonySettings.load(Reader.of(packet.settings())))
            .ifPresent(path -> opened(player, authority.colony(), path.id())));
    }

    private static void opened(ServerPlayer player, Colony colony, UUID id) {
        ColonyShell.editSelection(player, id);
        PacketDistributor.sendToPlayer(player, ColonyOverviews.of(player, colony));
    }

    static ColonyOverviewPacket buildOverview(ServerLevel level, Colony colony, BlockPos near) {
        List<ZoneSnapshot> zones = new ArrayList<>();
        for (ColonyZone zone : ColonyFront.of(colony).zonesIn(level)) {
            zones.add(ZoneSnapshot.of(zone));
        }
        List<Ghost> ghosts = ColonyViews.of(colony, level)
            .map(view -> ColonyGhosts.owed(view, colony, ColonyOverviewPacket.MAX_GHOSTS, near))
            .orElseGet(List::of);
        List<ColonyPath> paths = ColonyFront.of(colony).paths().stream()
            .filter(path -> path.dimension().equals(level.dimension())).toList();
        ColonyView view = colony.view(level);
        List<Facing> facings = Facing.all(colony);
        List<Placards.Note> notes = new ArrayList<>();
        List<Placard> placards = new ArrayList<>();
        for (ColonyZone zone : ColonyFront.of(colony).zonesIn(level)) {
            noted(notes, zone.id(), facings, facing -> facing.zoneLines(zone, view));
        }
        for (ColonyPath path : paths) {
            noted(notes, path.id(), facings, facing -> facing.pathLines(path, view));
        }
        for (Facing facing : facings) {
            placards.addAll(facing.placards(view));
        }
        return new ColonyOverviewPacket(ColonyGround.cells(level, colony), zones, ghosts,
            paths.stream().map(ColonyPath::save).toList(), notes, placards);
    }

    private static void noted(List<Placards.Note> notes, UUID id, List<Facing> facings,
            Function<Facing, List<Line>> lines) {
        List<Line> said = new ArrayList<>();
        for (Facing facing : facings) {
            said.addAll(lines.apply(facing));
        }
        if (!said.isEmpty()) {
            notes.add(new Placards.Note(id, said));
        }
    }

    private static void requestLookAt(RequestLookAtPacket packet, ServerPlayer player) {
        Optional<ColonyAuthority> aimed = ColonyAuthority.ofHeldBook(player, packet.colonyId(), packet.blockPos());
        Optional<ColonyAuthority> held = aimed.isPresent()
            ? aimed
            : ColonyAuthority.ofHeldBook(player, packet.colonyId());
        held.ifPresent(authority -> PacketDistributor.sendToPlayer(player, new LookAtSnapshotPacket(
            aimed.map(at -> lookLines(player.serverLevel(), at)).orElseGet(List::of),
            buildResidentLooks(player.serverLevel(), authority, player))));
    }

    private static List<Line> lookLines(ServerLevel level, ColonyAuthority authority) {
        return authority.at()
            .map(AtBlock::pos)
            .map(pos -> ColonyLooks.at(level, authority.colony(), pos))
            .orElseGet(List::of);
    }

    static List<LookLines> buildResidentLooks(ServerLevel level, ColonyAuthority authority,
            ServerPlayer player) {
        Colony colony = authority.colony();
        List<ColonyView> views = colony.views(level.getServer());
        List<LookLines> looks = new ArrayList<>();
        for (Body resident : ColonyLooks.nearest(level, colony, player, LookLines.MAX_RESIDENTS)) {
            looks.add(new LookLines(resident.mob().getId(),
                ColonyLooks.of(colony, resident, views)));
        }
        return List.copyOf(looks);
    }
}
