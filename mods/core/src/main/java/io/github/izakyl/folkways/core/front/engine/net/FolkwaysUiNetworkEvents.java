package io.github.izakyl.folkways.core.front.engine.net;

import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.core.base.persist.Reader;
import io.github.izakyl.folkways.core.card.api.notice.Attempt;
import io.github.izakyl.folkways.core.card.api.notice.Line;
import io.github.izakyl.folkways.core.card.net.CardOpen;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.colony.api.ColonyView;
import io.github.izakyl.folkways.core.facility.api.Facilities;
import io.github.izakyl.folkways.core.front.api.Ghost;
import io.github.izakyl.folkways.core.front.api.GhostSource;
import io.github.izakyl.folkways.core.front.api.Placard;
import io.github.izakyl.folkways.core.front.api.ZoneRing;
import io.github.izakyl.folkways.core.front.client.ColonyDebugState;
import io.github.izakyl.folkways.core.front.client.ColonyHighlightState;
import io.github.izakyl.folkways.core.front.client.ColonyLookState;
import io.github.izakyl.folkways.core.front.client.ZoneSelection;
import io.github.izakyl.folkways.core.front.engine.Enrollments;
import io.github.izakyl.folkways.core.front.engine.authority.AtBody;
import io.github.izakyl.folkways.core.front.engine.authority.ColonyAuthority;
import io.github.izakyl.folkways.core.colony.charter.CurrentColony;
import io.github.izakyl.folkways.core.front.engine.colony.BlockSweep;
import io.github.izakyl.folkways.core.front.engine.colony.ColonyFront;
import io.github.izakyl.folkways.core.front.engine.colony.ColonyGround;
import io.github.izakyl.folkways.core.front.engine.colony.ColonyPath;
import io.github.izakyl.folkways.core.front.engine.colony.ColonySettings;
import io.github.izakyl.folkways.core.front.engine.colony.ColonyStalls;
import io.github.izakyl.folkways.core.front.engine.colony.ColonyZone;
import io.github.izakyl.folkways.core.front.engine.colony.DebugFocus;
import io.github.izakyl.folkways.core.front.engine.colony.Endorsements;
import io.github.izakyl.folkways.core.front.engine.colony.HeldFacilities;
import io.github.izakyl.folkways.core.front.engine.colony.LaborDebug;
import io.github.izakyl.folkways.core.front.engine.colony.MemberToggle;
import io.github.izakyl.folkways.core.front.engine.colony.ZoneDrafts;
import io.github.izakyl.folkways.core.front.engine.item.ColonyBookItem;
import io.github.izakyl.folkways.core.front.ui.screen.ColonyShell;
import io.github.izakyl.folkways.core.resident.api.Bodies;
import io.github.izakyl.folkways.core.resident.api.Body;
import io.github.izakyl.folkways.core.travel.internal.ColonyWays;
import io.github.izakyl.folkways.core.travel.internal.graph.WayGraphDebug;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.BiConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.handling.IPayloadHandler;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

@EventBusSubscriber(modid = FolkwaysCoreMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD)
public final class FolkwaysUiNetworkEvents {
    private FolkwaysUiNetworkEvents() {
    }

