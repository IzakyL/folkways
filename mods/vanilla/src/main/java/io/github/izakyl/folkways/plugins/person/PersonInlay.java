package io.github.izakyl.folkways.plugins.person;

import com.lowdragmc.lowdraglib2.gui.sync.bindings.impl.DataBindingBuilder;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import io.github.izakyl.folkways.core.api.resident.body.Body;
import io.github.izakyl.folkways.front.api.ui.Inlay;
import io.github.izakyl.folkways.front.api.ui.Nook;
import io.github.izakyl.folkways.front.api.ui.Rows;
import io.github.izakyl.folkways.front.api.ui.Tokens;
import io.github.izakyl.folkways.plugins.person.look.LookPicker;
import io.github.izakyl.folkways.plugins.person.look.ResidentLooks;
import io.github.izakyl.folkways.plugins.person.name.ColonyNames;
import java.util.Optional;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;

// One person's own name and look, in their window: whatever the pools hold, any look there is can be put on,
// picked from a window of its own.
public final class PersonInlay implements Inlay {

    private static final int LABEL_WIDTH = 28;

    private static final String ID = "id";
    private static final String GIVEN = "given";
    private static final String SURNAME = "surname";
    private static final String LOOK = "look";

    private String typedGiven = "";
    private String typedSurname = "";
    private String filled = "";
    private String worn = "";
    private LookPicker.Open picking;

    @Override
    public boolean names(Body subject) {
        return subject instanceof ResidentEntity;
    }

    @Override
    public UIElement build(Nook nook) {
        TextField given = field("folkways.resident.given", text -> typedGiven = text);
        TextField surname = field("folkways.resident.surname", text -> typedSurname = text);
        Button apply = new Button()
            .setText(Component.translatable("folkways.action.rename"))
            .setOnServerClick(event -> rename(nook));
        apply.layout(layout -> layout.flexShrink(0));
        Tokens.name(apply, "folkways.resident.names.apply");

        Label look = Rows.value(Component.empty());
        look.layout(layout -> layout.flexGrow(1).flexShrink(1).minWidth(0));
        Button change = new Button().setText(Component.translatable("folkways.resident.look.change"));
        change.layout(layout -> layout.flexShrink(0));
        Tokens.name(change, "folkways.resident.look.change");

        UIElement inlay = Rows.page().addChildren(
            Rows.strip().addChildren(caption("folkways.resident.given"), given,
                caption("folkways.resident.surname"), surname, apply),
            Rows.strip().addChildren(caption("folkways.resident.look"), look, change));
        inlay.setDisplay(false);
        LookPicker.Line line = new LookPicker.Line(inlay, (word, what) -> {
            if (word.equals(LookPicker.PICK)) {
                wear(nook, what);
            }
        });
        change.setOnClick(event -> {
            if (picking != null) {
                picking.close();
            }
            picking = LookPicker.open(change, ResourceLocation.tryParse(worn),
                picked -> line.say(LookPicker.PICK, picked.toString()));
        });
        inlay.addSyncValue(DataBindingBuilder.tagS2C(() -> reading(nook))
            .onSyncReceived(tag -> {
                CompoundTag read = tag instanceof CompoundTag compound ? compound : new CompoundTag();
                String showing = read.getString(ID);
                inlay.setDisplay(!showing.isEmpty());
                worn = read.getString(LOOK);
                if (showing.isEmpty()) {
                    filled = "";
                    if (picking != null) {
                        picking.close();
                    }
                    return;
                }
                if (!showing.equals(filled)) {
                    given.setText(read.getString(GIVEN));
                    surname.setText(read.getString(SURNAME));
                    filled = showing;
                }
                ResourceLocation wearing = ResourceLocation.tryParse(worn);
                look.setText(wearing == null || worn.isEmpty() ? Component.empty() : LookPicker.nameOf(wearing));
                look.style(style -> style.tooltips(Component.literal(worn)));
                if (picking != null && picking.isOpen()) {
                    picking.wears(wearing);
                }
            })
            .build()
            .getSyncValue());
        return inlay;
    }

    private static TextField field(String token, java.util.function.Consumer<String> typed) {
        TextField field = new TextField();
        field.layout(layout -> layout.flexGrow(1).flexShrink(1).minWidth(0));
        field.bind(DataBindingBuilder.stringC2S(typed::accept).build());
        Tokens.name(field, token);
        return field;
    }

    private static Label caption(String key) {
        Label label = Rows.name(Component.translatable(key));
        label.layout(layout -> layout.width(LABEL_WIDTH).flexGrow(0).flexShrink(0));
        return label;
    }

    private static Optional<ResidentEntity> person(Nook nook) {
        return nook.subject()
            .filter(ResidentEntity.class::isInstance)
            .map(ResidentEntity.class::cast);
    }

    private static Tag reading(Nook nook) {
        CompoundTag tag = new CompoundTag();
        person(nook).ifPresent(person -> {
            tag.putString(ID, person.getUUID().toString());
            tag.putString(GIVEN, person.givenName());
            tag.putString(SURNAME, person.surname());
            tag.putString(LOOK, person.lookId().map(key -> key.location().toString()).orElse(""));
        });
        return tag;
    }

    private void rename(Nook nook) {
        String given = typedGiven.trim();
        String surname = typedSurname.trim();
        if (!ColonyNames.fits(given) || !(surname.isEmpty() || ColonyNames.fits(surname))) {
            return;
        }
        person(nook).ifPresent(person -> {
            person.setNames(given, surname);
            person.setCustomName(null);
        });
    }

    private static void wear(Nook nook, String written) {
        ResourceLocation look = ResourceLocation.tryParse(written);
        person(nook).ifPresent(person -> {
            if (look != null && ResidentLooks.pool(person.level().registryAccess()).containsKey(look)) {
                person.setLookId(ResourceKey.create(ResidentLooks.REGISTRY, look));
            }
        });
    }
}
