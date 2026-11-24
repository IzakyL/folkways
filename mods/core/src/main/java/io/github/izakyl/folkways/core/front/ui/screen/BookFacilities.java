package io.github.izakyl.folkways.core.front.ui.screen;

import com.lowdragmc.lowdraglib2.gui.texture.ColorRectTexture;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.data.TextWrap;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import io.github.izakyl.folkways.core.card.api.notice.Line;
import io.github.izakyl.folkways.core.card.client.CardLines;
import io.github.izakyl.folkways.core.card.client.CardWindow;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.facility.api.Operating;
import io.github.izakyl.folkways.core.facility.api.Operations;
import io.github.izakyl.folkways.core.facility.api.control.Value;
import io.github.izakyl.folkways.core.front.api.ui.Pages;
import io.github.izakyl.folkways.core.front.api.ui.Rows;
import io.github.izakyl.folkways.core.front.api.ui.Tokens;
import io.github.izakyl.folkways.core.front.client.ColonyHighlightState;
import io.github.izakyl.folkways.core.front.engine.colony.ColonyScopes;
import io.github.izakyl.folkways.core.front.engine.colony.HeldFacilities;
import io.github.izakyl.folkways.core.front.engine.colony.PanelFacilities;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The colony's facilities as the book lists them: each one's name, icon and kind, the status line that says most
 * (what stands in its way, else its first), and where it is. The server reads them at most once a second for each page
 * that lists them; the client draws each as a row that opens the facility's card, or shows its place.
 */
final class BookFacilities {
    static final int MOST = 200;
    private static final int PERIOD = 20;
    private static final int TITLE_SHARE = 40;
    static final int TROUBLE = 0xFF9A3324;
    static final ResourceLocation DEFINED = ResourceLocation.fromNamespaceAndPath("folkways_core", "defined");
    private static final ResourceLocation FACILITY = ResourceLocation.withDefaultNamespace("lever");

    record Entry(UUID id, Component title, ResourceLocation icon, ResourceLocation kind, Component kindName,
                 Group group, Optional<Line> line, boolean trouble, Optional<BlockPos> at) {
    }

    /**
     * Where a facility sits on the page, by how the colony came to have it: ground marked out with the book's box,
     * a route drawn with its line, a block taken in with its point, something a colony service keeps (a train, a
     * network, an order), or a facility a player defined. Every source lands in one, an extension's included.
     */
    enum Group {
        ZONES("grass_block"),
        ROUTES("rail"),
        BLOCKS("bricks"),
        KEPT("repeater"),
        DEFINED("writable_book");

        private final ResourceLocation icon;

        Group(String icon) {
            this.icon = ResourceLocation.withDefaultNamespace(icon);
        }

        String key() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }

        ResourceLocation icon() {
            return icon;
        }

        Component title() {
            return Component.translatable("folkways.book.facilities.group." + key());
        }

        static Group of(Operating operating) {
            if (operating instanceof HeldFacilities.Held held) {
                return switch (held.delegation().shape().gesture()) {
                    case BOX -> ZONES;
                    case LINE -> ROUTES;
                    case POINT -> BLOCKS;
                };
            }
            return operating.kind().equals(BookFacilities.DEFINED) ? DEFINED : KEPT;
        }

