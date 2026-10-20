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
    String FILTER_MODE = "filter_mode";
    String ACCESS = "access:";

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

    record FilterMode() implements OrderAct {
    }

    record Access(String mode) implements OrderAct {
    }

    default String key() {
        return switch (this) {
            case Maintain ignored -> MAINTAIN;
            case Deliver ignored -> DELIVER;
            case Withdraw withdraw -> WITHDRAW + withdraw.goods();
            case Admit admit -> admit.only() ? ADMIT_ONLY : ADMIT_EXCEPT;
            case AdmitAny ignored -> ADMIT_ANY;
            case FilterMode ignored -> FILTER_MODE;
            case Access access -> ACCESS + access.mode();
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
            case FILTER_MODE -> Optional.of(new FilterMode());
            case ADMIT_ANY -> Optional.of(new AdmitAny());
            case "access:take" -> Optional.of(new Access("take"));
            case "access:store" -> Optional.of(new Access("store"));
            default -> key.startsWith(WITHDRAW)
                ? Optional.of(new Withdraw(key.substring(WITHDRAW.length())))
                : Optional.empty();
        };
    }
}
