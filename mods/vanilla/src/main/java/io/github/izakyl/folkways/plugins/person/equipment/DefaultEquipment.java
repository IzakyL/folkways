package io.github.izakyl.folkways.plugins.person.equipment;

import io.github.izakyl.folkways.core.api.colony.Asking;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.resident.body.Equipment;
import io.github.izakyl.folkways.core.api.resident.body.PersonalItems;
import io.github.izakyl.folkways.core.api.resident.body.TaskTools;
import io.github.izakyl.folkways.core.api.terms.Containers;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.Reach;
import io.github.izakyl.folkways.core.api.terms.StoreRule;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Delivery;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.front.engine.authority.ColonyAuthority;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** Colony policy and finite ordinary production requests. Never an inventory or a recipe executor. */
final class DefaultEquipment {
    static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("folkways", "equipment_defaults");
    private static final String ORIGIN = "folkways:default_equipment";
    private static final String STATUS = "folkways.equipment.defaults.status.";
    private final EquipmentPlans plans;
    private final Colony colony;
    private final MinecraftServer server;
    private final Asking asking;
    private CompoundTag configuration;
    private final Map<UUID, EquipmentPlans.Goal> goals = new LinkedHashMap<>();
    private final Map<UUID, Long> settled = new LinkedHashMap<>();
    private final Map<UUID, Batch> batches = new LinkedHashMap<>();
    private final Map<UUID, Integer> nextPart = new LinkedHashMap<>();
    private final Map<UUID, Long> retryAfter = new LinkedHashMap<>();
    private CompoundTag statistics = new CompoundTag();
    private long nextSweep;
    private int cursor;

    private static final class Batch {
        final UUID id = UUID.randomUUID();
        final UUID resident;
        final EquipmentPlans.Demand demand;
        final WorldPos store;
        final Stances stances;
        final long started;
        String reason = "waiting_stock";
        long remaining;
        Batch(UUID resident, EquipmentPlans.Demand demand, WorldPos store, Stances stances, long started) {
            this.resident = resident; this.demand = demand; this.store = store; this.stances = stances; this.started = started;
            remaining = demand.count();
        }
        Grown work() {
            return Grown.of(Delivery.to(id, ID, new WorkSite.AtBlock(store), stances, demand.spec(), remaining,
                arrived -> remaining = Math.max(0, remaining - arrived)));
        }
    }

    DefaultEquipment(EquipmentPlans plans, Colony colony, MinecraftServer server) {
        this.plans = plans; this.colony = colony; this.server = server;
        CompoundTag saved = colony.kept(ID);
        configuration = saved.getCompound("configuration").copy();
        if (!configuration.contains("weapon")) configuration.putString("weapon", "none");
        if (!configuration.contains("ammo")) configuration.putInt("ammo", 8);
        for (Tag tag : saved.getList("settled", Tag.TAG_COMPOUND)) {
            CompoundTag row = (CompoundTag) tag;
            if (row.hasUUID("resident")) settled.put(row.getUUID("resident"), row.getLong("revision"));
        }
        asking = new Asking(colony, ID, (id, ending) -> {
            batches.values().removeIf(batch -> batch.id.equals(id)); nextSweep = 0;
        });
    }

    CompoundTag snapshot() {
        CompoundTag result = configuration.copy();
        for (String key : statistics.getAllKeys()) result.put(key, statistics.get(key).copy());
        result.putInt("planned", batches.size());
        result.putInt("production", activeDeliveries());
        if (!configuration.getBoolean("enabled")) result.putString("status", STATUS + "disabled");
        else if (!result.contains("status")) result.putString("status", STATUS + "waiting");
        return result;
    }

