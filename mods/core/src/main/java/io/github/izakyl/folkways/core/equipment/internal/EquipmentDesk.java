package io.github.izakyl.folkways.core.equipment.internal;

import io.github.izakyl.folkways.core.equipment.api.EquipmentKits;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.combat.api.Loadouts;
import io.github.izakyl.folkways.core.equipment.api.EquipmentPreview;
import io.github.izakyl.folkways.core.equipment.api.EquipmentProfile;
import io.github.izakyl.folkways.core.equipment.api.EquipmentProfiles;
import io.github.izakyl.folkways.core.equipment.api.EquipmentSuppliers;
import io.github.izakyl.folkways.core.equipment.api.GearSlot;
import io.github.izakyl.folkways.core.equipment.api.GearSlots;
import io.github.izakyl.folkways.core.front.api.notice.Notice;
import io.github.izakyl.folkways.core.front.api.notice.Sentence;
import io.github.izakyl.folkways.core.front.api.ui.Pages;
import io.github.izakyl.folkways.core.inventory.api.InventorySnapshot;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.resident.api.Bodies;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.JukeboxSong;

/**
 * The equipment page's server half: it applies the keeper's requests and reads the selected profile for the client.
 * Every change carries the profile revision the keeper saw, so a stale editor cannot overwrite another keeper.
 */
final class EquipmentDesk {
    static final String KEY = "folkways.equipment.";
    static final int RESIDENT_PAGE = 8;

    /** An entry's state for the assigned residents, worst first; the colour marks its slot. */
    enum State {
        NONE(0),
        INVALID(0xFFB23A26),
        // Still counting: no mark rather than a wrong one.
        PENDING(0),
        SHORT(0xFFB23A26),
        TRANSIT(0xFFD69A1E),
        NEED(0xFF8A7656),
        HELD(0xFF4F8A2E);

        final int color;

        State(int color) {
            this.color = color;
        }
    }

    private UUID context;
    private UUID selected;
    private Component message = Component.empty();
    private int residentOffset;
    private EquipmentPreview preview;
    private UUID previewProfile;
    private InventorySnapshot.Version previewInventory;
    private long previewAt;
    private long previewRevision;
    private ListTag weaponChoices = new ListTag();
    private CompoundTag slotChoices = new CompoundTag();
    private Map<UUID, Component> faults = Map.of();
    private Set<UUID> beyond = Set.of();
    private long weaponChoicesAt = Long.MIN_VALUE;

    EquipmentDesk(UUID context) {
        this.context = context;
    }

    void context(UUID colony) {
        context = colony;
    }

    private Optional<Colony> colony(Player player) {
        return Pages.colonyOf(player).filter(found -> found.id().equals(context));
    }

    static Component text(String key, Object... args) {
        return Component.translatable(KEY + key, args);
    }

    boolean apply(ServerPlayer player, CompoundTag request) {
        if (!player.getServer().isSameThread() || colony(player).isEmpty()) {
            message = text("rejected");
            return false;
        }
        Colony colony = colony(player).orElseThrow();
        EquipmentProfiles profiles = EquipmentProfiles.of(colony);
        try {
            message = null;
            String action = request.getString("action");
            switch (action) {
                case "create" -> selected = profiles.create(request.getString("name")).id();
                case "select" -> selected = profiles.get(request.getUUID("profile")).orElseThrow().id();
                case "page" -> residentOffset = Math.clamp(request.getInt("offset"), 0, 100000);
                case "assign_resident" -> assign(colony, profiles, request);
                default -> change(player, profiles, current(profiles, request), request);
            }
            if (message == null) {
                message = text("saved");
            }
            return true;
        } catch (IllegalArgumentException | IllegalStateException | NoSuchElementException rejected) {
            if (rejected instanceof EquipmentKits.Refused refused) {
                message = text("refused", refused.problem().reason());
            } else if (message == null) {
                message = text("rejected");
            }
            return false;
        }
    }

