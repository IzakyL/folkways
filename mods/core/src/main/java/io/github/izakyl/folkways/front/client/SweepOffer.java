package io.github.izakyl.folkways.front.client;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Toggle;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import dev.vfyjxf.taffy.style.FlexDirection;
import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.colony.Books;
import io.github.izakyl.folkways.front.api.Delegation;
import io.github.izakyl.folkways.front.api.ui.BoxOffers;
import io.github.izakyl.folkways.front.api.ui.Rows;
import io.github.izakyl.folkways.front.api.ui.Taking;
import io.github.izakyl.folkways.front.api.ui.Tokens;
import io.github.izakyl.folkways.front.engine.colony.BlockSweep;
import io.github.izakyl.folkways.front.engine.net.SweepBlocksPacket;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

// Offered over a marked box: every block in it the book could take in one at a time, a box for each kind of them.
@OnlyIn(Dist.CLIENT)
final class SweepOffer {

    private static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(FolkwaysMod.MOD_ID, "sweep");
    private static final String TOKEN = "folkways.sweep";
    private static final float BOX = 16f;

    private SweepOffer() {
    }

    static void register() {
        BoxOffers.register(new BoxOffers.Offer(ID, TOKEN, ResourceLocation.withDefaultNamespace("chest"),
            SweepOffer::body));
    }

    private static UIElement body(BlockPos min, BlockPos max, Taking taking) {
        UIElement page = Rows.page();
        Level level = Minecraft.getInstance().level;
        if (level == null) {
            return page;
        }
        if (!BlockSweep.fits(min, max)) {
            return page.addChildren(Rows.note(Component.translatable(TOKEN + ".too_large", BlockSweep.MOST_CELLS)));
        }
        // What the colony holds already is left out, as the server would.
        Set<BlockPos> held = Set.copyOf(ColonyHighlightState.members());
        Set<ResourceLocation> chosen = new LinkedHashSet<>();
        UIElement list = new UIElement()
            .layout(layout -> layout.flexDirection(FlexDirection.COLUMN).widthPercent(100).gapAll(1));
        for (Map.Entry<Delegation, List<BlockPos>> kind : BlockSweep.found(level, min, max).entrySet()) {
            List<BlockPos> fresh = kind.getValue().stream().filter(anchor -> !held.contains(anchor)).toList();
            if (!fresh.isEmpty()) {
                list.addChildren(row(level, kind.getKey(), fresh, chosen));
            }
        }
        if (chosen.isEmpty()) {
            return page.addChildren(Rows.note(Component.translatable(TOKEN + ".none")));
        }
        Button take = new Button().setText(Component.translatable(TOKEN + ".take"));
        take.layout(layout -> layout.flexShrink(0));
        Tokens.name(take, TOKEN + ".take");
        take.setOnClick(event -> {
            Optional<UUID> colony = colonyInHand();
            if (chosen.isEmpty() || colony.isEmpty()) {
                return;
            }
            PacketDistributor.sendToServer(new SweepBlocksPacket(colony.get(), min, max, List.copyOf(chosen)));
            taking.done();
        });
        list.addEventListener(UIEvents.TICK, event -> take.setDisplay(!chosen.isEmpty()));
        ScrollerView kinds = Rows.fills(Rows.box());
        kinds.addScrollViewChild(list);
        return page.addChildren(Rows.note(Component.translatable(TOKEN + ".hint")), kinds, Rows.actions(take));
    }

    private static UIElement row(Level level, Delegation kind, List<BlockPos> fresh, Set<ResourceLocation> chosen) {
        Toggle box = new Toggle().noText();
        box.layout(layout -> layout.width(BOX).height(BOX).flexShrink(0));
        box.setOn(true, false);
        chosen.add(kind.id());
        box.setOnToggleChanged(on -> {
            if (on) {
                chosen.add(kind.id());
            } else {
                chosen.remove(kind.id());
            }
        });
        // A kind shows as the first block of it found, so a store reads as the chest it is.
        ItemStack icon = new ItemStack(level.getBlockState(fresh.getFirst()).getBlock().asItem());
        UIElement row = Rows.row().addChildren(box, Rows.icon(icon),
            Rows.name(Component.translatable("folkways.delegation."
                + kind.id().getNamespace() + "." + kind.id().getPath())),
            Rows.spacer(), Rows.value(Component.translatable(TOKEN + ".count", fresh.size())));
        // The rest of the row answers a click as the box does.
        for (UIElement beside : row.getChildren()) {
            if (beside != box) {
                beside.addEventListener(UIEvents.MOUSE_DOWN, event -> box.setOn(!box.isOn(), true));
            }
        }
        Tokens.name(row, TOKEN + ".kind." + Tokens.of(kind.id()));
        return row;
    }

    private static Optional<UUID> colonyInHand() {
        return Minecraft.getInstance().player == null
            ? Optional.empty() : Books.heldColony(Minecraft.getInstance().player);
    }
}
