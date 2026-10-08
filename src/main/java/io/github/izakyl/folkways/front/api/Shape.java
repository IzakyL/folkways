package io.github.izakyl.folkways.front.api;

import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

public sealed interface Shape {

    Gesture gesture();

    enum Gesture implements StringRepresentable {
        POINT("point"),
        BOX("box"),
        LINE("line");

        private final String name;

        Gesture(String name) {
            this.name = name;
        }

        public boolean drawn() {
            return this != POINT;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }

    // A block the book points at. A fallback takes only what no other block delegation does: a general kind,
    // like any container, gives way to the particular ones.
    record Block(Accepts accepts, Anchor anchor, boolean fallback) implements Shape {

        public Block(Predicate<BlockState> accepts) {
            this(accepts, (level, clicked) -> clicked);
        }

        public Block(Predicate<BlockState> accepts, Anchor anchor) {
            this((level, pos) -> accepts.test(level.getBlockState(pos)), anchor, false);
        }

        public static Block fallback(Accepts accepts, Anchor anchor) {
            return new Block(accepts, anchor, true);
        }

        @Override
        public Gesture gesture() {
            return Gesture.POINT;
        }

        @FunctionalInterface
        public interface Accepts {
            boolean test(Level level, BlockPos pos);
        }

        @FunctionalInterface
        public interface Anchor {
            BlockPos of(Level level, BlockPos clicked);
        }
    }

    record Body(Predicate<EntityType<?>> accepts) implements Shape {

        @Override
        public Gesture gesture() {
            return Gesture.POINT;
        }
    }

    record Volume(int maxCells) implements Shape {

        @Override
        public Gesture gesture() {
            return Gesture.BOX;
        }
    }

    record Path(int maxPoints) implements Shape {

        @Override
        public Gesture gesture() {
            return Gesture.LINE;
        }
    }
}
