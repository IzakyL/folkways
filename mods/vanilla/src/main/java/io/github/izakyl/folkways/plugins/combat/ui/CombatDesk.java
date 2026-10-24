package io.github.izakyl.folkways.plugins.combat.ui;

import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.resident.body.Bodies;
import io.github.izakyl.folkways.core.api.resident.body.Body;
import io.github.izakyl.folkways.core.api.terms.Footing;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
import io.github.izakyl.folkways.front.api.ui.Pages;
import io.github.izakyl.folkways.plugins.combat.command.ResidentCommands;
import io.github.izakyl.folkways.plugins.combat.control.CombatSelectionOwnership;
import io.github.izakyl.folkways.plugins.combat.group.Deployment;
import io.github.izakyl.folkways.plugins.combat.group.UnitGroup;
import io.github.izakyl.folkways.plugins.combat.group.UnitGroups;
import io.github.izakyl.folkways.plugins.combat.weapon.PatrolSupplies;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.PathfindingContext;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** A keeper's page draft. Every action rechecks the currently authorized colony; no command is restored on restart. */
public final class CombatDesk {
    private record Point(WorldPos at, Vec3 front) { }
    private static final class Draft {
        final Set<UUID> picked = new LinkedHashSet<>();
        final List<Point> points = new ArrayList<>();
        UUID group;
        Component message = Component.translatable("folkways.combat.help");
    }
    private final Colony colony;
    private final Map<UUID, Draft> drafts = new HashMap<>();
    private final Map<UUID, Integer> groupNumbers = new HashMap<>();
    private int nextGroupNumber = 1;

    CombatDesk(Colony colony) { this.colony = colony; }
    public static CombatDesk of(Colony colony) { return colony.service(CombatContent.ID, CombatDesk.class).orElseThrow(); }

    private boolean authorized(ServerPlayer player) {
        return player.getServer().isSameThread() && Pages.colonyOf(player).filter(found -> found.id().equals(colony.id())).isPresent();
    }
    private Draft draft(ServerPlayer player) { return drafts.computeIfAbsent(player.getUUID(), ignored -> new Draft()); }

    /** Called only by the page's server controls; identifiers are validated against this colony on every click. */
    public boolean act(ServerPlayer player, String action, String value) {
        if (!authorized(player)) { return false; }
        Draft draft = draft(player);
        try {
            switch (action) {
                case "resident" -> {
                    UUID id = UUID.fromString(value);
                    body(player, id);
                    if (!draft.picked.remove(id)) {
                        require(draft.picked.size() < 8, "members");
                        draft.picked.add(id);
                    }
                }
                case "create_group" -> {
                    require(!draft.picked.isEmpty() && draft.picked.size() <= 8, "members");
                    List<Body> people = draft.picked.stream().map(id -> body(player, id)).toList();
                    draft.group = UnitGroups.of(colony).create(people).id();
                }
                case "group" -> {
                    UUID id = UUID.fromString(value);
                    ownedGroup(player, id);
                    draft.group = id;
                }
                case "add_point", "add_aim" -> {
                    require(draft.points.size() < 16, "points");
                    WorldPos at = WorldSpaces.at(player);
                    if (action.equals("add_aim")) {
                        HitResult hit = player.pick(128, 0, false);
                        require(hit instanceof BlockHitResult block && block.getType() == HitResult.Type.BLOCK
                            && block.getDirection() == Direction.UP, "destination");
                        at = WorldPos.of(player.serverLevel(), ((BlockHitResult) hit).getBlockPos().above());
                    }
                    validPoint(player, at);
                    require(draft.points.isEmpty() || draft.points.getFirst().at().sameRealm(at), "destination");
                    var direction = player.getDirection();
                    draft.points.add(new Point(at, new Vec3(direction.getStepX(), 0, direction.getStepZ())));
                }
                case "clear_points" -> draft.points.clear();
                case "execute" -> {
                    UnitGroup group = ownedGroup(player, draft.group);
                    List<Body> people = group.members().stream().map(id -> body(player, id)).toList();
                    require(!draft.points.isEmpty(), "points");
                    List<Deployment> route = draft.points.stream().map(point -> formation(point, people.size())).toList();
                    for (Deployment stop : route) { validate(people, stop); }
                    if (route.size() == 1) { group.guard(route.getFirst()); }
                    else { group.patrol(route); }
                }
                case "retreat" -> {
                    UnitGroup group = ownedGroup(player, draft.group);
                    List<Body> people = group.members().stream().map(id -> body(player, id)).toList();
                    WorldPos at = WorldSpaces.at(player);
                    validPoint(player, at);
                    Direction facing = player.getDirection();
                    Deployment destination = formation(new Point(at, new Vec3(facing.getStepX(), 0, facing.getStepZ())), people.size());
                    validate(people, destination);
                    group.retreat(destination);
                }
                case "release" -> {
                    UnitGroup group = ownedGroup(player, draft.group);
                    group.release();
                }
                case "loadout_melee", "loadout_bow", "loadout_crossbow" -> {
                    UnitGroup group = ownedGroup(player, draft.group);
                    List<Body> people = group.members().stream().map(id -> body(player, id)).toList();
                    String choice = action.substring("loadout_".length());
                    PatrolSupplies.Loadout loadout = PatrolSupplies.Loadout.valueOf(choice.toUpperCase(java.util.Locale.ROOT));
                    people.forEach(body -> PatrolSupplies.of(colony).loadout(body, loadout));
                }
                default -> throw new IllegalArgumentException("folkways.combat.error.rejected");
            }
            draft.message = Component.translatable("folkways.combat.saved");
            return true;
        } catch (IllegalArgumentException | IllegalStateException rejected) {
            String key = rejected.getMessage();
            draft.message = Component.translatable(key != null && key.startsWith("folkways.combat.error.")
                ? key : "folkways.combat.error.rejected");
            return false;
        }
    }