    @SubscribeEvent
    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(FolkwaysCoreMod.NETWORK_VERSION);
        registrar.playToServer(RequestColonyOverviewPacket.TYPE, RequestColonyOverviewPacket.STREAM_CODEC,
            asPlayer((packet, player) -> ColonyAuthority.forColony(player, packet.colonyId()).map(ColonyAuthority::colony)
                .ifPresent(colony -> PacketDistributor.sendToPlayer(player,
                    ColonyOverviews.requested(player, colony,
                        packet.recipesNeeded(), packet.unavailable())))));
        registrar.playToServer(ToggleColonyMemberPacket.TYPE, ToggleColonyMemberPacket.STREAM_CODEC,
            asPlayer((packet, player) -> ColonyAuthority
                .forColony(player, packet.colonyId(), Optional.of(packet.pos()))
                .ifPresent(authority -> toggleColonyMember(player, authority))));
        registrar.playToServer(ToggleColonyResidentPacket.TYPE, ToggleColonyResidentPacket.STREAM_CODEC,
            asPlayer((packet, player) -> ColonyAuthority.forColony(player, packet.colonyId())
                .ifPresent(authority -> toggleColonyEntity(player, authority, packet.entityId()))));
        registrar.playToServer(UseBookPacket.TYPE, UseBookPacket.STREAM_CODEC,
            asPlayer(FolkwaysUiNetworkEvents::useBook));
        registrar.playToServer(SetBookGesturePacket.TYPE, SetBookGesturePacket.STREAM_CODEC,
            asPlayer(FolkwaysUiNetworkEvents::setBookGesture));
        registrar.playToServer(
            OpenBookPagePacket.TYPE,
            OpenBookPagePacket.STREAM_CODEC,
            asPlayer(
            (packet, player) ->
                ColonyAuthority.forColony(player, packet.colonyId())
                    .ifPresent(authority -> ColonyShell.open(player, packet.page()))));
        registrar.playToServer(CreateZonePacket.TYPE, CreateZonePacket.STREAM_CODEC,
            asPlayer(FolkwaysUiNetworkEvents::createZone));
        registrar.playToClient(ZoneImpactPacket.TYPE, ZoneImpactPacket.STREAM_CODEC,
            (packet, context) -> onClient(context, () -> ZoneSelection.confirmImpact(packet)));
        registrar.playToServer(CreatePathPacket.TYPE, CreatePathPacket.STREAM_CODEC,
            asPlayer(FolkwaysUiNetworkEvents::createPath));
        registrar.playToServer(SweepBlocksPacket.TYPE, SweepBlocksPacket.STREAM_CODEC,
            asPlayer(FolkwaysUiNetworkEvents::sweepBlocks));
        registrar.playToServer(RequestLookAtPacket.TYPE, RequestLookAtPacket.STREAM_CODEC,
            asPlayer(FolkwaysUiNetworkEvents::requestLookAt));

        registrar.playToServer(RequestDebugFocusPacket.TYPE, RequestDebugFocusPacket.STREAM_CODEC,
            asPlayer((packet, player) -> {
                if (LaborDebug.enabled(player)) {
                    ColonyAuthority.forColony(player, packet.colony()).ifPresent(authority ->
                        PacketDistributor.sendToPlayer(player,
                            DebugFocus.read(
                                player, authority.colony(), packet.target())));
                }
            }));
        registrar.playToServer(RequestDebugWaypointsPacket.TYPE, RequestDebugWaypointsPacket.STREAM_CODEC,
            asPlayer((packet, player) -> {
                if (LaborDebug.enabled(player)) {
                    ColonyAuthority.forColony(player, packet.colony()).ifPresent(authority -> {
                        var level = player.serverLevel();
                        var graph = ColonyWays.published(authority.colony().id(), level)
                            .map(ways -> WayGraphDebug.read(level, ways, player.position()))
                            .orElse(WayGraphDebug.Snapshot.EMPTY);
                        PacketDistributor.sendToPlayer(player,
                            new DebugWaypointsPacket(authority.colony().id(), level.dimension(), graph));
                    });
                }
            }));
        registrar.playToClient(DebugWaypointsPacket.TYPE, DebugWaypointsPacket.STREAM_CODEC,
            (packet, context) -> onClient(context, () -> ColonyDebugState.waypoints(packet)));
        registrar.playToClient(DebugStatePacket.TYPE, DebugStatePacket.STREAM_CODEC,
            (packet, context) -> onClient(context, () ->
                ColonyDebugState.state(packet)));
        registrar.playToClient(DebugFocusPacket.TYPE, DebugFocusPacket.STREAM_CODEC,
            (packet, context) -> onClient(context, () ->
                ColonyDebugState.focus(packet)));

        registrar.playToClient(LookAtSnapshotPacket.TYPE, LookAtSnapshotPacket.STREAM_CODEC,
            (packet, context) -> onClient(context, () -> ColonyLookState.handleLookAtSnapshot(packet)));
        registrar.playToClient(ColonyOverviewPacket.TYPE, ColonyOverviewPacket.STREAM_CODEC,
            (packet, context) -> onClient(context, () -> ColonyHighlightState.handleColonyOverview(packet)));
        registrar.playToClient(CurrentColonyPacket.TYPE, CurrentColonyPacket.STREAM_CODEC,
            (packet, context) -> onClient(context, () -> CurrentColony.heard(packet.colonyId())));
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

    // The book in that hand turns to the gesture; the client has already said so.
    private static void setBookGesture(SetBookGesturePacket packet, ServerPlayer player) {
        ItemStack stack = player.getItemInHand(packet.hand());
        if (stack.getItem() instanceof ColonyBookItem) {
            ColonyBookItem.setGesture(stack, packet.gesture());
        }
    }

