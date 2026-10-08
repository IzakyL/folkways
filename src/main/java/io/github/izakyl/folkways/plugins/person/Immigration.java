package io.github.izakyl.folkways.plugins.person;

import io.github.izakyl.folkways.FolkwaysConfig;
import io.github.izakyl.folkways.front.api.Admission;
import io.github.izakyl.folkways.front.api.Endorsed;
import io.github.izakyl.folkways.front.api.Endorsement;
import io.github.izakyl.folkways.front.api.notice.Notice;
import io.github.izakyl.folkways.plugins.person.living.LivingContent;
import io.github.izakyl.folkways.plugins.person.living.SettlerNeed;
import java.util.Optional;
import net.minecraft.core.BlockPos;

final class Immigration implements Admission {

    @Override
    public Optional<Endorsed> answer(Endorsement asked) {
        return Optional.of(switch (asked) {
            case Endorsement.Called called -> admit(called);
            case Endorsement.Pointed pointed -> dismiss(pointed);
        });
    }

    private static Endorsed dismiss(Endorsement.Pointed pointed) {
        if (!pointed.enrolled()) {
            return new Endorsed.Refused(Notice.of(ImmigrationRefusal.NOT_ON_THE_ROSTER));
        }
        return new Endorsed.Leaves(pointed.at());
    }

    private static Endorsed admit(Endorsement.Called called) {
        if (!FolkwaysConfig.immigrationEnabled()) {
            return new Endorsed.Refused(Notice.of(ImmigrationRefusal.SWITCHED_OFF));
        }
        int heads = PersonPresence.heads(called.view());
        for (SettlerNeed need : LivingContent.settlerNeeds(called.colony(), called.view().level().getServer())) {
            if (!need.met(heads)) {
                return new Endorsed.Refused(need.asRefusal(heads));
            }
        }
        PersonPresence people = queue(called).orElse(null);
        if (people == null || people.waiting() <= 0) {
            return new Endorsed.Refused(Notice.of(ImmigrationRefusal.NOBODY_WAITING));
        }
        ResidentEntity person = PersonBody.RESIDENT.get().create(called.view().level());
        if (person == null) {
            return new Endorsed.Refused(Notice.of(ImmigrationRefusal.NO_ROOM_IN_THE_WORLD));
        }
        BlockPos at = called.at();
        person.moveTo(at.getX() + 0.5D, at.getY(), at.getZ() + 0.5D, 0.0F, 0.0F);
        if (!called.view().level().addFreshEntity(person)) {
            person.discard();
            return new Endorsed.Refused(Notice.of(ImmigrationRefusal.NO_ROOM_IN_THE_WORLD));
        }
        people.take();
        return new Endorsed.Joins(person);
    }

    private static Optional<PersonPresence> queue(Endorsement.Called called) {
        Optional<Object> works = called.colony().service(PersonContent.ID, Object.class);
        return works.filter(PersonPresence.class::isInstance).map(PersonPresence.class::cast);
    }
}
