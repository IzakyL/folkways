package io.github.izakyl.folkways.core.shell.rs2;

import com.refinedmods.refinedstorage.api.network.Network;
import com.refinedmods.refinedstorage.api.network.autocrafting.AutocraftingNetworkComponent;
import com.refinedmods.refinedstorage.api.network.storage.StorageNetworkComponent;
import com.refinedmods.refinedstorage.api.storage.Storage;
import com.refinedmods.refinedstorage.common.api.grid.Grid;
import com.refinedmods.refinedstorage.common.grid.GridData;
import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.core.shell.crafting.TerminalPreferences;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

public final class TerminalRecipes {

    public interface Menu extends TerminalPreferences.Menu {
        void folkways$receive(GridData data);
    }

    private static final Set<ColonyProvider> PROVIDERS = new HashSet<>();
    private static final ThreadLocal<ServerPlayer> OPENING = new ThreadLocal<>();

    private TerminalRecipes() {
    }

    static void added(ColonyProvider provider) {
        PROVIDERS.add(provider);
    }

    static void removed(ColonyProvider provider) {
        PROVIDERS.remove(provider);
    }

    public static void opening(ServerPlayer player) {
        if (player == null) {
            OPENING.remove();
        } else {
            OPENING.set(player);
        }
    }

    public static GridData opening(Grid grid, GridData data) {
        ServerPlayer player = OPENING.get();
        return player == null ? data : filter(player, grid.getItemStorage(), data);
    }

    public static GridData filter(ServerPlayer player, Storage storage, GridData data) {
        if (TerminalPreferences.visible(player)) {
            return data;
        }
        Network network = PROVIDERS.stream().map(ColonyProvider::getNetwork)
            .filter(value -> value != null && value.getComponent(StorageNetworkComponent.class) == storage)
            .findFirst().orElse(null);
        if (network == null) {
            return data;
        }
        var crafting = network.getComponent(AutocraftingNetworkComponent.class);
        var visible = new HashSet<>(data.autocraftableResources());
        visible.removeIf(key -> {
            var patterns = crafting.getPatternsByOutput(key);
            return !patterns.isEmpty() && patterns.stream()
                .allMatch(pattern -> crafting.getProviderByPattern(pattern) instanceof ColonyProvider);
        });
        return new GridData(data.active(), data.resources(), Set.copyOf(visible));
    }

    public static void send(ServerPlayer player, Grid grid, int menu) {
        PacketDistributor.sendToPlayer(player, new Snapshot(menu,
            filter(player, grid.getItemStorage(), new GridData(
                grid.isGridActive(), List.of(), grid.getAutocraftableResources()))));
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar(FolkwaysCoreMod.NETWORK_VERSION).playToClient(Snapshot.TYPE, Snapshot.CODEC,
            (packet, context) -> {
                var menu = context.player().containerMenu;
                if (menu.containerId == packet.menu() && menu instanceof Menu receiver) {
                    receiver.folkways$receive(packet.data());
                }
            });
    }

    public record Snapshot(int menu, GridData data) implements CustomPacketPayload {
        public static final Type<Snapshot> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath("folkways_core", "rs2_terminal"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Snapshot> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, Snapshot::menu, GridData.STREAM_CODEC, Snapshot::data, Snapshot::new);

        @Override
        public Type<Snapshot> type() {
            return TYPE;
        }
    }
}
