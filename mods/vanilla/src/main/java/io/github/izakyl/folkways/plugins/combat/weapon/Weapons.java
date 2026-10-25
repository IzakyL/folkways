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
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.neoforged.neoforge.common.Tags;

public final class Weapons {
    private static final ResourceLocation PATROLLING = ResourceLocation.fromNamespaceAndPath("folkways", "patrolling");
    private static final ToolNeed LEGACY_MELEE = new ToolNeed.Tagged(Tags.Items.MELEE_WEAPON_TOOLS);

    public static final WeaponAdapter BOW = new NativeAdapter(NativeWeaponSession.Kind.BOW, 15);
    public static final WeaponAdapter CROSSBOW = new NativeAdapter(NativeWeaponSession.Kind.CROSSBOW, 8);
    public static final WeaponAdapter MELEE = new NativeAdapter(NativeWeaponSession.Kind.MELEE, 4.5);
    private record Registered(ResourceLocation id, WeaponAdapter adapter) { }
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
    }

    public static boolean armed(Worker worker) {
        if (!(worker.body() instanceof ResidentEntity)) {
            return barehanded(worker) || !worker.held(LEGACY_MELEE).isEmpty();
        }
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
        Optional<WeaponAdapter> adapter = adapter(worker);
        if (adapter.isPresent()) {
            ItemStack assigned = ResidentEquipment.assigned((ResidentEntity) worker.body());
            if (!assigned.isEmpty()) return adapter.get().openAssigned(worker, assigned);
            return adapter.get().open(worker);
        }
        if (!barehanded(worker)) { return Optional.empty(); }
        Optional<WeaponAdapter> unarmed = registered.stream().map(Registered::adapter)
            .filter(candidate -> candidate.acceptsBarehanded(worker)).findFirst();
        return unarmed.isPresent() ? unarmed.get().open(worker) : Optional.of(NativeWeaponSession.barehanded(worker));
    }

    public static boolean barehanded(Worker worker) {
        return ResidentKinds.barehanded(worker.resident().kind(), Optional.of(Vocations.required(PATROLLING)));
    }

    private static Optional<WeaponAdapter> adapter(Worker worker) {
        if (worker.body() instanceof ResidentEntity resident && resident.hasWeaponSession()) {
            return registered.stream().map(Registered::adapter)
                .filter(adapter -> adapter.usable(worker, resident.getMainHandItem())).findFirst();
        }
        if (worker.body() instanceof ResidentEntity resident) {
            ItemStack assigned = ResidentEquipment.assigned(resident);
            if (!assigned.isEmpty()) {
                return registered.stream().map(Registered::adapter)
                    .filter(WeaponAdapter::supportsAssignment)
                    .filter(adapter -> adapter.usable(worker, assigned)).findFirst();
            }
            if (ResidentEquipment.mainLocked(resident)) { return Optional.empty(); }
        }
        if (worker.body() instanceof ResidentEntity resident && resident.level() instanceof ServerLevel level) {
            Optional<PatrolSupplies.Loadout> preference = resident.colonyId()
                .flatMap(id -> Colonies.of(level.getServer(), id)).flatMap(PatrolContent::suppliesOf)
                .flatMap(supplies -> supplies.preference(resident.id()));
            if (preference.isPresent()) {
                for (int slot = 0; slot < worker.pack().getContainerSize(); slot++) {
                    ItemStack stack = worker.pack().getItem(slot);
                    if (!ResidentEquipment.weaponCandidate(resident, stack)) { continue; }
                    if (!PatrolSupplies.prefers(preference.get(), stack)) { continue; }
                    Optional<WeaponAdapter> preferred = registered.stream().map(Registered::adapter)
                        .filter(adapter -> adapter.usable(worker, stack)).findFirst();
                    if (preferred.isPresent()) { return preferred; }
                }
            }
        }
        // Keep pack order: a resident deliberately carrying a sword before a bow uses its sword.
        for (int slot = 0; slot < worker.pack().getContainerSize(); slot++) {
            ItemStack stack = worker.pack().getItem(slot);
            if (worker.body() instanceof ResidentEntity resident
                    && !ResidentEquipment.weaponCandidate(resident, stack)) { continue; }
            Optional<WeaponAdapter> found = registered.stream().map(Registered::adapter)
                .filter(adapter -> adapter.usable(worker, stack)).findFirst();
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
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
        public Optional<WeaponSession> openAssigned(Worker worker, ItemStack selected) {
            if (!(worker.body() instanceof ResidentEntity resident)
                    || ResidentEquipment.assigned(resident) != selected || !usable(worker, selected)) return Optional.empty();
            return open(worker);
        }

        @Override
        public boolean ranged() { return kind != NativeWeaponSession.Kind.MELEE; }

        @Override
        public boolean usable(Worker worker, ItemStack stack) {
            if (!accepts(stack)) {
                return false;
            }
            if (!ranged() || kind == NativeWeaponSession.Kind.CROSSBOW && CrossbowItem.isCharged(stack)) {
                return true;
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
                case MELEE -> stack.is(Tags.Items.MELEE_WEAPON_TOOLS);
            };
        }

        @Override
        public Optional<WeaponSession> open(Worker worker) {
            if (!(worker.body() instanceof ResidentEntity resident) || resident.hasWeaponSession()
                    || worker.pack() != resident.pack() || !resident.isAlive() || resident.isRemoved()
                    || !(resident.level() instanceof ServerLevel level) || !level.getServer().isSameThread()) {
                return Optional.empty();
            }
            int selected = -1;
            for (int slot = 0; slot < worker.pack().getContainerSize(); slot++) {
                if (ResidentEquipment.weaponCandidate(resident, worker.pack().getItem(slot))
                        && accepts(worker.pack().getItem(slot))) {
                    if (selected < 0) { selected = slot; }
                    if (usable(worker, worker.pack().getItem(slot))) { selected = slot; break; }
                }
            }
            if (selected < 0) { return Optional.empty(); }
            ItemStack actual = worker.pack().removeItemNoUpdate(selected);
            NativeWeaponSession session = new NativeWeaponSession(worker, this, kind);
            resident.claimWeaponHand(session, actual);
            return Optional.of(session);
        }
    }
}
