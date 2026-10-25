package io.github.izakyl.folkways.plugins.person.equipment;

import io.github.izakyl.folkways.core.api.Declaring;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.ColonyView;
import io.github.izakyl.folkways.core.api.resident.body.Bodies;
import io.github.izakyl.folkways.core.api.resident.body.Equipment;
import io.github.izakyl.folkways.core.api.resident.body.PersonalItems;
import io.github.izakyl.folkways.core.api.resident.body.TaskTools;
import io.github.izakyl.folkways.core.api.participation.Participation;
import io.github.izakyl.folkways.core.api.participation.Participations;
import io.github.izakyl.folkways.core.api.participation.Stake;
import io.github.izakyl.folkways.core.api.terms.Containers;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.Reach;
import io.github.izakyl.folkways.core.api.terms.StoreRule;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.ReservedInput;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.Urge;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.front.engine.authority.ColonyAuthority;
import io.github.izakyl.folkways.plugins.combat.command.ResidentCommands;
import io.github.izakyl.folkways.plugins.combat.Threats;
import io.github.izakyl.folkways.plugins.combat.CombatReadiness;
import io.github.izakyl.folkways.plugins.combat.weapon.WeaponAdapter;
import io.github.izakyl.folkways.plugins.combat.weapon.Weapons;
import io.github.izakyl.folkways.plugins.combat.control.CombatSelectionOwnership;
import io.github.izakyl.folkways.plugins.combat.group.UnitGroups;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.person.fear.FearContent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Persistent item-ID intentions and explicit finite refill work; no saved inventory or live reservation. */
public final class EquipmentPlans {
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("folkways", "equipment_plans");
    static final int MAX_BATCH = 32;
    static final int MAX_STORES = 8;
    static final int MAX_CELLS = 128;
    private static final int RETRY = 100;
    private static final String STATUS = "folkways.equipment.plan.status.";

    record Template(String id, String name, boolean builtin, Map<EquipmentSlot, ResourceLocation> targets, int ammo) {
        Template { targets = Map.copyOf(targets); }
    }

    static final class Goal {
        final Template template;
        final UUID identity = UUID.randomUUID();
        boolean active;
        String status;
        long revision;
        long retryAt;
        Goal(Template template, boolean active, String status) { this.template = template; this.active = active; this.status = status; }
    }

    record Demand(EquipmentSlot slot, ResourceLocation item, int count, Optional<WeaponAdapter.Ammunition> supply) {
        Demand(EquipmentSlot slot, ResourceLocation item, int count) { this(slot, item, count, Optional.empty()); }
        boolean ammunition() { return slot == null; }
        ItemSpec spec() { return supply.map(WeaponAdapter.Ammunition::stock).orElseGet(() -> ItemSpec.of(item)); }
        boolean accepts(ItemStack actual) { return supply.map(ammo -> ammo.accepts(actual)).orElseGet(() -> matches(actual, item)); }
    }
    record Offer(Optional<WorldPos> store, int cell, ItemStack expected, int count, Stances stances) {
        Offer { expected = expected.copy(); }
    }

    private final Colony colony;
    private final MinecraftServer server;
    private final Map<String, Template> templates = new LinkedHashMap<>();
    private final Map<UUID, Goal> goals = new LinkedHashMap<>();
    private final Map<UUID, EquipmentRefillNode> pending = new LinkedHashMap<>();
    private record AutomaticAmmo(ItemStack weapon, Goal goal) { }
    private final Map<UUID, AutomaticAmmo> automaticAmmo = new LinkedHashMap<>();
    private final DefaultEquipment defaults;
    private final Set<UUID> ammunitionDisabled = new java.util.LinkedHashSet<>();
    private record Preview(String kind, String scope, ListTag members) { }
    private final Map<ServerPlayer, Preview> previews = new WeakHashMap<>();

    public EquipmentPlans(Colony colony, MinecraftServer server) {
        this.colony = colony; this.server = server;
        templates.put("melee_guard", new Template("melee_guard", "", true, Map.of(
            EquipmentSlot.MAINHAND, item("iron_sword"), EquipmentSlot.OFFHAND, item("shield"),
            EquipmentSlot.HEAD, item("iron_helmet"), EquipmentSlot.CHEST, item("iron_chestplate"),
            EquipmentSlot.LEGS, item("iron_leggings"), EquipmentSlot.FEET, item("iron_boots")), 8));
        templates.put("archer", new Template("archer", "", true, Map.of(
            EquipmentSlot.MAINHAND, item("bow"), EquipmentSlot.HEAD, item("leather_helmet"),
            EquipmentSlot.CHEST, item("leather_chestplate"), EquipmentSlot.LEGS, item("leather_leggings"),
            EquipmentSlot.FEET, item("leather_boots")), 16));
        CompoundTag saved = colony.kept(ID);
        for (Tag value : saved.getList("ammunitionDisabled", Tag.TAG_COMPOUND)) {
            CompoundTag row = (CompoundTag) value;
            if (row.hasUUID("resident")) ammunitionDisabled.add(row.getUUID("resident"));
        }
        for (Tag value : saved.getList("templates", Tag.TAG_COMPOUND)) {
            if (templates.size() >= 18) break;
            Template template = readTemplate((CompoundTag) value, false);
            if (!template.id().isBlank() && !templates.containsKey(template.id())) templates.put(template.id(), template);
        }
        for (Tag value : saved.getList("goals", Tag.TAG_COMPOUND)) {
            CompoundTag row = (CompoundTag) value;
            if (!row.hasUUID("resident")) continue;
            Template target = readTemplate(row.getCompound("target"), false);
            goals.put(row.getUUID("resident"), new Goal(target, row.getBoolean("active"),
                row.getBoolean("active") ? "waiting" : "selected"));
        }
        defaults = new DefaultEquipment(this, colony, server);
    }

