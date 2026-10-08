package io.github.izakyl.folkways.plugins.golem.resident;

import io.github.izakyl.folkways.front.api.Admission;
import io.github.izakyl.folkways.front.api.Endorsed;
import io.github.izakyl.folkways.front.api.Endorsement;
import io.github.izakyl.folkways.front.api.notice.Notice;
import java.util.Optional;

final class GolemAdmission implements Admission {

    @Override
    public Optional<Endorsed> answer(Endorsement asked) {
        if (!(asked instanceof Endorsement.Pointed pointed)) {
            return Optional.empty();
        }
        if (!(pointed.at() instanceof GolemBody golem) || !golem.endorsableBy(pointed.by())) {
            return Optional.of(new Endorsed.Refused(Notice.of(HandoverRefusal.NOT_YOURS_TO_HAND_OVER)));
        }
        if (pointed.enrolled()) {
            return Optional.of(new Endorsed.Leaves(pointed.at()));
        }
        if (pointed.at().colonyId().isEmpty()) {
            return Optional.of(new Endorsed.Joins(pointed.at()));
        }
        return Optional.of(new Endorsed.Refused(
            Notice.of(HandoverRefusal.ALREADY_SETTLED_ELSEWHERE)));
    }
}
