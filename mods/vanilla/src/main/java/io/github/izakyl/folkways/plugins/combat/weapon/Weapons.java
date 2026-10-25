package io.github.izakyl.folkways.plugins.combat.weapon;

import io.github.izakyl.folkways.core.api.resident.ResidentKinds;
import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.vocation.Vocations;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.ToolNeed;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import io.github.izakyl.folkways.plugins.person.equipment.ResidentEquipment;
import io.github.izakyl.folkways.plugins.patrol.PatrolContent;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.item.enchantment.Enchantments;
import net.neoforged.neoforge.common.Tags;

public final class Weapons {
    private static final ResourceLocation PATROLLING = ResourceLocation.fromNamespaceAndPath("folkways", "patrolling");
    private static final ToolNeed LEGACY_MELEE = new ToolNeed.Tagged(Tags.Items.MELEE_WEAPON_TOOLS);

    public static final WeaponAdapter BOW = new NativeAdapter(NativeWeaponSession.Kind.BOW, 15);
    public static final WeaponAdapter CROSSBOW = new NativeAdapter(NativeWeaponSession.Kind.CROSSBOW, 8);
    public static final WeaponAdapter MELEE = new NativeAdapter(NativeWeaponSession.Kind.MELEE, 4.5);
    private record Registered(ResourceLocation id, WeaponAdapter adapter) { }
    /** Actual resident-owned stack; inspection never copies, equips or assigns it. */
    public record Selection(ItemStack actual, WeaponAdapter adapter, double damagePerSecond) { }
    private record Cached(long until, int cell, ItemStack assigned, boolean locked, Optional<Selection> selection) { }
    private static final Map<ResidentEntity, Cached> choices = new WeakHashMap<>();
    private static volatile List<Registered> registered = List.of(
        new Registered(ResourceLocation.withDefaultNamespace("bow"), BOW),
        new Registered(ResourceLocation.withDefaultNamespace("crossbow"), CROSSBOW),
        new Registered(ResourceLocation.fromNamespaceAndPath("folkways", "melee"), MELEE));

    private Weapons() { }

    public static boolean accepts(ItemStack stack) {
        return registered.stream().anyMatch(entry -> entry.adapter().supportsAssignment() && entry.adapter().accepts(stack));
    }

    /** Register a native extension during mod setup. Explicit adapters precede the common melee tag. */
    public static synchronized void register(ResourceLocation id, WeaponAdapter adapter) {
        Objects.requireNonNull(id);
        Objects.requireNonNull(adapter);
        if (registered.stream().anyMatch(entry -> entry.id().equals(id))) {
            throw new IllegalArgumentException("weapon adapter already registered: " + id);
        }
        List<Registered> next = new ArrayList<>();
        next.add(new Registered(id, adapter));
        next.addAll(registered);
        registered = List.copyOf(next);
        choices.clear();
    }

    public static boolean armed(Worker worker) {
        if (!(worker.body() instanceof ResidentEntity)) {
            return barehanded(worker) || !worker.held(LEGACY_MELEE).isEmpty();
        }
        if (worker.body() instanceof ResidentEntity resident && ResidentEquipment.mainLocked(resident)
                && ResidentEquipment.assigned(resident).isEmpty()) return false;
        return adapter(worker).isPresent() || barehanded(worker);
    }

    public static boolean ranged(Worker worker) {
        if (!(worker.body() instanceof ResidentEntity)) { return false; }
        return adapter(worker).filter(WeaponAdapter::ranged).isPresent();
    }

    public static double range(Worker worker) {
        if (!(worker.body() instanceof ResidentEntity)) { return 4.5; }
        return adapter(worker).map(WeaponAdapter::range).orElse(4.5);
    }

    public static Optional<WeaponSession> open(Worker worker) {
        // Other ResidentKinds retain Core ToolUse combat until their own native hand ownership is adapted.
        if (!(worker.body() instanceof ResidentEntity)) { return Optional.empty(); }
        ResidentEntity resident = (ResidentEntity) worker.body();
        if (resident.hasWeaponSession() || worker.pack() != resident.pack()
                || !worker.resident().id().equals(resident.id()) || !resident.isAlive() || resident.isRemoved()
                || !(resident.level() instanceof ServerLevel level) || !level.getServer().isSameThread()) return Optional.empty();
        // Normalize a real native hand through its existing return lifecycle; presentation copies
        // never become selectable physical inventory.
        resident.clearHandPresentation();
        choices.remove(resident);
        Optional<Selection> selected = selection(worker);
        if (selected.isPresent()) return selected.get().adapter().openAssigned(worker, selected.get().actual());
        if (ResidentEquipment.mainLocked(resident)) return Optional.empty();
        if (!barehanded(worker)) { return Optional.empty(); }
        Optional<WeaponAdapter> unarmed = registered.stream().map(Registered::adapter)
            .filter(candidate -> candidate.acceptsBarehanded(worker)).findFirst();
        return unarmed.isPresent() ? unarmed.get().open(worker) : Optional.of(NativeWeaponSession.barehanded(worker));
    }