    private static EquipmentProfile current(EquipmentProfiles profiles, CompoundTag request) {
        EquipmentProfile profile = profiles.get(request.getUUID("profile")).orElseThrow();
        if (request.getLong("revision") != profiles.revision(profile.id())) {
            throw new IllegalArgumentException("stale profile");
        }
        return profile;
    }

    private void assign(Colony colony, EquipmentProfiles profiles, CompoundTag request) {
        UUID id = request.getUUID("resident");
        if (colony.claimOf(id).isEmpty() || colony.residents().stream().noneMatch(each -> each.id().equals(id))) {
            throw new IllegalArgumentException("resident outside colony");
        }
        Optional<UUID> expected = request.hasUUID("expected") ? Optional.of(request.getUUID("expected"))
            : Optional.empty();
        if (!profiles.assigned(id).map(EquipmentProfile::id).equals(expected)) {
            throw new IllegalArgumentException("stale assignment");
        }
        Optional<UUID> target = Optional.empty();
        if (request.hasUUID("profile")) {
            target = Optional.of(current(profiles, request).id());
        }
        profiles.assign(List.of(id), target);
        preview = null;
    }

    private void change(ServerPlayer player, EquipmentProfiles profiles, EquipmentProfile profile,
                        CompoundTag request) {
        switch (request.getString("action")) {
            case "riding" -> profiles.replace(profile.riding(request.getBoolean("riding")));
            case "rename" -> profiles.replace(profile.renamed(request.getString("name")));
            case "duplicate" -> selected = profiles.duplicate(profile.id()).id();
            case "delete" -> {
                profiles.delete(profile.id());
                if (profile.id().equals(selected)) {
                    selected = null;
                }
            }
            case "slot" -> {
                GearSlot gear = GearSlots.slot(net.minecraft.resources.ResourceLocation.parse(
                    request.getString("slot"))).orElseThrow();
                ItemSpec items = decode(player, request.getCompound("items"));
                if (EquipmentProfile.samples(items).stream().noneMatch(gear::fits)) {
                    message = text("wrong_slot", gear.name());
                    throw new IllegalArgumentException("nothing fits the slot");
                }
                profiles.put(profile.id(), gear.id(), items);
            }
            case "add" -> add(player, profiles, profile, request);
            default -> edit(player, profiles, profile, request);
        }
    }

    // A root entry goes through the ledger's own add, which may suggest ammunition; children are added together.
    private static void add(ServerPlayer player, EquipmentProfiles profiles, EquipmentProfile profile,
                            CompoundTag request) {
        List<ItemSpec> picked = new ArrayList<>();
        if (request.contains("each", Tag.TAG_LIST)) {
            for (Tag one : request.getList("each", Tag.TAG_COMPOUND)) {
                picked.add(decode(player, (CompoundTag) one));
            }
        } else {
            picked.add(decode(player, request.getCompound("items")));
        }
        if (picked.isEmpty() || picked.stream().anyMatch(items -> EquipmentProfile.samples(items).isEmpty())) {
            throw new IllegalArgumentException("empty goods");
        }
        if (!request.hasUUID("parent") && picked.size() == 1) {
            profiles.add(profile.id(), EquipmentProfile.Entry.carried(picked.getFirst(), 1, 1));
            return;
        }
        Optional<UUID> parent = request.hasUUID("parent") ? Optional.of(profile.entry(request.getUUID("parent"))
            .orElseThrow().id()) : Optional.empty();
        List<EquipmentProfile.Entry> entries = new ArrayList<>(profile.entries());
        for (ItemSpec items : picked) {
            EquipmentProfile.Entry added = EquipmentProfile.Entry.carried(items, 1, 1);
            entries.add(parent.map(added::under).orElse(added));
        }
        profiles.replace(profile.with(entries));
    }

