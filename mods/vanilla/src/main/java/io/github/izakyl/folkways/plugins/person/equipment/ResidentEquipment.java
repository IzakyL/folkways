package io.github.izakyl.folkways.plugins.person.equipment;

import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.resident.body.Equipment;
import io.github.izakyl.folkways.core.api.resident.body.PersonalItems;
import io.github.izakyl.folkways.core.api.resident.body.TaskTools;
import io.github.izakyl.folkways.core.api.terms.Containers;
import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
import io.github.izakyl.folkways.front.engine.authority.ColonyAuthority;
import io.github.izakyl.folkways.plugins.combat.control.CombatSelectionOwnership;
import io.github.izakyl.folkways.plugins.combat.weapon.Weapons;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ChargedProjectiles;
import org.slf4j.LoggerFactory;

/** A loadout names physical items, not copies of them. All exchanges run on the server thread. */
public final class ResidentEquipment {
    private static final String SETTINGS = "folkways:resident_equipment";
    private static final int PLAYER_CELLS = 36;
    public static final int EXCHANGE_RANGE = 6;
    public static final List<EquipmentSlot> SLOTS = List.of(EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND,
        EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET);
    // The weak key is the connection; values contain neither players nor entities and never enter a save.
    private static final Map<ServerPlayer, View> VIEWS = new WeakHashMap<>();
    private static final Map<ServerPlayer, Feedback> RESULTS = new WeakHashMap<>();

    private record View(UUID resident, UUID ticket, int menu, CompoundTag seen) { }
    private record Feedback(String key, int[] arguments) { }

    private ResidentEquipment() { }

    public static void clear() { VIEWS.clear(); RESULTS.clear(); }

    public static int ammoTarget(ResidentEntity resident) {
        int saved = resident.getPersistentData().getCompound(SETTINGS).getInt("ammoTarget");
        return saved == 8 || saved == 16 || saved == 32 ? saved : 8;
    }

    public static ItemStack assigned(ResidentEntity resident) {
        if (resident.hasWeaponSession() && PersonalItems.purpose(resident, resident.getMainHandItem())
                .filter(EquipmentSlot.MAINHAND.getName()::equals).isPresent()) {
            return resident.getMainHandItem();
        }
        int cell = assignedCell(resident);
        return cell < 0 ? ItemStack.EMPTY : resident.pack().getItem(cell);
    }

    private static int assignedCell(ResidentEntity resident) {
        for (int cell = 0; cell < resident.pack().getContainerSize(); cell++) {
            if (PersonalItems.purpose(resident, resident.pack().getItem(cell))
                    .filter(EquipmentSlot.MAINHAND.getName()::equals).isPresent()) return cell;
        }
        return -1;
    }

    public static boolean mainLocked(ResidentEntity resident) {
        return Equipment.locked(resident, EquipmentSlot.MAINHAND);
    }

    public static boolean ammoCandidate(ResidentEntity resident, ItemStack stack) {
        return !stack.isEmpty() && (!PersonalItems.marked(stack) || PersonalItems.belongsTo(resident, stack))
            && !TaskTools.holds(resident.id(), stack);
    }

    /** A spare is not a second assignment; other residents' goods are never eligible. */
    public static boolean weaponCandidate(ResidentEntity resident, ItemStack stack) {
        if (stack.isEmpty() || PersonalItems.marked(stack) && !PersonalItems.belongsTo(resident, stack)
                || TaskTools.holds(resident.id(), stack)) return false;
        ItemStack assigned = assigned(resident);
        if (!assigned.isEmpty()) return stack == assigned;
        return !mainLocked(resident);
    }

    public static CompoundTag read(ServerPlayer actor, ResidentEntity resident) {
        if (colony(actor, resident).isEmpty()) return new CompoundTag();
        CompoundTag data = snapshot(actor, resident);
        View view = VIEWS.get(actor);
        int menu = System.identityHashCode(actor.containerMenu);
        if (view == null || !view.resident().equals(resident.id()) || view.menu() != menu || !view.seen().equals(data)) {
            view = new View(resident.id(), UUID.randomUUID(), menu, data.copy());
            VIEWS.put(actor, view);
        }
        data.putUUID("ticket", view.ticket());
        Feedback feedback = RESULTS.get(actor);
        data.putString("result", editable(actor, resident) ? feedback == null ? "" : feedback.key() : key("forbidden"));
        if (feedback != null && editable(actor, resident)) data.putIntArray("resultArgs", feedback.arguments());
        return data;
    }

