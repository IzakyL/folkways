package io.github.izakyl.folkways.core.shell.crafting;

import io.github.izakyl.folkways.core.base.persist.Reader;
import io.github.izakyl.folkways.core.base.persist.Writer;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.inventory.api.StoreRule;
import io.github.izakyl.folkways.core.item.api.ItemFilter;
import io.github.izakyl.folkways.core.item.api.ItemQuery;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;

/**
 * What residents may do through each enrolled network device, kept in the colony save. The device's block tags stay
 * the ceiling; these switches only narrow it. A network has no stable identity of its own, so its CPU allowance is
 * written onto every enrolled device of that network and read back from them: see {@link #cpus}.
 */
public final class DeviceSettings {

    public record Device(boolean put, boolean take, boolean order, boolean commission, boolean only,
                         List<ItemFilter> filters, Optional<Integer> cpus) {

        public static final Device DEFAULT = new Device(true, true, true, true, false, List.of(), Optional.empty());

        public Device {
            filters = List.copyOf(filters);
        }

        // An empty blacklist restricts nothing; an empty whitelist admits nothing, as on an ordinary store.
        public Optional<StoreRule.Admit> admit(WorldPos where) {
            if (!only && filters.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new StoreRule.Admit(where, only, goods(filters)));
        }

        public boolean admits(WorldPos where, ItemSpec spec) {
            return admit(where).map(rule -> rule.admits(spec)).orElse(true);
        }

        public List<StoreRule> rules(WorldPos where) {
            List<StoreRule> rules = new ArrayList<>();
            if (!put || !take) {
                rules.add(new StoreRule.Access(where, take, put));
            }
            admit(where).ifPresent(rules::add);
            return rules;
        }

        public Device toggled(String what) {
            return switch (what) {
                case "put" -> new Device(!put, take, order, commission, only, filters, cpus);
                case "take" -> new Device(put, !take, order, commission, only, filters, cpus);
                case "order" -> new Device(put, take, !order, commission, only, filters, cpus);
                case "commission" -> new Device(put, take, order, !commission, only, filters, cpus);
                case "filter_mode" -> new Device(put, take, order, commission, !only, filters, cpus);
                default -> throw new IllegalArgumentException(what);
            };
        }

        public Device filtering(List<ItemFilter> next) {
            return new Device(put, take, order, commission, only, next, cpus);
        }

        public Device allowing(Optional<Integer> next) {
            return new Device(put, take, order, commission, only, filters, next);
        }

        boolean bare() {
            return equals(DEFAULT);
        }

        CompoundTag save(WorldPos at) {
            Writer writer = Writer.of().blob("at", at.save()).flag("put", put).flag("take", take)
                .flag("order", order).flag("commission", commission).flag("only", only)
                .children("filters", filters, filter -> Writer.of().string("rule", filter.write()).tag());
            cpus.ifPresent(count -> writer.integer("cpus", count));
            return writer.tag();
        }

        static Device load(Reader reader) {
            return new Device(reader.flag("put").orElse(true), reader.flag("take").orElse(true),
                reader.flag("order").orElse(true), reader.flag("commission").orElse(true),
                reader.flag("only").orElse(false), reader.children("filters").stream()
                    .flatMap(saved -> saved.string("rule").flatMap(ItemFilter::read).stream()).toList(),
                reader.integer("cpus").map(count -> Math.max(0, count)));
        }
    }

    private final Colony colony;
    private final ResourceLocation owner;
    private final Map<WorldPos, Device> devices = new LinkedHashMap<>();

    public DeviceSettings(Colony colony, ResourceLocation owner) {
        this.colony = colony;
        this.owner = owner;
        for (Reader saved : Reader.of(colony.kept(owner)).children("devices")) {
            saved.child("at").flatMap(WorldPos::load).ifPresent(at -> devices.put(at, Device.load(saved)));
        }
    }

    public Device at(WorldPos where) {
        return devices.getOrDefault(where, Device.DEFAULT);
    }

    public void set(WorldPos where, Device device) {
        if (device.bare()) {
            devices.remove(where);
        } else {
            devices.put(where, device);
        }
        CompoundTag next = Writer.of().children("devices", List.copyOf(devices.entrySet()),
            entry -> entry.getValue().save(entry.getKey())).tag();
        colony.keep(owner, next);
    }

    // Networks merge and split under the colony's feet. The smallest allowance any enrolled device of the network
    // remembers wins, so merging never hands residents more CPUs than either side allowed; a split keeps the value
    // on both sides. A network none of whose devices remembers one gets half its CPUs, leaving players the rest.
    public int cpus(Collection<WorldPos> network, int total, int byDefault) {
        int chosen = network.stream().map(this::at).flatMap(device -> device.cpus().stream())
            .mapToInt(Integer::intValue).min().orElse(byDefault);
        return Math.max(0, Math.min(total, chosen));
    }

    public boolean defaultCpus(Collection<WorldPos> network) {
        return network.stream().map(this::at).allMatch(device -> device.cpus().isEmpty());
    }

    public void cpus(Collection<WorldPos> network, Optional<Integer> count) {
        network.forEach(at -> set(at, at(at).allowing(count)));
    }

    public static int defaultCpus(int total) {
        return total / 2;
    }

    // Filter rules as goods: a query stands for the items it finds now.
    static List<ItemSpec> goods(List<ItemFilter> filters) {
        List<ItemSpec> allowed = new ArrayList<>();
        for (ItemFilter filter : filters) {
            switch (filter.kind()) {
                case ITEM -> allowed.add(ItemSpec.of(filter.id()));
                case TAG -> allowed.add(ItemSpec.of(TagKey.create(Registries.ITEM, filter.id())));
                case QUERY -> ItemQuery.parse(filter.text()).ifPresent(query -> query.items()
                    .forEach(item -> allowed.add(ItemSpec.of(BuiltInRegistries.ITEM.getKey(item)))));
            }
        }
        return allowed.isEmpty() ? List.of() : List.of(ItemSpec.anyOf(allowed));
    }
}
