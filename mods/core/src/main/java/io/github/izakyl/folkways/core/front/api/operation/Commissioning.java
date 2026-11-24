package io.github.izakyl.folkways.core.front.api.operation;

import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.front.api.Ghost;
import io.github.izakyl.folkways.core.front.api.Schema;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

// The building extension supplies survival commissioning without making the front depend on that extension.
public final class Commissioning {

    public record Request(ResourceLocation pattern, BlockPos from, BlockPos to, Direction facing, String mirror,
            Optional<List<BlockPos>> points, CompoundTag settings) {

        public Request {
            from = from.immutable();
            to = to.immutable();
            points = points.map(list -> list.stream().map(BlockPos::immutable).toList());
            settings = settings.copy();
        }

        public Request(ResourceLocation pattern, BlockPos from, BlockPos to, Direction facing, String mirror) {
            this(pattern, from, to, facing, mirror, Optional.empty(), new CompoundTag());
        }

        @Override
        public CompoundTag settings() {
            return settings.copy();
        }
    }

    public record Result(boolean filed, Component message, Optional<String> code) {

        public Result(boolean filed, Component message) {
            this(filed, message, Optional.empty());
        }
    }

    public record Pattern(ResourceLocation id, List<String> accepts, Schema knobs,
            Map<String, List<String>> options, Map<String, Component> errors, Optional<Component> marking) {

        public Pattern(ResourceLocation id, List<String> accepts, Schema knobs,
                Map<String, List<String>> options, Map<String, Component> errors) {
            this(id, accepts, knobs, options, errors, Optional.empty());
        }
    }

    public sealed interface Drawing {

        record Refused(String code, Component refusal, String diagnostic) implements Drawing {

            public Refused(String code, Component refusal) {
                this(code, refusal, "");
            }

            public Refused(Component refusal) {
                this("commission_refused", refusal);
            }
        }

        record RailConnections(boolean start, boolean end, boolean nearby) {

            public int connected() {
                return (start ? 1 : 0) + (end ? 1 : 0);
            }
        }

        record Ready(BlockPos from, BlockPos to, int blocks, Map<ResourceLocation, Long> materials, int joints,
                String fingerprint, List<Ghost> ghosts, List<BlockPos> anchors,
                Optional<RailConnections> railConnections) implements Drawing {

            public Ready {
                from = from.immutable();
                to = to.immutable();
                materials = Map.copyOf(materials);
                ghosts = List.copyOf(ghosts);
                anchors = anchors.stream().map(BlockPos::immutable).toList();
            }
        }
    }

    public static final class PatternFailure extends IllegalArgumentException {

        private final String code;

        public PatternFailure(String detail) {
            this("pattern_failed", detail);
        }

        public PatternFailure(String code, String detail) {
            super(detail);
            this.code = code;
        }

        public String code() {
            return code;
        }

        public Component refusal() {
            return Component.translatable("folkways.refusal.draft." + code, getMessage());
        }
    }

    public record Inline(ResourceLocation id, Schema knobs, Source source) {
    }

    public record Progress(int placed, int total) {
    }

    public record Order(UUID id, String name, WorldPos anchor, String pattern, List<BlockPos> marks,
            boolean path, boolean cancelling, Optional<Progress> progress) {

        public Order {
            marks = List.copyOf(marks);
        }
    }

    public interface Source {

        default List<Order> orders(Colony colony) {
            return List.of();
        }

        default boolean cancel(Colony colony, UUID id) {
            return false;
        }


        default String patternHelp() {
            return "";
        }

        default CompletableFuture<Inline> inline(ServerLevel level, String written) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("building_unavailable"));
        }

        List<ResourceLocation> patterns();

        List<Pattern> descriptions();

        CompletableFuture<Drawing> draw(ServerLevel level, Colony colony, Request request);

        Optional<String> refusal(ServerLevel level, Colony colony, Request request);

        String fingerprint(Request request);

        CompletableFuture<Result> commission(ServerPlayer player, ServerLevel level, Colony colony, Request request);

        /**
         * Sets the drawing down at once for a player in creative, as placing a blueprint offers; anyone else, or a
         * source that cannot, hands it to the colony instead.
         */
        default CompletableFuture<Result> raise(ServerPlayer player, ServerLevel level, Colony colony,
                Request request) {
            return commission(player, level, colony, request);
        }
    }

    private static Source source;

    private Commissioning() {
    }

    public static Optional<Component> refusal(String code) {
        return switch (code) {
            case "invalid_path_size", "unknown_pattern_or_mark", "building_unavailable", "invalid_setting_value",
                    "pattern_commission_has_no_mirror" ->
                Optional.of(Component.translatable("folkways.refusal.commission." + code));
            default -> Optional.empty();
        };
    }

    public static void install(Source installed) {
        source = installed;
    }

    public static Optional<Source> source() {
        return Optional.ofNullable(source);
    }
}