    private void edit(ServerPlayer player, EquipmentProfiles profiles, EquipmentProfile profile,
                      CompoundTag request) {
        List<EquipmentProfile.Entry> entries = new ArrayList<>(profile.entries());
        EquipmentProfile.Entry entry = profile.entry(request.getUUID("entry")).orElseThrow();
        int index = entries.indexOf(entry);
        switch (request.getString("action")) {
            case "remove" -> entries.remove(index);
            case "left", "right" -> {
                // Entries move among their siblings: the other children of their parent, or the other roots.
                List<EquipmentProfile.Entry> carried = profile.entries().stream().filter(each -> !each.slotted())
                    .filter(each -> each.parent().equals(entry.parent())).toList();
                int at = carried.indexOf(entry);
                int to = at + (request.getString("action").equals("left") ? -1 : 1);
                if (at < 0 || to < 0 || to >= carried.size()) {
                    throw new IllegalArgumentException("entry boundary");
                }
                Collections.swap(entries, index, entries.indexOf(carried.get(to)));
            }
            case "edit" -> {
                ItemSpec items = decode(player, request.getCompound("items"));
                if (entry.gear().isPresent()
                    && EquipmentProfile.samples(items).stream().noneMatch(entry.gear().get()::fits)) {
                    message = text("wrong_slot", entry.gear().get().name());
                    throw new IllegalArgumentException("nothing fits the slot");
                }
                entries.set(index, entry.withItems(items));
            }
            case "amount" -> {
                if (entry.slotted()) {
                    throw new IllegalArgumentException("slots hold one item");
                }
                entries.set(index, entry.withAmounts(request.getInt("target"), request.getInt("threshold")));
            }
            default -> throw new IllegalArgumentException("unknown action");
        }
        profiles.replace(profile.with(entries));
    }

    private static ItemSpec decode(ServerPlayer player, CompoundTag items) {
        return ItemSpec.CODEC.parse(player.registryAccess().createSerializationContext(NbtOps.INSTANCE),
            items).getOrThrow();
    }

    Tag reading(Player player) {
        CompoundTag tag = new CompoundTag();
        HolderLookup.Provider registries = player.registryAccess();
        tag.put("message", Pages.text(registries, message));
        if (!(player instanceof ServerPlayer server) || colony(player).isEmpty()) {
            return tag;
        }
        Colony colony = colony(player).orElseThrow();
        EquipmentProfiles profiles = EquipmentProfiles.of(colony);
        List<EquipmentProfile> all = profiles.all();
        if (profiles.get(selected).isEmpty() && !all.isEmpty()) {
            selected = all.getFirst().id();
        }
        ListTag profileRows = new ListTag();
        for (EquipmentProfile profile : all) {
            CompoundTag row = new CompoundTag();
            row.putUUID("id", profile.id());
            row.putString("name", profile.name());
            row.putLong("revision", profiles.revision(profile.id()));
            row.putInt("assigned", profiles.residents(profile.id()).size());
            profileRows.add(row);
        }
        tag.put("profiles", profileRows);
        tag.put("weaponChoices", weaponChoices(server).copy());
        tag.putString("gear_schema", GearSlots.all().stream().map(gear -> gear.id() + ":" + gear.name().getString())
            .collect(java.util.stream.Collectors.joining(";")));
        Optional<EquipmentProfile> chosen = profiles.get(selected);
        if (chosen.isPresent()) {
            read(server, colony, profiles, chosen.get(), tag);
        }
        residents(server, colony, profiles, tag);
        tag.put("message", Pages.text(registries, message));
        return tag;
    }

    private ListTag weaponChoices(ServerPlayer server) {
        if (weaponChoicesAt == Long.MIN_VALUE || server.serverLevel().getGameTime() >= weaponChoicesAt + 20) {
            weaponChoicesAt = server.serverLevel().getGameTime();
            weaponChoices = new ListTag();
            for (var provider : Loadouts.entries().values()) {
                for (ItemStack stack : provider.weaponChoices()) {
                    if (provider.accepts(stack)) {
                        CompoundTag choice = new CompoundTag();
                        choice.put("stack", stack.save(server.registryAccess()));
                        choice.put("items", provider.profileWeapon(stack).save(server.registryAccess()));
                        weaponChoices.add(choice);
                    }
                }
            }
        }
        return weaponChoices;
    }

