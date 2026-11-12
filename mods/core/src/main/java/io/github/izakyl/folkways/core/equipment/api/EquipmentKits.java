package io.github.izakyl.folkways.core.equipment.api;

import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.resident.api.Body;
import io.github.izakyl.folkways.core.resident.api.KitProvider;
import io.github.izakyl.folkways.core.resident.api.KitProviders;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import org.jetbrains.annotations.Nullable;

/** Profile trees rooted in worn items, with runtime state read from those items' native inventories. */
public final class EquipmentKits {

    private EquipmentKits() {
    }

    public record Slots(int used, int total) {
    }

    public record Problem(UUID entry, Kind kind, Component reason) {
    }

    public enum Kind {
        STRUCTURE, NEEDS_PARENT, REFUSED_CHILD, EXCLUSIVE, OVER_SLOTS, PARENT_COUNT, WRONG_SLOT
    }

    public static final class Refused extends IllegalArgumentException {
        private final transient Problem problem;

        public Refused(Problem problem) {
            super(problem.kind().name());
            this.problem = problem;
        }

        public Problem problem() {
            return problem;
        }
    }

    /** A physical item reached through its worn root. Paths refer to native slots, never profile indices. */
    public record Part(ItemStack root, List<Integer> path, ItemStack stack) {
        public Part {
            path = List.copyOf(path);
        }

        public Optional<IItemHandler> children() {
            return KitProviders.provider(root).flatMap(provider -> provider.children(root, path));
        }

        public Part child(int index, ItemStack stack) {
            List<Integer> next = new ArrayList<>(path);
            next.add(index);
            return new Part(root, next, stack);
        }
    }

    private static List<ResourceLocation> identities(EquipmentProfile.Entry entry) {
        return EquipmentProfile.samples(entry.items()).stream()
            .map(stack -> BuiltInRegistries.ITEM.getKey(stack.getItem())).distinct().toList();
    }

    public static Optional<EquipmentProfile> profile(Body body) {
        if (body.mob().getServer() == null) {
            return Optional.empty();
        }
        return body.colonyId().flatMap(id -> Colonies.of(body.mob().getServer(), id))
            .flatMap(colony -> colony.service(EquipmentProfiles.ID, EquipmentProfiles.class))
            .flatMap(profiles -> profiles.assigned(body.id()));
    }

    private static Component said(String key, Object... args) {
        return Component.translatable("folkways.kits." + key, args);
    }

    private static Component name(ResourceLocation item) {
        return BuiltInRegistries.ITEM.get(item).getDescription();
    }

    public static List<Problem> problems(EquipmentProfile profile) {
        List<Problem> found = new ArrayList<>();
        for (var entry : profile.entries()) {
            Set<UUID> path = new HashSet<>();
            var root = entry;
            boolean broken = false;
            while (root.parent().isPresent()) {
                var parent = profile.entry(root.parent().orElseThrow());
                if (!path.add(root.id()) || parent.isEmpty()) {
                    broken = true;
                    break;
                }
                root = parent.get();
            }
            if (broken || entry.parent().isPresent() && !root.slotted()) {
                found.add(new Problem(entry.id(), Kind.STRUCTURE, said("problem.structure")));
                continue;
            }
            // Removed frameworks keep their entire saved branch, with no display or supply until reinstalled.
            if (root.slotted() && root.gear().isEmpty()) {
                continue;
            }
            if (entry.slotted() && !identities(entry).isEmpty()
                && EquipmentProfile.samples(entry.items()).stream().noneMatch(entry.gear().orElseThrow()::fits)) {
                found.add(new Problem(entry.id(), Kind.WRONG_SLOT, said("problem.wrong_slot")));
            }
            if (entry.parent().isPresent()) {
                var parents = identities(profile.entry(entry.parent().orElseThrow()).orElseThrow());
                for (var item : identities(entry)) {
                    var refusing = parents.stream().filter(parent -> !acceptsChild(parent, item)).findFirst();
                    if (parents.isEmpty() || refusing.isPresent()) {
                        found.add(new Problem(entry.id(), Kind.REFUSED_CHILD, said("problem.refused_child", name(item),
                            refusing.map(EquipmentKits::name).orElseGet(() -> said("problem.unknown")))));
                        break;
                    }
                }
            }
            if (!entry.slotted() && entry.parent().isEmpty()) {
                continue;
            }
            var items = identities(entry);
            int used = used(profile, entry.id());
            int total = items.stream().mapToInt(EquipmentKits::childSlots).min().orElse(0);
            if (used > total) {
                found.add(new Problem(entry.id(), Kind.OVER_SLOTS, said("problem.over_slots",
                    items.stream().findFirst().map(EquipmentKits::name).orElse(Component.empty()),
                    items.stream().findFirst().map(EquipmentKits::childSlotsName).orElse(Component.empty()), used,
                    total)));
            }
            if (items.stream().anyMatch(EquipmentKits::isParent) && entry.target() != 1) {
                found.add(new Problem(entry.id(), Kind.PARENT_COUNT, said("problem.parent_count",
                    name(items.getFirst()))));
            }
        }
        return List.copyOf(found);
    }

    private static int used(EquipmentProfile profile, UUID parent) {
        return profile.entries().stream().filter(child -> child.parent().filter(parent::equals).isPresent())
            .mapToInt(EquipmentProfile.Entry::target).sum();
    }

    public static void validate(EquipmentProfile profile) {
        validate(profile, null);
    }