    public static void act(ServerPlayer actor, ResidentEntity resident, CompoundTag action) {
        if (colony(actor, resident).isEmpty() || !editable(actor, resident)) { report(actor, "forbidden"); return; }
        View view = VIEWS.remove(actor);
        if (view == null || !action.hasUUID("resident") || !action.hasUUID("ticket")
                || !action.getUUID("resident").equals(resident.id()) || !view.resident().equals(resident.id())
                || !action.getUUID("ticket").equals(view.ticket())
                || view.menu() != System.identityHashCode(actor.containerMenu) || !view.seen().equals(snapshot(actor, resident))) {
            report(actor, "stale"); return;
        }
        EquipmentSlot slot = SLOTS.stream().filter(value -> value.getName().equals(action.getString("slot")))
            .findFirst().orElse(null);
        if (action.getString("kind").equals("fill")) {
            RESULTS.put(actor, fill(actor, resident));
            return;
        }
        String result = switch (action.getString("kind")) {
            case "equip" -> slot == null ? "invalid" : equip(actor, resident, slot, action.getString("source"), action.getInt("index"));
            case "unequip" -> slot == null ? "invalid" : unequip(resident, slot);
            case "lock" -> slot == null ? "invalid" : lock(resident, slot, action.getBoolean("value"));
            case "ammo_target" -> target(resident, action.getInt("value"));
            case "ammo_fill" -> ammunition(actor, resident);
            default -> "invalid";
        };
        report(actor, result);
    }

    private static Optional<Colony> colony(ServerPlayer actor, ResidentEntity resident) {
        if (actor.getServer() == null || !actor.getServer().isSameThread() || !actor.isAlive() || actor.isRemoved()
                || actor.isSpectator() || !resident.isAlive() || resident.isRemoved()
                || !(resident.level() instanceof ServerLevel level) || level.getServer() != actor.getServer()
                || level.getEntity(resident.id()) != resident || resident.colonyId().isEmpty()) return Optional.empty();
        return ColonyAuthority.of(actor, Optional.empty()).map(ColonyAuthority::colony)
            .filter(colony -> resident.colonyId().filter(colony.id()::equals).isPresent()
                && colony.holdingOf(resident.id()).isPresent());
    }

    private static boolean editable(ServerPlayer actor, ResidentEntity resident) {
        return colony(actor, resident).filter(colony -> CombatSelectionOwnership.of(colony)
            .availableOnPage(actor, resident.id())).isPresent();
    }

    private static boolean nearby(ServerPlayer actor, ResidentEntity resident) {
        return actor.level() == resident.level() && WorldSpaces.at(actor).sameRealm(WorldSpaces.at(resident))
            && actor.distanceToSqr(resident) <= EXCHANGE_RANGE * EXCHANGE_RANGE;
    }

    private static boolean busy(ResidentEntity resident) {
        return resident.hasWeaponSession() || !resident.getMainHandItem().isEmpty();
    }

    private static boolean usable(ResidentEntity resident, ItemStack stack) {
        return !stack.isEmpty() && !TaskTools.holds(resident.id(), stack)
            && (PersonalItems.belongsTo(resident, stack)
                || !PersonalItems.marked(stack) && resident.doing().isEmpty());
    }

