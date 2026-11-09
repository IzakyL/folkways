package io.github.izakyl.folkways.vanilla.person.look;

import com.lowdragmc.lowdraglib2.gui.sync.bindings.impl.DataBindingBuilder;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.front.api.Books;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import io.github.izakyl.folkways.core.front.api.ui.Draw;
import io.github.izakyl.folkways.core.front.api.ui.Pages;
import io.github.izakyl.folkways.core.front.api.ui.Rows;
import io.github.izakyl.folkways.core.front.api.ui.Tokens;
import io.github.izakyl.folkways.vanilla.person.PoolSets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;

// The colony's look pool: every look there is, filed in folders, each ticked in or out alone or a folder at a
// time, and the sets that replace the pool whole.
public final class LooksPage implements Draw {

    private static final String ON = "on";
    private static final String TOTAL = "total";
    private static final String HOLDS = "folkways.pool.looks.holds";

    private final Label counter = Rows.text(Component.empty());
    private Set<ResourceLocation> current = Set.of();

    @Override
    public UIElement build(Player player) {
        Tokens.name(counter, "folkways.pool.looks.count");
        Button choose = new Button().setText(Component.translatable("folkways.pool.looks.choose"));
        Tokens.name(choose, "folkways.pool.looks.choose");
        choose.setOnClick(event -> {
            Set<ResourceLocation> before = Set.copyOf(current);
            LookPicker.pool(choose, before, picked -> {
                Set<ResourceLocation> after = new HashSet<>(picked);
                List<LookPoolEdits.Edit> edits = new ArrayList<>();
                for (ResourceLocation look : LookPreview.known()) {
                    if (LookPreview.disabledReason(look).isEmpty() && before.contains(look) != after.contains(look)) {
                        edits.add(new LookPoolEdits.Edit(look, after.contains(look)));
                    }
                }
                LookPoolEdits.send(edits, net.neoforged.neoforge.network.PacketDistributor::sendToServer);
            });
        });
        UIElement page = Rows.page().addChildren(
            new PoolSets("looks", LooksPage::offered, LooksPage::replace)
                .disabledReason(id -> LookPicker.disabledReason(
                    LookSet.of(player.level().registryAccess(), id).map(LookSet::looks)
                        .or(() -> Optional.ofNullable(MaidLooks.sets().get(id))).orElseGet(List::of)))
                .build(player), counter, choose);
        page.addSyncValue(DataBindingBuilder.tagS2C(() -> reading(player))
            .onSyncReceived(this::fill).build().getSyncValue());
        return page;
    }

    private static Tag reading(Player player) {
        CompoundTag tag = new CompoundTag();
        ListTag on = new ListTag();
        int total = 0;
        Optional<Colony> colony = Books.acting(player);
        Optional<ServerLevel> level = Pages.levelOf(player);
        if (colony.isPresent() && level.isPresent()) {
            LookPool pool = LookPool.of(colony.get());
            Set<ResourceLocation> looks = ResidentLooks.pool(level.get().registryAccess()).keySet();
            total = looks.size();
            looks.stream().filter(pool::allows).forEach(look -> on.add(StringTag.valueOf(look.toString())));
        }
        tag.put(ON, on);
        tag.putInt(TOTAL, total);
        return tag;
    }

    private static List<PoolSets.Offered> offered(Player player) {
        List<PoolSets.Offered> offered = new ArrayList<>();
        Pages.levelOf(player).ifPresent(level -> LookSet.all(level.registryAccess()).forEach(set ->
            offered.add(new PoolSets.Offered(set.getKey(), HOLDS, set.getValue().looks().size()))));
        MaidLooks.sets().forEach((id, looks) -> offered.add(new PoolSets.Offered(id, HOLDS, looks.size())));
        return offered;
    }

    private static void replace(Player player, ResourceLocation set) {
        Optional<Colony> colony = Books.acting(player);
        Optional<ServerLevel> level = Pages.levelOf(player);
        if (colony.isPresent() && level.isPresent()) {
            LookSet.of(level.get().registryAccess(), set).map(LookSet::looks)
                .or(() -> Optional.ofNullable(MaidLooks.sets().get(set)))
                .ifPresent(chosen -> LookPool.replace(colony.get(), chosen));
        }
    }

    private void fill(Tag tag) {
        CompoundTag reading = tag instanceof CompoundTag compound ? compound : new CompoundTag();
        ListTag written = reading.getList(ON, Tag.TAG_STRING);
        Set<ResourceLocation> on = new HashSet<>();
        for (int at = 0; at < written.size(); at++) {
            ResourceLocation look = ResourceLocation.tryParse(written.getString(at));
            if (look != null) {
                on.add(look);
            }
        }
        int total = reading.getInt(TOTAL);
        counter.setText(total == 0
            ? Component.translatable("folkways.settings.looks.empty")
            : Component.translatable("folkways.settings.looks.count", on.size(), total));
        current = Set.copyOf(on);
    }
}
