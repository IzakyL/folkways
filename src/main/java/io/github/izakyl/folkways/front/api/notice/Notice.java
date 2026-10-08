package io.github.izakyl.folkways.front.api.notice;

import io.github.izakyl.folkways.core.api.terms.RefusalKind;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;

public record Notice(String key, List<Arg> args) {

    public static final int MAX_KEY = 128;
    public static final int MAX_ARGS = 4;
    public static final int MAX_ARG = 160;

    public sealed interface Arg {

        record Literal(String text) implements Arg {
        }

        record Named(String key) implements Arg {
        }
    }

    public Notice {
        key = trim(key, MAX_KEY);
        List<Arg> capped = args == null ? List.of() : args;
        args = List.copyOf(capped.size() > MAX_ARGS ? capped.subList(0, MAX_ARGS) : capped);
    }

    public static Notice of(NoticeKind kind, Arg... args) {
        return new Notice(kind.translationKey(), List.of(args));
    }

    public static Notice of(RefusalKind why, Arg... args) {
        return new Notice(why.translationKey(), List.of(args));
    }

    public static Arg text(String text) {
        return new Arg.Literal(trim(text, MAX_ARG));
    }

    public static Arg count(long count) {
        return new Arg.Literal(Long.toString(count));
    }

    public static Arg named(String translationKey) {
        return new Arg.Named(trim(translationKey, MAX_ARG));
    }

    public Component component() {
        Object[] resolved = new Object[args.size()];
        for (int i = 0; i < resolved.length; i++) {
            resolved[i] = switch (args.get(i)) {
                case Arg.Literal literal -> literal.text();
                case Arg.Named named -> Component.translatable(named.key());
            };
        }
        return Component.translatable(key, resolved);
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeUtf(key, MAX_KEY);
        encodeArgs(buffer, args);
    }

    public static Notice decode(FriendlyByteBuf buffer) {
        return new Notice(buffer.readUtf(MAX_KEY), decodeArgs(buffer));
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("key", key);
        ListTag saved = new ListTag();
        for (Arg arg : args) {
            CompoundTag entry = new CompoundTag();
            switch (arg) {
                case Arg.Literal literal -> entry.putString("text", literal.text());
                case Arg.Named named -> entry.putString("named", named.key());
            }
            saved.add(entry);
        }
        tag.put("args", saved);
        return tag;
    }

    public static Notice load(CompoundTag tag) {
        List<Arg> args = new ArrayList<>();
        for (Tag saved : tag.getList("args", Tag.TAG_COMPOUND)) {
            CompoundTag entry = (CompoundTag) saved;
            args.add(entry.contains("named")
                ? new Arg.Named(trim(entry.getString("named"), MAX_ARG))
                : new Arg.Literal(trim(entry.getString("text"), MAX_ARG)));
        }
        return new Notice(tag.getString("key"), args);
    }

    public static void encodeArgs(FriendlyByteBuf buffer, List<Arg> args) {
        buffer.writeVarInt(args.size());
        for (Arg arg : args) {
            switch (arg) {
                case Arg.Literal literal -> {
                    buffer.writeBoolean(true);
                    buffer.writeUtf(literal.text(), MAX_ARG);
                }
                case Arg.Named named -> {
                    buffer.writeBoolean(false);
                    buffer.writeUtf(named.key(), MAX_ARG);
                }
            }
        }
    }

    public static List<Arg> decodeArgs(FriendlyByteBuf buffer) {
        int count = buffer.readVarInt();
        List<Arg> args = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            boolean literal = buffer.readBoolean();
            String value = buffer.readUtf(MAX_ARG);
            if (i < MAX_ARGS) {
                args.add(literal ? new Arg.Literal(value) : new Arg.Named(value));
            }
        }
        return args;
    }

    static String trim(String value, int max) {
        String normalized = value == null ? "" : value;
        return normalized.length() > max ? normalized.substring(0, max) : normalized;
    }
}