    public static void declare(Declaring event) {
        Participations.register(PersonBody.ID, Stake.urge(ID), Participation.ALWAYS);
        event.colony(ID, scope -> {
            EquipmentPlans plans = new EquipmentPlans(scope.colony(), scope.server());
            scope.share(plans); scope.urges(plans::urges); scope.tick(plans::tick);
            scope.closed(why -> { plans.defaults.closed(); plans.pending.clear(); plans.previews.clear(); plans.automaticAmmo.clear(); });
        });
    }

    public static EquipmentPlans of(Colony colony) { return colony.service(ID, EquipmentPlans.class).orElseThrow(); }
    public static boolean ammoEnabled(Colony colony, UUID resident) {
        return colony.service(ID, EquipmentPlans.class).map(plans -> !plans.ammunitionDisabled.contains(resident)).orElse(true);
    }

    public CompoundTag defaultSnapshot() { return defaults.snapshot(); }
    public String updateDefaults(ServerPlayer actor, CompoundTag requested) { return defaults.update(actor, requested); }
    boolean hasExplicit(UUID resident) { return goals.containsKey(resident); }

    public static boolean manages(Colony colony, UUID resident) {
        return colony.service(ID, EquipmentPlans.class).map(plans -> plans.goals.containsKey(resident) || plans.defaults.manages(resident)).orElse(false);
    }

    /** Only an existing stable order lends its runner; its command identity and route remain untouched. */
    public static boolean lendsRunner(Colony colony, UUID resident) {
        return colony.service(ID, EquipmentPlans.class).map(plans -> {
            EquipmentRefillNode work = plans.pending.get(resident);
            return work != null && work.order() != null && plans.valid(work);
        }).orElse(false);
    }

    public static boolean handles(String kind) {
        return Set.of("plan_select", "template_save", "template_apply", "template_preview", "refill", "refill_cancel", "ammo_auto").contains(kind);
    }

    /** Read-only metadata: no warehouse scanning, item removal, or preview refresh on sync ticks. */
    public CompoundTag snapshot(ServerPlayer actor, ResidentEntity resident) {
        CompoundTag data = new CompoundTag();
        ListTag choices = new ListTag(); templates.values().forEach(template -> choices.add(writeTemplate(template)));
        data.put("templates", choices);
        data.put("ammunition", ammunitionSnapshot(resident));
        data.putBoolean("shared", true);
        data.put("defaults", defaults.snapshot());
        CompoundTag scopes = new CompoundTag();
        for (String scope : List.of("self", "selection", "group")) {
            List<UUID> members = members(actor, resident, scope);
            CompoundTag row = new CompoundTag(); row.putInt("count", members.size());
            ListTag people = new ListTag();
            for (UUID id : members.stream().limit(MAX_BATCH).toList()) people.add(member(actor, id));
            row.put("members", people); row.putBoolean("limited", members.size() > MAX_BATCH); scopes.put(scope, row);
        }
        data.put("scopes", scopes);
        Goal goal = goals.get(resident.id());
        CompoundTag target = goal == null ? new CompoundTag() : writeTemplate(goal.template);
        target.putBoolean("active", goal != null && goal.active);
        target.put("missing", goal == null ? new ListTag() : gaps(resident, goal.template));
        target.putString("status", STATUS + (goal == null ? "none" : goal.status));
        data.put("goal", target); data.putString("status", target.getString("status"));
        Preview previous = previews.get(actor);
        ListTag results = previous == null ? new ListTag() : previous.members().copy();
        data.putString("previewKind", previous == null ? "" : previous.kind());
        data.putString("previewScope", previous == null ? "" : previous.scope());
        if (previous != null && !previous.kind().equals("template_preview") && !previous.kind().equals("plan_select")) {
            for (Tag value : results) {
                CompoundTag row = (CompoundTag) value;
                if (!row.hasUUID("resident")) continue;
                UUID id = row.getUUID("resident");
                Optional<ResidentEntity> body = loaded(id);
                boolean permitted = authorized(actor, id);
                Goal current = goals.get(id);
                row.putBoolean("loaded", body.isPresent()); row.putBoolean("editable", permitted);
                // A rejected member never inherits an older goal's success when access/loading returns.
                if (!row.getBoolean("accepted")) { row.put("missing", new ListTag()); continue; }
                boolean same = current != null && row.hasUUID("goalIdentity")
                    && row.getUUID("goalIdentity").equals(current.identity) && row.getLong("goalRevision") == current.revision;
                row.put("missing", !same || !permitted || body.isEmpty() ? new ListTag() : gaps(body.get(), current.template));
                body.ifPresent(person -> row.putString("name", person.getName().getString()));
                row.putString("status", STATUS + (!same ? "replaced" : !permitted ? "forbidden" : body.isEmpty() ? "unloaded" : current.status));
            }
        }
        data.put("preview", results);
        return data;
    }

