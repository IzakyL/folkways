package io.github.izakyl.folkways.vanilla.person.living;

import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.front.api.notice.Notice;
import java.util.Optional;

// What one more settler asks the colony for, and how much of it is there.
public record SettlerNeed(LivingNeed kind, int have, int each, Per per,
                          Optional<ItemSpec> counts, boolean complete) {

    public enum Per {

        SETTLER,

        RESIDENT
    }

    public SettlerNeed(LivingNeed kind, int have, int each, Per per, Optional<ItemSpec> counts) {
        this(kind, have, each, per, counts, true);
    }

    public SettlerNeed(LivingNeed kind, int have, int each) {
        this(kind, have, each, Per.SETTLER, Optional.empty());
    }

    public static SettlerNeed counting(LivingNeed kind, ItemSpec what, int each, Per per) {
        return new SettlerNeed(kind, 0, each, per, Optional.of(what));
    }

    public SettlerNeed filled(long held) {
        return filled(held, complete);
    }

    public SettlerNeed filled(long held, boolean complete) {
        return new SettlerNeed(kind, (int) Math.min(held, Integer.MAX_VALUE), each, per, counts, complete);
    }

    public SettlerNeed plus(int more) {
        return new SettlerNeed(kind, have + more, each, per, counts, complete);
    }

    public int want(int residents) {
        return per == Per.SETTLER ? each : each * (Math.max(0, residents) + 1);
    }

    public boolean met(int residents) {
        return complete && have >= want(residents);
    }

    public Notice asRefusal(int residents) {
        return Notice.of(kind, Notice.count(have), Notice.count(want(residents)));
    }
}