    public static void validate(EquipmentProfile profile, @Nullable EquipmentProfile previous) {
        for (var problem : problems(profile)) {
            if (problem.kind() == Kind.OVER_SLOTS && previous != null) {
                var before = previous.entry(problem.entry());
                if (before.isPresent() && used(previous, problem.entry()) >= used(profile, problem.entry())
                    && before.get().items().equals(profile.entry(problem.entry()).orElseThrow().items())) {
                    continue;
                }
            }
            throw new Refused(problem);
        }
    }

    public static boolean requiresParent(ResourceLocation item) {
        return KitProviders.all().stream().anyMatch(provider -> provider.requiresParent(item));
    }

    public static Component childSlotsName(ResourceLocation item) {
        return KitProviders.all().stream().filter(provider -> provider.parent(item))
            .map(provider -> provider.childSlotsName(item)).flatMap(Optional::stream).findFirst()
            .orElseGet(() -> said("child_slots"));
    }

    public static boolean isParent(ResourceLocation item) {
        return KitProviders.all().stream().anyMatch(provider -> provider.parent(item));
    }

    public static boolean acceptsChild(ResourceLocation parent, ResourceLocation child) {
        return KitProviders.all().stream().anyMatch(provider -> provider.parent(parent)
            && provider.acceptsChild(parent, child));
    }

    public static int childSlots(ResourceLocation item) {
        return KitProviders.all().stream().filter(provider -> provider.parent(item))
            .mapToInt(provider -> provider.childSlots(item)).max().orElse(0);
    }

    public static Map<UUID, Slots> slots(EquipmentProfile profile) {
        Map<UUID, Slots> result = new LinkedHashMap<>();
        for (var entry : profile.entries()) {
            if (!entry.slotted() && entry.parent().isEmpty()) {
                continue;
            }
            var items = identities(entry);
            if (items.stream().anyMatch(EquipmentKits::isParent)) {
                result.put(entry.id(), new Slots(used(profile, entry.id()),
                    items.stream().mapToInt(EquipmentKits::childSlots).min().orElse(0)));
            }
        }
        return Map.copyOf(result);
    }

    public static List<ItemStack> worn(Body body) {
        return GearSlots.all().stream().filter(GearSlot::personal).map(gear -> gear.worn(body))
            .filter(stack -> !stack.isEmpty()).toList();
    }

    public static Map<UUID, List<Part>> parts(Body body, EquipmentProfile profile) {
        Map<UUID, List<Part>> found = new LinkedHashMap<>();
        for (var entry : profile.entries()) {
            if (!entry.slotted()) {
                continue;
            }
            entry.gear().filter(GearSlot::personal).ifPresent(gear -> {
                ItemStack stack = gear.worn(body);
                if (entry.matches(stack) && gear.fits(body, stack)) {
                    Part part = new Part(stack, List.of(), stack);
                    found.put(entry.id(), List.of(part));
                    parts(profile, entry.id(), part, found);
                }
            });
        }
        return Map.copyOf(found);
    }

    private static void parts(EquipmentProfile profile, UUID parent, Part part, Map<UUID, List<Part>> found) {
        part.children().ifPresent(inventory -> {
            int[] left = new int[inventory.getSlots()];
            for (int slot = 0; slot < left.length; slot++) {
                left[slot] = inventory.getStackInSlot(slot).getCount();
            }
            for (var entry : profile.entries()) {
                if (entry.parent().filter(parent::equals).isEmpty()) {
                    continue;
                }
                List<Part> held = new ArrayList<>();
                int need = entry.target();
                for (int slot = 0; slot < left.length && need > 0; slot++) {
                    ItemStack stack = inventory.getStackInSlot(slot);
                    if (left[slot] > 0 && entry.matches(stack)) {
                        int count = Math.min(need, left[slot]);
                        Part child = part.child(slot, stack.copyWithCount(count));
                        held.add(child);
                        parts(profile, entry.id(), child, found);
                        left[slot] -= count;
                        need -= count;
                    }
                }
                found.put(entry.id(), List.copyOf(held));
            }
        });
    }

    public static List<KitProvider.Held> planned(EquipmentProfile profile) {
        return profile.entries().stream().filter(EquipmentProfile.Entry::slotted)
            .flatMap(entry -> identities(entry).stream().limit(1).map(item -> planned(profile, entry, item))).toList();
    }

    private static KitProvider.Held planned(EquipmentProfile profile, EquipmentProfile.Entry entry,
        ResourceLocation item) {
        List<KitProvider.Held> children = profile.entries().stream()
            .filter(child -> child.parent().filter(entry.id()::equals).isPresent())
            .flatMap(child -> identities(child).stream().limit(1).map(id -> planned(profile, child, id))).toList();
        return new KitProvider.Held(item, entry.target(), children);
    }

    public static List<KitProvider.Held> held(Body body) {
        return KitProviders.activeRoots(body).stream().map(stack -> {
            var held = held(new Part(stack, List.of(), stack));
            boolean ticking = KitProviders.provider(stack).map(provider -> provider.ticking(body, stack)).orElse(true);
            return new KitProvider.Held(held.item(), held.count(), held.children(), ticking);
        }).toList();
    }

    private static KitProvider.Held held(Part part) {
        List<KitProvider.Held> children = new ArrayList<>();
        part.children().ifPresent(inventory -> {
            for (int slot = 0; slot < inventory.getSlots(); slot++) {
                ItemStack stack = inventory.getStackInSlot(slot);
                Part child = part.child(slot, stack);
                if (!stack.isEmpty() && KitProviders.provider(part.root())
                    .map(provider -> provider.active(part.root(), child.path())).orElse(true)) {
                    children.add(held(child));
                }
            }
        });
        return new KitProvider.Held(BuiltInRegistries.ITEM.getKey(part.stack().getItem()), part.stack().getCount(),
            children);
    }
}