    /** Called only after ResidentEquipment's single-use ticket and live snapshot validation. */
    public String act(ServerPlayer actor, ResidentEntity resident, CompoundTag action) {
        if (!authorized(actor, resident.id()) || loaded(resident.id()).orElse(null) != resident) return "forbidden";
        String kind = action.getString("kind");
        if (kind.equals("ammo_auto")) {
            if (action.getBoolean("value")) ammunitionDisabled.remove(resident.id());
            else ammunitionDisabled.add(resident.id());
            automaticAmmo.remove(resident.id());
            EquipmentRefillNode old = pending.get(resident.id());
            if (old != null && old.automatic()) pending.remove(resident.id());
            save(); return "ok";
        }
        if (kind.equals("template_save")) {
            String name = action.getString("name").strip();
            if (name.isBlank() || name.length() > 48 || templates.size() >= 18) return "invalid";
            Map<EquipmentSlot, ResourceLocation> items = new LinkedHashMap<>();
            for (EquipmentSlot slot : ResidentEquipment.SLOTS) {
                ItemStack actual = equipped(resident, slot);
                if (!actual.isEmpty() && !TaskTools.holds(resident.id(), actual)
                        && (!PersonalItems.marked(actual) || PersonalItems.belongsTo(resident, actual))) {
                    items.put(slot, BuiltInRegistries.ITEM.getKey(actual.getItem()));
                }
            }
            String id = "custom/" + UUID.randomUUID();
            templates.put(id, new Template(id, name, false, items, ResidentEquipment.ammoTarget(resident)));
            save(); return "ok";
        }
        String scope = action.getString("scope");
        if (scope.isEmpty()) scope = "self";
        if (!List.of("self", "selection", "group").contains(scope)) return "invalid";
        List<UUID> members = members(actor, resident, scope);
        if (members.isEmpty()) return "empty";
        if (members.size() > MAX_BATCH) return "invalid";
        Template selected = templates.get(action.getString("template"));
        if ((kind.equals("template_apply") || kind.equals("template_preview") || kind.equals("plan_select")) && selected == null) return "invalid";
        ListTag results = new ListTag(); int accepted = 0;
        for (UUID id : members) {
            CompoundTag row = member(actor, id);
            int acceptedBefore = accepted;
            Optional<ResidentEntity> body = loaded(id);
            String status;
            if (!authorized(actor, id)) status = "forbidden";
            else if (body.isEmpty()) status = "unloaded";
            else if (kind.equals("template_preview") || kind.equals("plan_select")) {
                CompoundTag preview = preview(body.get(), selected); preview.putBoolean("accepted", true);
                results.add(preview); accepted++; continue;
            } else if (kind.equals("template_apply")) {
                DefaultEquipment.detach(body.get());
                Goal goal = new Goal(selected, false, "selected");
                Goal previous = goals.get(id); goal.revision = previous == null ? 1 : previous.revision + 1;
                goals.put(id, goal); pending.remove(id);
                ResidentEquipment.target(body.get(), selected.ammo()); status = "selected"; accepted++;
            } else if (kind.equals("refill")) {
                Goal goal = goals.get(id);
                if (goal == null) status = "none";
                else { goal.active = true; goal.revision++; goal.retryAt = 0; goal.status = "waiting"; pending.remove(id); status = "waiting"; accepted++; }
            } else if (kind.equals("refill_cancel")) {
                Goal goal = goals.get(id);
                if (goal == null) status = "none";
                else { goal.active = false; goal.revision++; goal.status = "cancelled"; pending.remove(id); status = "cancelled"; accepted++; }
            } else status = "none";
            row.putBoolean("accepted", accepted > acceptedBefore);
            Goal acceptedGoal = goals.get(id);
            if (accepted > acceptedBefore && acceptedGoal != null) {
                row.putUUID("goalIdentity", acceptedGoal.identity); row.putLong("goalRevision", acceptedGoal.revision);
                row.putString("goalTemplate", acceptedGoal.template.id());
            }
            row.putString("status", STATUS + status); results.add(row);
        }
        previews.put(actor, new Preview(kind, scope, results));
        if (!kind.equals("template_preview") && !kind.equals("plan_select")) save();
        return accepted == members.size() ? "ok" : accepted > 0 ? "partial" : "no_change";
    }