    // What the colony holds where the book was used opens on its card, a block before the ground it stands in;
    // anywhere else, the colony's panel.
    private static void useBook(UseBookPacket packet, ServerPlayer player) {
        Optional<ColonyAuthority> authority = ColonyAuthority.forColony(player, packet.colonyId());
        if (authority.isEmpty()) {
            ColonyShell.open(player);
            return;
        }
        Colony colony = authority.get().colony();
        ServerLevel level = player.serverLevel();
        Optional<UUID> held = packet.block().filter(level::isLoaded)
            .flatMap(at -> HeldFacilities.at(level, colony, at))
            .or(() -> packet.holding().flatMap(id -> HeldFacilities.find(colony, id)))
            .map(HeldFacilities.Held::id);
        if (held.isPresent()) {
            CardOpen.send(player, colony.id(), held.get());
        } else {
            ColonyShell.open(player);
        }
    }

    static void toggleColonyResident(ServerPlayer player, ColonyAuthority authority, Body body) {
        Colony colony = authority.colony();
        Optional<Attempt> answered =
            Endorsements.pointed(player.serverLevel(), colony, player, body);
        if (answered.isEmpty()) {
            return;
        }
        answered.get().refusal().ifPresent(why -> player.displayClientMessage(why.component(), true));
        PacketDistributor.sendToPlayer(player, ColonyOverviews.fresh(player, colony));
    }

    static void toggleColonyEntity(ServerPlayer player, ColonyAuthority authority, int entityId) {
        var entity = player.serverLevel().getEntity(entityId);
        if (entity == null || !entity.isAlive() || entity.isRemoved() || player.distanceToSqr(entity) > 64) {
            return;
        }
        if (Bodies.of(entity).isPresent()) {
            AtBody.of(player, entityId)
                .ifPresent(aimed -> toggleColonyResident(player, authority, aimed.body()));
            return;
        }
        MemberToggle.toggleEntity(authority.colony(), entity, player);
        PacketDistributor.sendToPlayer(player, ColonyOverviews.of(player, authority.colony()));
    }

    public static void toggleColonyMember(ServerPlayer player, ColonyAuthority authority) {
        ServerLevel level = player.serverLevel();
        BlockPos pos = authority.at().orElseThrow().pos();
        Colony colony = authority.colony();
        if (MemberToggle.toggleAt(level, colony, pos, player).refused()) {
            return;
        }
        PacketDistributor.sendToPlayer(player, ColonyOverviews.fresh(player, colony));
    }

    private record PendingZone(ServerLevel level, CreateZonePacket request, List<Facilities.Source> affected,
                               long expires) {
    }

    private static final WeakHashMap<ServerPlayer, PendingZone> PENDING_ZONES = new WeakHashMap<>();

    private static void createZone(CreateZonePacket packet, ServerPlayer player) {
        ColonyAuthority.forColony(player, packet.colonyId()).ifPresent(authority -> {
            ServerLevel level = player.serverLevel();
            PendingZone pending = PENDING_ZONES.remove(player);
            if (packet.confirmation().isPresent() && (pending == null || pending.level() != level
                    || pending.expires() < level.getGameTime() || !pending.request().equals(packet))) {
                return;
            }
            ColonySettings settings = ColonySettings.load(Reader.of(packet.settings()));
            var affected = ZoneDrafts.affected(level, packet.delegation(), packet.min(), packet.max(), settings);
            if (!affected.isEmpty() && (packet.confirmation().isEmpty() || !affected.equals(pending.affected()))) {
                var confirm = new CreateZonePacket(packet.colonyId(), packet.min(), packet.max(), packet.delegation(),
                    packet.settings(), Optional.of(UUID.randomUUID()));
                PENDING_ZONES.put(player, new PendingZone(level, confirm, affected, level.getGameTime() + 1800));
                PacketDistributor.sendToPlayer(player,
                    new ZoneImpactPacket(confirm, ZoneDrafts.describeAffected(level, affected)));
                return;
            }
            ZoneDrafts.zone(level, authority.colony(), packet.delegation(), packet.min(), packet.max(), settings)
                .ifPresent(zone -> opened(player, authority.colony(), zone.id()));
        });
    }

