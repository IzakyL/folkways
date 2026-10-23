package io.github.izakyl.folkways.plugins.person.living;

import com.lowdragmc.lowdraglib2.gui.sync.bindings.impl.DataBindingBuilder;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import dev.vfyjxf.taffy.style.FlexWrap;
import io.github.izakyl.folkways.front.api.ui.Inlay;
import io.github.izakyl.folkways.front.api.ui.Nook;
import io.github.izakyl.folkways.front.api.ui.Pages;
import io.github.izakyl.folkways.front.api.ui.Rows;
import io.github.izakyl.folkways.front.api.ui.Tokens;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;

public final class LivingInlay implements Inlay {

    private static final int GOOD = 0xFF527B32;
    private static final int BAD = 0xFFA33F32;
    private static final int NEUTRAL = 0xFFBA923F;
    private static final int CAPTION_WIDTH = 56;
    private static final int METER_WIDTH = 90;

    @Override
    public UIElement build(Nook nook) {
        UIElement page = Rows.page();
        Tokens.name(page, "folkways.resident.living");
        page.setDisplay(false);
        page.addSyncValue(DataBindingBuilder.tagS2C(() -> nook.subject()
                .map(LivingContent::reading).orElseGet(CompoundTag::new))
            .onSyncReceived(tag -> {
                CompoundTag read = tag instanceof CompoundTag compound ? compound : new CompoundTag();
                page.setDisplay(read.contains("mood"));
                page.clearAllChildren();
                if (read.contains("mood")) { show(page, read); }
            }).build().getSyncValue());
        return page;
    }

    private static void show(UIElement page, CompoundTag read) {
        double mood = read.getDouble("mood");
        UIElement meter = Rows.meter();
        Rows.meterFill(meter, (float) mood, mood < 40 ? BAD : mood >= 70 ? GOOD : NEUTRAL);
        Tokens.name(meter, "folkways.resident.mood");
        meter.layout(layout -> layout.width(METER_WIDTH).flexGrow(0).flexShrink(0));
        page.addChildren(Rows.heading(Component.translatable("folkways.mood.title")),
            Rows.strip().addChildren(caption("folkways.mood.title"), meter,
                Rows.value(Component.literal(Long.toString(Math.round(mood)))),
                Rows.value(Component.translatable("folkways.mood.work_speed", String.format(Locale.ROOT, "%.2f", read.getDouble("speed"))))));
        int alertness = read.getInt("alertness");
        UIElement rested = Rows.meter();
        Rows.meterFill(rested, 100f * alertness / Rest.SPENT, NEUTRAL);
        Tokens.name(rested, "folkways.resident.alertness");
        rested.layout(layout -> layout.width(METER_WIDTH).flexGrow(0).flexShrink(0));
        rested.style(style -> style.tooltips(Component.literal(alertness + "/" + Rest.SPENT)));
        page.addChildren(Rows.strip().addChildren(caption("folkways.mood.alertness"), rested,
            Rows.value(Component.literal(Long.toString(Math.round(100.0 * alertness / Rest.SPENT))))));

        UIElement reasons = Rows.strip();
        reasons.layout(layout -> layout.flexWrap(FlexWrap.WRAP));
        CompoundTag parts = read.getCompound("reasons");
        for (String reason : List.of("food", "dining", "drink", "housing", "rest", "leisure", "social", "safety", "environment")) {
            int amount = parts.getInt(reason);
            if (amount == 0) { continue; }
            Label label = Rows.value(Component.translatable("folkways.mood.reason." + reason, signed(amount)));
            label.textStyle(style -> style.textColor(amount > 0 ? GOOD : BAD));
            // Each reason keeps its width, so a long list wraps instead of squeezing them all.
            label.layout(layout -> layout.flexShrink(0));
            Tokens.name(label, "folkways.resident.reason." + reason);
            if (reason.equals("housing")) {
                var registries = Minecraft.getInstance().level.registryAccess();
                label.style(style -> style.tooltips(
                    read.contains("room") ? Pages.readText(registries, read.get("room"))
                        : Component.translatable("folkways.housing.no_bed"),
                    read.contains("advice") ? Pages.readText(registries, read.get("advice"))
                        : Component.translatable("folkways.housing.reason.no_bed")));
            }
            reasons.addChildren(label);
        }
        page.addChildren(reasons, Rows.heading(Component.translatable("folkways.mood.recent")));
        ListTag events = read.getList("events", Tag.TAG_COMPOUND);
        if (events.isEmpty()) {
            page.addChildren(Rows.note(Component.translatable("folkways.mood.recent.empty")));
        }
        for (int index = 0; index < events.size(); index++) {
            CompoundTag event = events.getCompound(index);
            int amount = event.getInt("amount");
            String reason = event.getString("reason");
            Component text = reason.equals("attacked") ? Component.translatable("folkways.mood.event.attacked")
                : Component.translatable("folkways.mood.reason." + reason, signed(amount));
            if (reason.equals("attacked")) { text = text.copy().append(" " + signed(amount)); }
            Label label = Rows.name(Component.literal(amount > 0 ? "▲ " : "▼ ").append(text));
            label.textStyle(style -> style.textColor(amount > 0 ? GOOD : BAD));
            Label age = Rows.value(age(event.getLong("age")));
            age.textStyle(style -> style.textColor(Rows.QUIET));
            Tokens.name(label, "folkways.resident.event." + index);
            page.addChildren(Rows.strip().addChildren(label, age));
        }
    }

    // The mood and alertness rows start their meters at the same edge.
    private static Label caption(String key) {
        Label label = Rows.value(Component.translatable(key));
        label.layout(layout -> layout.width(CAPTION_WIDTH).flexShrink(0));
        return label;
    }

    private static Component age(long ticks) {
        if (ticks < Mood.DAY / 24) { return Component.translatable("folkways.mood.ago.now"); }
        return ticks < Mood.DAY ? Component.translatable("folkways.mood.ago.hours", ticks / (Mood.DAY / 24))
            : Component.translatable("folkways.mood.ago.days", ticks / Mood.DAY);
    }

    private static String signed(int amount) {
        return amount > 0 ? "+" + amount : "−" + -(long) amount;
    }
}