    private List<UUID> members(ServerPlayer actor, ResidentEntity resident, String scope) {
        return switch (scope) {
            case "selection" -> CombatSelectionOwnership.of(colony).snapshot(actor).selection();
            case "group" -> UnitGroups.of(colony).ofResident(resident.id())
                .map(group -> group.members().stream().sorted().toList()).orElse(List.of());
            default -> List.of(resident.id());
        };
    }

    private boolean authorized(ServerPlayer actor, UUID id) {
        return actor.getServer() == server && actor.isAlive() && !actor.isRemoved() && !actor.isSpectator()
            && ColonyAuthority.of(actor, Optional.empty()).filter(authority -> authority.colony().id().equals(colony.id())).isPresent()
            && colony.holdingOf(id).isPresent() && CombatSelectionOwnership.of(colony).availableOnPage(actor, id);
    }

    private CompoundTag member(ServerPlayer actor, UUID id) {
        CompoundTag row = new CompoundTag(); row.putUUID("resident", id);
        Optional<ResidentEntity> body = loaded(id);
        row.putString("name", body.map(person -> person.getName().getString()).orElse(id.toString()));
        row.putBoolean("loaded", body.isPresent()); row.putBoolean("editable", authorized(actor, id));
        return row;
    }

    Optional<ResidentEntity> loaded(UUID id) {
        for (ServerLevel level : server.getAllLevels()) {
            if (level.getEntity(id) instanceof ResidentEntity resident && resident.isAlive() && !resident.isRemoved()
                    && resident.colonyId().filter(colony.id()::equals).isPresent() && colony.holdingOf(id).isPresent()) return Optional.of(resident);
        }
        return Optional.empty();
    }

    private CompoundTag preview(ResidentEntity body, Template template) {
        CompoundTag row = new CompoundTag(); row.putUUID("resident", body.id()); row.putString("name", body.getName().getString());
        List<Demand> needs = demands(body, template);
        ListTag missing = new ListTag(); boolean locked = false, unavailable = false;
        for (Demand demand : needs) {
            CompoundTag item = new CompoundTag(); item.putString("slot", demand.ammunition() ? "ammo" : demand.slot().getName());
            item.putString("item", demand.item().toString()); item.putInt("count", demand.count());
            boolean blocked = demand.slot() != null && Equipment.locked(body, demand.slot());
            boolean found = !blocked && offer(body, demand).isPresent();
            item.putBoolean("locked", blocked); item.putBoolean("available", found); missing.add(item);
            locked |= blocked; unavailable |= !blocked && !found;
        }
        row.put("missing", missing);
        row.putString("status", STATUS + (needs.isEmpty() ? "equipped" : !safe(body) ? "busy" : locked ? "locked" : unavailable ? "missing" : "available"));
        return row;
    }

    /** Current deficits only; resolving a gap never surveys or reserves a warehouse. */
    private ListTag gaps(ResidentEntity body, Template template) {
        ListTag gaps = new ListTag();
        for (Demand demand : demands(body, template)) {
            CompoundTag row = new CompoundTag();
            row.putString("slot", demand.ammunition() ? "ammo" : demand.slot().getName());
            row.putString("item", demand.item().toString()); row.putInt("count", demand.count());
            row.putBoolean("locked", !demand.ammunition() && Equipment.locked(body, demand.slot()));
            gaps.add(row);
        }
        return gaps;
    }

    List<Demand> demands(ResidentEntity body, Template template) {
        List<Demand> wanted = new ArrayList<>();
        for (EquipmentSlot slot : ResidentEquipment.SLOTS) {
            ResourceLocation target = template.targets().get(slot);
            if (target != null && !matches(equipped(body, slot), target)) wanted.add(new Demand(slot, target, 1));
        }
        ResourceLocation main = template.targets().get(EquipmentSlot.MAINHAND);
        ItemStack assigned = ResidentEquipment.assigned(body);
        ItemStack weapon = main == null || matches(assigned, main) ? assigned : new ItemStack(BuiltInRegistries.ITEM.get(main));
        Weapons.ammunition(weapon, template.ammo()).flatMap(ammo -> ammunitionDemand(body, ammo)).ifPresent(wanted::add);
        return List.copyOf(wanted);
    }

    static ItemStack equipped(ResidentEntity body, EquipmentSlot slot) {
        return slot == EquipmentSlot.MAINHAND ? ResidentEquipment.assigned(body) : body.getItemBySlot(slot);
    }

    boolean safe(ResidentEntity body) { return safe(body, null, false); }

    private boolean safe(ResidentEntity body, ResidentCommands.Order lease) { return safe(body, lease, true); }

