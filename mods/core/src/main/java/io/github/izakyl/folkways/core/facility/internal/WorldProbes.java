package io.github.izakyl.folkways.core.facility.internal;

import io.github.izakyl.folkways.core.facility.api.Controls;
import io.github.izakyl.folkways.core.facility.api.DefinitionCodec;
import io.github.izakyl.folkways.core.facility.api.control.ItemPort;
import io.github.izakyl.folkways.core.facility.api.control.Readings;
import io.github.izakyl.folkways.core.facility.api.control.Value;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

final class WorldProbes {

    private WorldProbes() {
    }

    static void install() {
        Readings.register(id("block_state"), WorldProbes::block);
        Readings.register(id("block_entity"), (level, at) ->
            Optional.ofNullable(level.getBlockEntity(at.block(level)))
                .map(entity -> new Value.Data(entity.saveWithFullMetadata(level.registryAccess()))));
        configured("item_counts", "part", name -> !name.isBlank(), WorldProbes::items);
        configured("entity_data", "entity", name -> uuid(name).isPresent(), WorldProbes::entity);
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("folkways_core", path);
    }

    private static Optional<Value> block(ServerLevel level, WorldPos at) {
        var state = level.getBlockState(at.block(level));
        Map<String, Value> properties = new LinkedHashMap<>();
        state.getProperties().forEach(property -> properties.put(property.getName(),
            property(state, property)));
        return Optional.of(DefinitionCodec.object(Map.of(
            "id", new Value.Text(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString()),
            "properties", DefinitionCodec.object(properties))));
    }

    private static <T extends Comparable<T>> Value property(BlockState state, Property<T> property) {
        T value = state.getValue(property);
        return switch (value) {
            case Boolean flag -> Value.of(flag);
            case Integer count -> Value.of(count);
            default -> new Value.Text(property.getName(value));
        };
    }

    private static Optional<Value> items(ServerLevel level, WorldPos at, String name) {
        return Controls.at(level, at).stream().filter(ItemPort.class::isInstance).map(ItemPort.class::cast)
            .filter(port -> port.part().equals(name) && port.valid(level)).findFirst().flatMap(port -> {
                var read = port.reader(level, Optional.empty());
                if (!read.step(128)) {
                    return Optional.empty();
                }
                Map<String, Double> totals = new LinkedHashMap<>();
                read.result().entries().forEach(entry -> totals.merge(
                    BuiltInRegistries.ITEM.getKey(entry.prototype().getItem()).toString(),
                    (double) entry.count(), Double::sum));
                Map<String, Value> counts = new LinkedHashMap<>();
                totals.forEach((id, count) -> counts.put(id, Value.of(count)));
                return Optional.of(DefinitionCodec.object(counts));
            });
    }

    private static Optional<Value> entity(ServerLevel level, WorldPos at, String name) {
        return uuid(name).map(level::getEntity).filter(entity -> entity.isAlive()
                && at.equals(WorldPos.of(level, entity.blockPosition())))
            .map(entity -> new Value.Data(entity.saveWithoutId(new CompoundTag())));
    }

    private static Optional<UUID> uuid(String name) {
        try {
            return Optional.of(UUID.fromString(name));
        } catch (IllegalArgumentException invalid) {
            return Optional.empty();
        }
    }

    @FunctionalInterface
    private interface Probe {
        Optional<Value> read(ServerLevel level, WorldPos at, String option);
    }

    private static void configured(String id, String key, Predicate<String> valid, Probe probe) {
        Readings.register(id(id), new Readings.ConfiguredProbe() {
            @Override
            public Optional<Value> read(ServerLevel level, WorldPos at, Map<String, Value> config) {
                return probe.read(level, at, ((Value.Text) config.get(key)).value());
            }

            @Override
            public boolean valid(Map<String, Value> config) {
                return config.size() == 1 && config.get(key) instanceof Value.Text text && valid.test(text.value());
            }
        });
    }
}