    void close() { drafts.clear(); groupNumbers.clear(); nextGroupNumber = 1; }
    private int number(UUID group) { return groupNumbers.computeIfAbsent(group, ignored -> nextGroupNumber++); }
    private static void require(boolean allowed, String reason) {
        if (!allowed) { throw new IllegalArgumentException("folkways.combat.error." + reason); }
    }
    private UnitGroup ownedGroup(ServerPlayer player, UUID id) {
        require(id != null, "group");
        UnitGroup group = UnitGroups.of(colony).group(id).orElseThrow(() -> new IllegalArgumentException("folkways.combat.error.group"));
        require(!group.members().isEmpty() && group.members().stream()
            .allMatch(member -> colony.residents().stream().anyMatch(resident -> resident.id().equals(member)
                && resident.kind().equals(PersonBody.ID)) && colony.holdingOf(member).isPresent()), "group");
        require(group.members().stream().allMatch(member -> CombatSelectionOwnership.of(colony).availableOnPage(player, member)), "group");
        return group;
    }
    private Body body(ServerPlayer player, UUID id) {
        Body body = java.util.Optional.ofNullable(player.serverLevel().getEntity(id)).flatMap(Bodies::of)
            .orElseThrow(() -> new IllegalArgumentException("folkways.combat.error.members"));
        require(body.kind().id().equals(PersonBody.ID) && body.mob().isAlive() && !body.mob().isRemoved()
            && !body.mob().isPassenger() && body.colonyId().filter(colony.id()::equals).isPresent()
            && colony.holdingOf(id).isPresent() && WorldSpaces.at(body.mob()).sameRealm(WorldSpaces.at(player)), "members");
        require(CombatSelectionOwnership.of(colony).availableOnPage(player, id), "members");
        return body;
    }
    private void validPoint(ServerPlayer player, WorldPos point) {
        var level = player.serverLevel();
        require(point.sameRealm(WorldSpaces.at(player)) && point.in(level), "destination");
        BlockPos feet = point.block(level);
        require(level.hasChunkAt(feet) && Footing.withFeetAt(level, feet) && level.getFluidState(feet).isEmpty()
            && level.getFluidState(feet.above()).isEmpty()
            && WorldSpaces.world(level, point).filter(at -> at.distanceToSqr(player.position()) <= 128 * 128).isPresent(), "destination");
        Mob guide = colony.residents().stream().map(resident -> level.getEntity(resident.id()))
            .filter(java.util.Objects::nonNull).flatMap(entity -> Bodies.of(entity).stream())
            .filter(body -> body.kind().id().equals(PersonBody.ID) && body.mob().isAlive()
                && WorldSpaces.at(body.mob()).sameRealm(point)).map(Body::mob).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("folkways.combat.error.members"));
        require(safeFooting(guide, point), "destination");
    }
    private static boolean safeFooting(Mob body, WorldPos at) {
        var level = body.level();
        BlockPos feet = at.block(level);
        return level.hasChunksAt(feet.offset(-1, -1, -1), feet.offset(1, 2, 1))
            && Footing.withFeetAt(level, feet) && level.getFluidState(feet).isEmpty() && level.getFluidState(feet.above()).isEmpty()
            && WalkNodeEvaluator.getPathTypeStatic(new PathfindingContext(level, body), feet.mutable()) == PathType.WALKABLE;
    }
    private static Deployment formation(Point point, int count) {
        Vec3 side = new Vec3(-point.front().z, 0, point.front().x).scale(Math.max(0, count - 1));
        Vec3 center = Vec3.atBottomCenterOf(point.at().cell());
        return new Deployment(point.at().at(BlockPos.containing(center.subtract(side))),
            point.at().at(BlockPos.containing(center.add(side))), point.front(), new Deployment.Formation(2));
    }
    private void validate(List<Body> people, Deployment deployment) {
        List<WorldPos> cells = deployment.line(people.size());
        for (int n = 0; n < people.size(); n++) {
            Body body = people.get(n);
            ResidentCommands.of(colony).validate(body, cells.get(n));
            require(safeFooting(body.mob(), cells.get(n)), "destination");
        }
    }

    public CompoundTag reading(ServerPlayer player) {
        CompoundTag tag = new CompoundTag();
        if (!authorized(player)) { return tag; }
        Draft draft = draft(player);
        ListTag people = new ListTag();
        colony.residents().stream().sorted(Comparator.comparing(resident -> resident.id().toString())).forEach(resident -> {
            try {
                Body body = body(player, resident.id());
                CompoundTag row = new CompoundTag();
                row.putUUID("id", body.id()); row.put("name", Pages.text(player.registryAccess(), body.mob().getDisplayName()));
                Component command = ResidentCommands.of(colony).current(body.id())
                    .map(order -> Component.translatable("folkways.combat.order_state",
                        Component.translatable("folkways.combat.command." + order.mode().name().toLowerCase(java.util.Locale.ROOT)),
                        Component.translatable("folkways.combat.order." + order.status().name().toLowerCase(java.util.Locale.ROOT))))
                    .orElseGet(() -> Component.translatable("folkways.combat.command.none"));
                var stock = PatrolSupplies.of(colony).status(body.id()).state();
                row.put("detail", Pages.text(player.registryAccess(), Component.translatable("folkways.combat.resident_status",
                    (int) Math.ceil(body.mob().getHealth()), (int) Math.ceil(body.mob().getMaxHealth()), command,
                    Component.translatable("folkways.combat.stock." + stock.name().toLowerCase(java.util.Locale.ROOT)))));
                row.putBoolean("selected", draft.picked.contains(body.id())); people.add(row);
            } catch (IllegalArgumentException unavailable) { /* The page lists only currently controllable people. */ }
        });
        ListTag groups = new ListTag();
        var manager = UnitGroups.of(colony);
        colony.residents().stream().map(resident -> manager.ofResident(resident.id())).flatMap(java.util.Optional::stream)
            .filter(group -> group.members().stream().allMatch(member -> CombatSelectionOwnership.of(colony).availableOnPage(player, member)))
            .distinct().sorted(Comparator.comparing(group -> group.id().toString())).forEach(group -> {
                CompoundTag row = new CompoundTag(); row.putUUID("id", group.id()); row.putInt("number", number(group.id()));
                row.putInt("members", group.members().size());
                row.putBoolean("selected", group.id().equals(draft.group)); groups.add(row);
            });
        UnitGroup group = draft.group == null ? null : manager.group(draft.group).orElse(null);
        Component mode = Component.translatable(draft.points.isEmpty() ? "folkways.combat.mode.empty"
            : draft.points.size() == 1 ? "folkways.combat.mode.guard" : "folkways.combat.mode.patrol");
        tag.put("residents", people); tag.put("groups", groups);
        tag.putInt("selected", draft.picked.size()); tag.putInt("pointCount", draft.points.size());
        tag.put("summary", Pages.text(player.registryAccess(), Component.translatable("folkways.combat.summary",
            group == null ? Component.translatable("folkways.combat.no_group") : Component.literal(Integer.toString(number(group.id()))),
            group == null ? 0 : group.members().size(), group == null ? Component.empty()
                : Component.translatable("folkways.combat.phase." + group.phase().name().toLowerCase(java.util.Locale.ROOT)))));
        tag.put("points", Pages.text(player.registryAccess(), Component.translatable("folkways.combat.points", draft.points.size(), mode)));
        Set<String> preferences = new LinkedHashSet<>();
        if (group != null) { group.members().forEach(member -> preferences.add(PatrolSupplies.of(colony).preference(member)
            .map(choice -> choice.name().toLowerCase(java.util.Locale.ROOT)).orElse("auto"))); }
        String preference = preferences.isEmpty() ? "auto" : preferences.size() == 1 ? preferences.iterator().next() : "mixed";
        tag.put("loadout", Pages.text(player.registryAccess(), Component.translatable("folkways.combat.preference",
            Component.translatable("folkways.combat.loadout." + preference))));
        tag.put("message", Pages.text(player.registryAccess(), draft.message));
        StringBuilder locations = new StringBuilder();
        for (int n = 0; n < draft.points.size(); n++) {
            if (!locations.isEmpty()) { locations.append(" · "); }
            locations.append(n + 1).append(": ").append(draft.points.get(n).at().cell().toShortString());
        }
        tag.putString("locations", locations.toString());
        return tag;
    }
}
