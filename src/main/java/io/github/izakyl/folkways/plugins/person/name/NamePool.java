package io.github.izakyl.folkways.plugins.person.name;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;

public record NamePool(List<String> names, List<String> surnames) {

    public static final int MAX_LENGTH = 16;

    public static final Codec<NamePool> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        Codec.string(1, MAX_LENGTH).listOf().fieldOf("names").forGetter(NamePool::names),
        Codec.string(1, MAX_LENGTH).listOf().optionalFieldOf("surnames", List.of())
            .forGetter(NamePool::surnames)
    ).apply(instance, NamePool::new));

    public NamePool {
        names = List.copyOf(names);
        surnames = List.copyOf(surnames);
    }
}