        static Group parse(String key) {
            for (Group group : values()) {
                if (group.key().equals(key)) {
                    return group;
                }
            }
            return KEPT;
        }
    }

    /** What a page last heard: the facilities it lists, how many more there were, and the colony they belong to. */
    record Listing(UUID colony, List<Entry> entries, int more) {
        static final Listing NONE = new Listing(new UUID(0, 0), List.of(), 0);
    }

    /** The server side of one page's listing, read again once its period has passed. */
    static final class Reader {
        private long readAt = -PERIOD;
        private Tag read = new CompoundTag();

        Tag tag(Player player) {
            Optional<Colony> colony = Pages.colonyOf(player);
            Optional<ServerLevel> level = Pages.levelOf(player);
            if (colony.isEmpty() || level.isEmpty()) {
                return new CompoundTag();
            }
            MinecraftServer server = level.get().getServer();
            long now = server.getTickCount();
            if (now - readAt >= PERIOD || now < readAt) {
                readAt = now;
                read = BookFacilities.tag(player.registryAccess(), colony.get().id(), read(server, colony.get()));
            }
            return read;
        }
    }

    private BookFacilities() {
    }

    /** Every facility the colony lists, troubled ones first; a module's book sections and its settings are not. */
    static List<Entry> read(MinecraftServer server, Colony colony) {
        List<Entry> entries = new ArrayList<>();
        for (Operating operating : Operations.all(colony)) {
            if (operating instanceof ColonyScopes.Scope || operating instanceof PanelFacilities.Section) {
                continue;
            }
            entries.add(entry(server, operating));
        }
        entries.sort(Comparator.comparing((Entry entry) -> !entry.trouble())
            .thenComparing(entry -> entry.kind().toString()));
        return entries;
    }

    private static Entry entry(MinecraftServer server, Operating operating) {
        ResourceLocation kind = operating.kind();
        Component kindName = operating instanceof HeldFacilities.Held held
            ? held.title()
            : Component.translatableWithFallback(
                "folkways.book.facilities.kind." + kind.getNamespace() + "." + kind.getPath(), kind.toString());
        List<Line> lines = List.of();
        Optional<BlockPos> at = Optional.empty();
        try {
            at = Optional.of(operating.facility().site().cell());
            ServerLevel level = operating.loaded(server).orElse(null);
            if (level != null) {
                Map<String, Value> taken = new LinkedHashMap<>();
                operating.facility().readings(level)
                    .forEach((name, reading) -> reading.read(level).ifPresent(value -> taken.put(name, value)));
                lines = operating.facility().lines(level, taken);
            }
        } catch (RuntimeException unreadable) {
            lines = List.of();
        }
        List<Line> told = lines;
        Optional<Line> blocked = told.stream().filter(Line.Blocked.class::isInstance).findFirst();
        Optional<Line> line = blocked.or(() -> told.stream().findFirst());
        return new Entry(operating.id(), operating.title(), operating.icon().orElse(FACILITY), kind, kindName,
            Group.of(operating), line, blocked.isPresent(), at);
    }

    static Tag tag(RegistryAccess access, UUID colony, List<Entry> entries) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("colony", colony);
        ListTag list = new ListTag();
        for (Entry entry : entries.subList(0, Math.min(MOST, entries.size()))) {
            CompoundTag row = new CompoundTag();
            row.putUUID("id", entry.id());
            row.put("title", Pages.text(access, entry.title()));
            row.putString("icon", entry.icon().toString());
            row.putString("kind", entry.kind().toString());
            row.put("kindName", Pages.text(access, entry.kindName()));
            row.putString("group", entry.group().key());
            row.putBoolean("trouble", entry.trouble());
            entry.at().ifPresent(at -> row.putLong("at", at.asLong()));
            entry.line().ifPresent(line -> {
                var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), access);
                try {
                    Line.encodeAll(buffer, List.of(line), 1);
                    byte[] bytes = new byte[buffer.readableBytes()];
                    buffer.readBytes(bytes);
                    row.putByteArray("line", bytes);
                } finally {
                    buffer.release();
                }
            });
            list.add(row);
        }
        tag.put("entries", list);
        tag.putInt("more", Math.max(0, entries.size() - MOST));
        return tag;
    }

    static Listing parse(RegistryAccess access, Tag tag) {
        if (!(tag instanceof CompoundTag data) || !data.hasUUID("colony")) {
            return Listing.NONE;
        }
        List<Entry> entries = new ArrayList<>();
        for (Tag each : data.getList("entries", Tag.TAG_COMPOUND)) {
            CompoundTag row = (CompoundTag) each;
            Optional<Line> line = Optional.empty();
            if (row.contains("line")) {
                var buffer = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(row.getByteArray("line")), access);
                try {
                    line = Line.decodeAll(buffer, 1).stream().findFirst();
                } catch (RuntimeException malformed) {
                    line = Optional.empty();
                } finally {
                    buffer.release();
                }
            }
            ResourceLocation icon = ResourceLocation.tryParse(row.getString("icon"));
            ResourceLocation kind = ResourceLocation.tryParse(row.getString("kind"));
            entries.add(new Entry(row.getUUID("id"), Pages.readText(access, row.get("title")),
                icon == null ? FACILITY : icon, kind == null ? DEFINED : kind,
                Pages.readText(access, row.get("kindName")), Group.parse(row.getString("group")),
                line, row.getBoolean("trouble"),
                row.contains("at") ? Optional.of(BlockPos.of(row.getLong("at"))) : Optional.empty()));
        }
        return new Listing(data.getUUID("colony"), List.copyOf(entries), data.getInt("more"));
    }

    /**
     * One facility on a page. A press opens its card beside the book; the compass at its end puts the book away to
     * show the place, and the card comes along over the world.
     */
    static UIElement row(UUID colony, Entry entry) {
        UIElement mark = new UIElement()
            .layout(layout -> layout.width(2).heightPercent(100).flexShrink(0))
            .style(style -> style.background(new ColorRectTexture(entry.trouble() ? TROUBLE : 0)));
        Label title = Rows.value(entry.title());
        title.layout(layout -> layout.widthPercent(TITLE_SHARE).flexShrink(0).minWidth(0));
        title.textStyle(style -> style.textWrap(TextWrap.HOVER_ROLL).adaptiveWidth(false));
        UIElement status = new UIElement()
            .layout(layout -> layout.flexDirection(FlexDirection.ROW).flexGrow(1).flexShrink(1).minWidth(0)
                .heightPercent(100).alignItems(AlignItems.CENTER));
        status.setOverflowVisible(false);
        entry.line().ifPresent(line -> status.addChildren(status(line)));
        Button press = new Button().noText();
        press.layout(layout -> layout.flexDirection(FlexDirection.ROW).flexGrow(1).flexShrink(1).minWidth(0)
            .heightPercent(100).alignItems(AlignItems.CENTER).gapAll(Rows.GAP).paddingAll(1).paddingRight(Rows.GAP));
        press.addClass("folkways_nav");
        press.setOverflowVisible(false);
        press.addChildren(mark, Rows.icon(SettingsPage.iconOf(entry.icon())), title, status);
        List<Component> tip = new ArrayList<>();
        tip.add(entry.title());
        tip.add(entry.kindName().copy().withStyle(ChatFormatting.GRAY));
        entry.line().map(CardLines::words).filter(words -> !words.getString().isEmpty())
            .ifPresent(words -> tip.add(words.copy().withStyle(entry.trouble() ? ChatFormatting.RED
                : ChatFormatting.GRAY)));
        tip.add(Component.translatable("folkways.book.facilities.open").withStyle(ChatFormatting.DARK_GRAY));
        press.style(style -> style.tooltips(tip.toArray(Component[]::new)));
        press.setOnClick(event -> BookCards.open(colony, entry.id()));
        Tokens.name(press, "folkways.book.facility." + entry.id());
        UIElement row = new UIElement()
            .layout(layout -> layout.flexDirection(FlexDirection.ROW).widthPercent(100).height(Rows.ROW_HEIGHT)
                .flexShrink(0).alignItems(AlignItems.CENTER).gapAll(2));
        row.setOverflowVisible(false);
        row.addChildren(press);
        entry.at().ifPresent(at -> {
            Button ping = Rows.act();
            ping.addChildren(Rows.actIcon(new ItemStack(Items.COMPASS)));
            ping.style(style -> style.tooltips(Component.translatable("folkways.book.facilities.show"),
                Component.translatable("folkways.book.facilities.at", at.getX(), at.getY(), at.getZ())
                    .withStyle(ChatFormatting.GRAY)));
            ping.setOnClick(event -> {
                ColonyHighlightState.pingBlock(at);
                CardWindow.open(colony, entry.id());
            });
            Tokens.name(ping, "folkways.book.facility.show." + entry.id());
            row.addChildren(ping);
        });
        return row;
    }

    // The line in a row's height: words on one line, rolling when long; pictures as they are.
    private static UIElement status(Line line) {
        Component words = CardLines.words(line);
        if (!words.getString().isEmpty()) {
            Label label = Rows.name(words);
            int ink = line instanceof Line.Blocked ? CardLines.WARN : Rows.QUIET;
            label.textStyle(style -> style.textColor(ink));
            return label;
        }
        List<UIElement> drawn = CardLines.of(line, Rows.QUIET);
        return drawn.isEmpty() ? new UIElement() : drawn.getFirst();
    }
}
