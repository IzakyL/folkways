package io.github.izakyl.folkways.plugins.build;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import io.github.izakyl.folkways.base.task.Jobs;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaConfig;
import io.github.izakyl.folkways.core.api.colony.Books;
import io.github.izakyl.folkways.front.api.Ghost;
import io.github.izakyl.folkways.front.api.ui.LonePanel;
import io.github.izakyl.folkways.front.api.ui.Placing;
import io.github.izakyl.folkways.front.api.ui.Rows;
import io.github.izakyl.folkways.plugins.build.draft.Templates;
import io.github.izakyl.folkways.plugins.build.draft.client.LocalPatterns;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * A blueprint taken off the page and held with the book: it is set down by its name when it is the server's, and
 * sent whole when it is the player's own. One the server holds just the same is the server's.
 */
@OnlyIn(Dist.CLIENT)
public final class HeldBlueprints {

    private HeldBlueprints() {
    }

    private static final float PICKER_WIDTH = 300f;
    private static final float PICKER_HEIGHT = 230f;

    public static void install() {
        BlueprintsPage.installPage(HeldBlueprints::page, entry -> take(entry, () -> {
            Player player = Minecraft.getInstance().player;
            if (player != null) {
                player.closeContainer();
            }
        }));
        Placing.shelve(new Placing.Shelf(BuildContent.BLUEPRINTS.nameKey(), BuildContent.BLUEPRINTS.icon(),
            HeldBlueprints::pick));
    }

    // The same list on a panel of its own, which goes back to the view it was opened from once one is picked.
    private static void pick() {
        LonePanel.open("blueprint_picker", Component.translatable(BuildContent.BLUEPRINTS.nameKey()),
            PICKER_WIDTH, PICKER_HEIGHT, pane -> page(entry -> take(entry, pane::close)));
    }

    private static UIElement page(Consumer<BlueprintsPage.Entry> takes) {
        UIElement page = Rows.page().addChildren(Rows.text(Component.translatable("folkways.library.loading")));
        Jobs.Scope files = LocalPatterns.scope();
        page.addEventListener(UIEvents.REMOVED, event -> {
            if (event.target == page) {
                files.close();
            }
        });
        Map<ResourceLocation, CompoundTag> offered = Templates.files();
        LocalPatterns.blueprints(files, page, mine -> {
            page.clearAllChildren();
            page.addChildren(BlueprintsPage.shelfOf(shelf(offered, mine), takes));
        }).whenComplete((loaded, broken) -> {
            if (broken != null) {
                Minecraft.getInstance().execute(() -> {
                    if (files.isOpen() && LocalPatterns.visible(page)) {
                        page.clearAllChildren();
                        page.addChildren(Rows.text(Component.translatable("folkways.library.failed")),
                            BlueprintsPage.shelfOf(shelf(Templates.files(), Map.of()), takes));
                    }
                });
            }
        });
        return page;
    }

    private static List<BlueprintsPage.Entry> shelf(Map<ResourceLocation, CompoundTag> offered,
            Map<ResourceLocation, CompoundTag> mine) {
        List<BlueprintsPage.Entry> entries = new ArrayList<>();
        offered.keySet().stream().filter(Templates::isBlueprint).sorted().forEach(id ->
            entries.add(new BlueprintsPage.Entry(id, Component.literal(nameOf(id)), false, offered.get(id))));
        mine.keySet().stream().sorted().forEach(id -> {
            CompoundTag tag = mine.get(id);
            if (!Objects.equals(tag, offered.get(id))) {
                entries.add(new BlueprintsPage.Entry(id, Component.literal(nameOf(id)), true, tag));
            }
        });
        return entries;
    }

    // away puts the list out of the way once the blueprint is in hand.
    private static void take(BlueprintsPage.Entry entry, Runnable away) {
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        Level level = minecraft.level;
        if (player == null || level == null) {
            return;
        }
        Optional<CompoundTag> file = Optional.ofNullable(entry.file());
        int most = player.isCreative() ? Integer.MAX_VALUE : FolkwaysVanillaConfig.maxBuildCells();
        Optional<Blueprint> blueprint = file.flatMap(tag -> StructureNbt.toBlueprint(tag, level.registryAccess(),
            UUID.randomUUID(), nameOf(entry.id()), 0L, StructureNbt.AirMeaning.UNCONSTRAINED, most));
        if (blueprint.isEmpty()) {
            player.displayClientMessage(file.isPresent()
                ? Component.translatable("folkways.blueprint.too_large", most)
                : Component.translatable("folkways.blueprint.unreadable"), true);
            return;
        }
        away.run();
        Placing.hold(new Held(entry, file.get(), blueprint.get()));
    }

    private static String nameOf(ResourceLocation id) {
        String path = id.getPath();
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private record Held(BlueprintsPage.Entry entry, CompoundTag file, Blueprint blueprint) implements Placing.Held {

        @Override
        public Component name() {
            return entry.name();
        }

        @Override
        public List<Ghost> blocks(Rotation rotation, Mirror mirror) {
            return blueprint.transformed(rotation, mirror)
                .map(turned -> turned.blocks().stream().map(block -> new Ghost(block.offset(), block.state())).toList())
                .orElse(List.of());
        }

        @Override
        public void place(BlockPos corner, Rotation rotation, Mirror mirror) {
            BlueprintHandoffPanel.open((excavate, siteName, raise) -> {
                if (send(corner, rotation, mirror, excavate, siteName, raise)) {
                    Placing.drop();
                }
            });
        }

        private boolean send(BlockPos corner, Rotation rotation, Mirror mirror, boolean excavate, String siteName,
                boolean raise) {
            Player player = Minecraft.getInstance().player;
            Optional<UUID> colony = player == null ? Optional.empty() : Books.heldColony(player);
            if (colony.isEmpty()) {
                if (player != null) {
                    player.displayClientMessage(Component.translatable(BlueprintHandoffPanel.TOKEN + ".no_book"), true);
                }
                return false;
            }
            if (!entry.own()) {
                PacketDistributor.sendToServer(new PlaceBlueprintPacket(colony.get(), entry.id(), siteName, corner,
                    excavate, rotation, mirror, raise));
                return true;
            }
            Optional<byte[]> bytes = bytesOf(file).filter(BlueprintUploads::fits);
            if (bytes.isEmpty()) {
                player.displayClientMessage(Component.translatable("folkways.blueprints.too_big_to_send"), true);
                return false;
            }
            BlueprintUploads.send(new UploadBlueprintPacket(colony.get(), nameOf(entry.id()), siteName, bytes.get(),
                corner, excavate, rotation, mirror, raise), PacketDistributor::sendToServer);
            return true;
        }

        private static Optional<byte[]> bytesOf(CompoundTag file) {
            try (ByteArrayOutputStream written = new ByteArrayOutputStream()) {
                NbtIo.writeCompressed(file, written);
                return Optional.of(written.toByteArray());
            } catch (IOException | RuntimeException unwritable) {
                return Optional.empty();
            }
        }
    }
}