    String update(ServerPlayer actor, CompoundTag requested) {
        if (actor.getServer() != server || !actor.isAlive() || actor.isRemoved() || actor.isSpectator()
                || ColonyAuthority.of(actor, Optional.empty()).filter(authority -> authority.colony().id().equals(colony.id())).isEmpty()) return "forbidden";
        String weapon = requested.getString("weapon"); int ammo = requested.getInt("ammo");
        if (!List.of("none", "melee", "bow", "crossbow").contains(weapon) || ammo != 8 && ammo != 16 && ammo != 32) return "invalid";
        if (requested.getLong("revision") != configuration.getLong("revision")) return "stale";
        CompoundTag next = new CompoundTag();
        for (String key : List.of("enabled", "head", "chest", "legs", "feet")) next.putBoolean(key, requested.getBoolean(key));
        next.putString("weapon", weapon); next.putInt("ammo", ammo);
        next.putLong("revision", configuration.getLong("revision") + 1);
        configuration = next; goals.clear(); batches.clear(); nextPart.clear(); retryAfter.clear(); statistics = new CompoundTag();
        asking.offer(Map.of()); nextSweep = 0; save(); return "ok";
    }

    EquipmentPlans.Goal goal(ResidentEntity body) {
        if (!configuration.getBoolean("enabled") || plans.hasExplicit(body.id()) || settled.getOrDefault(body.id(), -1L) == revision()) {
            goals.remove(body.id()); return null;
        }
        EquipmentPlans.Template target = template(body);
        ResourceLocation desiredWeapon = target.targets().get(EquipmentSlot.MAINHAND);
        if (desiredWeapon != null && plans.safe(body) && !borrowed(body, EquipmentSlot.MAINHAND)
                && Goods.matches(ResidentEquipment.assigned(body), ItemSpec.of(desiredWeapon))) {
            // The configured reserve also applies when the correct physical weapon was already present.
            ResidentEquipment.target(body, target.ammo());
        }
        EquipmentPlans.Goal prior = goals.get(body.id());
        if (prior == null || !prior.template.equals(target)) {
            prior = new EquipmentPlans.Goal(target, true, "waiting"); prior.revision = revision(); goals.put(body.id(), prior);
        }
        return prior;
    }

    boolean manages(UUID resident) { return configuration.getBoolean("enabled") && !configuration.getString("weapon").equals("none"); }

    List<EquipmentPlans.Demand> demands(ResidentEntity body, EquipmentPlans.Template template) {
        return plans.demands(body, template).stream().filter(demand -> !demand.ammunition() || template.targets().containsKey(EquipmentSlot.MAINHAND)).toList();
    }

    EquipmentPlans.Goal existing(UUID id) { return goals.get(id); }
    boolean current(ResidentEntity body, EquipmentPlans.Goal goal) {
        return configuration.getBoolean("enabled") && !plans.hasExplicit(body.id()) && goals.get(body.id()) == goal
            && goal.revision == revision() && goal.template.equals(template(body));
    }
    private long revision() { return configuration.getLong("revision"); }

