package io.github.izakyl.folkways.vanilla.toms;

import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.core.card.api.notice.Attempt;
import io.github.izakyl.folkways.core.colony.charter.Charters;
import io.github.izakyl.folkways.core.shell.toms.TomsIntegration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

// The terminal's order board travels whole each time: a player asks, the server answers with what can be ordered,
// the requests here and what became of the last press.
public final class TomsOrderNetwork {

    static final int MOST_OFFERS = 512;
    static final int MOST_REQUESTS = 64;

    private TomsOrderNetwork() {
    }

    public enum Action {
        PEEK, OPEN, PLACE, CANCEL, CATALOG
    }

    public record Ask(Action action, String item, int count) implements CustomPacketPayload {
        static final Type<Ask> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "toms_order_ask"));
        static final StreamCodec<RegistryFriendlyByteBuf, Ask> CODEC = new StreamCodec<>() {
            public Ask decode(RegistryFriendlyByteBuf buf) {
                Action[] actions = Action.values();
                int action = buf.readVarInt();
                if (action < 0 || action >= actions.length) {
                    throw new IllegalArgumentException("unknown order action");
                }
                return new Ask(actions[action], buf.readUtf(256), buf.readVarInt());
            }

            public void encode(RegistryFriendlyByteBuf buf, Ask ask) {
                buf.writeVarInt(ask.action().ordinal());
                buf.writeUtf(ask.item(), 256);
                buf.writeVarInt(ask.count());
            }
        };

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record Board(Action answering, Optional<Component> closed, List<TomsOrders.Offer> offers,
                        List<TomsOrders.Request> requests, Optional<Component> said, boolean went)
        implements CustomPacketPayload {
        static final Type<Board> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "toms_order_board"));
        private static final StreamCodec<RegistryFriendlyByteBuf, Optional<Component>> TEXT =
            ComponentSerialization.TRUSTED_STREAM_CODEC.apply(net.minecraft.network.codec.ByteBufCodecs::optional);
        static final StreamCodec<RegistryFriendlyByteBuf, Board> CODEC = new StreamCodec<>() {
            public Board decode(RegistryFriendlyByteBuf buf) {
                Action answering = Action.values()[Math.floorMod(buf.readVarInt(), Action.values().length)];
                Optional<Component> closed = TEXT.decode(buf);
                int offers = Math.min(buf.readVarInt(), MOST_OFFERS);
                List<TomsOrders.Offer> offered = new ArrayList<>();
                for (int i = 0; i < offers; i++) {
                    offered.add(new TomsOrders.Offer(buf.readResourceLocation(), buf.readVarLong(), buf.readVarLong(),
                        buf.readBoolean()));
                }
                int requests = Math.min(buf.readVarInt(), MOST_REQUESTS);
                List<TomsOrders.Request> asked = new ArrayList<>();
                TomsOrders.Status[] statuses = TomsOrders.Status.values();
                for (int i = 0; i < requests; i++) {
                    asked.add(new TomsOrders.Request(buf.readResourceLocation(), buf.readVarLong(), buf.readVarLong(),
                        buf.readVarLong(), buf.readVarLong(), statuses[Math.floorMod(buf.readVarInt(), statuses.length)],
                        TEXT.decode(buf)));
                }
                return new Board(answering, closed, List.copyOf(offered), List.copyOf(asked), TEXT.decode(buf),
                    buf.readBoolean());
            }

            public void encode(RegistryFriendlyByteBuf buf, Board board) {
                buf.writeVarInt(board.answering().ordinal());
                TEXT.encode(buf, board.closed());
                List<TomsOrders.Offer> offers = board.offers().subList(0, Math.min(board.offers().size(), MOST_OFFERS));
                buf.writeVarInt(offers.size());
                for (TomsOrders.Offer offer : offers) {
                    buf.writeResourceLocation(offer.item());
                    buf.writeVarLong(offer.here());
                    buf.writeVarLong(offer.elsewhere());
                    buf.writeBoolean(offer.made());
                }
                List<TomsOrders.Request> requests = board.requests()
                    .subList(0, Math.min(board.requests().size(), MOST_REQUESTS));
                buf.writeVarInt(requests.size());
                for (TomsOrders.Request request : requests) {
                    buf.writeResourceLocation(request.item());
                    buf.writeVarLong(request.asked());
                    buf.writeVarLong(request.high());
                    buf.writeVarLong(request.held());
                    buf.writeVarLong(request.coming());
                    buf.writeVarInt(request.status().ordinal());
                    TEXT.encode(buf, request.reason());
                }
                TEXT.encode(buf, board.said());
                buf.writeBoolean(board.went());
            }
        };

        public boolean open() {
            return closed.isEmpty();
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar(FolkwaysCoreMod.NETWORK_VERSION);
        registrar.playToServer(Ask.TYPE, Ask.CODEC, (ask, context) -> context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                PacketDistributor.sendToPlayer(player, answer(player, ask));
            }
        }));
        registrar.playToClient(Board.TYPE, Board.CODEC, (board, context) -> context.enqueueWork(() -> {
            if (ModList.get().isLoaded("toms_storage")) {
                TomsOrdersClient.heard(board);
            }
        }));
    }

    /** The board for the terminal a player has open, after carrying out what they asked. */
    public static Board answer(ServerPlayer player, Ask ask) {
        if (!ModList.get().isLoaded("toms_storage")) {
            return closed(ask.action(), TomsOrderRefusal.NOT_A_STORE);
        }
        var at = TomsIntegration.terminalAt(player.containerMenu);
        if (at.isEmpty()) {
            return closed(ask.action(), TomsOrderRefusal.NOT_A_STORE);
        }
        var network = at.get().level(player.getServer()).flatMap(level -> TomsOrders.network(level, at.get()));
        if (network.isEmpty()) {
            return closed(ask.action(), TomsOrderRefusal.NOT_A_STORE);
        }
        if (!Charters.member(player.getServer(), network.get().colony(), player.getUUID())) {
            return closed(ask.action(), TomsOrderRefusal.NOT_A_MEMBER);
        }
        Optional<ResourceLocation> item = Optional.ofNullable(ResourceLocation.tryParse(ask.item()));
        Attempt attempt = switch (ask.action()) {
            case PEEK, OPEN, CATALOG -> Attempt.went();
            case PLACE -> item.map(id -> TomsOrders.place(network.get(), id, ask.count()))
                .orElseGet(() -> Attempt.refused(TomsOrderRefusal.UNKNOWN));
            case CANCEL -> item.map(id -> TomsOrders.cancel(network.get(), id))
                .orElseGet(() -> Attempt.refused(TomsOrderRefusal.UNKNOWN));
        };
        boolean peek = ask.action() == Action.PEEK;
        return new Board(ask.action(), Optional.empty(), peek ? List.of() : TomsOrders.offers(network.get()),
            peek ? List.of() : TomsOrders.requests(network.get()),
            attempt.refusal().map(why -> why.component()), !attempt.refused());
    }

    private static Board closed(Action action, TomsOrderRefusal why) {
        return new Board(action, Optional.of(Component.translatable(why.translationKey())), List.of(), List.of(),
            Optional.empty(), false);
    }
}
