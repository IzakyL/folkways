package io.github.izakyl.folkways.plugins.patrol;

import com.lowdragmc.lowdraglib2.gui.sync.bindings.IDataSource;
import com.lowdragmc.lowdraglib2.gui.sync.bindings.impl.DataBindingBuilder;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.front.api.Fronts;
import io.github.izakyl.folkways.front.api.ui.Picker;
import io.github.izakyl.folkways.front.api.ui.Pages;
import io.github.izakyl.folkways.front.api.ui.Rows;
import io.github.izakyl.folkways.front.api.ui.Tokens;
import io.github.izakyl.folkways.plugins.combat.weapon.LoadoutProfiles;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

final class StaffingPanel {

    private static final String KEY = "folkways.staffing.";
    private final Supplier<UUID> selected;
    private CompoundTag shown = new CompoundTag();
    private String sent = "";
    private int sequence;
    private String echoed = "";

    StaffingPanel(Supplier<UUID> selected) {
        this.selected = selected;
    }

    UIElement build(Player player) {
        UIElement panel = Rows.table();
        Label status = Rows.note(Component.empty());
        Label hint = Rows.note(Component.translatable(KEY + "sentry_hint"));
        Tokens.name(status, KEY + "status");
        Button allowed = new Button().setText(Component.translatable(KEY + "allowed"));
        Tokens.name(allowed, KEY + "allowed");
        allowed.setOnClick(event -> {
            CompoundTag snapshot = shown.copy();
            List<Picker.Row> options = new ArrayList<>();
            Set<String> current = new HashSet<>();
            for (Tag value : snapshot.getList("profiles", Tag.TAG_COMPOUND)) {
                CompoundTag profile = (CompoundTag) value;
                String id = profile.getUUID("id").toString();
                options.add(new Picker.Row(id, Component.literal(profile.getString("name")),
                    new ItemStack(Items.IRON_CHESTPLATE)));
                if (profile.getBoolean("allowed")) {
                    current.add(id);
                }
            }
            Picker.openMany(allowed, KEY + "allowed", options, current, picked -> {
                CompoundTag request = request(snapshot, "allowed");
                ListTag profiles = new ListTag();
                for (String id : picked) {
                    CompoundTag profile = new CompoundTag();
                    profile.putUUID("id", UUID.fromString(id));
                    profiles.add(profile);
                }
                request.put("allowed", profiles);
                send(request);
            });
        });
        TextField count = new TextField();
        count.setNumbersOnlyInt(0, PatrolStaffing.MAX_COUNT);
        count.layout(layout -> layout.width(64).flexShrink(0));
        Tokens.name(count, KEY + "headcount_label");
        count.registerValueListener(value -> {
            if (!value.isEmpty() && !value.equals(echoed) && shown.hasUUID("target")) {
                CompoundTag request = request(shown, "count");
                request.putString("count", value);
                send(request);
            }
        });
        Button retry = new Button().setText(Component.translatable(KEY + "retry"))
            .setOnClick(event -> send(request(shown, "retry")));
        Tokens.name(retry, KEY + "retry");
        panel.addChildren(allowed, Rows.row().addChildren(Rows.name(Component.translatable(KEY + "headcount_label")),
            count), hint, status, retry);
        var sending = DataBindingBuilder.stringC2S(raw -> {
            if (player instanceof ServerPlayer server) {
                try {
                    apply(server, TagParser.parseTag(raw.substring(raw.indexOf('\n') + 1)));
                } catch (RuntimeException | CommandSyntaxException rejected) {
                    server.displayClientMessage(Component.translatable(KEY + "rejected"), true);
                }
            }
        }).build();
        sending.setRemoteDataSource(IDataSource.of(ignored -> {}, () -> sent));
        panel.addSyncValue(sending.getSyncValue());
        panel.addSyncValue(DataBindingBuilder.tagS2C(() -> snapshot(player)).onSyncReceived(value -> {
            CompoundTag tag = (CompoundTag) value;
            boolean changedTarget = !Objects.equals(tag.get("target"), shown.get("target"));
            String previous = echoed;
            shown = tag;
            panel.setDisplay(tag.hasUUID("target"));
            status.setText(Pages.readText(player.registryAccess(), tag.get("status")));
            echoed = Integer.toString(tag.getInt("count"));
            if (changedTarget || !echoed.equals(previous)) {
                count.setValue(echoed);
            }
            retry.setDisplay(tag.getBoolean("route"));
            hint.setDisplay(!tag.getBoolean("route"));
            allowed.setText(Component.translatable(KEY + "allowed_count", tag.getInt("selected")));
        }).build().getSyncValue());
        return panel;
    }

