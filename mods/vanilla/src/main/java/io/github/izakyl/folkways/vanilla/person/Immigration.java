package io.github.izakyl.folkways.vanilla.person;

import io.github.izakyl.folkways.core.front.api.Admission;
import io.github.izakyl.folkways.core.front.api.Endorsed;
import io.github.izakyl.folkways.core.front.api.Endorsement;
import io.github.izakyl.folkways.core.card.api.notice.Notice;
import io.github.izakyl.folkways.vanilla.FolkwaysVanillaConfig;
import io.github.izakyl.folkways.vanilla.person.living.LivingContent;
import io.github.izakyl.folkways.vanilla.person.living.SettlerNeed;
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
        // A person who has settled stays: the book strikes no one off the roster.
        return new Endorsed.Refused(Notice.of(ImmigrationRefusal.SETTLED_FOR_GOOD));
    }

    private static Endorsed admit(Endorsement.Called called) {
        boolean creative = called.by().isCreative();
        PersonPresence people = creative ? null : queue(called).orElse(null);
        if (!creative) {
            Optional<Endorsed> blocked = checkSettler(called, people);
            if (blocked.isPresent()) {
                return blocked.get();
            }
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
        if (people != null) {
            people.take();
        }
        return new Endorsed.Joins(person);
    }

    private static Optional<Endorsed> checkSettler(Endorsement.Called called, PersonPresence people) {
        if (!FolkwaysVanillaConfig.immigrationEnabled()) {
            return Optional.of(new Endorsed.Refused(Notice.of(ImmigrationRefusal.SWITCHED_OFF)));
        }
        if (people == null || people.waiting() <= 0) {
            return Optional.of(new Endorsed.Refused(Notice.of(ImmigrationRefusal.NOBODY_WAITING)));
        }
        LivingContent.reconcileBeds(called.colony(), called.view().level().getServer());
        int heads = PersonPresence.heads(called.view());
        for (SettlerNeed need : LivingContent.settlerNeeds(called.colony(), called.view().level().getServer())) {
            if (!need.complete()) {
                called.colony().inventory().ensureObserved();
                people.retry(called);
                return Optional.of(new Endorsed.Pending());
            }
            if (!need.met(heads)) {
                return Optional.of(new Endorsed.Refused(need.asRefusal(heads)));
            }
        }
        return Optional.empty();
    }

    private static Optional<PersonPresence> queue(Endorsement.Called called) {
        Optional<Object> works = called.colony().service(PersonContent.ID, Object.class);
        return works.filter(PersonPresence.class::isInstance).map(PersonPresence.class::cast);
    }
}
