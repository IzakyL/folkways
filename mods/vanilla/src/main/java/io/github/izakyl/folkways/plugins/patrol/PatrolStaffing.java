package io.github.izakyl.folkways.plugins.patrol;

import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.resident.body.Bodies;
import io.github.izakyl.folkways.core.api.resident.body.Body;
import io.github.izakyl.folkways.front.api.Fronts;
import io.github.izakyl.folkways.front.api.ZoneView;
import io.github.izakyl.folkways.front.api.notice.Line;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaConfig;
import io.github.izakyl.folkways.plugins.combat.DefenseControl;
import io.github.izakyl.folkways.plugins.combat.weapon.LoadoutProfile;
import io.github.izakyl.folkways.plugins.combat.weapon.LoadoutProfiles;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/** Desired headcounts and allowed profiles; reconciliation preserves valid incumbents before filling vacancies. */
public final class PatrolStaffing {
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("folkways", "patrol_staffing");
    public static final int UNLOAD_GRACE = 200;
    public static final int MAX_COUNT = 256;
    private static final String KEY = "folkways.staffing.";

    public record Position(UUID id, Optional<UUID> member, String status) {
    }

    private static final class Composition {
        private final Set<UUID> allowed = new LinkedHashSet<>();
        private final List<Position> positions = new ArrayList<>();
        private long revision;
    }

    private final Colony colony;
    private final Map<UUID, Composition> compositions = new LinkedHashMap<>();
    private final Map<UUID, Long> missingSince = new HashMap<>();

    public PatrolStaffing(Colony colony) {
        this.colony = colony;
        for (Tag value : colony.kept(ID).getList("targets", Tag.TAG_COMPOUND)) {
            CompoundTag row = (CompoundTag) value;
            if (!row.hasUUID("target")) {
                continue;
            }
            Composition composition = new Composition();
            for (Tag entry : row.getList("allowed", Tag.TAG_COMPOUND)) {
                CompoundTag profile = (CompoundTag) entry;
                if (profile.hasUUID("id")) {
                    composition.allowed.add(profile.getUUID("id"));
                }
            }
            resize(composition, Math.clamp(row.getInt("count"), 0, MAX_COUNT));
            ListTag members = row.getList("positions", Tag.TAG_COMPOUND);
            for (int index = 0; index < Math.min(members.size(), composition.positions.size()); index++) {
                CompoundTag member = members.getCompound(index);
                if (member.hasUUID("id")) {
                    composition.positions.set(index, new Position(member.getUUID("id"),
                        uuid(member, "member"), "unfilled"));
                }
            }
            compositions.put(row.getUUID("target"), composition);
        }
        for (Tag value : colony.kept(ID).getList("operators", Tag.TAG_COMPOUND)) {
            CompoundTag row = (CompoundTag) value;
            if (row.hasUUID("target") && station(row.getUUID("target"))) {
                Composition composition = new Composition();
                composition.positions.add(new Position(UUID.randomUUID(), uuid(row, "member"), "unfilled"));
                compositions.put(row.getUUID("target"), composition);
            }
        }
        save();
    }

    private static Optional<UUID> uuid(CompoundTag tag, String key) {
        return tag.hasUUID(key) ? Optional.of(tag.getUUID(key)) : Optional.empty();
    }

    public static PatrolStaffing of(Colony colony) {
        return colony.service(ID, PatrolStaffing.class).orElseThrow();
    }

    private boolean station(UUID target) {
        return colony.holding(target).filter(holding -> holding.owner().equals(SentryPosts.STATION)).isPresent();
    }

    public boolean configured(UUID target) {
        return colony.holding(target).filter(holding -> SentryPosts.post(holding.owner())
            || holding.owner().equals(PatrolContent.BEAT)).isPresent();
    }

    public Set<UUID> allowed(UUID target) {
        Composition composition = compositions.get(target);
        return composition == null ? Set.of() : Set.copyOf(composition.allowed);
    }

    public int count(UUID target) {
        return station(target) ? 1 : positions(target).size();
    }

    public long revision(UUID target) {
        Composition composition = compositions.get(target);
        return composition == null ? 0 : composition.revision;
    }

    public List<Position> positions(UUID target) {
        Composition composition = compositions.get(target);
        return composition == null ? List.of() : List.copyOf(composition.positions);
    }

    public List<UUID> members(UUID target) {
        return positions(target).stream().filter(position -> position.status().equals("filled"))
            .flatMap(position -> position.member().stream()).toList();
    }

    boolean effective(ServerLevel level, UUID target, UUID resident) {
        List<Position> positions = positions(target);
        for (int index = 0; index < positions.size(); index++) {
            Position position = positions.get(index);
            if (position.member().filter(resident::equals).isPresent()) {
                return status(level.getServer(), target, index, resident).equals("filled");
            }
        }
        return false;
    }

    public boolean claimed(UUID resident) {
        return compositions.values().stream().flatMap(value -> value.positions.stream())
            .anyMatch(position -> position.member().filter(resident::equals).isPresent());
    }

    public Optional<UUID> targetOf(UUID resident) {
        return compositions.keySet().stream().filter(target -> members(target).contains(resident)).findFirst();
    }

