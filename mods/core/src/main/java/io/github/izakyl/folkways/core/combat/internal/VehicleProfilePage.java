package io.github.izakyl.folkways.core.combat.internal;

import com.lowdragmc.lowdraglib2.gui.sync.bindings.IDataSource;
import com.lowdragmc.lowdraglib2.gui.sync.bindings.impl.DataBindingBuilder;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import io.github.izakyl.folkways.core.combat.api.VehicleProfile;
import io.github.izakyl.folkways.core.combat.api.Vehicles;
import io.github.izakyl.folkways.core.combat.api.WeaponRules;
import io.github.izakyl.folkways.core.equipment.api.EquipmentProfiles;
import io.github.izakyl.folkways.core.front.api.ui.Draw;
import io.github.izakyl.folkways.core.front.api.ui.ItemPicker;
import io.github.izakyl.folkways.core.front.api.ui.Pages;
import io.github.izakyl.folkways.core.front.api.ui.Picker;
import io.github.izakyl.folkways.core.front.api.ui.Rows;
import io.github.izakyl.folkways.core.front.api.ui.Tokens;
import io.github.izakyl.folkways.core.item.api.ItemFilter;
import io.github.izakyl.folkways.core.item.api.ItemQuery;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.rts.combat.net.UnitOrders;
import io.github.izakyl.folkways.core.rts.combat.net.UnitRequest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/** A book editor over the same authenticated mutations used by unit windows. */
public final class VehicleProfilePage implements Draw {
    private static final String KEY = "combat.vehicle.profile.";
    private static final Vehicles.Order STOP = new Vehicles.Order(Vehicles.Action.STOP, List.of(), -1,
        Optional.empty(), Optional.empty());
    private final Label notice = Rows.note(Component.empty());
    private String sent = "";
    private int sequence;
    private String result = "";
    private CompoundTag cached = new CompoundTag();
    private long readAt = Long.MIN_VALUE;
    private CompoundTag shown = new CompoundTag();
    private final List<VehicleProfile> profiles = new ArrayList<>();
    private VehicleProfile draft;
    private Player player;
    private UIElement content;
    private TextField name;
    private TextField seats;
    private TextField energy;
    private final List<TextField> targets = new ArrayList<>();
    private final List<TextField> thresholds = new ArrayList<>();
    private final Map<String, TextField> ranges = new LinkedHashMap<>();

    @Override
    public UIElement build(Player player) {
        this.player = player;
        content = Rows.page();
        var binding = DataBindingBuilder.stringC2S(raw -> {
            if (player instanceof ServerPlayer server) {
                try {
                    CompoundTag request = TagParser.parseTag(raw.substring(raw.indexOf('\n') + 1));
                    Pages.colonyOf(server).ifPresent(colony -> {
                        var operation = UnitRequest.Operation.valueOf(request.getString("operation"));
                        Optional<VehicleProfile> profile = request.contains("profile")
                            ? Optional.of(VehicleProfile.load(request.getCompound("profile"), server.registryAccess()))
                            : Optional.empty();
                        readAt = Long.MIN_VALUE;
                        result = UnitOrders.handle(server, new UnitRequest(0, colony.id(), request.getUUID("unit"),
                            operation, List.of(), -1, -1, STOP, "", WeaponRules.DEFAULT, profile)).result().key();
                    });
                } catch (RuntimeException | com.mojang.brigadier.exceptions.CommandSyntaxException rejected) {
                    result = "combat.vehicle.unavailable";
                }
            }
        }).build();
        binding.setRemoteDataSource(IDataSource.of(ignored -> { }, () -> sent));
        content.addSyncValue(binding.getSyncValue());
        var scroll = Rows.fills(Rows.box());
        scroll.addScrollViewChild(content);
        scroll.addSyncValue(DataBindingBuilder.tagS2C(() -> reading(player)).onSyncReceived(value -> {
            if (value instanceof CompoundTag tag && !tag.equals(shown)) {
                shown = tag.copy();
                profiles.clear();
                for (Tag row : tag.getList("profiles", Tag.TAG_COMPOUND)) {
                    profiles.add(VehicleProfile.load((CompoundTag) row, player.registryAccess()));
                }
                if (draft != null) {
                    draft = profiles.stream().filter(profile -> profile.id().equals(draft.id()))
                        .findFirst().orElse(null);
                }
                paint();
            }
        }).build().getSyncValue());
        return scroll;
    }