    private void read(ServerPlayer server, Colony colony, EquipmentProfiles profiles, EquipmentProfile profile,
                      CompoundTag tag) {
        HolderLookup.Provider registries = server.registryAccess();
        tag.putUUID("profile", profile.id());
        tag.putLong("revision", profiles.revision(profile.id()));
        tag.putString("name", profile.name());
        tag.putBoolean("riding", profile.riding());
        long now = server.serverLevel().getGameTime();
        if (preview == null || !profile.id().equals(previewProfile)
            || profiles.revision(profile.id()) != previewRevision
            || !colony.inventory().snapshot().version().equals(previewInventory) || now >= previewAt + 20) {
            preview = EquipmentSuppliers.preview(colony, profile, server.getServer());
            slotChoices = choices(server, profiles, profile);
            previewProfile = profile.id();
            previewAt = now;
            previewRevision = profiles.revision(profile.id());
            previewInventory = colony.inventory().snapshot().version();
        }
        tag.put("slot_choices", slotChoices.copy());
        faults = faults(profile);
        int assigned = profiles.residents(profile.id()).size();
        ListTag figures = new ListTag();
        figures.add(line(registries, figure("assigned", assigned, false)));
        if (assigned > 0 && preview.complete()) {
            figures.add(line(registries, figure("incomplete", preview.incomplete().size(), true)));
        }
        if (!preview.unloaded().isEmpty()) {
            figures.add(line(registries, figure("unloaded", preview.unloaded().size(), true)));
        }
        preview.figures().forEach(said -> figures.add(line(registries, said)));
        tag.put("figures", figures);
        ListTag slots = new ListTag();
        for (EquipmentProfile.Entry entry : profile.entries()) {
            if (entry.slotted() && entry.gear().isPresent()) {
                CompoundTag row = row(registries, entry, assigned);
                row.putString("slot", entry.slot().orElseThrow().toString());
                slots.add(row);
            }
        }
        tag.put("slots", slots);
        ListTag carried = new ListTag();
        int short1 = 0, coming = 0;
        for (EquipmentProfile.Entry entry : profile.entries()) {
            if (!CarriedKit.active(profile, entry)) {
                continue;
            }
            State state = state(entry, assigned);
            short1 += state == State.SHORT || state == State.INVALID ? 1 : 0;
            coming += state == State.TRANSIT ? 1 : 0;
            if (entry.slotted()) {
                continue;
            }
            CompoundTag row = row(registries, entry, assigned);
            row.putInt("target", entry.target());
            row.putInt("threshold", entry.threshold());
            carried.add(row);
        }
        tag.put("carried", carried);
        Component footer = text("personal_inventory");
        if (assigned > 0 && !preview.complete()) {
            footer = footer.copy().append(" · ").append(text("inventory_pending"));
        } else if (assigned > 0) {
            footer = footer.copy().append(" · ").append(text("short", short1).copy().withColor(
                (short1 > 0 ? State.SHORT.color : 0xFF7D6748) & 0xFFFFFF)).append(" · ").append(text("transit", coming)
                .copy().withColor((coming > 0 ? State.TRANSIT.color : 0xFF7D6748) & 0xFFFFFF));
        }
        if (!EquipmentKits.problems(profile).isEmpty()) {
            footer = footer.copy().append(" · ").append(text("kit_inert").copy().withColor(State.SHORT.color
                & 0xFFFFFF));
        }
        tag.put("footer", Pages.text(registries, footer));
    }

