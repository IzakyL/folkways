package io.github.izakyl.folkways.front.api.notice;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.work.Doing;
import io.netty.handler.codec.DecoderException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Something told in pictures, read left to right: glyphs, items with their counts, a plus between items, an arrow
 * from what is used to what is made, and words where no picture will do. A glyph is a gui sprite by id, a doing's
 * {@code <namespace>:doing/<path>} and anything else's {@code <namespace>:glyph/<name>}; where the sprite is not
 * drawn, the glyph says its {@code otherwise} words, or nothing.
 */
public record Sentence(List<Token> tokens) {
    private static final String AS_IS = "folkways.sign.as_is";

    public static final int MOST = 24;
    public static final Sentence EMPTY = new Sentence(List.of());
    public static final Token PLUS = new Token.Plus();
    public static final Token YIELDS = new Token.Yields();

    public sealed interface Token {

        record Glyph(ResourceLocation sprite, Optional<Notice> otherwise) implements Token {
        }

        /** An item and how many; a count of one or none shows the item alone. */
        record Ware(ResourceLocation item, long count) implements Token {
        }

        record Plus() implements Token {
        }

        record Yields() implements Token {
        }

        record Word(Notice notice) implements Token {
        }
    }

    public Sentence {
        tokens = List.copyOf(tokens.size() > MOST ? tokens.subList(0, MOST) : tokens);
    }

    public static Sentence of(Token... tokens) {
        return new Sentence(List.of(tokens));
    }

    public static Token glyph(String name) {
        return glyph(ResourceLocation.fromNamespaceAndPath(FolkwaysMod.MOD_ID, "glyph/" + name));
    }

    public static Token glyph(ResourceLocation sprite) {
        return new Token.Glyph(sprite, Optional.empty());
    }

    public static Token glyph(ResourceLocation sprite, Notice otherwise) {
        return new Token.Glyph(sprite, Optional.of(otherwise));
    }

    /** The doing's own glyph, or its name where it has none. */
    public static Token doing(ResourceLocation what) {
        return new Token.Glyph(sprite(what), Optional.of(Doings.said(what, Optional.empty())));
    }

    public static Token ware(ResourceLocation item, long count) {
        return new Token.Ware(item, count);
    }

    public static Token ware(ItemStack stack) {
        return new Token.Ware(BuiltInRegistries.ITEM.getKey(stack.getItem()), stack.getCount());
    }

    public static Token word(Notice notice) {
        return new Token.Word(notice);
    }

    /** A bare word: a name or a number, said as it is. */
    public static Token word(Notice.Arg said) {
        return new Token.Word(new Notice(AS_IS, List.of(said)));
    }

    /** {@code lead}, then the wares with a plus between each two. */
    public static Sentence wares(Token lead, List<Token.Ware> wares) {
        List<Token> tokens = new ArrayList<>();
        tokens.add(lead);
        joined(tokens, wares);
        return new Sentence(tokens);
    }

    /** What is used, an arrow, and what is made. */
    public static Sentence turning(List<Token.Ware> used, List<Token.Ware> made) {
        List<Token> tokens = new ArrayList<>();
        joined(tokens, used);
        tokens.add(YIELDS);
        joined(tokens, made);
        return new Sentence(tokens);
    }

    /**
     * A doing told in pictures: the doing's glyph and the items it works on, or for work that turns some items into
     * others, the ones it uses, an arrow, and the ones it makes. A doing on the way to a job is the glyph of how the
     * body goes, then the job. Work with no items to show says its name in words after its glyph.
     */
    public static Sentence of(Doing doing) {
        List<Token> tokens = new ArrayList<>();
        Optional<ResourceLocation> toward = doing.toward();
        if (toward.isPresent()) {
            tokens.add(doing(doing.what()));
            job(tokens, toward.get(), doing);
        } else {
            job(tokens, doing.what(), doing);
        }
        return new Sentence(tokens);
    }

    public Sentence then(Token... more) {
        List<Token> joined = new ArrayList<>(tokens);
        joined.addAll(List.of(more));
        return new Sentence(joined);
    }

    public Sentence then(Sentence more) {
        List<Token> joined = new ArrayList<>(tokens);
        joined.addAll(more.tokens);
        return new Sentence(joined);
    }

    public boolean isEmpty() {
        return tokens.isEmpty();
    }