    private boolean safe(ResidentEntity body, ResidentCommands.Order lease, boolean existingLease) {
        if (danger(body) || body.hasWeaponSession() || !body.getMainHandItem().isEmpty() || body.doing().filter(doing ->
                doing.toward().orElse(doing.what()).equals(FearContent.FLEEING)).isPresent()) return false;
        Optional<ResidentCommands.Order> order = ResidentCommands.of(colony).current(body.id());
        if (order.isPresent()) {
            var held = order.get();
            return (held.mode() == ResidentCommands.Mode.HOLD || held.mode() == ResidentCommands.Mode.GUARD)
                && held.boundTo(body)
                // The captured stable command may report MOVING after the refill leaves its anchor.
                && (held == lease || held.status() == ResidentCommands.Status.HOLDING || held.status() == ResidentCommands.Status.ARRIVED)
                && !held.engaged() && !held.yieldingToDanger();
        }
        return !CombatSelectionOwnership.of(colony).reserved(body.id())
            && (body.doing().isEmpty() || body.doing().filter(doing -> {
                ResourceLocation activity = doing.toward().orElse(doing.what());
                return activity.equals(ID)
                    // Idle leisure can lend the same runner; walking toward ordinary work cannot.
                    || activity.equals(ResourceLocation.fromNamespaceAndPath("folkways", "strolling"))
                    || activity.equals(ResourceLocation.fromNamespaceAndPath("folkways", "relaxing"))
                    || existingLease && activity.equals(io.github.izakyl.folkways.plugins.person.living.LivingContent.EATING);
            }).isPresent());
    }

    private static boolean danger(ResidentEntity body) {
        var hurt = body.getLastDamageSource();
        if (hurt != null && (hurt.is(net.minecraft.tags.DamageTypeTags.PANIC_CAUSES)
                || hurt.is(net.minecraft.tags.DamageTypeTags.IS_FIRE))) return true;
        return body.getLastHurtByMob() instanceof net.minecraft.world.entity.Mob pursuer
            && pursuer.getTarget() == body && Threats.hostile(pursuer, body)
            && pursuer.level() == body.level() && body.distanceToSqr(pursuer) <= 16 * 16;
    }

    public List<Urge> urges(Worker who, ColonyView view) {
        if (!(who.body() instanceof ResidentEntity body) || !(body.level() instanceof ServerLevel level)) return List.of();
        AutomaticAmmo automatic = automatic(body);
        boolean quiet = automatic == null || CombatReadiness.quiet(body);
        Goal explicit = goals.get(body.id());
        Goal inherited = defaults.goal(body);
        boolean inheritedRequest = (explicit == null || !explicit.active) && inherited != null;
        boolean automaticRequest = !inheritedRequest && (explicit == null || !explicit.active);
        Goal goal = inheritedRequest ? inherited : automaticRequest ? automatic == null ? null : automatic.goal() : explicit;
        EquipmentRefillNode current = pending.get(body.id());
        if (current != null && valid(current) && level.getGameTime() < current.deadline()) return List.of(current.urge());
        pending.remove(body.id());
        if (goal == null || !goal.active) return List.of();
        if (!safe(body) || body.doing().filter(doing -> doing.what().equals(io.github.izakyl.folkways.plugins.person.living.LivingContent.EATING)).isPresent()
                || (automaticRequest || inheritedRequest) && (!CombatReadiness.quiet(body) || !quiet || UnitGroups.of(colony).ofResident(body.id())
                    .filter(group -> !group.maintenanceAllowed(false)).isPresent())) { status(goal, "busy"); return List.of(); }
        List<Demand> demands = automaticRequest ? automaticDemands(body) : inheritedRequest ? defaults.demands(body, goal.template) : demands(body, goal.template);
        if (demands.isEmpty()) {
            goal.active = false; status(goal, "equipped"); if (!automaticRequest) save(); return List.of();
        }
        if (level.getGameTime() < goal.retryAt) return List.of();
        boolean locked = false;
        for (Demand demand : demands) {
            if (!demand.ammunition() && Equipment.locked(body, demand.slot())) { locked = true; continue; }
            Optional<Offer> offer = offer(body, demand);
            if (offer.isEmpty()) continue;
            var order = ResidentCommands.of(colony).current(body.id()).orElse(null);
            EquipmentRefillNode request = new EquipmentRefillNode(this, body, goal.revision, order, demand, offer.get(), level.getGameTime() + 240, automaticRequest, inheritedRequest);
            pending.put(body.id(), request); status(goal, "fetching"); return List.of(request.urge());
        }
        goal.retryAt = level.getGameTime() + RETRY; status(goal, locked ? "locked" : "missing");
        return List.of();
    }

    private Goal requestGoal(EquipmentRefillNode request) {
        AutomaticAmmo automatic = automaticAmmo.get(request.body().id());
        return request.inherited() ? defaults.existing(request.body().id())
            : request.automatic() ? automatic == null ? null : automatic.goal() : goals.get(request.body().id());
    }