    public static boolean barehanded(Worker worker) {
        return ResidentKinds.barehanded(worker.resident().kind(), Optional.of(Vocations.required(PATROLLING)));
    }

    private static Optional<WeaponAdapter> adapter(Worker worker) {
        if (!(worker.body() instanceof ResidentEntity resident)) return Optional.empty();
        if (worker.pack() != resident.pack() || !worker.resident().id().equals(resident.id())) return Optional.empty();
        if (resident.hasWeaponSession()) {
            // Read the existing lease, including an empty chamber; never rescan or change it mid-shot.
            return registered.stream().map(Registered::adapter).filter(WeaponAdapter::supportsAssignment)
                .filter(adapter -> adapter.accepts(resident.getMainHandItem())).findFirst();
        }
        long now = resident.level().getGameTime();
        Cached cached = choices.get(resident);
        if (cached != null && now < cached.until() && cached.locked() == ResidentEquipment.mainLocked(resident)
                && cached.assigned() == ResidentEquipment.assigned(resident)
                && (cached.selection().isEmpty() || cached.cell() < worker.pack().getContainerSize()
                    && worker.pack().getItem(cached.cell()) == cached.selection().get().actual()
                    && ResidentEquipment.selectedForCombat(resident, cached.selection().get().actual()))) {
            return cached.selection().map(Selection::adapter);
        }
        Optional<Selection> selected = selection(worker);
        int cell = -1;
        if (selected.isPresent()) for (int index = 0; index < worker.pack().getContainerSize(); index++) {
            if (worker.pack().getItem(index) == selected.get().actual()) { cell = index; break; }
        }
        // Planning asks armed/range repeatedly. Only these hints may lag ten ticks; open always rescans.
        // An empty hint cannot witness an item or ammunition being inserted later in this same
        // tick. Never cache absence: doing so can suppress real self-defence after a fresh supply.
        if (selected.isPresent()) choices.put(resident, new Cached(now + 10, cell, ResidentEquipment.assigned(resident), ResidentEquipment.mainLocked(resident), selected));
        else choices.remove(resident);
        return selected.map(Selection::adapter);
    }

    /** One bounded scan at acquisition/reselection. Ranged readiness precedes sustained DPS; ties keep pack order. */
    public static Optional<Selection> selection(Worker worker) {
        if (!(worker.body() instanceof ResidentEntity resident) || worker.pack() != resident.pack()
                || !worker.resident().id().equals(resident.id())) return Optional.empty();
        ItemStack assigned = ResidentEquipment.assigned(resident);
        boolean locked = ResidentEquipment.mainLocked(resident);
        Selection best = null;
        for (int cell = 0; cell < worker.pack().getContainerSize(); cell++) {
            ItemStack actual = worker.pack().getItem(cell);
            if (locked ? assigned.isEmpty() || actual != assigned || !ResidentEquipment.selectedForCombat(resident, actual)
                    : !ResidentEquipment.automaticWeaponCandidate(resident, actual)) continue;
            Optional<WeaponAdapter> nativeAdapter = registered.stream().map(Registered::adapter)
                .filter(WeaponAdapter::supportsAssignment).filter(candidate -> candidate.usable(worker, actual)).findFirst();
            if (nativeAdapter.isEmpty()) continue;
            WeaponAdapter candidate = nativeAdapter.get();
            double score = candidate.sustainedDamagePerSecond(worker, actual);
            if (!Double.isFinite(score) || score < 0) score = 0;
            Selection next = new Selection(actual, candidate, score);
            if (best == null || next.adapter().ranged() && !best.adapter().ranged()
                    || next.adapter().ranged() == best.adapter().ranged() && next.damagePerSecond() > best.damagePerSecond() + .000001) best = next;
        }
        return Optional.ofNullable(best);
    }