    private static CompoundTag snapshot(ServerPlayer actor, ResidentEntity resident) {
        CompoundTag data = new CompoundTag();
        data.putUUID("resident", resident.id());
        data.putBoolean("near", nearby(actor, resident));
        data.putBoolean("busy", busy(resident));
        data.putBoolean("editable", editable(actor, resident));
        data.putInt("ammoTarget", ammoTarget(resident));
        data.putInt("ammoCount", resident.pack().countMatching(stack -> stack.is(ItemTags.ARROWS) && ammoCandidate(resident, stack)));
        data.putInt("loadedAmmo", loadedAmmo(resident));
        data.put("current", resident.getMainHandItem().saveOptional(actor.registryAccess()));
        ListTag slots = new ListTag();
        for (EquipmentSlot slot : SLOTS) {
            CompoundTag row = new CompoundTag();
            row.putString("slot", slot.getName());
            ItemStack stack = slot == EquipmentSlot.MAINHAND ? assigned(resident) : resident.getItemBySlot(slot);
            row.put("stack", stack.saveOptional(actor.registryAccess()));
            row.putBoolean("locked", Equipment.locked(resident, slot));
            row.putBoolean("busy", slot == EquipmentSlot.MAINHAND && busy(resident) || TaskTools.holds(resident.id(), stack));
            slots.add(row);
        }
        data.put("slots", slots);
        ListTag pack = new ListTag();
        for (int cell = 0; cell < resident.pack().getContainerSize(); cell++) {
            ItemStack stack = resident.pack().getItem(cell);
            CompoundTag row = cell(actor, cell, stack);
            row.putBoolean("personal", PersonalItems.belongsTo(resident, stack));
            row.putBoolean("borrowed", TaskTools.holds(resident.id(), stack));
            row.putBoolean("remote", !nearby(actor, resident) && !PersonalItems.belongsTo(resident, stack));
            row.putBoolean("usable", usable(resident, stack) && (nearby(actor, resident) || PersonalItems.belongsTo(resident, stack)));
            pack.add(row);
        }
        data.put("pack", pack);
        ListTag player = new ListTag();
        for (int cell = 0; cell < PLAYER_CELLS; cell++) {
            ItemStack stack = actor.getInventory().getItem(cell);
            CompoundTag row = cell(actor, cell, stack);
            row.putBoolean("personal", !PersonalItems.marked(stack));
            row.putBoolean("usable", nearby(actor, resident) && !stack.isEmpty() && !PersonalItems.marked(stack));
            player.add(row);
        }
        data.put("player", player);
        return data;
    }

    private static CompoundTag cell(ServerPlayer actor, int cell, ItemStack stack) {
        CompoundTag row = new CompoundTag();
        row.putInt("index", cell);
        row.put("stack", stack.saveOptional(actor.registryAccess()));
        return row;
    }

    public static int loadedAmmo(ResidentEntity resident) {
        List<ItemStack> stacks = new ArrayList<>(resident.pack().contents());
        if (resident.hasWeaponSession()) stacks.add(resident.getMainHandItem());
        int loaded = 0;
        for (ItemStack stack : stacks) {
            var projectiles = stack.get(DataComponents.CHARGED_PROJECTILES);
            // Native Multishot stores several projectile copies for one paid arrow.
            if (projectiles != null && ammoCandidate(resident, stack)
                    && projectiles.getItems().stream().anyMatch(item -> item.is(ItemTags.ARROWS))) loaded++;
        }
        return loaded;
    }

    /** Ownership ends when the item or its loaded ammunition leaves the resident's custody. */
    public static void released(ItemStack actual) {
        PersonalItems.clear(actual);
        var loaded = actual.get(DataComponents.CHARGED_PROJECTILES);
        if (loaded != null && !loaded.isEmpty()) {
            List<ItemStack> projectiles = loaded.getItems().stream().map(ItemStack::copy).toList();
            projectiles.forEach(PersonalItems::clear);
            actual.set(DataComponents.CHARGED_PROJECTILES, ChargedProjectiles.of(projectiles));
        }
    }