    boolean valid(EquipmentRefillNode request) {
        Goal goal = requestGoal(request);
        if (request.inherited() && (goal == null || !defaults.current(request.body(), goal) || !CombatReadiness.quiet(request.body()))) return false;
        if (request.automatic()) {
            if (!CombatReadiness.quiet(request.body())) return false;
            AutomaticAmmo automatic = automaticAmmo.get(request.body().id());
            Goal explicit = goals.get(request.body().id());
            ItemStack actual = ResidentEquipment.assigned(request.body());
            if (ammunitionDisabled.contains(request.body().id()) || automatic == null || explicit != null && explicit.active || actual.isEmpty()
                    || !ItemStack.isSameItemSameComponents(actual, automatic.weapon())
                    || automatic.goal().template.ammo() != ResidentEquipment.ammoTarget(request.body())) return false;
        }
        return goal != null && goal.active && goal.revision == request.revision()
            && pending.get(request.body().id()) == request && loaded(request.body().id()).orElse(null) == request.body()
            && safe(request.body(), request.order()) && ResidentCommands.of(colony).current(request.body().id()).orElse(null) == request.order()
            && (request.automatic() ? automaticDemands(request.body()) : request.inherited() ? defaults.demands(request.body(), goal.template) : demands(request.body(), goal.template)).stream()
                .anyMatch(demand -> demand.slot() == request.demand().slot() && demand.accepts(request.offer().expected())
                    && demand.count() >= request.offer().count())
            && (request.demand().ammunition() || !Equipment.locked(request.body(), request.demand().slot()));
    }

    private Optional<Demand> ammunitionDemand(ResidentEntity body, WeaponAdapter.Ammunition ammo) {
        int missing = Math.max(0, ammo.target() - ammunitionCarried(body, ammo) - ammo.loaded());
        return missing == 0 ? Optional.empty() : Optional.of(new Demand(null, ammunitionIcon(ammo), missing, Optional.of(ammo)));
    }

    private int ammunitionCarried(ResidentEntity body, WeaponAdapter.Ammunition ammo) {
        Container available = free((ServerLevel) body.level(), Optional.empty(), body);
        int carried = 0;
        for (int cell = 0; cell < available.getContainerSize(); cell++) {
            ItemStack actual = available.getItem(cell);
            if (ResidentEquipment.ammoCandidate(body, actual) && ammo.accepts(actual)) carried += actual.getCount();
        }
        return carried;
    }

    private static ResourceLocation ammunitionIcon(WeaponAdapter.Ammunition ammo) {
        return ammo.stock().item().orElseGet(() -> Goods.members(ammo.stock()).stream()
            .map(BuiltInRegistries.ITEM::getKey).sorted().findFirst().orElse(item("air")));
    }

    private List<Demand> automaticDemands(ResidentEntity body) {
        return Weapons.ammunition(body).flatMap(supply -> ammunitionDemand(body, supply.demand())).map(List::of).orElse(List.of());
    }

    /** Only a real assignment opts into persistent maintenance; raw task cargo is not a second loadout. */
    private AutomaticAmmo automatic(ResidentEntity body) {
        Optional<Weapons.AmmunitionSupply> supply = ammunitionDisabled.contains(body.id()) || ResidentEquipment.assigned(body).isEmpty() ? Optional.empty() : Weapons.ammunition(body);
        if (supply.isEmpty()) {
            automaticAmmo.remove(body.id());
            EquipmentRefillNode old = pending.get(body.id()); if (old != null && old.automatic()) pending.remove(body.id());
            return null;
        }
        int reserve = ResidentEquipment.ammoTarget(body);
        AutomaticAmmo previous = automaticAmmo.get(body.id());
        if (previous == null || !ItemStack.isSameItemSameComponents(previous.weapon(), supply.get().weapon())
                || previous.goal().template.ammo() != reserve) {
            Goal next = new Goal(new Template("automatic_ammo", "", false, Map.of(), reserve), false, "waiting");
            next.revision = previous == null ? 1 : previous.goal().revision + 1;
            previous = new AutomaticAmmo(supply.get().weapon().copy(), next); automaticAmmo.put(body.id(), previous);
        }
        var ammo = supply.get().demand();
        int total = ammunitionCarried(body, ammo) + ammo.loaded();
        if (total >= ammo.target()) { previous.goal().active = false; previous.goal().status = "equipped"; }
        else if (!previous.goal().active && total < Math.max(1, ammo.target() / 2)) {
            previous.goal().active = true; previous.goal().retryAt = 0; previous.goal().revision++;
            previous.goal().status = "waiting";
        }
        return previous;
    }