    /** The weapon is the actual physical stack; consumers must not mutate it while inspecting its demand. */
    public record AmmunitionSupply(ItemStack weapon, WeaponAdapter.Ammunition demand) { }

    public static Optional<WeaponAdapter.Ammunition> ammunition(ItemStack actualWeapon, int reserve) {
        return registered.stream().map(Registered::adapter).filter(adapter -> adapter.accepts(actualWeapon)).findFirst()
            .flatMap(adapter -> adapter.ammunition(actualWeapon, reserve));
    }

    /** Finds the actual selected weapon even when its adapter's usable() currently rejects an empty chamber. */
    public static Optional<AmmunitionSupply> ammunition(ResidentEntity resident) {
        ItemStack actual = resident.hasWeaponSession() ? resident.getMainHandItem() : ResidentEquipment.assigned(resident);
        if (!actual.isEmpty()) {
            ItemStack selected = actual;
            return ammunition(actual, ResidentEquipment.ammoTarget(resident)).map(demand -> new AmmunitionSupply(selected, demand));
        }
        if (ResidentEquipment.mainLocked(resident)) return Optional.empty();
        Optional<PatrolSupplies.Loadout> preference = resident.level() instanceof ServerLevel level ? resident.colonyId()
            .flatMap(id -> Colonies.of(level.getServer(), id)).flatMap(PatrolContent::suppliesOf)
            .flatMap(supplies -> supplies.preference(resident.id())) : Optional.empty();
        if (preference.isPresent()) {
            for (int cell = 0; cell < resident.pack().getContainerSize(); cell++) {
                ItemStack stack = resident.pack().getItem(cell);
                if (ResidentEquipment.weaponCandidate(resident, stack) && PatrolSupplies.prefers(preference.get(), stack)) {
                    return ammunition(stack, ResidentEquipment.ammoTarget(resident)).map(demand -> new AmmunitionSupply(stack, demand));
                }
            }
        }
        for (int cell = 0; cell < resident.pack().getContainerSize(); cell++) {
            ItemStack stack = resident.pack().getItem(cell);
            if (ResidentEquipment.weaponCandidate(resident, stack)
                    && registered.stream().anyMatch(entry -> entry.adapter().accepts(stack))) {
                return ammunition(stack, ResidentEquipment.ammoTarget(resident)).map(demand -> new AmmunitionSupply(stack, demand));
            }
        }
        return Optional.empty();
    }

    private record NativeAdapter(NativeWeaponSession.Kind kind, double range) implements WeaponAdapter {
        @Override
        public Optional<Ammunition> ammunition(ItemStack actualWeapon, int reserve) {
            if (!ranged() || !accepts(actualWeapon)) return Optional.empty();
            ProjectileWeaponItem weapon = (ProjectileWeaponItem) actualWeapon.getItem();
            var compatible = weapon.getAllSupportedProjectiles(actualWeapon);
            var loaded = actualWeapon.get(net.minecraft.core.component.DataComponents.CHARGED_PROJECTILES);
            int paid = loaded != null && loaded.getItems().stream().anyMatch(compatible) ? 1 : 0;
            return Optional.of(new Ammunition(io.github.izakyl.folkways.core.api.terms.ItemSpec.of(net.minecraft.tags.ItemTags.ARROWS),
                compatible, reserve, paid));
        }
        @Override
        public boolean supportsAssignment() { return true; }

        @Override
        public double sustainedDamagePerSecond(Worker worker, ItemStack actual) {
            if (kind == NativeWeaponSession.Kind.MELEE) return WeaponScores.nativeMelee(worker.body(), actual);
            if (kind == NativeWeaponSession.Kind.BOW) {
                int power = WeaponScores.enchantment(worker.body(), actual, Enchantments.POWER);
                double arrowDamage = 2 + (power == 0 ? 0 : .5 * power + .5);
                return Math.ceil(3 * arrowDamage) * 1.25 * 20 / BowItem.MAX_DRAW_DURATION;
            }
            // Native crossbows spend their actual enchanted charge time plus one firing tick.
            return Math.ceil(CrossbowItem.MOB_ARROW_POWER * 2) * 20 / Math.max(1, CrossbowItem.getChargeDuration(actual, worker.body()) + 1);
        }

        @Override
        public Optional<WeaponSession> openAssigned(Worker worker, ItemStack selected) {
            return openSelected(worker, selected, true);
        }

