package io.github.izakyl.folkways.plugins.rail.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.chat.Component;

public final class RailTooltip {

    private static final int DETAIL = 0x92A9BD;

    private RailTooltip() {
    }

    public static List<Component> lines(UUID train) {
        TrainCrew aboard = RailPackets.crewOf(train).orElse(null);
        if (aboard == null) {
            return List.of();
        }
        List<Component> lines = new ArrayList<>();
        lines.add(detail(aboard.drivers().isEmpty()
            ? Component.translatable("folkways.train_map.driver_missing")
            : Component.translatable("folkways.train_map.driver", String.join(", ", aboard.drivers()))));
        lines.add(detail(Component.translatable("folkways.train_map.riders",
            aboard.seatsTaken(), aboard.seatsTotal())));
        aboard.fault().ifPresent(fault -> lines.add(detail(Component.translatable(fault.key(), fault.stop()))));
        return lines;
    }

    private static Component detail(Component line) {
        return line.copy().withStyle(style -> style.withColor(DETAIL));
    }
}
