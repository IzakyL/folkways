## `folkways_core:item`
<!-- guide:parts/folkways_core:item -->

Transfers items through a vanilla container or an integration's default storage port.
Item identity including components and a positive integer count. Discover the block and use a transfer, then
check the actual quantity and the fresh inventory. Slot, cell, count and distinct-type limits come from the
underlying inventory and its configuration. A result can be partial. A proxy and its backing storage can share
one inventory, so avoid counting both as separate stock. Discovery alone does not ensure admission or access.

### Vanilla and generic containers

Chests, barrels and other supported inventory blocks use native container rules. A double chest is one inventory.
Check free room before a deposit and exact available items before a draw. For a machine with input/output faces,
use the actual `folkways_core:item/<face>` part to preserve native slot routing.

### Create item access

A Create Item Vault resolves its native multiblock inventory through the exposed capability. Treat faces of the same
vault as aliases. A Packager and a Portable Storage Interface depend on the native package or docking state. Check
which face is exposed after docking and confirm the transfer before moving away. Package routing, Stock Ticker orders,
fan processing, elevator calls and FireFighting Additions hose operation have separate integrations or native behavior.
They do not introduce a dedicated part ID in these discovery tools. TACZ likewise uses resident weapon operations,
including its native draw and reload state, and exposes no TACZ-specific facility signal or port.

### Storage Drawers and Functional Storage

Storage Drawers exposes drawer cells, the controller and controller I/O as a proxy of the valid linked group.
Locked drawers retain the item assignment. Compacting forms share one backing supply. Folkways counts the
smallest unit for Storage Drawers fractional drawers. Functional Storage exposes ordinary drawers and links
through controllers/extensions, and uses the native handler for compacting and simple compacting drawers.
Check which form can be drawn and its conversion before reserving stock. Unsupported upgrades, voiding and
protected groups can prevent safe colony access even when the native UI works.

### Sophisticated Storage and Backpacks

Placed sophisticated containers and backpacks use their native item storage and slot validation. A Storage
Controller and linked Storage IO proxy member inventories. Configure filters and upgrades natively, use the
physical container or usable proxy for transfers, and check stock after effects such as automatic pulling or
pushing. The part does not install upgrades or open a carried backpack for transfer.

### Create: Storage and Tom's Simple Storage

Create: Storage (FXNT) exposes storage boxes, simple and reserve boxes and placed backpacks when usable.
A connected storage controller in its supported default fill mode accepts deposits only. Draw from its member
boxes. Filtered or buffer interfaces and passers are not directed hand-access ports.

Tom's terminals and local connectors resolve a native merged inventory into its physical local members.
Connect them, ensure the members are loaded and unfiltered, then transfer through the terminal or connector.
Filtered networks, beacon-backed remote links and unmappable members are refused. Inventory proxies, level
emitters, interfaces, basic inventory hoppers, open crates and filing cabinets do not grant directed network access.

### Colossal Chests

The interface and core of a completed multiblock address the same underlying inventory. Finish the matching
material shell and use a valid access block. An Uncolossal Chest has a separate inventory. An incomplete
multiblock is not usable as a store.

### Applied Energistics 2, Refined Storage 2 and Mekanism QIO

AE2 terminals on cable and selected network devices, RS2 grids and storage monitors, and QIO dashboards can
grant item access according to the installed Folkways tags. Set up the native network and keep the relevant
grid or frequency active. Use a permitted endpoint and check the returned transfer counts. Native filters,
available storage, network changes and permissions can limit access. AE2/RS2 crafting is a separate operation
with its own endpoints and completion. Interfaces and disk devices treated as buffers do not grant bulk network
access. QIO drive arrays and QIO automation buffers do not grant resident dashboard access.

The same part ID is used for these adapters because each provides the default item port of its block.