    /** Stable, read-only UI metadata: this inspects the pack, never warehouse contents. */
    public CompoundTag ammunitionSnapshot(ResidentEntity body) {
        CompoundTag data = new CompoundTag();
        Optional<Weapons.AmmunitionSupply> supply = Weapons.ammunition(body);
        data.putBoolean("enabled", !ammunitionDisabled.contains(body.id()));
        data.putBoolean("declared", supply.isPresent()); data.putString("status", "none");
        if (supply.isEmpty()) return data;
        var ammo = supply.get().demand(); int carried = ammunitionCarried(body, ammo); int total = carried + ammo.loaded();
        int trigger = Math.max(1, ammo.target() / 2);
        AutomaticAmmo automatic = automaticAmmo.get(body.id());
        boolean eligible = !ResidentEquipment.assigned(body).isEmpty();
        boolean active = eligible && automatic != null && automatic.goal().active;
        data.putString("weapon", BuiltInRegistries.ITEM.getKey(supply.get().weapon().getItem()).toString());
        data.putString("item", ammunitionIcon(ammo).toString()); data.put("stock", ammo.stock().save());
        data.putInt("carried", carried); data.putInt("loaded", ammo.loaded()); data.putInt("total", total);
        data.putInt("target", ammo.target()); data.putInt("trigger", trigger); data.putInt("needed", Math.max(0, ammo.target() - total));
        data.putBoolean("automatic", eligible); data.putBoolean("active", active);
        data.putString("status", active ? automatic.goal().status.equals("equipped") ? "ready" : automatic.goal().status
            : total < trigger ? "waiting" : "ready");
        return data;
    }
    Optional<Offer> offer(ResidentEntity body, Demand demand) {
        if (!(body.level() instanceof ServerLevel level)) return Optional.empty();
        if (!demand.ammunition()) {
            Container carried = free(level, Optional.empty(), body);
            for (int cell = 0; cell < carried.getContainerSize(); cell++) {
                ItemStack stack = carried.getItem(cell);
                if (usable(body, stack) && !PersonalItems.purpose(body, stack).filter(EquipmentSlot.MAINHAND.getName()::equals).isPresent() && demand.accepts(stack)) {
                    return Optional.of(new Offer(Optional.empty(), cell, stack, 1, Stances.WHEREVER));
                }
            }
        }
        for (WorldPos at : stores(body)) {
            Optional<Container> found = source(level, at); if (found.isEmpty()) continue;
            Container available = free(level, Optional.of(at), body);
            Optional<Stances> stances = Stances.of(level, Reach.workableCells(level, at.block(level)).stream()
                .filter(feet -> feet.distSqr(at.block(level)) <= 4).toList());
            if (stances.isEmpty()) continue;
            for (int cell = 0; cell < available.getContainerSize(); cell++) {
                ItemStack stack = available.getItem(cell);
                if (!PersonalItems.marked(stack) && demand.accepts(stack)) {
                    return Optional.of(new Offer(Optional.of(at), cell, stack, Math.min(demand.count(), stack.getCount()), stances.get()));
                }
            }
        }
        return Optional.empty();
    }

    List<WorldPos> stores(ResidentEntity body) {
        ServerLevel level = (ServerLevel) body.level();
        return colony.storesIn(level.dimension()).stream().filter(at -> at.sameRealm(WorldSpaces.at(body)))
            .filter(at -> WorldSpaces.world(level, at).filter(pos -> pos.distanceToSqr(body.position()) <= 32 * 32).isPresent())
            .sorted(Comparator.comparingDouble(at -> WorldSpaces.world(level, at).orElseThrow().distanceToSqr(body.position())))
            .limit(MAX_STORES).toList();
    }

    Optional<Container> source(ServerLevel level, WorldPos at) {
        return at.in(level) && level.isLoaded(at.block(level)) && colony.storesIn(level.dimension()).contains(at)
            && StoreRule.takes(colony.storeRules(at)) ? colony.store(level, at) : Optional.empty();
    }

    Container free(ServerLevel level, Optional<WorldPos> at, ResidentEntity body) {
        Container actual = at.flatMap(pos -> source(level, pos)).orElseGet(() -> at.isEmpty() ? body.pack() : new SimpleContainer(0));
        SimpleContainer free = copy(actual);
        for (ReservedInput claim : colony.reservedInputs(level)) {
            boolean applies = at.isEmpty() ? claim.store().isEmpty() && claim.resident().filter(body.id()::equals).isPresent()
                : claim.store().filter(pos -> pos.equals(at.get()) || pos.in(level) && level.isLoaded(pos.block(level))
                    && Containers.anchor(level, pos.block(level)).equals(Containers.anchor(level, at.get().block(level)))).isPresent();
            if (applies && !claim.pending()) {
                // A material obligation spans component variants; Containers.extract returns just one variant.
                long remaining = claim.count();
                for (int cell = 0; cell < free.getContainerSize() && remaining > 0; cell++) {
                    ItemStack stack = free.getItem(cell);
                    if (PersonalItems.marked(stack) || !Goods.matches(stack, claim.goods())) continue;
                    int claimed = (int) Math.min(remaining, stack.getCount());
                    stack.shrink(claimed); remaining -= claimed;
                }
            }
        }
        return free;
    }

    private static boolean usable(ResidentEntity body, ItemStack stack) {
        return !stack.isEmpty() && !TaskTools.holds(body.id(), stack)
            && (!PersonalItems.marked(stack) || PersonalItems.belongsTo(body, stack));
    }