    public int shortage(UUID target) {
        return Math.max(0, count(target) - members(target).size());
    }

    // Compatible unmet demand, shared by all allowed profiles; it is not additive across profiles.
    public int shortageFor(UUID profile) {
        return compositions.keySet().stream().filter(target -> station(target) || allowed(target).contains(profile))
            .mapToInt(this::shortage).sum();
    }

    private void thread() {
        if (!ServerLifecycleHooks.getCurrentServer().isSameThread()) {
            throw new IllegalStateException("patrol staffing requires the server thread");
        }
    }

    private boolean target(ServerLevel level, UUID target) {
        return Fronts.of(colony).paths().stream()
            .anyMatch(path -> path.id().equals(target) && path.delegation().equals(PatrolContent.BEAT)
                && path.dimension().equals(level.dimension()))
            || SentryPosts.of(colony).zone(level, target).isPresent();
    }

    public void configure(ServerLevel level, UUID target, Set<UUID> allowed, int count) {
        thread();
        if (station(target) || !target(level, target) || count < 0 || count > MAX_COUNT
            || allowed.stream().anyMatch(profile -> LoadoutProfiles.of(colony).get(profile).isEmpty()
                && !allowed(target).contains(profile))) {
            throw new IllegalArgumentException("invalid staffing target, profiles or headcount");
        }
        Composition composition = compositions.computeIfAbsent(target, ignored -> new Composition());
        composition.allowed.clear();
        allowed.stream().sorted().forEach(composition.allowed::add);
        long now = level.getServer().overworld().getGameTime();
        for (int index = 0; index < composition.positions.size(); index++) {
            Position position = composition.positions.get(index);
            if (position.member().isPresent() && !retain(
                status(level.getServer(), target, index, position.member().get()), position.member().get(), now)) {
                composition.positions.set(index, new Position(position.id(), Optional.empty(), "unfilled"));
            }
        }
        resize(composition, count);
        composition.revision++;
        tick(level.getServer());
        save();
    }

    private static void resize(Composition composition, int count) {
        while (composition.positions.size() > count) {
            int removed = composition.positions.size() - 1;
            for (int index = removed; index >= 0; index--) {
                if (composition.positions.get(index).member().isEmpty()) {
                    removed = index;
                    break;
                }
            }
            composition.positions.remove(removed);
            for (int index = removed; index < composition.positions.size(); index++) {
                Position position = composition.positions.get(index);
                composition.positions.set(index,
                    new Position(UUID.randomUUID(), position.member(), position.status()));
            }
        }
        while (composition.positions.size() < count) {
            composition.positions.add(new Position(UUID.randomUUID(), Optional.empty(), "unfilled"));
        }
    }

    private Optional<Body> body(MinecraftServer server, UUID id) {
        for (ServerLevel level : server.getAllLevels()) {
            if (level.getEntity(id) != null) {
                return Bodies.of(level.getEntity(id));
            }
        }
        return Optional.empty();
    }

    private boolean eligible(Body body) {
        return body.mob().isAlive() && !body.mob().isRemoved()
            && colony.residents().stream().anyMatch(resident -> resident.id().equals(body.id()))
            && body.licences().of(PatrolContent.trade()).isPresent();
    }

    private String status(MinecraftServer server, UUID target, int index, UUID resident) {
        Optional<LoadoutProfile> assigned = LoadoutProfiles.of(colony).assigned(resident);
        if (assigned.isEmpty() || !station(target) && !allowed(target).contains(assigned.get().id())) {
            return "mismatch";
        }
        if (colony.residents().stream().noneMatch(member -> member.id().equals(resident))) {
            return "departed";
        }
        Optional<Body> body = body(server, resident);
        if (body.isEmpty()) {
            return "unloaded";
        }
        if (!eligible(body.get())) {
            return "unavailable";
        }
        ServerLevel level = (ServerLevel) body.get().mob().level();
        if (!target(level, target)) {
            return "unloaded";
        }
        Optional<ZoneView> zone = SentryPosts.of(colony).zone(level, target);
        if (zone.isPresent()) {
            if (!(body.get().mob() instanceof ResidentEntity)) {
                return "unsupported_kind";
            }
            if (!station(target) && !assigned.get().hasRanged()) {
                return "ranged_required";
            }
        }
        String restriction = restriction(server, target, index);
        if (!restriction.equals("unfilled")) {
            return restriction;
        }
        DefenseControl control = DefenseControl.of(colony);
        if (control.current(resident).isPresent() || control.reserved(resident) || control.failedWaiting(resident)) {
            return "controlled";
        }
        return "filled";
    }