    private CompoundTag reading(Player player) {
        CompoundTag tag = new CompoundTag();
        if (!(player instanceof ServerPlayer server)) {
            return tag;
        }
        long now = server.serverLevel().getGameTime();
        if (readAt != Long.MIN_VALUE && now >= readAt && now - readAt < 20) {
            return cached.copy();
        }
        readAt = now;
        Pages.colonyOf(server).ifPresent(colony -> {
            var profiles = new io.github.izakyl.folkways.core.combat.api.VehicleProfiles(
                colony, server.registryAccess());
            ListTag rows = new ListTag();
            profiles.all().forEach(profile -> rows.add(profile.save(server.registryAccess())));
            tag.put("profiles", rows);
            CompoundTag weapons = new CompoundTag();
            for (var entity : server.serverLevel().getAllEntities()) {
                Vehicles.of(entity).filter(provider -> UnitOrders.available(server, colony, entity))
                    .ifPresent(provider -> {
                        String type = provider.type(entity).toString();
                        CompoundTag names = weapons.getCompound(type);
                        provider.weapons(entity).forEach((key, label) ->
                            names.put(key, Pages.text(server.registryAccess(), label)));
                        weapons.put(type, names);
                    });
            }
            tag.put("weapons", weapons);
            ListTag equipment = new ListTag();
            EquipmentProfiles.of(colony).all().forEach(profile -> {
                CompoundTag row = new CompoundTag();
                row.putUUID("id", profile.id());
                row.putString("name", profile.name());
                equipment.add(row);
            });
            tag.put("equipment", equipment);
        });
        tag.putString("result", result);
        cached = tag.copy();
        return tag;
    }

    private static Component text(String key) {
        return Component.translatable(KEY + key);
    }

    private Button button(String key, Runnable click) {
        Button button = new Button().setText(text(key)).setOnClick(event -> {
            try {
                click.run();
            } catch (IllegalArgumentException rejected) {
                notice.setText(Component.translatable("combat.vehicle.unavailable"));
            }
        });
        Tokens.name(button, KEY + key);
        return button;
    }

    private TextField field(String key, String value) {
        TextField field = new TextField();
        field.setText(value);
        Tokens.name(field, KEY + key);
        return field;
    }

    private TextField number(String key, int value, int max) {
        TextField field = field(key, Integer.toString(value));
        field.setNumbersOnlyInt(0, max);
        field.layout(layout -> layout.width(60));
        return field;
    }

