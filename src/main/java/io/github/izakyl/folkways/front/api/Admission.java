package io.github.izakyl.folkways.front.api;

import java.util.Optional;

public interface Admission {

    Optional<Endorsed> answer(Endorsement asked);

    Admission SHUT = asked -> Optional.empty();
}