    private Map<EquipmentSlot, ResourceLocation> requestedTargets() {
        Map<EquipmentSlot, ResourceLocation> targets = new LinkedHashMap<>();
        for (EquipmentSlot slot : List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)) {
            if (configuration.getBoolean(slot.getName())) {
                String name = switch (slot) { case HEAD -> "iron_helmet"; case CHEST -> "iron_chestplate"; case LEGS -> "iron_leggings"; default -> "iron_boots"; };
                targets.put(slot, ResourceLocation.withDefaultNamespace(name));
            }
        }
        String weapon = configuration.getString("weapon");
        if (!weapon.equals("none")) targets.put(EquipmentSlot.MAINHAND,
            ResourceLocation.withDefaultNamespace(weapon.equals("melee") ? "iron_sword" : weapon));
        return targets;
    }

    private EquipmentPlans.Template template(ResidentEntity body) {
        Map<EquipmentSlot, ResourceLocation> targets = requestedTargets();
        // Existing personal assignments and locked empty slots are never an invitation to replace gear.
        targets.entrySet().removeIf(entry -> {
            ItemStack actual = EquipmentPlans.equipped(body, entry.getKey());
            return borrowed(body, entry.getKey()) || Equipment.locked(body, entry.getKey()) || !actual.isEmpty() && PersonalItems.marked(actual)
                && !issuedTo(body, actual, entry.getKey()) && !Goods.matches(actual, ItemSpec.of(entry.getValue()));
        });
        return new EquipmentPlans.Template("colony_default", "", false, targets, configuration.getInt("ammo"));
    }

    void tick() {
        long now = server.overworld().getGameTime();
        if (now < nextSweep) return;
        nextSweep = now + 20;
        var roster = colony.residents().stream().map(resident -> resident.id()).toList();
        goals.keySet().retainAll(roster); nextPart.keySet().retainAll(roster); retryAfter.keySet().retainAll(roster);
        if (settled.keySet().removeIf(id -> !roster.contains(id))) save();
        statistics = new CompoundTag();
        for (String key : List.of("eligible", "equipped", "waiting", "busy", "locked", "overridden", "unloaded")) statistics.putInt(key, 0);
        if (!configuration.getBoolean("enabled")) { batches.clear(); asking.offer(Map.of()); return; }
        batches.values().removeIf(batch -> {
            Optional<ResidentEntity> body = plans.loaded(batch.resident);
            EquipmentPlans.Goal goal = body.map(this::goal).orElse(null);
            if (now - batch.started >= 1200) { retryAfter.put(batch.resident, now + 100); return true; }
            return batch.remaining <= 0 || goal == null || !validStore(body.get(), batch.store, batch.demand)
                || demands(body.get(), goal.template).stream().noneMatch(demand -> demand.slot() == batch.demand.slot()
                    && demand.spec().equals(batch.demand.spec()) && demand.count() >= batch.remaining);
        });
        int count = Math.min(32, roster.size()); boolean noStore = false;
        ListTag parts = new ListTag();
        statistics.putInt("total", roster.size()); statistics.putInt("sampled", count);
        for (int offset = 0; offset < count; offset++) {
            UUID id = roster.get(Math.floorMod(cursor + offset, roster.size()));
            Optional<ResidentEntity> loaded = plans.loaded(id);
            if (loaded.isEmpty()) { increment("unloaded"); continue; }
            ResidentEntity body = loaded.get();
            if (plans.hasExplicit(id)) { increment("overridden"); continue; }
            increment("eligible");
            EquipmentPlans.Goal goal = goal(body);
            if (goal == null) { increment("equipped"); continue; }
            List<EquipmentPlans.Demand> demands = demands(body, goal.template);
            for (var target : requestedTargets().entrySet()) {
                if (borrowed(body, target.getKey()) || !goal.template.targets().containsKey(target.getKey()) && !Goods.matches(EquipmentPlans.equipped(body, target.getKey()), ItemSpec.of(target.getValue()))) {
                    CompoundTag part = part(id, target.getKey().getName(), target.getValue().toString(), 1);
                    part.putString("reason", borrowed(body, target.getKey()) ? "busy" : Equipment.locked(body, target.getKey()) ? "locked" : "personal"); parts.add(part);
                }
            }
            for (var demand : demands) {
                CompoundTag part = part(id, demand.ammunition() ? "ammo" : demand.slot().getName(), demand.item().toString(), demand.count());
                part.putString("reason", plans.safe(body) ? "waiting_stock" : "busy"); parts.add(part);
            }
            boolean loan = requestedTargets().keySet().stream().anyMatch(slot -> borrowed(body, slot));
            if (demands.isEmpty()) {
                boolean blocked = hasProtectedSlot(body);
                if (!blocked) { settled.put(id, revision()); save(); }
                increment(loan ? "busy" : blocked ? "locked" : "equipped"); continue;
            }
            if (!plans.safe(body)) { increment("busy"); continue; }
            increment(loan ? "busy" : "waiting");
            if (batches.size() >= 2 || batches.containsKey(id) || now < retryAfter.getOrDefault(id, 0L)) continue;
            for (int index = 0; index < demands.size(); index++) {
                int position = Math.floorMod(nextPart.getOrDefault(id, 0) + index, demands.size());
                EquipmentPlans.Demand demand = demands.get(position);
                if (plans.offer(body, demand).isPresent()) {
                    findPart(parts, id, demand).ifPresent(part -> part.putString("reason", "available")); continue;
                }
                Optional<WorldPos> store = plans.stores(body).stream().filter(at -> validStore(body, at, demand)).findFirst();
                if (store.isEmpty()) { noStore = true; findPart(parts, id, demand).ifPresent(part -> part.putString("reason", "no_store")); continue; }
                Optional<Stances> stands = Stances.of((ServerLevel) body.level(), Reach.workableCells((ServerLevel) body.level(), store.get().block(body.level())));
                if (stands.isEmpty()) continue;
                batches.put(id, new Batch(id, demand, store.get(), stands.get(), now));
                nextPart.put(id, (position + 1) % demands.size()); break;
            }
        }
        if (!roster.isEmpty()) cursor = (cursor + count) % roster.size();
        Map<ResourceKey<Level>, List<Grown>> work = new LinkedHashMap<>();
        for (Batch batch : batches.values()) if (batch.remaining > 0) {
            batch.store.level(server).ifPresent(level -> {
                batch.reason = productionReason(level, batch);
                if (!batch.reason.equals("no_station") && !batch.reason.equals("unsupported_recipe")) {
                    work.computeIfAbsent(level.dimension(), ignored -> new ArrayList<>()).add(batch.work());
                }
            });
        }
        asking.offer(work);
        for (Batch batch : batches.values()) {
            CompoundTag part = findPart(parts, batch.resident, batch.demand).orElseGet(() -> {
                CompoundTag added = part(batch.resident, batch.demand.ammunition() ? "ammo" : batch.demand.slot().getName(), batch.demand.item().toString(), batch.remaining);
                parts.add(added); return added;
            });
            batch.store.level(server).ifPresent(level -> {
                part.putString("reason", batch.reason);
                colony.stallsIn(level.dimension()).stream().filter(stall -> stall.goods().filter(goods -> Goods.overlap(goods, batch.demand.spec())).isPresent())
                    .findFirst().ifPresent(stall -> part.putString("refusal", stall.why().translationKey()));
            });
        }
        statistics.put("parts", parts);
        statistics.putString("status", STATUS + (activeDeliveries() > 0 ? "working" : noStore ? "no_store" : "waiting"));
    }

    private int activeDeliveries() {
        var live = asking.live();
        return (int) batches.values().stream().filter(batch -> batch.remaining > 0 && live.contains(batch.id)).count();
    }
    private String productionReason(ServerLevel level, Batch batch) {
        boolean station = colony.view(level).blocks(io.github.izakyl.folkways.plugins.wares.WaresContent.STATION).stream().limit(64)
            .anyMatch(pos -> level.isLoaded(pos) && level.getBlockState(pos).is(net.minecraft.world.level.block.Blocks.CRAFTING_TABLE));
        if (!station) return "no_station";
        // The bounded vanilla defaults use their native recipe ID for a cheap input hint. Alternate
        // data-pack recipes remain the ordinary planner's decision; absence here is not a rejection.
        var nativeRecipe = level.getRecipeManager().byKey(batch.demand.item())
            .filter(holder -> holder.value() instanceof net.minecraft.world.item.crafting.CraftingRecipe)
            .filter(holder -> Goods.matches(holder.value().getResultItem(level.registryAccess()), batch.demand.spec()));
        if (nativeRecipe.isEmpty()) return "waiting_production";
        var recipes = List.of(nativeRecipe.get());
        Optional<ResidentEntity> recipient = plans.loaded(batch.resident);
        if (recipient.isEmpty()) return "unloaded";
        for (var recipe : recipes) {
            List<net.minecraft.world.Container> sources = plans.stores(recipient.get()).stream()
                .map(at -> plans.free(level, Optional.of(at), recipient.get())).toList();
            boolean inputs = true;
            for (var ingredient : recipe.value().getIngredients()) {
                if (ingredient.isEmpty()) continue;
                boolean found = false;
                for (var stock : sources) {
                    if (!Containers.extract(stock, actual -> !PersonalItems.marked(actual) && ingredient.test(actual), 1).isEmpty()) { found = true; break; }
                }
                if (!found) { inputs = false; break; }
            }
            if (inputs) return "waiting_production";
        }
        return "missing_inputs";
    }

    private static CompoundTag part(UUID resident, String slot, String item, long missing) {
        CompoundTag part = new CompoundTag(); part.putUUID("resident", resident); part.putString("slot", slot);
        part.putString("item", item); part.putLong("missing", missing); return part;
    }

    private static Optional<CompoundTag> findPart(ListTag parts, UUID resident, EquipmentPlans.Demand demand) {
        String slot = demand.ammunition() ? "ammo" : demand.slot().getName();
        return parts.stream().map(tag -> (CompoundTag) tag).filter(part -> part.getUUID("resident").equals(resident)
            && part.getString("slot").equals(slot)).findFirst();
    }

    private boolean hasProtectedSlot(ResidentEntity body) {
        for (var target : requestedTargets().entrySet()) {
            ItemStack actual = EquipmentPlans.equipped(body, target.getKey());
            if (borrowed(body, target.getKey())) return true;
            if (Goods.matches(actual, ItemSpec.of(target.getValue()))) continue;
            if (Equipment.locked(body, target.getKey()) || !actual.isEmpty() && PersonalItems.marked(actual) && !issuedTo(body, actual, target.getKey())) return true;
        }
        return false;
    }

    static boolean issuedTo(ResidentEntity body, ItemStack actual, EquipmentSlot slot) {
        if (!PersonalItems.purpose(body, actual).filter(slot.getName()::equals).isPresent()) return false;
        CompoundTag origin = actual.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
            net.minecraft.world.item.component.CustomData.EMPTY).copyTag().getCompound(ORIGIN);
        return origin.hasUUID("token") && origin.hasUUID("resident") && origin.getUUID("resident").equals(body.id())
            && origin.hasUUID("colony") && body.colonyId().filter(origin.getUUID("colony")::equals).isPresent()
            && origin.getString("slot").equals(slot.getName());
    }

    static void issued(ResidentEntity body, EquipmentSlot slot) {
        ItemStack actual = EquipmentPlans.equipped(body, slot);
        if (actual.isEmpty() || !PersonalItems.belongsTo(body, actual) || body.colonyId().isEmpty()) return;
        CompoundTag origin = new CompoundTag(); origin.putUUID("token", UUID.randomUUID()); origin.putUUID("resident", body.id());
        origin.putUUID("colony", body.colonyId().orElseThrow()); origin.putString("slot", slot.getName());
        CompoundTag data = actual.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
            net.minecraft.world.item.component.CustomData.EMPTY).copyTag(); data.put(ORIGIN, origin);
        actual.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of(data));
    }

    static void detach(ItemStack actual) {
        var previous = actual.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
        if (previous == null) return;
        CompoundTag data = previous.copyTag(); data.remove(ORIGIN);
        if (data.isEmpty()) actual.remove(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
        else actual.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of(data));
    }

    static void detach(ResidentEntity body) {
        for (int cell = 0; cell < body.pack().getContainerSize(); cell++) detach(body.pack().getItem(cell));
        for (EquipmentSlot slot : ResidentEquipment.SLOTS) detach(body.getItemBySlot(slot));
    }
    private static boolean borrowed(ResidentEntity body, EquipmentSlot slot) {
        return TaskTools.holds(body.id(), EquipmentPlans.equipped(body, slot))
            || slot == EquipmentSlot.MAINHAND && TaskTools.holds(body.id(), body.getMainHandItem());
    }
    private boolean validStore(ResidentEntity body, WorldPos at, EquipmentPlans.Demand demand) {
        if (!(body.level() instanceof ServerLevel level) || !at.in(level) || plans.source(level, at).isEmpty()
                || !plans.stores(body).contains(at)) return false;
        var rules = colony.storeRules(at);
        if (!StoreRule.stores(rules) || !StoreRule.admits(rules, demand.spec()) || StoreRule.reserves(rules, demand.spec())) return false;
        var stock = plans.source(level, at).orElseThrow();
        ItemStack example = new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.get(demand.item()), demand.count());
        return !example.isEmpty() && Containers.insert(EquipmentPlans.copy(stock), example).isEmpty();
    }

    private void increment(String key) { statistics.putInt(key, statistics.getInt(key) + 1); }
    private void save() {
        CompoundTag saved = new CompoundTag(); saved.put("configuration", configuration.copy());
        ListTag done = new ListTag(); settled.forEach((id, revision) -> {
            CompoundTag row = new CompoundTag(); row.putUUID("resident", id); row.putLong("revision", revision); done.add(row);
        }); saved.put("settled", done); colony.keep(ID, saved);
    }
    void closed() { batches.clear(); goals.clear(); nextPart.clear(); retryAfter.clear(); asking.offer(Map.of()); }
}