    private String restriction(MinecraftServer server, UUID target, int index) {
        if (!station(target)) {
            if (allowed(target).isEmpty()) {
                return "no_profiles";
            }
            if (allowed(target).stream().noneMatch(id -> LoadoutProfiles.of(colony).get(id).isPresent())) {
                return "missing_profile";
            }
        }
        for (ServerLevel level : server.getAllLevels()) {
            Optional<ZoneView> zone = SentryPosts.of(colony).zone(level, target);
            if (zone.isPresent()) {
                if (zone.get().delegation().equals(SentryPosts.ZONE)) {
                    if (zone.get().cells().size() > FolkwaysVanillaConfig.maxSentryCells()) {
                        return "too_large";
                    }
                    if (allowed(target).stream().noneMatch(id -> LoadoutProfiles.of(colony).get(id)
                        .filter(LoadoutProfile::hasRanged).isPresent())) {
                        return "ranged_required";
                    }
                }
                if (index >= SentryPosts.of(colony).capacity(level, zone.get())) {
                    return "capacity";
                }
            }
        }
        return "unfilled";
    }

    public void tick(MinecraftServer server) {
        thread();
        boolean dirty = compositions.keySet().removeIf(id -> !configured(id));
        for (UUID station : SentryPosts.of(colony).stationPosts()) {
            if (!compositions.containsKey(station)) {
                Composition composition = new Composition();
                resize(composition, 1);
                compositions.put(station, composition);
                dirty = true;
            }
        }
        long now = server.overworld().getGameTime();
        List<UUID> targets = compositions.keySet().stream()
            .sorted(Comparator.comparing((UUID id) -> !station(id)).thenComparing(Comparator.naturalOrder())).toList();
        Set<UUID> used = new HashSet<>();
        List<UUID> candidates = colony.residents().stream().map(resident -> resident.id()).sorted()
            .filter(id -> body(server, id).filter(this::eligible).isPresent()).toList();
        // Reserve incumbents globally before stations and then zones/routes receive idle residents.
        for (UUID target : targets) {
            Composition composition = compositions.get(target);
            for (int index = 0; index < composition.positions.size(); index++) {
                Position old = composition.positions.get(index);
                Optional<UUID> member = old.member();
                String reason = restriction(server, target, index);
                if (member.isPresent()) {
                    UUID id = member.get();
                    reason = status(server, target, index, id);
                    boolean retain = retain(reason, id, now);
                    if (used.contains(id) || !retain) {
                        member = Optional.empty();
                        reason = restriction(server, target, index);
                    } else {
                        used.add(id);
                    }
                }
                Position next = new Position(old.id(), member, reason);
                dirty |= !next.member().equals(old.member());
                composition.positions.set(index, next);
            }
        }
        for (UUID target : targets) {
            Composition composition = compositions.get(target);
            for (int index = 0; index < composition.positions.size(); index++) {
                Position position = composition.positions.get(index);
                if (position.member().isPresent()) {
                    continue;
                }
                for (UUID id : candidates) {
                    if (!used.contains(id) && status(server, target, index, id).equals("filled")) {
                        composition.positions.set(index, new Position(position.id(), Optional.of(id), "filled"));
                        used.add(id);
                        dirty = true;
                        break;
                    }
                }
            }
        }
        missingSince.keySet().removeIf(id -> !used.contains(id));
        if (dirty) {
            save();
        }
    }

    private boolean retain(String reason, UUID resident, long now) {
        if (reason.equals("unloaded")) {
            missingSince.putIfAbsent(resident, now);
            return now - missingSince.get(resident) < UNLOAD_GRACE;
        }
        missingSince.remove(resident);
        return reason.equals("filled") || reason.equals("controlled") || reason.equals("capacity")
            || reason.equals("ranged_required") || reason.equals("too_large");
    }

    private void save() {
        CompoundTag saved = new CompoundTag();
        ListTag targets = new ListTag();
        ListTag operators = new ListTag();
        compositions.forEach((target, composition) -> {
            if (station(target)) {
                CompoundTag operator = new CompoundTag();
                operator.putUUID("target", target);
                composition.positions.getFirst().member().ifPresent(id -> operator.putUUID("member", id));
                operators.add(operator);
                return;
            }
            CompoundTag row = new CompoundTag();
            row.putUUID("target", target);
            row.putInt("count", composition.positions.size());
            ListTag allowed = new ListTag();
            composition.allowed.forEach(id -> {
                CompoundTag entry = new CompoundTag();
                entry.putUUID("id", id);
                allowed.add(entry);
            });
            row.put("allowed", allowed);
            ListTag positions = new ListTag();
            for (Position position : composition.positions) {
                CompoundTag entry = new CompoundTag();
                entry.putUUID("id", position.id());
                position.member().ifPresent(id -> entry.putUUID("member", id));
                positions.add(entry);
            }
            row.put("positions", positions);
            targets.add(row);
        });
        saved.put("targets", targets);
        saved.put("operators", operators);
        colony.keep(ID, saved);
    }

    public Component summary(UUID target) {
        int shortage = shortage(target);
        Component result = Component.translatable(KEY + "headcount", members(target).size(), count(target));
        if (shortage == 0) {
            return result;
        }
        String reason = positions(target).stream().filter(position -> !position.status().equals("filled"))
            .map(Position::status).filter(status -> !status.equals("unfilled")).findFirst().orElse("unfilled");
        return result.copy().append(" · ").append(Component.translatable(KEY + "vacancies", shortage,
            Component.translatable(KEY + reason)));
    }

    public List<Line> lines(UUID target) {
        return List.of(Line.text(summary(target)));
    }
}