    private static void createPath(CreatePathPacket packet, ServerPlayer player) {
        ColonyAuthority.forColony(player, packet.colonyId()).ifPresent(authority -> ZoneDrafts
            .path(player.serverLevel(), authority.colony(), packet.delegation(), packet.points(),
                ColonySettings.load(Reader.of(packet.settings())))
            .ifPresent(path -> opened(player, authority.colony(), path.id())));
    }

    private static void sweepBlocks(SweepBlocksPacket packet, ServerPlayer player) {
        ColonyAuthority.forColony(player, packet.colonyId()).ifPresent(authority -> {
            Colony colony = authority.colony();
            if (!BlockSweep.take(player.serverLevel(), colony, packet.min(), packet.max(),
                    Set.copyOf(packet.kinds()), player).refused()) {
                PacketDistributor.sendToPlayer(player, ColonyOverviews.fresh(player, colony));
            }
        });
    }

    private static void opened(ServerPlayer player, Colony colony, UUID id) {
        CardOpen.send(player, colony.id(), id);
        PacketDistributor.sendToPlayer(player, ColonyOverviews.fresh(player, colony));
    }

    static ColonyOverviewPacket buildOverview(ServerLevel level, Colony colony) {
        List<ZoneSnapshot> zones = new ArrayList<>();
        for (ColonyZone zone : ColonyFront.of(colony).zonesIn(level)) {
            List<ZoneRing> rings = new ArrayList<>();
            HeldFacilities.find(colony, zone.id()).ifPresent(held -> rings.addAll(held.rings(colony.view(level))));
            zones.add(ZoneSnapshot.of(zone, rings.stream().limit(64).toList()));
        }
        List<ColonyPath> paths = ColonyFront.of(colony).paths().stream()
            .filter(path -> path.dimension().equals(level.dimension())).toList();
        ColonyView view = colony.view(level);
        var sources = Enrollments.ghostSource(colony).values();
        List<Ghost> ghosts = new ArrayList<>();
        for (GhostSource source : sources) {
            ghosts.addAll(source.ghosts(view));
        }
        List<Placards.Note> notes = new ArrayList<>();
        List<Placard> placards = new ArrayList<>();
        // A zone's or a path's card over it says what its card says it is doing.
        for (ColonyZone zone : ColonyFront.of(colony).zonesIn(level)) {
            noted(notes, zone.id(), HeldFacilities.find(colony, zone.id())
                .map(held -> held.lines(level, java.util.Map.of())).orElse(List.of()));
        }
        for (ColonyPath path : paths) {
            noted(notes, path.id(), HeldFacilities.find(colony, path.id())
                .map(held -> held.lines(level, java.util.Map.of())).orElse(List.of()));
        }
        for (GhostSource source : sources) {
            for (Placard placard : source.placards(view)) {
                placards.add(stalled(colony, level, placard));
            }
        }
        return new ColonyOverviewPacket(ColonyGround.cells(level, colony), zones, List.copyOf(ghosts),
            paths.stream().map(ColonyPath::save).toList(), notes, placards,
            sources.stream().flatMap(source -> source.previewRecipes(view).stream()).limit(512).toList());
    }

    // A card over a block or a site also tells the work standing still inside it, unless it tells that itself.
    private static Placard stalled(Colony colony, ServerLevel level, Placard placard) {
        if (!(placard.outline() instanceof Placard.Outline.Box box)) {
            return placard;
        }
        BoundingBox bounds = BoundingBox.fromCorners(box.min(), box.max());
        List<Line> lines = new ArrayList<>(placard.lines());
        for (Line line : ColonyStalls.lines(colony, level, bounds::isInside)) {
            if (!lines.contains(line)) {
                lines.add(line);
            }
        }
        return lines.size() == placard.lines().size() ? placard : new Placard(placard.id(), placard.outline(), lines);
    }

    static BlockPos corner(Placard.Outline outline) {
        return switch (outline) {
            case Placard.Outline.Box box -> box.min();
            case Placard.Outline.Path path -> path.points().getFirst();
        };
    }

    private static void noted(List<Placards.Note> notes, UUID id, List<Line> said) {
        if (!said.isEmpty()) {
            notes.add(new Placards.Note(id, said));
        }
    }

    private static void requestLookAt(RequestLookAtPacket packet, ServerPlayer player) {
        ColonyAuthority.forColony(player, packet.colonyId()).map(ColonyAuthority::colony)
            .ifPresent(colony -> PacketDistributor.sendToPlayer(player, ColonyOverviews.looks(player, colony)));
    }

}
