package io.github.izakyl.folkways.plugins.patrol;

import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.Release;
import io.github.izakyl.folkways.front.api.notice.Attempt;
import io.github.izakyl.folkways.front.api.notice.Notice;
import io.github.izakyl.folkways.core.api.colony.ColonyView;
import io.github.izakyl.folkways.core.api.colony.Held;
import io.github.izakyl.folkways.core.api.terms.Footing;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
import io.github.izakyl.folkways.core.api.vocation.Vocations;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.Urge;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.front.api.Facing;
import io.github.izakyl.folkways.front.api.Fronts;
import io.github.izakyl.folkways.front.api.ZoneRing;
import io.github.izakyl.folkways.front.api.ZoneView;
import io.github.izakyl.folkways.front.api.notice.Line;
import io.github.izakyl.folkways.front.api.panel.Board;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaConfig;
import io.github.izakyl.folkways.plugins.combat.DefenseControl;
import io.github.izakyl.folkways.plugins.combat.SpottedTargets;
import io.github.izakyl.folkways.plugins.combat.WeaponStations;
import io.github.izakyl.folkways.plugins.combat.weapon.LoadoutProfile;
import io.github.izakyl.folkways.plugins.combat.weapon.LoadoutProfiles;
import io.github.izakyl.folkways.plugins.combat.weapon.Weapons;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Executes aggregate staffing; active duties and station leases belong to the loaded world. */
public final class SentryPosts implements Facing {
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("folkways", "sentry_posts");
    public static final ResourceLocation ZONE = ResourceLocation.fromNamespaceAndPath("folkways", "sentry");

    public static final ResourceLocation STATION = ResourceLocation.fromNamespaceAndPath("folkways", "weapon_station");

    private final Colony colony;
    private final Map<UUID, SentryNode> active = new LinkedHashMap<>();

    public SentryPosts(Colony colony) {
        this.colony = colony;
    }

    public static SentryPosts of(Colony colony) {
        return colony.service(ID, SentryPosts.class).orElseThrow();
    }

    public boolean assigned(UUID resident) {
        return PatrolStaffing.of(colony).targetOf(resident)
            .flatMap(colony::holding).filter(holding -> post(holding.owner())).isPresent();
    }

    public static boolean post(ResourceLocation owner) {
        return owner.equals(ZONE) || owner.equals(STATION);
    }

    public List<UUID> stationPosts() {
        return colony.holdings().stream().filter(holding -> holding.owner().equals(STATION))
            .map(holding -> holding.id()).toList();
    }

    public List<WeaponStations.Station> stations(ServerLevel level, ZoneView zone) {
        if (!(zone instanceof StationPost post)) {
            return List.of();
        }
        return WeaponStations.find(level, zone).stream()
            .filter(station -> station.entity().filter(post.entity()::equals).isPresent()).toList();
    }

    public Optional<ZoneView> zone(ServerLevel level, UUID id) {
        var holding = colony.holding(id).filter(value -> value.owner().equals(STATION));
        if (holding.isPresent() && holding.get().what() instanceof Held.Entity held) {
            var entity = level.getEntity(held.entity());
            if (entity != null && entity.isAlive() && !entity.isRemoved()) {
                return Optional.of(StationPost.of(id, entity, Fronts.of(colony).settings(PatrolContent.ID)));
            }
            return Optional.empty();
        }
        return Fronts.of(colony).zonesIn(level).stream()
            .filter(zone -> zone.id().equals(id) && zone.delegation().equals(ZONE)).map(ZoneView.class::cast)
            .findFirst();
    }

    public List<BlockPos> stances(ServerLevel level, ZoneView zone) {
        return stances(level, zone, null);
    }

    List<BlockPos> stances(ServerLevel level, ZoneView zone, Mob operator) {
        var station = zone instanceof StationPost post ? level.getEntity(post.entity()) : null;
        return zone.cells().stream().flatMap(cell -> List.of(cell, cell.above()).stream())
            .distinct()
            .filter(cell -> zone.cells().contains(cell) || zone.cells().contains(cell.below()))
            .filter(cell -> level.hasChunkAt(cell) && Footing.withFeetAt(level, cell))
            .filter(cell -> {
                if (!(zone instanceof StationPost)) {
                    return true;
                }
                if (station == null) {
                    return false;
                }
                Vec3 feet = Vec3.atBottomCenterOf(cell);
                AABB bounds = operator == null ? PersonBody.RESIDENT.get().getDimensions().makeBoundingBox(feet)
                    : operator.getBoundingBox().move(feet.subtract(operator.position()));
                return !bounds.intersects(station.getBoundingBox())
                    && bounds.inflate(1, 0, 1).intersects(station.getBoundingBox())
                    && level.noCollision(operator, bounds);
            })
            .sorted(Comparator.comparingLong(BlockPos::asLong)).toList();
    }

