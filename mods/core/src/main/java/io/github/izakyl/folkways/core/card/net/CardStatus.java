package io.github.izakyl.folkways.core.card.net;

import io.github.izakyl.folkways.core.card.api.Card;
import io.github.izakyl.folkways.core.card.api.CardCodec;
import io.github.izakyl.folkways.core.card.api.notice.Line;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.facility.api.Operating;
import io.github.izakyl.folkways.core.facility.api.control.Value;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/**
 * The live side of one facility's card, sent only to the players looking at it: its title and card, what its parameters and
 * switches are set to, the readings its card names, which presses cannot be made now and why, its runs, its status
 * lines and lists, and its overlays. A facility with nothing loaded sends empty readings, lines and lists.
 */
public record CardStatus(UUID colony, UUID facility, Component title, Card card, Map<String, Value> parameters,
                         Map<String, Boolean> triggers, List<Reading> readings, Map<String, String> disabled,
                         List<String> running, Optional<Operating.Recent> recent, List<Line> lines,
                         Map<String, List<Card.Row>> rows, List<Operating.Overlay> overlays)
    implements CustomPacketPayload {
    public static final Type<CardStatus> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath("folkways_core", "card_status"));
    public static final StreamCodec<RegistryFriendlyByteBuf, CardStatus> STREAM_CODEC =
        StreamCodec.ofMember(CardStatus::encode, CardStatus::decode);
    private static final int MAX = 256;
    private static final int MOST_LINES = 32;

    /** A reading by name; {@code range} bounds a number when its signal declares one. */
    public record Reading(String name, Optional<Value> value, Optional<double[]> range) {
        @Override
        public boolean equals(Object other) {
            return other instanceof Reading that && name.equals(that.name) && value.equals(that.value)
                && range.map(java.util.Arrays::toString).equals(that.range.map(java.util.Arrays::toString));
        }

        @Override
        public int hashCode() {
            return name.hashCode() * 31 + value.hashCode();
        }
    }

    public CardStatus {
        parameters = Map.copyOf(parameters);
        triggers = Map.copyOf(triggers);
        readings = List.copyOf(readings);
        disabled = Map.copyOf(disabled);
        running = List.copyOf(running);
        lines = List.copyOf(lines);
        rows = Map.copyOf(rows);
        overlays = List.copyOf(overlays);
        if (readings.size() > MAX || disabled.size() > MAX || running.size() > MAX || overlays.size() > MAX
                || rows.size() > MAX || overlays.stream().anyMatch(overlay -> overlay instanceof Operating.Overlay.Path line
                    && (line.points().isEmpty() || line.points().size() > MAX))) {
            throw new IllegalArgumentException("card status too large");
        }
    }

    /** What a player looking at this facility's card sees now. */
    public static CardStatus of(MinecraftServer server, Colony colony, Operating operating) {
        Card card = operating.card();
        ServerLevel level = operating.loaded(server).orElse(null);
        Set<String> named = new LinkedHashSet<>();
        Map<String, String> disabled = new LinkedHashMap<>();
        Map<String, List<Card.Row>> rows = new LinkedHashMap<>();
        Map<String, io.github.izakyl.folkways.core.facility.api.control.Reading> sources =
            level == null ? Map.of() : operating.readings(level);
        Map<String, Value> taken = new LinkedHashMap<>();
        if (level != null) {
            sources.forEach((name, reading) -> reading.read(level).ifPresent(value -> taken.put(name, value)));
        }
        for (int at = 0; at < card.elements().size(); at++) {
            final int index = at;
            switch (card.elements().get(index)) {
                case Card.Element.Readout readout -> named.add(readout.reading());
                case Card.Element.Button button -> operating.disabled(button.routine(), button.enabledWhen())
                    .ifPresent(why -> disabled.put(button.routine(), why));
                case Card.Element.Target target -> operating.disabled(target.routine(), target.enabledWhen())
                    .ifPresent(why -> disabled.put(Card.Element.Target.statusKey(index), why));
                case Card.Element.Choice choice -> operating.disabled(choice.run().orElse(""), choice.enabledWhen())
                    .ifPresent(why -> disabled.put(Card.Element.Choice.statusKey(index), why));
                case Card.Element.Rows list -> {
                    if (level != null) {
                        rows.put(list.source(), operating.facility().rows(level, list.source(), taken));
                    }
                }
                default -> {
                }
            }
        }
        List<Reading> readings = new ArrayList<>();
        for (String name : named) {
            readings.add(new Reading(name, Optional.ofNullable(taken.get(name)),
                level == null ? Optional.empty() : operating.range(level, name)));
        }
        List<Line> lines = level == null || card.elements().stream().noneMatch(Card.Element.Lines.class::isInstance)
            ? List.of() : operating.facility().lines(level, taken);
        return new CardStatus(colony.id(), operating.id(), operating.title(), card, operating.parameters(),
            operating.switches(), readings, disabled, operating.running(), operating.recent(),
            lines.subList(0, Math.min(lines.size(), MOST_LINES)), rows,
            level == null ? List.of() : operating.overlays(level));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private void encode(RegistryFriendlyByteBuf b) {
        b.writeUUID(colony);
        b.writeUUID(facility);
        ComponentSerialization.STREAM_CODEC.encode(b, title);
        CardCodec.write(b, card);
        CardCodec.values(b, parameters);
        b.writeVarInt(triggers.size());
        triggers.forEach((trigger, on) -> {
            b.writeUtf(trigger, MAX);
            b.writeBoolean(on);
        });
        b.writeVarInt(readings.size());
        for (Reading reading : readings) {
            b.writeUtf(reading.name(), MAX);
            CardCodec.value(b, reading.value());
            b.writeBoolean(reading.range().isPresent());
            reading.range().ifPresent(range -> {
                b.writeDouble(range[0]);
                b.writeDouble(range[1]);
            });
        }
        b.writeVarInt(disabled.size());
        disabled.forEach((routine, reason) -> {
            b.writeUtf(routine, MAX);
            b.writeUtf(reason, MAX);
        });
        b.writeVarInt(running.size());
        running.forEach(routine -> b.writeUtf(routine, MAX));
        b.writeBoolean(recent.isPresent());
        recent.ifPresent(last -> {
            b.writeUtf(last.routine(), MAX);
            b.writeUtf(last.ending(), MAX);
            b.writeOptional(last.reason(), (buf, reason) -> buf.writeUtf(reason, MAX));
        });
        Line.encodeAll(b, lines, MOST_LINES);
        b.writeVarInt(rows.size());
        rows.forEach((source, list) -> {
            b.writeUtf(source, MAX);
            CardCodec.rows(b, list);
        });
        b.writeVarInt(overlays.size());
        for (Operating.Overlay overlay : overlays) {
            switch (overlay) {
                case Operating.Overlay.Ring ring -> {
                    b.writeByte(0);
                    b.writeVec3(ring.center());
                    b.writeDouble(ring.radius());
                }
                case Operating.Overlay.Path line -> {
                    b.writeByte(1);
                    b.writeBoolean(line.endsAtMark());
                    b.writeVarInt(line.points().size());
                    line.points().forEach(b::writeVec3);
                }
            }
        }
    }

    private static CardStatus decode(RegistryFriendlyByteBuf b) {
        UUID colony = b.readUUID();
        UUID facility = b.readUUID();
        Component title = ComponentSerialization.STREAM_CODEC.decode(b);
        Card card = CardCodec.read(b);
        Map<String, Value> parameters = CardCodec.values(b);
        Map<String, Boolean> triggers = new LinkedHashMap<>();
        for (int n = count(b); n > 0; n--) {
            triggers.put(b.readUtf(MAX), b.readBoolean());
        }
        List<Reading> readings = new ArrayList<>();
        for (int n = count(b); n > 0; n--) {
            String name = b.readUtf(MAX);
            Optional<Value> value = CardCodec.value(b);
            readings.add(new Reading(name, value, b.readBoolean()
                ? Optional.of(new double[] {b.readDouble(), b.readDouble()}) : Optional.empty()));
        }
        Map<String, String> disabled = new LinkedHashMap<>();
        for (int n = count(b); n > 0; n--) {
            disabled.put(b.readUtf(MAX), b.readUtf(MAX));
        }
        List<String> running = new ArrayList<>();
        for (int n = count(b); n > 0; n--) {
            running.add(b.readUtf(MAX));
        }
        Optional<Operating.Recent> recent = b.readBoolean() ? Optional.of(new Operating.Recent(b.readUtf(MAX),
            b.readUtf(MAX), b.readOptional(buf -> buf.readUtf(MAX)))) : Optional.empty();
        List<Line> lines = Line.decodeAll(b, MOST_LINES);
        Map<String, List<Card.Row>> rows = new LinkedHashMap<>();
        for (int n = count(b); n > 0; n--) {
            rows.put(b.readUtf(MAX), CardCodec.rows(b));
        }
        List<Operating.Overlay> overlays = new ArrayList<>();
        for (int n = count(b); n > 0; n--) {
            overlays.add(switch (b.readByte()) {
                case 0 -> new Operating.Overlay.Ring(b.readVec3(), b.readDouble());
                case 1 -> {
                    boolean endsAtMark = b.readBoolean();
                    List<Vec3> path = new ArrayList<>();
                    for (int p = count(b); p > 0; p--) {
                        path.add(b.readVec3());
                    }
                    yield new Operating.Overlay.Path(path, endsAtMark);
                }
                default -> throw new IllegalArgumentException("invalid card overlay");
            });
        }
        return new CardStatus(colony, facility, title, card, parameters, triggers, readings, disabled, running, recent,
            lines, rows, overlays);
    }

    private static int count(RegistryFriendlyByteBuf b) {
        int count = b.readVarInt();
        if (count < 0 || count > MAX || count > b.readableBytes()) {
            throw new IllegalArgumentException("invalid card status count");
        }
        return count;
    }
}
