package io.github.izakyl.folkways.core.api.work;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/**
 * What a body is up to, by name. {@code about} is the translation key of what it is being done to
 * (an item or a block), when the name reads with one: "Harvesting %s". {@code toward} is the job a
 * walk is heading for, which {@code about} and {@code wares} then belong to.
 */
public record Doing(ResourceLocation what, Optional<ResourceLocation> toward, Optional<String> about,
                    Optional<Progress> progress, Wares wares) {

    public Doing(ResourceLocation what, Optional<Progress> progress) {
        this(what, Optional.empty(), Optional.empty(), progress, Wares.NONE);
    }

    /** The items a job goes through, by id: what it uses up and what it makes. */
    public record Wares(List<Ware> used, List<Ware> made) {
        public static final Wares NONE = new Wares(List.of(), List.of());
        public static final int MOST = 6;

        public Wares {
            used = List.copyOf(used.size() > MOST ? used.subList(0, MOST) : used);
            made = List.copyOf(made.size() > MOST ? made.subList(0, MOST) : made);
        }

        private void encode(FriendlyByteBuf buffer) {
            buffer.writeCollection(used, Ware::encode);
            buffer.writeCollection(made, Ware::encode);
        }

        private static Wares decode(FriendlyByteBuf buffer) {
            List<Ware> used = buffer.readCollection(ArrayList::new, Ware::decode);
            return new Wares(used, buffer.readCollection(ArrayList::new, Ware::decode));
        }
    }

    /** One item and how many of it; a count of zero names the item without counting it. */
    public record Ware(ResourceLocation item, long count) {

        private static void encode(FriendlyByteBuf buffer, Ware ware) {
            buffer.writeResourceLocation(ware.item);
            buffer.writeVarLong(ware.count);
        }

        private static Ware decode(FriendlyByteBuf buffer) {
            return new Ware(buffer.readResourceLocation(), buffer.readVarLong());
        }
    }

    public sealed interface Progress {

        record Span(long from, long until) implements Progress {

            @Override
            public float fraction(long gameTime) {
                long length = until - from;
                if (length <= 0L) {
                    return 1.0F;
                }
                return Math.clamp((gameTime - from) / (float) length, 0.0F, 1.0F);
            }
        }

        record Ground(float done) implements Progress {

            public Ground {
                done = Math.clamp(done, 0.0F, 1.0F);
            }

            @Override
            public float fraction(long gameTime) {
                return done;
            }
        }

        float fraction(long gameTime);
    }

    public static Doing open(ResourceLocation what) {
        return new Doing(what, Optional.empty());
    }

    public static Doing over(ResourceLocation what, long from, long until) {
        return new Doing(what, Optional.of(new Progress.Span(from, until)));
    }

    public static Doing along(ResourceLocation what, float done) {
        return new Doing(what, Optional.of(new Progress.Ground(done)));
    }

    public Doing about(Optional<String> key) {
        return new Doing(what, toward, key, progress, wares);
    }

    public Doing with(Wares going) {
        return new Doing(what, toward, about, progress, going);
    }

    public static Doing heading(ResourceLocation job, Optional<String> about, Wares wares) {
        return new Doing(Doings.WALKING, Optional.of(job), about, Optional.empty(), wares);
    }

    /** The same doing, done on the way to {@code job}: waiting for or riding a train to work. */
    public Doing headingFor(ResourceLocation job, Optional<String> about, Wares wares) {
        return new Doing(what, Optional.of(job), about, progress, wares);
    }

    public Optional<Float> fraction(long gameTime) {
        return progress.map(at -> at.fraction(gameTime));
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeResourceLocation(what);
        buffer.writeOptional(toward, FriendlyByteBuf::writeResourceLocation);
        buffer.writeOptional(about, FriendlyByteBuf::writeUtf);
        buffer.writeOptional(progress, (buf, at) -> {
            switch (at) {
                case Progress.Span(long from, long until) -> {
                    buf.writeVarInt(0);
                    buf.writeVarLong(from);
                    buf.writeVarLong(until);
                }
                case Progress.Ground(float done) -> {
                    buf.writeVarInt(1);
                    buf.writeFloat(done);
                }
            }
        });
        wares.encode(buffer);
    }

    public static Doing decode(FriendlyByteBuf buffer) {
        ResourceLocation what = buffer.readResourceLocation();
        Optional<ResourceLocation> toward = buffer.readOptional(FriendlyByteBuf::readResourceLocation);
        Optional<String> about = buffer.readOptional(FriendlyByteBuf::readUtf);
        Optional<Progress> progress = buffer.readOptional(buf -> buf.readVarInt() == 1
            ? new Progress.Ground(buf.readFloat())
            : new Progress.Span(buf.readVarLong(), buf.readVarLong()));
        return new Doing(what, toward, about, progress, Wares.decode(buffer));
    }
}