    public int capacity(ServerLevel level, ZoneView zone) {
        if (zone.delegation().equals(STATION)) {
            return stances(level, zone).isEmpty() ? 0 : 1;
        }
        long area = zone.cells().stream().map(cell -> new BlockPos(cell.getX(), 0, cell.getZ())).distinct().count();
        return Math.min(Math.max(1, (int) area / 4), stances(level, zone).size());
    }

    public List<UUID> residents(UUID zone) {
        return PatrolStaffing.of(colony).members(zone);
    }

    private Optional<UUID> assignment(UUID resident) {
        return PatrolStaffing.of(colony).targetOf(resident)
            .filter(target -> colony.holding(target).filter(holding -> post(holding.owner())).isPresent());
    }

    private int stanceIndex(UUID zone, UUID resident) {
        List<PatrolStaffing.Position> positions = PatrolStaffing.of(colony).positions(zone);
        for (int index = 0; index < positions.size(); index++) {
            if (positions.get(index).member().filter(resident::equals).isPresent()) {
                return index;
            }
        }
        return -1;
    }

    Optional<UUID> slot(UUID zone, UUID resident) {
        return PatrolStaffing.of(colony).positions(zone).stream()
            .filter(position -> position.member().filter(resident::equals).isPresent())
            .map(PatrolStaffing.Position::id).findFirst();
    }

    private boolean ranged(UUID resident) {
        return LoadoutProfiles.of(colony).assigned(resident).filter(LoadoutProfile::hasRanged).isPresent();
    }

    public String status(ServerLevel level, UUID resident, ZoneView zone) {
        PatrolStaffing staffing = PatrolStaffing.of(colony);
        if (LoadoutProfiles.of(colony).assigned(resident).filter(profile -> zone.delegation().equals(STATION)
            || staffing.allowed(zone.id()).contains(profile.id())).isEmpty()) {
            return "profile_mismatch";
        }
        if (!zone.delegation().equals(STATION) && !ranged(resident)) {
            return "ranged_required";
        }
        if (level.getEntity(resident) != null && !(level.getEntity(resident) instanceof ResidentEntity)) {
            return "unsupported_kind";
        }
        if (!(level.getEntity(resident) instanceof ResidentEntity body) || !body.isAlive() || body.isRemoved()) {
            return "unloaded";
        }
        if (body.licences().of(Vocations.required(PatrolContent.VOCATION)).isEmpty()) {
            return "trade_required";
        }
        if (zone.delegation().equals(ZONE) && zone.cells().size() > FolkwaysVanillaConfig.maxSentryCells()) {
            return "too_large";
        }
        if (capacity(level, zone) == 0) {
            return "no_footing";
        }
        if (stanceIndex(zone.id(), resident) < 0 || stanceIndex(zone.id(), resident) >= capacity(level, zone)) {
            return "full";
        }
        DefenseControl control = DefenseControl.of(colony);
        if (control.current(resident).isPresent() || control.reserved(resident) || control.failedWaiting(resident)) {
            return "controlled";
        }
        if (zone.delegation().equals(STATION) && stations(level, zone).isEmpty()) {
            return "station_unavailable";
        }
        SentryNode node = active.get(resident);
        return node == null ? "returning" : node.status();
    }

    boolean valid(ServerLevel level, UUID resident, UUID zoneId) {
        Optional<ZoneView> zone = zone(level, zoneId);
        return assignment(resident).filter(zoneId::equals).isPresent() && zone.isPresent()
            && (!PatrolStaffing.of(colony).configured(zoneId)
                || PatrolStaffing.of(colony).effective(level, zoneId, resident))
            && List.of("returning", "watching", "fighting", "no_weapon", "no_ammo", "station_busy", "blocked",
                "station_unavailable")
            .contains(status(level, resident, zone.get()));
    }

