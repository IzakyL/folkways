package io.github.izakyl.folkways.plugins.supply;

import io.github.izakyl.folkways.core.api.terms.ItemFilter;
import io.github.izakyl.folkways.core.api.terms.ItemQuery;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

// A source of finite withdrawals, never a container holding an invented infinite inventory.
public final class SupplyBox extends BlockEntity {
    public static final int MOST_RULES = 64;
    public static final int MOST_RULE_LENGTH = 256;
    private volatile boolean all = true;
    private volatile List<ItemFilter> filters = List.of();
    private volatile long revision;
    private final Map<String, Long> generated = new LinkedHashMap<>();

    public SupplyBox(BlockPos at, BlockState state) {
        super(SupplyContent.BLOCK_ENTITY.get(), at, state);
    }

    public boolean allItems() { return all; }
    public List<ItemFilter> filters() { return filters; }
    public long revision() { return revision; }
    public Map<String, Long> generated() { return Map.copyOf(generated); }

    public boolean allows(Item item) {
        if (item == net.minecraft.world.item.Items.AIR) return false;
        if (all) return true;
        for (ItemFilter rule : filters) {
            boolean matches = switch (rule.kind()) {
                case ITEM -> BuiltInRegistries.ITEM.getKey(item).equals(rule.id());
                case TAG -> item.builtInRegistryHolder().is(net.minecraft.tags.TagKey.create(
                    net.minecraft.core.registries.Registries.ITEM, rule.id()));
                case QUERY -> ItemQuery.parse(rule.text()).filter(query -> query.matches(item)).isPresent();
            };
            if (matches) return true;
        }
        return false;
    }

    public void configure(boolean allItems, List<ItemFilter> rules) {
        if (rules.size() > MOST_RULES || rules.stream().anyMatch(rule -> rule.text().length() > MOST_RULE_LENGTH)) {
            throw new IllegalArgumentException("supply whitelist exceeds its limit");
        }
        List<ItemFilter> next = rules.stream().distinct().toList();
        if (all == allItems && filters.equals(next)) return;
        all = allItems;
        filters = next;
        revision++;
        setChanged();
        if (level != null && !level.isClientSide()) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    void generated(ItemStack goods) {
        generated.merge(BuiltInRegistries.ITEM.getKey(goods.getItem()).toString(), (long) goods.getCount(),
            (before, added) -> before > Long.MAX_VALUE - added ? Long.MAX_VALUE : before + added);
        setChanged();
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider lookup) {
        super.saveAdditional(tag, lookup);
        tag.putBoolean("all", all);
        tag.putLong("revision", revision);
        ListTag rules = new ListTag();
        filters.forEach(rule -> rules.add(StringTag.valueOf(rule.write())));
        tag.put("whitelist", rules);
        CompoundTag counts = new CompoundTag();
        generated.forEach(counts::putLong);
        tag.put("generated", counts);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider lookup) {
        super.loadAdditional(tag, lookup);
        all = !tag.contains("all") || tag.getBoolean("all");
        revision = Math.max(0, tag.getLong("revision"));
        List<ItemFilter> rules = new ArrayList<>();
        ListTag saved = tag.getList("whitelist", Tag.TAG_STRING);
        for (int i = 0; i < Math.min(saved.size(), MOST_RULES); i++) {
            String line = saved.getString(i);
            if (line.length() <= MOST_RULE_LENGTH + 1) ItemFilter.read(line).ifPresent(rules::add);
        }
        filters = rules.stream().distinct().toList();
        generated.clear();
        CompoundTag counts = tag.getCompound("generated");
        counts.getAllKeys().forEach(id -> {
            if (net.minecraft.resources.ResourceLocation.tryParse(id) != null && counts.getLong(id) > 0) {
                generated.put(id, counts.getLong(id));
            }
        });
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider lookup) {
        CompoundTag tag = saveWithoutMetadata(lookup);
        tag.remove("generated");
        return tag;
    }

    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() { return ClientboundBlockEntityDataPacket.create(this); }
}
