package io.github.izakyl.folkways.core.shell.create.control;

import io.github.izakyl.folkways.core.facility.api.Controls;
import io.github.izakyl.folkways.core.facility.api.Post;
import io.github.izakyl.folkways.core.facility.api.PostDefinition;
import io.github.izakyl.folkways.core.facility.api.Posts;
import io.github.izakyl.folkways.core.facility.api.control.PostKey;
import io.github.izakyl.folkways.core.facility.api.control.Reading;
import io.github.izakyl.folkways.core.facility.api.control.Signal;
import io.github.izakyl.folkways.core.facility.api.control.SignalType;
import io.github.izakyl.folkways.core.facility.api.control.Value;
import io.github.izakyl.folkways.core.resident.api.Bodies;
import io.github.izakyl.folkways.core.world.api.Stances;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.LevelEvent;

public final class HubPosts {
    public static final String PART = "level";
    private static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("folkways_core", "control_hub");
    private static final SignalType.Real TYPE = new SignalType.Real(0, 15, 1);
    private static final Map<ServerLevel, Set<Typewriter>> LOADED = new HashMap<>();

    private HubPosts() {
    }

    static void install() {
        Controls.register(ID, Controls.SPECIFIC, (level, at) -> terminal(level, at) != null
            ? List.of(new Channel(at)) : List.of());
        Posts.register((level, colony) -> LOADED.getOrDefault(level, Set.of()).stream()
            .filter(hub -> !hub.isRemoved() && level.isLoaded(hub.getBlockPos()))
            .flatMap(hub -> post(level, hub).stream()).toList());
        NeoForge.EVENT_BUS.addListener(LevelEvent.Unload.class, event -> LOADED.remove(event.getLevel()));
    }

    static void loaded(ServerLevel level, Typewriter hub) {
        LOADED.computeIfAbsent(level, world -> new HashSet<>()).add(hub);
    }

    static void unloaded(ServerLevel level, Typewriter hub) {
        var hubs = LOADED.get(level);
        if (hubs != null) {
            hubs.remove(hub);
        }
    }

    static PostKey key(ServerLevel level, Typewriter hub) {
        return new PostKey(ID, hub.id(), level.dimension().location() + "/" + hub.getBlockPos().asLong());
    }

    private static Optional<Post> post(ServerLevel level, Typewriter hub) {
        if (hub.points().isEmpty()) {
            return Optional.empty();
        }
        return PostDefinition.atSeatBlock(level, WorldSpaces.at(level, hub.seat()), key(level, hub), ref ->
            ref.part().equals(PART)
                && Optional.ofNullable(terminal(level, ref.at())).flatMap(Terminal::hub).orElse(null) == hub,
            new Post.Demand(Post.Staffing.ON_DEMAND));
    }

    private static Terminal terminal(ServerLevel level, WorldPos at) {
        if (!at.in(level) || !level.isLoaded(at.block(level))) {
            return null;
        }
        return level.getBlockEntity(at.block(level)) instanceof Terminal terminal ? terminal : null;
    }

    private record Channel(WorldPos anchor) implements Signal {
        @Override
        public String part() {
            return PART;
        }

        @Override
        public SignalType type() {
            return TYPE;
        }

        @Override
        public boolean valid(ServerLevel level) {
            return hub(level).isPresent();
        }

        @Override
        public Optional<Stances> stance(ServerLevel level) {
            return Optional.of(Stances.WHEREVER);
        }

        @Override
        public Optional<PostKey> requiredPost(ServerLevel level) {
            return hub(level).map(hub -> key(level, hub));
        }

        @Override
        public Optional<Reading> feedback() {
            return Optional.of(level -> Optional.ofNullable(terminal(level, anchor))
                .map(terminal -> Value.of(terminal.signal())));
        }

        @Override
        public Optional<Reading> commandFeedback() {
            return Optional.of(level -> Optional.ofNullable(terminal(level, anchor))
                .map(terminal -> Value.of(terminal.output())));
        }

        @Override
        public boolean admits(Mob writer) {
            return writer != null && Bodies.of(writer).isPresent();
        }

        @Override
        public Write write(ServerLevel level, Mob writer, Value value) {
            Terminal terminal = terminal(level, anchor);
            if (!(value instanceof Value.Real(double number)) || TYPE.check(value).isPresent()
                    || terminal == null || !admits(writer) || !terminal.staffedBy(writer)) {
                return Write.REFUSED;
            }
            terminal.output(writer, (int) Math.rint(number));
            return Write.WRITTEN;
        }

        private Optional<Typewriter> hub(ServerLevel level) {
            return Optional.ofNullable(terminal(level, anchor)).flatMap(Terminal::hub);
        }
    }
}