    private static String equip(ServerPlayer actor, ResidentEntity resident, EquipmentSlot slot, String source, int cell) {
        if (!source.equals("resident") && !source.equals("player")) return "invalid";
        if (source.equals("player") && !nearby(actor, resident)) return "too_far";
        if (slot == EquipmentSlot.MAINHAND && busy(resident)) return "busy";
        SimpleContainer pack = copy(resident.pack(), resident.pack().getContainerSize());
        SimpleContainer player = copy(actor.getInventory(), PLAYER_CELLS);
        Container from = source.equals("resident") ? pack : player;
        if (cell < 0 || cell >= from.getContainerSize()) return "invalid";
        ItemStack candidate = from.getItem(cell);
        if (candidate.isEmpty()) return "no_item";
        if (source.equals("resident") && !PersonalItems.belongsTo(resident, candidate) && !nearby(actor, resident)) return "too_far";
        if (source.equals("resident") ? !usable(resident, candidate) : PersonalItems.marked(candidate)) return "not_personal";
        if (!accepts(resident, slot, candidate)) return "invalid";
        int mainCell = assignedCell(resident);
        if (slot == EquipmentSlot.MAINHAND && source.equals("resident") && cell == mainCell) return "no_change";
        ItemStack original = slot == EquipmentSlot.MAINHAND ? assigned(resident) : resident.getItemBySlot(slot);
        if (TaskTools.holds(resident.id(), original)) return "busy";
        if (PersonalItems.marked(original) && !PersonalItems.belongsTo(resident, original)) return "not_personal";
        ItemStack incoming = from.removeItem(cell, 1);
        ItemStack old = original.copy();
        if (slot == EquipmentSlot.MAINHAND && mainCell >= 0) pack.setItem(mainCell, ItemStack.EMPTY);
        if (!old.isEmpty()) {
            if (source.equals("player")) released(old);
            else PersonalItems.mark(resident, old, "spare");
            if (!Containers.insert(from, old).isEmpty()) return "no_room";
        }
        PersonalItems.mark(resident, incoming, slot.getName());
        if (slot == EquipmentSlot.MAINHAND && !Containers.insert(pack, incoming).isEmpty()) return "no_room";
        if (!roomForReturn(resident, pack)) return "no_room";
        SimpleContainer packBefore = copy(resident.pack(), resident.pack().getContainerSize());
        SimpleContainer playerBefore = copy(actor.getInventory(), PLAYER_CELLS);
        write(resident.pack(), pack);
        write(actor.getInventory(), player);
        if (slot != EquipmentSlot.MAINHAND && !wear(resident, slot, original, incoming)) {
            write(resident.pack(), packBefore); write(actor.getInventory(), playerBefore); return "invalid";
        }
        Equipment.manual(resident, slot);
        Equipment.lock(resident, slot, true);
        return "ok";
    }

    private static boolean accepts(ResidentEntity resident, EquipmentSlot slot, ItemStack stack) {
        if (slot == EquipmentSlot.MAINHAND) return Weapons.accepts(stack);
        if (slot == EquipmentSlot.OFFHAND) return true;
        return resident.getEquipmentSlotForItem(stack) == slot;
    }

    private static boolean wear(ResidentEntity resident, EquipmentSlot slot, ItemStack original, ItemStack actual) {
        try {
            resident.setItemSlot(slot, actual);
        } catch (RuntimeException | LinkageError broken) {
            if (resident.getItemBySlot(slot) == original) return false;
            if (resident.getItemBySlot(slot) != actual) throw broken;
            LoggerFactory.getLogger("folkways-equipment").warn("equipment installed before its event failed", broken);
        }
        resident.setDropChance(slot, 2.0F);
        return true;
    }

    private static String unequip(ResidentEntity resident, EquipmentSlot slot) {
        if (slot == EquipmentSlot.MAINHAND && busy(resident)) return "busy";
        ItemStack original = slot == EquipmentSlot.MAINHAND ? assigned(resident) : resident.getItemBySlot(slot);
        if (TaskTools.holds(resident.id(), original)) return "busy";
        if (original.isEmpty()) return "empty";
        if (PersonalItems.marked(original) && !PersonalItems.belongsTo(resident, original)) return "not_personal";
        if (slot == EquipmentSlot.MAINHAND) {
            PersonalItems.mark(resident, original, "spare");
        } else {
            SimpleContainer pack = copy(resident.pack(), resident.pack().getContainerSize());
            ItemStack old = original.copy(); PersonalItems.mark(resident, old, "spare");
            if (!Containers.insert(pack, old).isEmpty()) return "no_room";
            if (!roomForReturn(resident, pack)) return "no_room";
            if (!wear(resident, slot, original, ItemStack.EMPTY)) return "invalid";
            write(resident.pack(), pack);
        }
        Equipment.manual(resident, slot);
        Equipment.lock(resident, slot, true);
        return "ok";
    }

    private static String lock(ResidentEntity resident, EquipmentSlot slot, boolean value) {
        if (slot == EquipmentSlot.MAINHAND && busy(resident)) return "busy";
        if (TaskTools.holds(resident.id(), slot == EquipmentSlot.MAINHAND ? assigned(resident) : resident.getItemBySlot(slot))) return "busy";
        Equipment.lock(resident, slot, value);
        return "ok";
    }

