package io.github.izakyl.folkways.front.api.panel;

import io.github.izakyl.folkways.front.api.notice.Sentence;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

public record Board(List<Figure> figures, List<Row> rows, Optional<Component> whenEmpty,
                   CompoundTag data) {

    public Board {
        figures = List.copyOf(figures);
        rows = List.copyOf(rows);
        data = data.copy();
    }

    public Board(List<Figure> figures, List<Row> rows, Optional<Component> whenEmpty) {
        this(figures, rows, whenEmpty, new CompoundTag());
    }

    public static Board of(List<Row> rows) {
        return new Board(List.of(), rows, Optional.empty());
    }

    public record Figure(String labelKey, Component value) {
    }

    public record Meter(int value, int max) {

        public float percent() {
            return max <= 0 ? 0f : Math.min(100f, 100f * value / max);
        }

        public boolean met() {
            return value >= max;
        }
    }

    /** {@code told} is the detail in pictures, shown in its place where it is drawn; the words stay for the rest. */
    public record Row(ItemStack icon, Component title, Component detail, List<Act> acts,
                      Optional<Meter> meter, boolean heading, Optional<Sentence> told) {

        public static final int MAX_ACTS = 4;

        public Row {
            acts = List.copyOf(acts);
            if (acts.size() > MAX_ACTS) {
                throw new IllegalArgumentException(
                    "a board row offers at most " + MAX_ACTS + " acts, got " + acts.size());
            }
        }

        public Row(ItemStack icon, Component title, Component detail, List<Act> acts,
                Optional<Meter> meter, boolean heading) {
            this(icon, title, detail, acts, meter, heading, Optional.empty());
        }

        public Row(ItemStack icon, Component title, Component detail, List<Act> acts) {
            this(icon, title, detail, acts, Optional.empty(), false);
        }

        public static Row of(ItemStack icon, Component title, Component detail) {
            return new Row(icon, title, detail, List.of());
        }

        public static Row heading(Component title) {
            return new Row(ItemStack.EMPTY, title, Component.empty(), List.of(),
                Optional.empty(), true);
        }

        public static Row gauged(ItemStack icon, Component title, Component detail,
                int value, int max, List<Act> acts) {
            return new Row(icon, title, detail, acts, Optional.of(new Meter(value, max)), false);
        }

        public Row told(Sentence sentence) {
            return new Row(icon, title, detail, acts, meter, heading,
                sentence.isEmpty() ? Optional.empty() : Optional.of(sentence));
        }
    }

    public sealed interface Act {

        record Edit(String settingKey) implements Act {
        }

        record Admit(String labelKey, ResourceLocation kind) implements Act {
        }

        record Ping(BlockPos at) implements Act {
        }

        record Open(ResourceLocation page) implements Act {
        }

        record Do(String actionKey, String labelKey) implements Act {
        }
    }
}
