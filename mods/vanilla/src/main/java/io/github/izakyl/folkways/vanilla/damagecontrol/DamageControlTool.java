package io.github.izakyl.folkways.vanilla.damagecontrol;

import io.github.izakyl.folkways.core.front.api.notice.Line;
import io.github.izakyl.folkways.core.front.api.ui.OverheadTools;
import io.github.izakyl.folkways.core.front.client.ZoneSelection;
import io.github.izakyl.folkways.core.front.engine.item.HeldBook;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.network.PacketDistributor;

@OnlyIn(Dist.CLIENT)
public final class DamageControlTool implements OverheadTools.FocusedTool {
    private static final DamageControlTool TOOL = new DamageControlTool();
    private DamageControlState state;
    private UUID colony;
    private UUID selected;
    private int cooldown;
    private long receivedAt;

    private DamageControlTool() {
    }

    public static void install(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            OverheadTools.registerMode(new OverheadTools.Mode(DamageControlContent.ID,
                "folkways.overhead.mode.damage_control", TOOL.icon()));
            OverheadTools.register(TOOL);
        });
    }

    public static void heard(DamageControlState packet) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null
            || !minecraft.level.dimension().location().equals(packet.dimension())
            || HeldBook.bound(minecraft.player).map(HeldBook::colonyId).filter(packet.colony()::equals).isEmpty()) {
            return;
        }
        TOOL.state = packet;
        TOOL.receivedAt = Util.getMillis();
        if (TOOL.area().isEmpty()) {
            TOOL.selected = packet.areas().isEmpty() ? null : packet.areas().getLast().id();
        }
    }

    @Override
    public ResourceLocation id() {
        return DamageControlContent.ID;
    }

    @Override
    public ResourceLocation mode() {
        return DamageControlContent.ID;
    }

    @Override
    public String nameKey() {
        return "folkways.page.damage_control";
    }

    @Override
    public ResourceLocation icon() {
        return ResourceLocation.withDefaultNamespace("water_bucket");
    }

    @Override
    public void tick(UUID current) {
    }

    @Override
    public void backgroundTick(Optional<UUID> bound) {
        if (bound.isEmpty()) {
            reset();
            return;
        }
        UUID current = bound.orElseThrow();
        Minecraft minecraft = Minecraft.getInstance();
        if (!current.equals(colony) || (state != null && minecraft.level != null
            && !state.dimension().equals(minecraft.level.dimension().location()))) {
            reset();
            colony = current;
        }
        if (state != null && Util.getMillis() - receivedAt > 3000) {
            state = null;
            selected = null;
        }
        if (cooldown-- <= 0) {
            send(DamageControlCommand.Action.POLL, DamageControlCommand.NONE, BlockPos.ZERO, BlockPos.ZERO);
            cooldown = 20;
        }
    }

    @Override
    public void leave() {
        selected = null;
    }

    @Override
    public void reset() {
        state = null;
        colony = null;
        selected = null;
        cooldown = 0;
    }

    private Optional<DamageControlState.Area> area() {
        return state == null ? Optional.empty() : state.areas().stream()
            .filter(area -> area.id().equals(selected))
            .findFirst();
    }

    @Override
    public List<OverheadTools.Action> actions() {
        List<OverheadTools.Action> actions = new ArrayList<>();
        if (OverheadTools.box().isPresent()) {
            actions.add(action("folkways.damage_control.protect", true,
                () -> use(OverheadTools.box(), Optional.empty())));
        }
        if (OverheadTools.drafting()) {
            actions.add(action("folkways.keys.discard", true, OverheadTools::discardBox));
        }
        return actions;
    }

    private static OverheadTools.Action action(String key, boolean on, Runnable run) {
        return new OverheadTools.Action(Component.translatable(key), on, run);
    }

    @Override
    public Object selectionKey() {
        return selected == null ? DamageControlCommand.NONE : selected;
    }

    @Override
    public Component status() {
        return area().map(area -> Component.translatable("folkways.damage_control.selected",
            area.min().toShortString()))
            .orElseGet(() -> Component.translatable("folkways.damage_control.start"));
    }

    @Override
    public List<OverheadTools.Mark> marks() {
        if (state == null) {
            return List.of();
        }
        List<OverheadTools.Mark> marks = new ArrayList<>();
        for (var area : state.areas()) {
            List<Line> lines = new ArrayList<>();
            lines.add(Line.text(Component.translatable("folkways.damage_control.selected",
                area.min().toShortString())));
            lines.addAll(area.lines());
            marks.add(new OverheadTools.Mark(area.id(), area.min(), area.max(),
                area.id().equals(selected) ? 0x7CFFAD : 0x3A9D70, lines));
        }
        for (var mark : state.marks()) {
            marks.add(new OverheadTools.Mark(mark.area(), mark.at(), mark.at(), mark.color(), mark.lines()));
        }
        return List.copyOf(marks);
    }

    @Override
    public void use(Optional<OverheadTools.Box> box, Optional<OverheadTools.Mark> aimed) {
        if (box.isPresent()) {
            send(DamageControlCommand.Action.CREATE, DamageControlCommand.NONE, box.get().min(), box.get().max());
            OverheadTools.clearBox();
        } else if (!OverheadTools.drafting()) {
            aimed.filter(mark -> !mark.area().equals(DamageControlCommand.NONE)).ifPresent(mark -> {
                selected = mark.area();
                Minecraft minecraft = Minecraft.getInstance();
                HeldBook.bound(minecraft.player).ifPresent(book -> {
                    if (minecraft.gameMode != null) {
                        ZoneSelection.openOn(mark.area());
                        minecraft.gameMode.useItem(minecraft.player, book.hand());
                    }
                });
            });
        }
    }

    private void send(DamageControlCommand.Action action, UUID area, BlockPos min, BlockPos max) {
        HeldBook.bound(Minecraft.getInstance().player).ifPresent(book -> PacketDistributor.sendToServer(
            new DamageControlCommand(book.colonyId(), action, area, min, max)));
    }
}
