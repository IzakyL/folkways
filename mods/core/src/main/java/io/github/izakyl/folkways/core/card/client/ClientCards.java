package io.github.izakyl.folkways.core.card.client;

import io.github.izakyl.folkways.core.card.api.notice.Notice;
import io.github.izakyl.folkways.core.card.net.CardOpen;
import io.github.izakyl.folkways.core.card.net.CardOrders;
import io.github.izakyl.folkways.core.card.net.CardPress;
import io.github.izakyl.folkways.core.card.net.CardReply;
import io.github.izakyl.folkways.core.card.net.CardStatus;
import io.github.izakyl.folkways.core.card.net.CardWatch;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import net.neoforged.neoforge.network.PacketDistributor;

/** The cards this client has been told about, and the presses it is waiting to hear back on. */
public final class ClientCards {
    private static final Map<UUID, CardStatus> STATUS = new LinkedHashMap<>();
    private static final Map<Long, Consumer<Optional<Notice>>> WAITING = new LinkedHashMap<>();
    private static long request;

    private ClientCards() {
    }

    public static Optional<CardStatus> status(UUID facility) {
        return Optional.ofNullable(STATUS.get(facility));
    }

    public static void accept(CardStatus status) {
        STATUS.put(status.facility(), status);
    }

    private static Consumer<CardOpen> opener = open -> { };

    /** Installs what opens a card window when the server asks for one. */
    public static void opener(Consumer<CardOpen> installed) {
        opener = installed;
    }

    public static void accept(CardOpen open) {
        opener.accept(open);
    }

    public static void accept(CardReply reply) {
        Consumer<Optional<Notice>> answer = WAITING.remove(reply.request());
        if (answer != null) {
            answer.accept(reply.refused());
        }
    }

    public static void watch(UUID colony, UUID facility, boolean open) {
        if (!open) {
            STATUS.remove(facility);
        }
        PacketDistributor.sendToServer(new CardWatch(colony, facility, open));
    }

    /** Sends a press; {@code answer} hears the refusal notice, or empty when it was carried out. */
    public static void press(UUID colony, UUID facility, CardOrders.Press press, Consumer<Optional<Notice>> answer) {
        long id = ++request;
        WAITING.put(id, answer);
        while (WAITING.size() > 64) {
            WAITING.remove(WAITING.keySet().iterator().next());
        }
        PacketDistributor.sendToServer(new CardPress(id, colony, facility, press));
    }

    public static void forget() {
        STATUS.clear();
        WAITING.clear();
    }
}