    private static CompoundTag choices(ServerPlayer player, EquipmentProfiles profiles, EquipmentProfile profile) {
        List<io.github.izakyl.folkways.core.resident.api.Body> loaded = new ArrayList<>();
        for (UUID resident : profiles.residents(profile.id())) {
            for (var level : player.getServer().getAllLevels()) {
                Optional.ofNullable(level.getEntity(resident)).flatMap(Bodies::of).ifPresent(loaded::add);
            }
        }
        CompoundTag choices = new CompoundTag();
        if (loaded.isEmpty()) {
            return choices;
        }
        for (var gear : GearSlots.all()) {
            ListTag allowed = new ListTag();
            for (var item : BuiltInRegistries.ITEM) {
                ItemStack stack = item.getDefaultInstance();
                if (gear.fits(stack) && loaded.stream().allMatch(body -> gear.fits(body, stack))) {
                    allowed.add(StringTag.valueOf(BuiltInRegistries.ITEM.getKey(item).toString()));
                }
            }
            choices.put(gear.id().toString(), allowed);
        }
        return choices;
    }

    private static CompoundTag line(HolderLookup.Provider registries, Component said) {
        CompoundTag line = new CompoundTag();
        line.put("text", Pages.text(registries, said));
        return line;
    }

    private static Component figure(String key, int value, boolean alert) {
        return text("figure." + key).copy().withColor(0x7D6748).append(" ").append(Component.literal(
            Integer.toString(value)).withColor(alert && value > 0 ? 0x9A3324 : 0x3B2A1A));
    }

    private CompoundTag row(HolderLookup.Provider registries, EquipmentProfile.Entry entry, int assigned) {
        CompoundTag row = new CompoundTag();
        row.putUUID("id", entry.id());
        row.putInt("target", entry.target());
        row.putInt("threshold", entry.threshold());
        entry.parent().ifPresent(parent -> row.putUUID("parent", parent));
        var slots = preview.slots().get(entry.id());
        if (slots != null) {
            row.putInt("slots_used", slots.used());
            row.putInt("slots_total", slots.total());
            ListTag kinds = new ListTag();
            EquipmentProfile.samples(entry.items()).stream().map(stack -> BuiltInRegistries.ITEM.getKey(
                stack.getItem()).toString()).distinct().forEach(id -> kinds.add(StringTag.valueOf(id)));
            row.put("kinds", kinds);
            row.put("slots_name", Pages.text(registries, EquipmentProfile.samples(entry.items()).stream()
                .findFirst().map(stack -> EquipmentKits.childSlotsName(BuiltInRegistries.ITEM.getKey(stack.getItem())))
                .orElse(Component.empty())));
        }
        if (beyond.contains(entry.id())) {
            row.putBoolean("beyond", true);
        }
        ItemStack display = entry.items().equals(ProfileLedger.UNAVAILABLE) ? ItemStack.EMPTY
            : Goods.display(entry.items());
        row.put("stack", display.saveOptional(registries));
        // Every disc is called "Music Disc"; its song is what tells two apart.
        Component label = display.isEmpty() ? text("unavailable_item") : JukeboxSong.fromStack(registries, display)
            .<Component>map(song -> song.value().description()).orElseGet(display::getHoverName);
        row.put("label", Pages.text(registries, label));
        State state = state(entry, assigned);
        row.putString("state", state.name());
        row.put("status", Pages.text(registries, status(entry, state)));
        row.putString("spec", entry.items().describe());
        return row;
    }