        private Optional<WeaponSession> openSelected(Worker worker, ItemStack selected, boolean requireReady) {
            if (!(worker.body() instanceof ResidentEntity resident)
                    || resident.hasWeaponSession() || worker.pack() != resident.pack()
                    || !worker.resident().id().equals(resident.id()) || !resident.isAlive() || resident.isRemoved()
                    || !(resident.level() instanceof ServerLevel level) || !level.getServer().isSameThread()
                    || !ResidentEquipment.selectedForCombat(resident, selected) || !accepts(selected)
                    || requireReady && !usable(worker, selected)) return Optional.empty();
            for (int cell = 0; cell < worker.pack().getContainerSize(); cell++) {
                if (worker.pack().getItem(cell) != selected) continue;
                ItemStack actual = worker.pack().removeItemNoUpdate(cell);
                NativeWeaponSession session = new NativeWeaponSession(worker, this, kind);
                try { resident.claimWeaponHand(session, actual); }
                catch (RuntimeException | LinkageError failure) {
                    if (resident.ownsWeaponHand(session)) session.close();
                    else if (worker.pack().getItem(cell).isEmpty()) worker.pack().setItem(cell, actual);
                    else { ItemStack remainder = resident.pack().insert(actual); if (!remainder.isEmpty()) resident.spawnAtLocation(remainder); }
                    throw failure;
                }
                return Optional.of(session);
            }
            return Optional.empty();
        }

        @Override
        public boolean ranged() { return kind != NativeWeaponSession.Kind.MELEE; }

        @Override
        public boolean usable(Worker worker, ItemStack stack) {
            if (!accepts(stack)) {
                return false;
            }
            if (!ranged()) return true;
            if (kind == NativeWeaponSession.Kind.CROSSBOW && CrossbowItem.isCharged(stack)) {
                var loaded = stack.get(DataComponents.CHARGED_PROJECTILES);
                return loaded != null && loaded.getItems().stream().anyMatch(projectile ->
                    ((ProjectileWeaponItem) stack.getItem()).getAllSupportedProjectiles(stack).test(projectile)
                        && (!(worker.body() instanceof ResidentEntity resident) || ResidentEquipment.ammoCandidate(resident, projectile)));
            }
            ProjectileWeaponItem item = (ProjectileWeaponItem) stack.getItem();
            for (int slot = 0; slot < worker.pack().getContainerSize(); slot++) {
                ItemStack ammunition = worker.pack().getItem(slot);
                if (!ammunition.isEmpty() && (!(worker.body() instanceof ResidentEntity resident)
                        || ResidentEquipment.ammoCandidate(resident, ammunition))
                        && item.getAllSupportedProjectiles(stack).test(ammunition)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean accepts(ItemStack stack) {
            return !stack.isEmpty() && switch (kind) {
                case BOW -> stack.is(Items.BOW);
                case CROSSBOW -> stack.is(Items.CROSSBOW);
                case MELEE -> stack.is(Tags.Items.MELEE_WEAPON_TOOLS) || stack.getItem() instanceof AxeItem;
            };
        }

        @Override
        public Optional<WeaponSession> open(Worker worker) {
            if (!(worker.body() instanceof ResidentEntity resident) || resident.hasWeaponSession()
                    || worker.pack() != resident.pack() || !resident.isAlive() || resident.isRemoved()
                    || !(resident.level() instanceof ServerLevel level) || !level.getServer().isSameThread()) {
                return Optional.empty();
            }
            // Retain the explicit adapter entry point: callers may inspect/reload an empty native
            // weapon. Automatic combat always uses selection()+openAssigned(), which require ammo.
            ItemStack best = ItemStack.EMPTY;
            double score = -1;
            boolean ready = false;
            for (int cell = 0; cell < worker.pack().getContainerSize(); cell++) {
                ItemStack actual = worker.pack().getItem(cell);
                if (!accepts(actual) || !ResidentEquipment.selectedForCombat(resident, actual)) continue;
                boolean usable = usable(worker, actual);
                double next = usable ? sustainedDamagePerSecond(worker, actual) : 0;
                if (best.isEmpty() || usable && !ready || usable == ready && next > score) {
                    best = actual; score = next; ready = usable;
                }
            }
            return best.isEmpty() ? Optional.empty() : openSelected(worker, best, false);
        }
    }
}