    static CompoundTag request(CompoundTag snapshot, String kind) {
        CompoundTag request = new CompoundTag();
        if (snapshot.hasUUID("colony") && snapshot.hasUUID("target")) {
            request.putUUID("colony", snapshot.getUUID("colony"));
            request.putUUID("target", snapshot.getUUID("target"));
            request.putLong("revision", snapshot.getLong("revision"));
        }
        request.putString("kind", kind);
        return request;
    }

    private void send(CompoundTag request) {
        sent = ++sequence + "\n" + request;
    }

    private Optional<Colony> colony(Player player) {
        UUID target = selected.get();
        return Pages.colonyOf(player).filter(colony -> target != null && (Fronts.of(colony).paths().stream()
            .anyMatch(path -> path.id().equals(target) && path.delegation().equals(PatrolContent.BEAT)
                && path.dimension().equals(player.level().dimension()))
            || Fronts.of(colony).zonesIn(((ServerPlayer) player).serverLevel()).stream()
                .anyMatch(zone -> zone.id().equals(target) && zone.delegation().equals(SentryPosts.ZONE))));
    }

    CompoundTag snapshot(Player player) {
        CompoundTag tag = new CompoundTag();
        colony(player).ifPresent(colony -> {
            UUID target = selected.get();
            PatrolStaffing staffing = PatrolStaffing.of(colony);
            tag.putUUID("colony", colony.id());
            tag.putUUID("target", target);
            tag.putLong("revision", staffing.revision(target));
            tag.putInt("count", staffing.count(target));
            tag.putInt("selected", staffing.allowed(target).size());
            boolean route = colony.holding(target).orElseThrow().owner().equals(PatrolContent.BEAT);
            tag.putBoolean("route", route);
            Component status = staffing.summary(target);
            if (route) {
                PatrolTeams teams = colony.service(PatrolContent.ID, PatrolPresence.class).orElseThrow().teams();
                status = status.copy().append(" · ").append(Component.translatable(KEY + "team",
                    Component.translatable(KEY + teams.status(target)), teams.stop(target) + 1));
            }
            tag.put("status", Pages.text(player.registryAccess(), status));
            ListTag profiles = new ListTag();
            LoadoutProfiles.of(colony).all().forEach(profile -> {
                CompoundTag row = new CompoundTag();
                row.putUUID("id", profile.id());
                row.putString("name", profile.name());
                row.putBoolean("allowed", staffing.allowed(target).contains(profile.id()));
                profiles.add(row);
            });
            tag.put("profiles", profiles);
        });
        return tag;
    }

    void apply(ServerPlayer player, CompoundTag request) {
        Colony colony = colony(player).filter(value -> request.hasUUID("colony") && request.hasUUID("target")
            && value.id().equals(request.getUUID("colony")) && selected.get().equals(request.getUUID("target")))
            .orElseThrow();
        UUID target = selected.get();
        PatrolStaffing staffing = PatrolStaffing.of(colony);
        if (request.getLong("revision") != staffing.revision(target)) {
            throw new IllegalArgumentException("stale staffing panel");
        }
        switch (request.getString("kind")) {
            case "allowed" -> {
                Set<UUID> allowed = new HashSet<>();
                for (Tag value : request.getList("allowed", Tag.TAG_COMPOUND)) {
                    allowed.add(((CompoundTag) value).getUUID("id"));
                }
                staffing.configure(player.serverLevel(), target, allowed, staffing.count(target));
            }
            case "count" -> staffing.configure(player.serverLevel(), target, staffing.allowed(target),
                Integer.parseInt(request.getString("count")));
            case "retry" -> colony.service(PatrolContent.ID, PatrolPresence.class).orElseThrow().teams().retry(target);
            default -> throw new IllegalArgumentException("unknown staffing panel action");
        }
    }
}
