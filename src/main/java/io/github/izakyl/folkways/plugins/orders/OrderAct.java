package io.github.izakyl.folkways.plugins.orders;

import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import java.util.Optional;

sealed interface OrderAct {

    String MAINTAIN = "maintain";
    String DELIVER = "deliver";
    String WITHDRAW = "withdraw:";
    String ADMIT_ONLY = "admit_only";
    String ADMIT_EXCEPT = "admit_except";
    String ADMIT_ANY = "admit_any";
    String SLOTS = "slots";
    String ALL_SLOTS = "all_slots";

    record Maintain() implements OrderAct {
    }

    record Deliver() implements OrderAct {
    }

    record Withdraw(String goods) implements OrderAct {
    }

    record Admit(boolean only) implements OrderAct {
    }

    record AdmitAny() implements OrderAct {
    }

    record Slots() implements OrderAct {
    }

    record AllSlots() implements OrderAct {
    }

    default String key() {
        return switch (this) {
            case Maintain ignored -> MAINTAIN;
            case Deliver ignored -> DELIVER;
            case Withdraw withdraw -> WITHDRAW + withdraw.goods();
            case Admit admit -> admit.only() ? ADMIT_ONLY : ADMIT_EXCEPT;
            case AdmitAny ignored -> ADMIT_ANY;
            case Slots ignored -> SLOTS;
            case AllSlots ignored -> ALL_SLOTS;
        };
    }

    static OrderAct withdrawing(ItemSpec what) {
        return new Withdraw(what.describe());
    }

    static Optional<OrderAct> parse(String key) {
        return switch (key) {
            case MAINTAIN -> Optional.of(new Maintain());
            case DELIVER -> Optional.of(new Deliver());
            case ADMIT_ONLY -> Optional.of(new Admit(true));
            case ADMIT_EXCEPT -> Optional.of(new Admit(false));
            case ADMIT_ANY -> Optional.of(new AdmitAny());
            case SLOTS -> Optional.of(new Slots());
            case ALL_SLOTS -> Optional.of(new AllSlots());
            default -> key.startsWith(WITHDRAW)
                ? Optional.of(new Withdraw(key.substring(WITHDRAW.length())))
                : Optional.empty();
        };
    }
}