    private static Feedback fill(ServerPlayer actor, ResidentEntity resident) {
        int changed = 0, ambiguous = 0, noRoom = 0;
        for (EquipmentSlot slot : SLOTS) {
            if (Equipment.locked(resident, slot) || slot == EquipmentSlot.MAINHAND && busy(resident)) continue;
            if (!(slot == EquipmentSlot.MAINHAND ? assigned(resident) : resident.getItemBySlot(slot)).isEmpty()) continue;
            List<Integer> candidates = new ArrayList<>();
            for (int cell = 0; cell < resident.pack().getContainerSize(); cell++) {
                ItemStack stack = resident.pack().getItem(cell);
                if (PersonalItems.belongsTo(resident, stack) && usable(resident, stack)
                        && accepts(resident, slot, stack)
                        && (slot != EquipmentSlot.OFFHAND || resident.getEquipmentSlotForItem(stack) == slot)) candidates.add(cell);
            }
            if (candidates.size() == 1) {
                String result = equip(actor, resident, slot, "resident", candidates.getFirst());
                if (result.equals("ok")) changed++;
                else if (result.equals("no_room")) noRoom++;
            } else if (candidates.size() > 1) ambiguous++;
        }
        if (noRoom > 0 || changed > 0 && ambiguous > 0) return new Feedback(key("fill_summary"), new int[]{changed, ambiguous, noRoom});
        return new Feedback(key(ambiguous > 0 ? "ambiguous" : changed > 0 ? "ok" : "no_change"), new int[0]);
    }

    private static String target(ResidentEntity resident, int value) {
        if (value != 8 && value != 16 && value != 32) return "invalid";
        CompoundTag settings = resident.getPersistentData().getCompound(SETTINGS);
        settings.putInt("ammoTarget", value); resident.getPersistentData().put(SETTINGS, settings);
        return "ok";
    }

    private static String ammunition(ServerPlayer actor, ResidentEntity resident) {
        if (!nearby(actor, resident)) return "too_far";
        int missing = Math.max(0, ammoTarget(resident) - loadedAmmo(resident)
            - resident.pack().countMatching(stack -> stack.is(ItemTags.ARROWS) && ammoCandidate(resident, stack)));
        if (missing == 0) return "no_change";
        SimpleContainer pack = copy(resident.pack(), resident.pack().getContainerSize());
        SimpleContainer player = copy(actor.getInventory(), PLAYER_CELLS);
        int inserted = 0;
        boolean stock = false;
        for (int cell = 0; cell < PLAYER_CELLS && inserted < missing; cell++) {
            ItemStack stack = player.getItem(cell);
            if (!stack.is(ItemTags.ARROWS) || PersonalItems.marked(stack)) continue;
            stock = true;
            ItemStack offer = stack.copyWithCount(Math.min(stack.getCount(), missing - inserted));
            PersonalItems.mark(resident, offer, "ammo");
            int count = offer.getCount() - Containers.insert(pack, offer).getCount();
            if (!roomForReturn(resident, pack)) return "no_room";
            if (count > 0) { player.removeItem(cell, count); inserted += count; }
        }
        if (inserted == 0) return stock ? "no_room" : "no_item";
        write(resident.pack(), pack); write(actor.getInventory(), player);
        return inserted < missing ? "partial" : "ok";
    }

    private static SimpleContainer copy(Container original, int size) {
        SimpleContainer copy = new SimpleContainer(size);
        for (int cell = 0; cell < size; cell++) copy.setItem(cell, original.getItem(cell).copy());
        return copy;
    }

    private static boolean roomForReturn(ResidentEntity resident, Container pack) {
        return !resident.hasWeaponSession() || Containers.insert(copy(pack, pack.getContainerSize()),
            resident.getMainHandItem().copy()).isEmpty();
    }

    private static void write(Container into, Container from) {
        for (int cell = 0; cell < from.getContainerSize(); cell++) into.setItem(cell, from.getItem(cell));
        into.setChanged();
    }

    private static String key(String result) { return "folkways.equipment.result." + result; }
    private static void report(ServerPlayer actor, String result) { RESULTS.put(actor, new Feedback(key(result), new int[0])); }
}