    private static void job(List<Token> tokens, ResourceLocation what, Doing doing) {
        List<Token.Ware> used = known(doing.wares().used());
        List<Token.Ware> made = known(doing.wares().made());
        if (!used.isEmpty() && !made.isEmpty()) {
            tokens.addAll(turning(used, made).tokens);
            return;
        }
        List<Token.Ware> goods = used.isEmpty() ? made : used;
        if (goods.isEmpty()) {
            tokens.add(glyph(sprite(what)));
            tokens.add(word(Doings.said(what, doing.about())));
            return;
        }
        tokens.addAll(wares(doing(what), goods).tokens);
    }

    private static List<Token.Ware> known(List<Doing.Ware> wares) {
        List<Token.Ware> known = new ArrayList<>();
        for (Doing.Ware ware : wares) {
            if (BuiltInRegistries.ITEM.get(ware.item()) != Items.AIR) {
                known.add(new Token.Ware(ware.item(), ware.count()));
            }
        }
        return known;
    }

    private static void joined(List<Token> tokens, List<Token.Ware> wares) {
        for (int at = 0; at < wares.size(); at++) {
            if (at > 0) {
                tokens.add(PLUS);
            }
            tokens.add(wares.get(at));
        }
    }

    private static ResourceLocation sprite(ResourceLocation what) {
        return ResourceLocation.fromNamespaceAndPath(what.getNamespace(), "doing/" + what.getPath());
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeVarInt(tokens.size());
        for (Token token : tokens) {
            switch (token) {
                case Token.Glyph glyph -> {
                    buffer.writeVarInt(0);
                    buffer.writeResourceLocation(glyph.sprite());
                    buffer.writeOptional(glyph.otherwise(), (buf, notice) -> notice.encode(buf));
                }
                case Token.Ware ware -> {
                    buffer.writeVarInt(1);
                    buffer.writeResourceLocation(ware.item());
                    buffer.writeVarLong(ware.count());
                }
                case Token.Plus plus -> buffer.writeVarInt(2);
                case Token.Yields yields -> buffer.writeVarInt(3);
                case Token.Word word -> {
                    buffer.writeVarInt(4);
                    word.notice().encode(buffer);
                }
            }
        }
    }

    public static Sentence decode(FriendlyByteBuf buffer) {
        int count = buffer.readVarInt();
        if (count < 0 || count > MOST) {
            throw new DecoderException("too long a sentence: " + count);
        }
        List<Token> tokens = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            tokens.add(switch (buffer.readVarInt()) {
                case 0 -> new Token.Glyph(buffer.readResourceLocation(), buffer.readOptional(Notice::decode));
                case 1 -> new Token.Ware(buffer.readResourceLocation(), buffer.readVarLong());
                case 2 -> PLUS;
                case 3 -> YIELDS;
                case 4 -> new Token.Word(Notice.decode(buffer));
                default -> throw new DecoderException("no such token in a sentence");
            });
        }
        return new Sentence(tokens);
    }

    public ListTag save() {
        ListTag saved = new ListTag();
        for (Token token : tokens) {
            CompoundTag entry = new CompoundTag();
            switch (token) {
                case Token.Glyph glyph -> {
                    entry.putString("glyph", glyph.sprite().toString());
                    glyph.otherwise().ifPresent(notice -> entry.put("otherwise", notice.save()));
                }
                case Token.Ware ware -> {
                    entry.putString("ware", ware.item().toString());
                    entry.putLong("count", ware.count());
                }
                case Token.Plus plus -> entry.putBoolean("plus", true);
                case Token.Yields yields -> entry.putBoolean("yields", true);
                case Token.Word word -> entry.put("word", word.notice().save());
            }
            saved.add(entry);
        }
        return saved;
    }

    public static Sentence load(ListTag saved) {
        List<Token> tokens = new ArrayList<>();
        for (Tag each : saved) {
            if (!(each instanceof CompoundTag entry)) {
                continue;
            }
            if (entry.contains("glyph")) {
                ResourceLocation sprite = ResourceLocation.tryParse(entry.getString("glyph"));
                if (sprite != null) {
                    tokens.add(new Token.Glyph(sprite, entry.contains("otherwise")
                        ? Optional.of(Notice.load(entry.getCompound("otherwise"))) : Optional.empty()));
                }
            } else if (entry.contains("ware")) {
                ResourceLocation item = ResourceLocation.tryParse(entry.getString("ware"));
                if (item != null) {
                    tokens.add(new Token.Ware(item, entry.getLong("count")));
                }
            } else if (entry.contains("plus")) {
                tokens.add(PLUS);
            } else if (entry.contains("yields")) {
                tokens.add(YIELDS);
            } else if (entry.contains("word")) {
                tokens.add(new Token.Word(Notice.load(entry.getCompound("word"))));
            }
        }
        return new Sentence(tokens);
    }
}
