package io.github.izakyl.folkways.plugins.person.look;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.util.StringRepresentable;

public record ResidentLook(Appearance appearance, int weight) {
    public static final Codec<ResidentLook> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        Appearance.CODEC.forGetter(ResidentLook::appearance),
        ExtraCodecs.POSITIVE_INT.optionalFieldOf("weight", 1).forGetter(ResidentLook::weight)
    ).apply(instance, ResidentLook::new));

    public static ResidentLook ofSkin(ResourceLocation texture, Arms arms) {
        return new ResidentLook(new Skin(texture, arms), 1);
    }

    public sealed interface Appearance {
        MapCodec<Appearance> CODEC =
            Variant.CODEC.dispatchMap("type", Appearance::variant, Variant::codec);

        Variant variant();

        Skin skin();
    }

    public record Skin(ResourceLocation texture, Arms arms) implements Appearance {
        public static final MapCodec<Skin> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            ResourceLocation.CODEC.fieldOf("texture").forGetter(Skin::texture),
            Arms.CODEC.optionalFieldOf("arms", Arms.WIDE).forGetter(Skin::arms)
        ).apply(instance, Skin::new));

        @Override
        public Variant variant() {
            return Variant.SKIN;
        }

        @Override
        public Skin skin() {
            return this;
        }
    }

    public record Model(
            ResourceLocation geometry,
            ResourceLocation animations,
            ResourceLocation texture,
            String idleAnimation,
            String walkAnimation,
            java.util.Optional<String> workAnimation,
            Skin fallback) implements Appearance {

        public static final MapCodec<Model> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            ResourceLocation.CODEC.fieldOf("geometry").forGetter(Model::geometry),
            ResourceLocation.CODEC.fieldOf("animations").forGetter(Model::animations),
            ResourceLocation.CODEC.fieldOf("texture").forGetter(Model::texture),
            Codec.STRING.optionalFieldOf("idle_animation", "animation.resident.idle").forGetter(Model::idleAnimation),
            Codec.STRING.optionalFieldOf("walk_animation", "animation.resident.walk").forGetter(Model::walkAnimation),
            Codec.STRING.optionalFieldOf("work_animation").forGetter(Model::workAnimation),
            Skin.CODEC.fieldOf("fallback").forGetter(Model::fallback)
        ).apply(instance, Model::new));

        @Override
        public Variant variant() {
            return Variant.MODEL;
        }

        @Override
        public Skin skin() {
            return fallback;
        }
    }

    public enum Variant implements StringRepresentable {
        SKIN("skin", Skin.CODEC),
        MODEL("model", Model.CODEC);

        public static final Codec<Variant> CODEC = StringRepresentable.fromEnum(Variant::values);

        private final String id;
        private final MapCodec<? extends Appearance> codec;

        Variant(String id, MapCodec<? extends Appearance> codec) {
            this.id = id;
            this.codec = codec;
        }

        public MapCodec<? extends Appearance> codec() {
            return codec;
        }

        @Override
        public String getSerializedName() {
            return id;
        }
    }

    public enum Arms implements StringRepresentable {
        WIDE("wide"),
        SLIM("slim");

        public static final Codec<Arms> CODEC = StringRepresentable.fromEnum(Arms::values);

        private final String id;

        Arms(String id) {
            this.id = id;
        }

        @Override
        public String getSerializedName() {
            return id;
        }
    }
}