    boolean posting(ServerLevel level, UUID resident) {
        return assignment(resident).filter(zone -> valid(level, resident, zone)).isPresent();
    }

    public List<Urge> urges(Worker worker, ColonyView view) {
        UUID resident = worker.resident().id();
        UUID assigned = assignment(resident).orElse(null);
        // A sentry sets out for its own supplies when the colony's plan sends it, and takes up its post again after.
        if (assigned == null || !valid(view.level(), resident, assigned) || Weapons.awaitingSupply(worker)
            || PatrolContent.suppliesOf(colony).filter(supplies -> supplies.underway(resident)).isPresent()) {
            return List.of();
        }
        ZoneView zone = zone(view.level(), assigned).orElseThrow();
        List<BlockPos> cells = stances(view.level(), zone, worker.body());
        int index = stanceIndex(assigned, resident);
        if (index < 0 || index >= cells.size()) {
            return List.of();
        }
        BlockPos cell = cells.get(index);
        WorldPos at = zone.cells().contains(cell) ? zone.at(cell) : zone.at(cell.below()).above();
        Stances standing = zone instanceof StationPost
            ? Stances.of(view.level(), cells).orElseThrow() : Stances.at(at);
        ResourceLocation intent = ResourceLocation.fromNamespaceAndPath("folkways",
            "sentry/" + assigned + "/" + (zone instanceof StationPost post ? post.anchor().cell() : cell).asLong()
                + "/" + slot(assigned, resident)
                .map(UUID::toString).orElse("legacy"));
        boolean fighting = SpottedTargets.of(colony).observe(worker.body()).stream()
            .anyMatch(target -> worker.body().distanceTo(target) <= Weapons.rangedRange(worker, target));
        SentryNode running = active.get(resident);
        fighting |= running != null && running.status().equals("fighting");
        double weight = fighting && worker.body().getHealth() > worker.body().getMaxHealth() * .35 ? 2.5 : 1.5;
        return List.of(new Urge(intent, weight, true, () -> {
            SentryNode node = new SentryNode(this, colony, resident, assigned, at, view.dimension(),
                zone instanceof StationPost post ? Optional.of(post.anchor()) : Optional.empty(), standing);
            active.put(resident, node);
            return node;
        }));
    }

    public void tick(MinecraftServer server) {
        for (Map.Entry<UUID, SentryNode> entry : List.copyOf(active.entrySet())) {
            SentryNode node = entry.getValue();
            ServerLevel level = server.getLevel(node.dimension());
            if (level != null) {
                node.maintain(level);
            }
            if (level == null || !node.ready(level)) {
                release(entry.getKey());
            }
        }

    }

    void ended(UUID resident, SentryNode node) {
        active.remove(resident, node);
    }

    private void release(UUID resident) {
        SentryNode node = active.remove(resident);
        if (node != null) {
            node.close();
        }
    }

    public void close() {
        for (UUID resident : List.copyOf(active.keySet())) {
            release(resident);
        }
    }

    @Override
    public List<Line> zoneLines(ZoneView zone, ColonyView view) {
        if (!zone.delegation().equals(ZONE)) {
            return List.of();
        }
        List<Line> lines = new ArrayList<>();
        lines.add(Line.text(Component.translatable("folkways.sentry.capacity", residents(zone.id()).size(),
            capacity(view.level(), zone))));
        lines.addAll(PatrolStaffing.of(colony).lines(zone.id()));
        for (UUID resident : residents(zone.id())) {
            lines.add(Line.text(Component.translatable("folkways.sentry.resident", name(view.level(), resident),
                Component.translatable("folkways.sentry." + status(view.level(), resident, zone)))));
            SentryNode node = active.get(resident);
            if (node != null && node.targetName().isPresent()) {
                lines.add(Line.text(Component.translatable("folkways.sentry.target", node.targetName().get())));
            }
        }
        lines.add(Line.text(Component.translatable("folkways.sentry.sight",
            FolkwaysVanillaConfig.maximumSightRadius())));
        return List.copyOf(lines);
    }