    private void paint() {
        content.clearAllChildren();
        targets.clear();
        thresholds.clear();
        ranges.clear();
        content.addChildren(Rows.note(text("nearby")));
        for (VehicleProfile profile : profiles) {
            Button select = button("select", () -> {
                draft = profile;
                paint();
            });
            select.setText(Component.empty().append(profile.displayName()).append(" · " + profile.type().getPath()));
            Tokens.name(select, KEY + "select." + profile.id());
            content.addChildren(select);
        }
        if (draft == null) {
            content.addChildren(notice);
            return;
        }
        name = field("name", draft.displayName().getString());
        content.addChildren(Rows.heading(Component.literal(draft.type().toString())), name);
        seats = field("seats", draft.seats().stream().sorted().map(Object::toString).collect(Collectors.joining(",")));
        energy = field("energy", Long.toString(draft.energy()));
        energy.setNumbersOnlyInt(0, Integer.MAX_VALUE);
        content.addChildren(Rows.value(text("seats")), seats, Rows.note(text("seat_help")),
            Rows.value(text("energy")), energy);
        content.addChildren(button("equipment", () -> {
            capture();
            List<Picker.Row> choices = new ArrayList<>();
            choices.add(new Picker.Row("none", text("none"), ItemStack.EMPTY));
            for (Tag value : shown.getList("equipment", Tag.TAG_COMPOUND)) {
                CompoundTag row = (CompoundTag) value;
                choices.add(new Picker.Row(row.getUUID("id").toString(), Component.literal(row.getString("name")),
                    ItemStack.EMPTY));
            }
            Picker.open(content, text("equipment"), choices, draft.equipment().map(UUID::toString).orElse("none"),
                picked -> {
                    draft = new VehicleProfile(draft.id(), draft.name(), draft.type(), draft.seats(),
                        picked.equals("none") ? Optional.empty() : Optional.of(UUID.fromString(picked)), draft.stock(),
                        draft.energy(), draft.rules());
                    paint();
                });
        }));
        content.addChildren(Rows.heading(text("inventory")));
        for (int n = 0; n < draft.stock().size(); n++) {
            int index = n;
            var entry = draft.stock().get(n);
            TextField target = number("target." + n, entry.target(), 65536);
            TextField threshold = number("threshold." + n, entry.threshold(), 65536);
            targets.add(target);
            thresholds.add(threshold);
            Button item = button("items", () -> {
                capture();
                pick(picked -> {
                    List<VehicleProfile.Stock> stock = new ArrayList<>(draft.stock());
                    var held = stock.get(index);
                    stock.set(index, new VehicleProfile.Stock(picked, held.target(), held.threshold()));
                    stock(stock);
                });
            });
            Tokens.name(item, KEY + "items." + index);
            item.setText(io.github.izakyl.folkways.core.item.api.Goods.display(entry.items()).getHoverName());
            content.addChildren(Rows.strip().addChildren(item, Rows.value(text("target")), target,
                Rows.value(text("threshold")), threshold, button("remove", () -> {
                    capture();
                    List<VehicleProfile.Stock> stock = new ArrayList<>(draft.stock());
                    stock.remove(index);
                    stock(stock);
                })));
        }
        content.addChildren(button("add_stock", () -> {
            capture();
            pick(picked -> {
                List<VehicleProfile.Stock> stock = new ArrayList<>(draft.stock());
                stock.add(new VehicleProfile.Stock(picked, 64, 16));
                stock(stock);
            });
        }));
        content.addChildren(Rows.heading(text("weapons")));
        Map<String, WeaponRules.Rule> rules = new LinkedHashMap<>(draft.rules());
        CompoundTag weapons = shown.getCompound("weapons").getCompound(draft.type().toString());
        weapons.getAllKeys().stream().sorted().forEach(key -> rules.putIfAbsent(key, WeaponRules.DEFAULT));
        rules.forEach((key, rule) -> {
            TextField range = field("range." + key, Double.toString(rule.range()));
            ranges.put(key, range);
            Button mode = button("mode", () -> {
                capture();
                var updated = new LinkedHashMap<>(draft.rules());
                updated.put(key, new WeaponRules.Rule(WeaponRules.Mode.values()[(rule.mode().ordinal() + 1) % 3],
                    updated.get(key).range(), rule.indirect()));
                rules(updated);
            });
            Tokens.name(mode, KEY + "mode." + key);
            mode.setText(Component.translatable(KEY + rule.mode().name().toLowerCase(java.util.Locale.ROOT)));
            Button indirect = button("indirect", () -> {
                capture();
                var updated = new LinkedHashMap<>(draft.rules());
                updated.put(key, new WeaponRules.Rule(rule.mode(), updated.get(key).range(), !rule.indirect()));
                rules(updated);
            });
            Tokens.name(indirect, KEY + "indirect." + key);
            indirect.setText(text(rule.indirect() ? "indirect_on" : "indirect_off"));
            Component label = weapons.contains(key) ? Pages.readText(player.registryAccess(), weapons.get(key))
                : Component.literal(key);
            String seat = key.substring(0, Math.max(0, key.indexOf('/')));
            content.addChildren(Rows.strip().addChildren(Rows.value(Component.literal(seat + " · ").append(label)),
                mode, Rows.value(text("range")), range, indirect));
        });
        content.addChildren(Rows.strip().addChildren(button("save", () -> {
            capture();
            send(UnitRequest.Operation.PROFILE_SAVE, draft.id(), Optional.of(draft));
        }), button("create", () -> {
            capture();
            draft = new VehicleProfile(UUID.randomUUID(), draft.name(), draft.type(), draft.seats(), draft.equipment(),
                draft.stock(), draft.energy(), draft.rules());
            send(UnitRequest.Operation.PROFILE_SAVE, draft.id(), Optional.of(draft));
        }), button("delete", () -> send(UnitRequest.Operation.PROFILE_DELETE, draft.id(), Optional.empty()))));
        notice.setText(Component.translatable(shown.getString("result")));
        content.addChildren(notice);
    }