    String install(EquipmentRefillNode request, List<ItemStack> supplied) {
        if (!valid(request) || supplied.size() != 1) return "busy";
        ItemStack incoming = supplied.getFirst();
        if (!request.demand().accepts(incoming) || incoming.getCount() != request.offer().count()) return "invalid";
        if (!request.demand().ammunition()) {
            String result = ResidentEquipment.automatic(request.body(), request.demand().slot(), incoming);
            if (result.equals("ok") && request.inherited()) {
                DefaultEquipment.issued(request.body(), request.demand().slot());
                if (request.demand().slot() == EquipmentSlot.MAINHAND) ResidentEquipment.target(request.body(), requestGoal(request).template.ammo());
            }
            return result;
        }
        SimpleContainer pack = copy(request.body().pack());
        ItemStack actual = incoming.copy(); PersonalItems.mark(request.body(), actual, "ammo");
        if (!Containers.insert(pack, actual).isEmpty() || !ResidentEquipment.roomForReturn(request.body(), pack)) return "no_room";
        write(request.body().pack(), pack); return "ok";
    }

    void finished(EquipmentRefillNode request, Ending ending, String failure) {
        if (!pending.remove(request.body().id(), request)) return;
        Goal goal = requestGoal(request); if (goal == null) return;
        goal.retryAt = request.body().level().getGameTime() + (ending instanceof Ending.Done ? 0 : RETRY);
        status(goal, ending instanceof Ending.Done ? "waiting" : failure == null ? "busy" : failure);
    }

    private void tick(MinecraftServer ignored) {
        defaults.tick();
        Set<UUID> roster = colony.residents().stream().map(resident -> resident.id()).collect(java.util.stream.Collectors.toSet());
        boolean removed = goals.keySet().removeIf(id -> !roster.contains(id));
        automaticAmmo.keySet().retainAll(roster);
        removed |= ammunitionDisabled.removeIf(id -> !roster.contains(id));
        pending.entrySet().removeIf(entry -> !valid(entry.getValue()));
        if (removed) save();
    }

    private void status(Goal goal, String status) {
        if (!goal.status.equals(status)) { goal.status = status; if (goals.containsValue(goal)) save(); }
    }

    private void save() {
        CompoundTag saved = new CompoundTag(); ListTag choices = new ListTag();
        templates.values().stream().filter(template -> !template.builtin()).forEach(template -> choices.add(writeTemplate(template)));
        saved.put("templates", choices); ListTag targets = new ListTag();
        goals.forEach((id, goal) -> {
            CompoundTag row = new CompoundTag(); row.putUUID("resident", id); row.put("target", writeTemplate(goal.template));
            row.putBoolean("active", goal.active); targets.add(row);
        });
        saved.put("goals", targets);
        ListTag disabled = new ListTag();
        ammunitionDisabled.forEach(id -> { CompoundTag row = new CompoundTag(); row.putUUID("resident", id); disabled.add(row); });
        saved.put("ammunitionDisabled", disabled); colony.keep(ID, saved);
    }

    private static ResourceLocation item(String path) { return ResourceLocation.withDefaultNamespace(path); }
    static boolean matches(ItemStack stack, ResourceLocation item) {
        return !stack.isEmpty() && BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(item);
    }

    private static CompoundTag writeTemplate(Template template) {
        CompoundTag data = new CompoundTag(); data.putString("id", template.id()); data.putString("name", template.name());
        data.putBoolean("builtin", template.builtin()); data.putInt("ammoTarget", template.ammo());
        CompoundTag targets = new CompoundTag(); template.targets().forEach((slot, item) -> targets.putString(slot.getName(), item.toString()));
        data.put("targets", targets); return data;
    }

    private static Template readTemplate(CompoundTag data, boolean builtin) {
        Map<EquipmentSlot, ResourceLocation> targets = new LinkedHashMap<>();
        CompoundTag saved = data.getCompound("targets");
        for (EquipmentSlot slot : ResidentEquipment.SLOTS) {
            ResourceLocation id = ResourceLocation.tryParse(saved.getString(slot.getName()));
            if (id != null && BuiltInRegistries.ITEM.containsKey(id) && !id.equals(item("air"))) targets.put(slot, id);
        }
        int ammo = data.getInt("ammoTarget"); if (ammo != 8 && ammo != 16 && ammo != 32) ammo = 8;
        return new Template(data.getString("id"), data.getString("name"), builtin || Set.of("melee_guard", "archer").contains(data.getString("id")), targets, ammo);
    }

    static SimpleContainer copy(Container actual) {
        SimpleContainer copy = new SimpleContainer(Math.min(MAX_CELLS, actual.getContainerSize()));
        for (int cell = 0; cell < copy.getContainerSize(); cell++) copy.setItem(cell, actual.getItem(cell).copy());
        return copy;
    }

    static void write(Container actual, Container from) {
        for (int cell = 0; cell < from.getContainerSize(); cell++) actual.setItem(cell, from.getItem(cell));
        actual.setChanged();
    }
}