    @Override
    public List<ZoneRing> zoneRings(ZoneView zone, ColonyView view) {
        if (!zone.delegation().equals(ZONE)) {
            return List.of();
        }
        List<BlockPos> cells = stances(view.level(), zone);
        List<ZoneRing> rings = new ArrayList<>();
        List<UUID> posted = residents(zone.id());
        int count = Math.min(posted.size(), Math.min(capacity(view.level(), zone), cells.size()));
        for (int index = 0; index < count; index++) {
            int stance = stanceIndex(zone.id(), posted.get(index));
            if (stance < 0 || stance >= Math.min(capacity(view.level(), zone), cells.size())) {
                continue;
            }
            double radius = FolkwaysVanillaConfig.maximumSightRadius();
            BlockPos center = cells.get(stance);
            if (view.level().getEntity(posted.get(index)) instanceof ResidentEntity body) {
                radius = SpottedTargets.maximumSight(body);
                if (WeaponStations.ownsSeat(body)) {
                    center = WorldSpaces.storage(view.level(), WorldSpaces.at(body)).orElse(body.blockPosition());
                }
            }
            rings.add(new ZoneRing(center, radius));
        }
        if (rings.isEmpty()) {
            rings.add(new ZoneRing(zone.cells().iterator().next().above(),
                FolkwaysVanillaConfig.maximumSightRadius()));
        }
        return List.copyOf(rings);
    }

    private Component name(ServerLevel level, UUID resident) {
        return level.getEntity(resident) == null ? Component.literal(resident.toString().substring(0, 8))
            : level.getEntity(resident).getDisplayName();
    }

    @Override
    public Optional<Board> board(ResourceLocation page, ColonyView view) {
        if (!page.equals(ID)) {
            return Optional.empty();
        }
        List<Board.Row> rows = new ArrayList<>();
        for (ZoneView zone : Fronts.of(colony).zonesIn(view.level())) {
            if (!zone.delegation().equals(ZONE)) {
                continue;
            }
            BlockPos at = zone.cells().iterator().next();
            rows.add(new Board.Row(new ItemStack(Items.BOW),
                Component.translatable("folkways.delegation.folkways.sentry"),
                Component.translatable("folkways.sentry.capacity", residents(zone.id()).size(),
                    capacity(view.level(), zone)),
                List.of(new Board.Act.Ping(at))));
            rows.add(Board.Row.of(ItemStack.EMPTY, Component.translatable("folkways.staffing.headcount_label"),
                PatrolStaffing.of(colony).summary(zone.id())));
        }
        for (UUID id : stationPosts().stream().sorted().toList()) {
            var holding = colony.holding(id).orElseThrow();
            Component title = Component.translatable("folkways.delegation.folkways.weapon_station");
            if (holding.what() instanceof Held.Entity held && view.level().getEntity(held.entity()) != null) {
                var entity = view.level().getEntity(held.entity());
                title = entity.getDisplayName().copy().append(" · " + entity.blockPosition().toShortString());
            }
            Component detail = PatrolStaffing.of(colony).summary(id);
            Optional<ZoneView> zone = zone(view.level(), id);
            if (zone.isEmpty()) {
                detail = detail.copy().append(" · ").append(Component.translatable("folkways.sentry.unloaded"));
            } else {
                for (UUID resident : residents(id)) {
                    detail = detail.copy().append(" · ").append(name(view.level(), resident)).append(": ")
                        .append(Component.translatable(
                            "folkways.sentry." + status(view.level(), resident, zone.get())));
                }
            }
            rows.add(new Board.Row(new ItemStack(Items.DISPENSER), title, detail,
                List.of(new Board.Act.Do("withdraw/" + id, "folkways.sentry.withdraw_station"))));
        }
        return Optional.of(new Board(List.of(), rows, Optional.of(Component.translatable("folkways.sentry.empty"))));
    }

    @Override
    public Attempt act(ResourceLocation page, String action, Optional<BlockPos> at, ColonyView view) {
        if (page.equals(ID) && action.startsWith("withdraw/")) {
            try {
                UUID target = UUID.fromString(action.substring("withdraw/".length()));
                if (colony.holding(target).filter(holding -> holding.owner().equals(STATION)).isPresent()) {
                    colony.release(target, Release.LET_GO);
                    PatrolStaffing.of(colony).tick(view.level().getServer());
                    tick(view.level().getServer());
                    return Attempt.went();
                }
            } catch (IllegalArgumentException ignored) {
            }
        }
        return new Attempt.Refused(new Notice("folkways.staffing.rejected", List.of()));
    }
}
