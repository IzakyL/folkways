package io.github.izakyl.folkways.plugins.combat.weapon;

import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.resident.api.ResidentKinds;
import io.github.izakyl.folkways.core.vocation.api.Vocations;
import io.github.izakyl.folkways.core.work.api.ToolNeed;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.plugins.patrol.PatrolContent;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
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

    private record Candidate(WeaponAdapter adapter, ItemStack stack, ItemSpec items) {
        boolean ranged() {
            return LoadoutRegistry.using(stack).map(provider -> provider.ranged(stack)).orElseGet(adapter::ranged);
        }

        double range(Worker worker) {
            double reach = LoadoutRegistry.using(stack)
                .map(provider -> provider.range(stack))
                .orElseGet(() -> adapter.range(worker, stack));
            return reach + (ranged() ? 0 : MountedMelee.reach(worker.body()));
        }
    }

    private record Registered(ResourceLocation id, WeaponAdapter adapter) {
    }

    private static volatile List<Registered> registered =
        List.of(
        new Registered(ResourceLocation.withDefaultNamespace("bow"), BOW),
        new Registered(ResourceLocation.withDefaultNamespace("crossbow"), CROSSBOW),
        new Registered(ResourceLocation.fromNamespaceAndPath("folkways", "melee"), MELEE));

    private Weapons() {
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
        return permitted(worker) && !awaitingSupply(worker)
            && (profile(worker).isEmpty() || adapter(worker, null).isPresent() || barehanded(worker));
    }

    public static boolean awaitingSupply(Worker worker) {
        return supplies(worker).filter(value -> value.awaitingWeapon(worker.resident().id())).isPresent();
    }

    public static boolean ranged(Worker worker) {
        if (!(worker.body() instanceof ResidentEntity)) {
            return false;
        }
        return adapter(worker, null).filter(candidate -> candidate.ranged()).isPresent();
    }

    public static double range(Worker worker) {
        if (!(worker.body() instanceof ResidentEntity)) {
            return 4.5;
        }
        return adapter(worker, null).map(candidate -> candidate.range(worker))
            .orElseGet(() -> 4.5 + MountedMelee.reach(worker.body()));
    }

    public static Optional<WeaponSession> open(Worker worker) {
        // Other ResidentKinds retain Core ToolUse combat until their own native hand ownership is
        // adapted.
        if (!(worker.body() instanceof ResidentEntity)) {
            return Optional.empty();
        }
        if (!permitted(worker)) {
            return Optional.empty();
        }
        return open(worker, null);
    }

    private static boolean barehanded(Worker worker) {
        return ResidentKinds.barehanded(worker.resident().kind(), Optional.of(Vocations.required(PATROLLING)));
    }

    private static Optional<PatrolSupplies> supplies(Worker worker) {
        return worker.body() instanceof ResidentEntity resident && resident.level() instanceof ServerLevel level
            ? resident.colonyId()
            .flatMap(id -> Colonies.of(level.getServer(), id))
            .flatMap(PatrolContent::suppliesOf)
            : Optional.empty();
    }

    public static boolean permitted(Worker worker) {
        return supplies(worker).map(supplies -> supplies.mayEngage(worker.resident().id())).orElse(true);
    }

    public static boolean ranged(Worker worker, LivingEntity target) {
        return adapter(worker, target).filter(candidate -> candidate.ranged()).isPresent();
    }

    public static boolean indirect(Worker worker, LivingEntity target) {
        return adapter(worker, target)
            .flatMap(candidate -> LoadoutRegistry.using(candidate.stack())
                .map(provider -> provider.indirect(candidate.stack())))
            .orElse(false);
    }

    public static boolean longRange(Worker worker, LivingEntity target) {
        return adapter(worker, target).filter(Candidate::ranged)
            .flatMap(candidate -> LoadoutRegistry.using(candidate.stack())
                .map(provider -> provider.longRange(candidate.stack())))
            .orElse(false);
    }

    public static double range(Worker worker, LivingEntity target) {
        return adapter(worker, target).map(candidate -> candidate.range(worker))
            .orElseGet(() -> 4.5 + MountedMelee.reach(worker.body()));
    }

    public static Optional<WeaponSession> open(Worker worker, LivingEntity target) {
        return Optional.ofNullable(select(worker, target, null));
    }

    /** Return the old hand before opening another; only the current work node advances either session. */
    public static WeaponSession select(Worker worker, LivingEntity target, WeaponSession current) {
        WeaponSession selected = select(worker, current, adapter(worker, target), false);
        return selected != null || !permitted(worker) || profile(worker).isPresent() || !barehanded(worker)
            ? selected : NativeWeaponSession.barehanded(worker);
    }

    public static WeaponSession selectRanged(Worker worker, LivingEntity target, WeaponSession current) {
        return select(worker, current, adapter(worker, target, true), true);
    }

    public static double rangedRange(Worker worker, LivingEntity target) {
        return adapter(worker, target, true).map(candidate -> candidate.range(worker)).orElse(0.0);
    }

    public static boolean rangedIndirect(Worker worker, LivingEntity target) {
        return adapter(worker, target, true).flatMap(candidate -> LoadoutRegistry.using(candidate.stack())
            .map(provider -> provider.indirect(candidate.stack()))).orElse(false);
    }

    private static WeaponSession select(Worker worker, WeaponSession current, Optional<Candidate> desired,
                                        boolean rangedOnly) {
        if (!(worker.body() instanceof ResidentEntity body) || !permitted(worker)) {
            if (current != null) {
                current.close();
            }
            return null;
        }
        if (current != null && current.state() != WeaponSession.State.CLOSED
            && current.state() != WeaponSession.State.STOPPING && body.ownsWeaponHand(current)
            && (profile(worker).isEmpty() || desired.filter(candidate ->
                candidate.adapter().accepts(body.getMainHandItem())
                    && Goods.matches(body.getMainHandItem(), candidate.items())).isPresent())) {
            return current;
        }
        if (current != null) {
            current.close();
        }
        for (int slot = 0; slot < worker.pack().getContainerSize(); slot++) {
            ItemStack stack = worker.pack().getItem(slot);
            for (Registered entry : registered) {
                WeaponAdapter adapter = entry.adapter();
                if (!adapter.accepts(stack) || rangedOnly && !adapter.ranged()
                    || desired.isPresent() && (!desired.get().adapter().equals(adapter)
                        || !Goods.matches(stack, desired.get().items()))
                    || profile(worker).isPresent() && desired.isEmpty()
                    || !adapter.usable(worker, stack)
                    || !LoadoutRegistry.using(stack).map(provider -> provider.usable(worker, stack)).orElse(true)) {
                    continue;
                }
                Optional<WeaponSession> opened = adapter.open(worker, stack);
                if (opened.isPresent()) {
                    return opened.get();
                }
            }
        }
        colony(body).ifPresent(colony -> colony.inventoryFeedback().changedResident(body.id()));
        supplies(worker).ifPresent(value -> value.weaponFailed(body.id()));
        return null;
    }

    private static Optional<Candidate> adapter(Worker worker, LivingEntity target) {
        return adapter(worker, target, false);
    }

    private static Optional<Candidate> adapter(Worker worker, LivingEntity target, boolean rangedOnly) {
        List<Candidate> candidates = new ArrayList<>(candidates(worker));
        if (rangedOnly) {
            candidates.removeIf(candidate -> !candidate.ranged());
        }
        if (target == null) {
            return candidates.stream().findFirst();
        }
        double distance = worker.body().distanceTo(target);
        boolean sight = worker.body().hasLineOfSight(target);
        return candidates.stream()
            .min(
            Comparator.comparingInt(
            candidate -> {
                double meleeReach = candidate.ranged() ? 4.5 + MountedMelee.reach(worker.body()) : candidate.range(worker);
                if (distance <= meleeReach) {
                    return candidate.ranged() ? 1 : 0;
                }
                if (!candidate.ranged()) {
                    return 2;
                }
                boolean indirect = LoadoutRegistry.using(candidate.stack())
                    .map(provider -> provider.indirect(candidate.stack())).orElse(false);
                return sight || indirect ? distance <= candidate.range(worker) ? 0 : 1 : 3;
            }));
    }

    private static Optional<Colony> colony(ResidentEntity body) {
        return body.level() instanceof ServerLevel level
            ? body.colonyId().flatMap(id -> Colonies.of(level.getServer(), id)) : Optional.empty();
    }

    private static Optional<LoadoutProfile> profile(Worker worker) {
        return supplies(worker).flatMap(value -> value.profiles().assigned(worker.resident().id()));
    }

    private static List<Candidate> candidates(Worker worker) {
        List<Candidate> result = new ArrayList<>();
        profile(worker).ifPresent(profile -> {
            for (LoadoutProfile.Entry entry : profile.entries()) {
                if (entry.kind() != LoadoutProfile.Kind.WEAPON) {
                    continue;
                }
                for (ItemStack stack : LoadoutProfile.samples(entry.items())) {
                    // Prefer an adapter this body can use now (an extension may refuse a mounted rider),
                    // so a declined extension falls back to the next adapter instead of disarming the profile.
                    List<WeaponAdapter> accepting = registered.stream().map(Registered::adapter)
                        .filter(adapter -> adapter.accepts(stack)).toList();
                    accepting.stream().filter(adapter -> adapter.usable(worker, stack)).findFirst()
                        .or(() -> accepting.stream().findFirst())
                        .ifPresent(adapter -> result.add(new Candidate(adapter, stack, entry.items())));
                }
            }
        });
        return result;
    }

    private record NativeAdapter(NativeWeaponSession.Kind kind, double range) implements WeaponAdapter {
        @Override
        public boolean ranged() {
            return kind != NativeWeaponSession.Kind.MELEE;
        }

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
                if (!ammunition.isEmpty() && item.getAllSupportedProjectiles(stack).test(ammunition)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean accepts(ItemStack stack) {
            return !stack.isEmpty()
                && switch (kind) {
                    case BOW -> stack.is(Items.BOW);
                    case CROSSBOW -> stack.is(Items.CROSSBOW);
                    case MELEE -> stack.is(Tags.Items.MELEE_WEAPON_TOOLS);
                };
        }

        @Override
        public Optional<WeaponSession> open(Worker worker) {
            for (int slot = 0; slot < worker.pack().getContainerSize(); slot++) {
                ItemStack stack = worker.pack().getItem(slot);
                if (usable(worker, stack)) {
                    return open(worker, stack);
                }
            }
            return Optional.empty();
        }

        @Override
        public Optional<WeaponSession> open(Worker worker, ItemStack weapon) {
            if (!(worker.body() instanceof ResidentEntity resident)
                || resident.hasWeaponSession()
                || worker.pack() != resident.pack()
                || !resident.isAlive()
                || resident.isRemoved()
                || !(resident.level() instanceof ServerLevel level)
                || !level.getServer().isSameThread()
                || !usable(worker, weapon)) {
                return Optional.empty();
            }
            int selected = -1;
            for (int slot = 0; slot < worker.pack().getContainerSize(); slot++) {
                if (worker.pack().getItem(slot) == weapon && accepts(weapon)) {
                    selected = slot;
                    break;
                }
            }
            if (selected < 0) {
                return Optional.empty();
            }
            ItemStack actual = worker.pack().removeItemNoUpdate(selected);
            NativeWeaponSession session = new NativeWeaponSession(worker, this, kind);
            resident.claimWeaponHand(session, actual);
            return Optional.of(session);
        }
    }
}
