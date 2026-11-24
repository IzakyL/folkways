package io.github.izakyl.folkways.core.shell.create.dispatch;


import com.simibubi.create.content.logistics.box.PackageItem;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.colony.api.Colony;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * Which train may take a parcel out of a postbox: one packed for an order some colony waits on goes only aboard the
 * train that colony booked for it, from the station it was booked at, and waits in the postbox until then. Any other
 * parcel goes as Create would send it.
 */
public final class MailGate {

    @FunctionalInterface
    public interface Bookings {
        Optional<Boolean> boards(Colony colony, int order, UUID train, String station);
    }

    private static Bookings bookings = (colony, order, train, station) -> Optional.empty();

    public static void install(Bookings lookup) {
        bookings = lookup;
    }

    private MailGate() {
    }

    public static boolean mayBoard(ItemStack parcel, UUID train, String station) {
        if (!PackageItem.isPackage(parcel) || !PackageItem.hasOrderData(parcel)) {
            return true;
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return true;
        }
        int order = PackageItem.getOrderId(parcel);
        for (Colony colony : Colonies.all(server)) {
            Optional<Boolean> boards = bookings.boards(colony, order, train, station);
            if (boards.isPresent()) {
                return boards.get();
            }
        }
        return true;
    }
}