    // Tree faults first; then children past their parent's slots, the last ones in profile order.
    private Map<UUID, Component> faults(EquipmentProfile profile) {
        Map<UUID, Component> found = new LinkedHashMap<>();
        Set<UUID> past = new HashSet<>();
        EquipmentKits.problems(profile).forEach(problem -> found.putIfAbsent(problem.entry(), problem.reason()));
        EquipmentKits.slots(profile).forEach((parent, slots) -> {
            int used = 0;
            for (EquipmentProfile.Entry child : profile.entries()) {
                if (child.parent().filter(parent::equals).isEmpty()) {
                    continue;
                }
                used += child.target();
                if (used > slots.total()) {
                    past.add(child.id());
                    Component name = profile.entry(parent).map(EquipmentProfile.Entry::items)
                        .map(Goods::display).map(ItemStack::getHoverName).orElse(Component.empty());
                    found.putIfAbsent(child.id(), text("beyond_slots", name, slots.total()));
                }
            }
        });
        beyond = Set.copyOf(past);
        return found;
    }

    private State state(EquipmentProfile.Entry entry, int assigned) {
        if (!entry.available() || faults.containsKey(entry.id())) {
            return State.INVALID;
        }
        if (assigned == 0) {
            return State.NONE;
        }
        if (!preview.complete()) {
            return State.PENDING;
        }
        if (preview.alerts().containsKey(entry.id())) {
            return State.SHORT;
        }
        if (preview.missing().getOrDefault(entry.id(), 0) == 0) {
            return State.HELD;
        }
        if (preview.inTransit().getOrDefault(entry.id(), 0) > 0) {
            return State.TRANSIT;
        }
        return preview.unavailableStock().getOrDefault(entry.id(), 0) > 0 ? State.SHORT : State.NEED;
    }

    private Component status(EquipmentProfile.Entry entry, State state) {
        int missing = preview == null ? 0 : preview.missing().getOrDefault(entry.id(), 0);
        int stock = preview == null ? 0 : preview.unavailableStock().getOrDefault(entry.id(), 0);
        Component said = switch (state) {
            case NONE -> text("status.unassigned");
            case INVALID -> faults.getOrDefault(entry.id(), text("invalid_entry"));
            case PENDING -> text("inventory_pending");
            case SHORT -> preview.alerts().getOrDefault(entry.id(), text("status.short", missing, stock));
            case TRANSIT -> text("status.transit", missing, preview.inTransit().getOrDefault(entry.id(), 0));
            case NEED -> text("status.need", missing);
            case HELD -> text("status.ready");
        };
        return said;
    }

    private void residents(ServerPlayer server, Colony colony, EquipmentProfiles profiles, CompoundTag tag) {
        HolderLookup.Provider registries = server.registryAccess();
        var residents = colony.residents();
        residentOffset = Math.clamp(residentOffset, 0, Math.max(0, residents.size() - 1) / RESIDENT_PAGE
            * RESIDENT_PAGE);
        tag.putInt("residentCount", residents.size());
        tag.putInt("residentOffset", residentOffset);
        ListTag rows = new ListTag();
        for (var resident : residents.subList(residentOffset,
                Math.min(residents.size(), residentOffset + RESIDENT_PAGE))) {
            CompoundTag row = new CompoundTag();
            row.putUUID("id", resident.id());
            Sentence job = Sentence.of(Sentence.word(new Notice(KEY + "unloaded", List.of())));
            Component name = Component.literal(resident.id().toString().substring(0, 8));
            for (var level : server.getServer().getAllLevels()) {
                var loaded = Optional.ofNullable(level.getEntity(resident.id())).flatMap(Bodies::of);
                if (loaded.isPresent()) {
                    name = loaded.get().mob().getDisplayName();
                    job = loaded.get().doing().map(Sentence::of).orElseGet(() -> Sentence.of(
                        Sentence.word(new Notice("folkways.resident.unassigned", List.of()))));
                    break;
                }
            }
            row.put("name", Pages.text(registries, name));
            row.put("job", job.save());
            profiles.assigned(resident.id()).ifPresent(assigned -> row.putUUID("assigned", assigned.id()));
            row.put("assignment", Pages.text(registries, profiles.assigned(resident.id())
                .<Component>map(assigned -> Component.literal(assigned.name())).orElseGet(() -> text("none"))));
            rows.add(row);
        }
        tag.put("residents", rows);
    }
}