    private void pick(Consumer<ItemSpec> confirmed) {
        ItemPicker.many(content, text("items"), List.of(), false, filters -> {
            List<ItemSpec> choices = new ArrayList<>();
            for (ItemFilter filter : filters) {
                switch (filter.kind()) {
                    case ITEM -> choices.add(ItemSpec.of(filter.id()));
                    case TAG -> choices.add(ItemSpec.of(TagKey.create(Registries.ITEM, filter.id())));
                    case QUERY -> ItemQuery.parse(filter.text()).ifPresent(query -> query.items()
                        .forEach(item -> choices.add(ItemSpec.of(item))));
                }
            }
            if (!choices.isEmpty()) {
                confirmed.accept(ItemSpec.anyOf(choices));
            }
        });
    }

    private void capture() {
        Set<Integer> required = Arrays.stream(seats.getText().split(",")).map(String::strip)
            .filter(value -> !value.isEmpty()).map(Integer::parseInt).collect(Collectors.toSet());
        List<VehicleProfile.Stock> stock = new ArrayList<>();
        for (int n = 0; n < draft.stock().size(); n++) {
            stock.add(new VehicleProfile.Stock(draft.stock().get(n).items(), Integer.parseInt(targets.get(n).getText()),
                Integer.parseInt(thresholds.get(n).getText())));
        }
        Map<String, WeaponRules.Rule> rules = new LinkedHashMap<>(draft.rules());
        ranges.forEach((key, field) -> {
            var rule = rules.getOrDefault(key, WeaponRules.DEFAULT);
            rules.put(key, new WeaponRules.Rule(rule.mode(), Double.parseDouble(field.getText()), rule.indirect()));
        });
        draft = new VehicleProfile(draft.id(), name.getText(), draft.type(), required, draft.equipment(), stock,
            Long.parseLong(energy.getText()), rules);
    }

    private void stock(List<VehicleProfile.Stock> stock) {
        draft = new VehicleProfile(draft.id(), draft.name(), draft.type(), draft.seats(), draft.equipment(), stock,
            draft.energy(), draft.rules());
        paint();
    }

    private void rules(Map<String, WeaponRules.Rule> rules) {
        draft = new VehicleProfile(draft.id(), draft.name(), draft.type(), draft.seats(), draft.equipment(),
            draft.stock(), draft.energy(), rules);
        paint();
    }

    private void send(UnitRequest.Operation operation, UUID unit, Optional<VehicleProfile> profile) {
        CompoundTag request = new CompoundTag();
        request.putString("operation", operation.name());
        request.putUUID("unit", unit);
        profile.ifPresent(value -> request.put("profile", value.save(player.registryAccess())));
        sent = ++sequence + "\n" + request;
    }
}
