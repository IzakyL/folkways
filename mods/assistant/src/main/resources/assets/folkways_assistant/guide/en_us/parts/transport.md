## `destination/<uuid>`
<!-- guide:parts/destination/<uuid> -->

Sends a loaded Create train, AstikorCarts cart or Superb Warfare vehicle toward a destination.
Data with a block position `[x,y,z]` in integer coordinates. Create trains also accept an exact nonblank station
name. The suffix identifies the train or vehicle, so keep the discovered ID including its UUID.

- Create train. Have a conductor in a native conductor seat on the train when a resident is writing. Pick a station
  on its graph in the current dimension. Coordinates select the nearest station on the graph, and duplicate names
  select by station ID. Wait for previous navigation to finish. The adapter finds a native route, selects a matching
  entry on a running schedule when possible, otherwise starts navigation directly and pauses the current schedule.
  Use the train's current station and navigation state to confirm arrival before unloading.
- AstikorCarts Redux. Attach a mob puller natively first. An operator must sit on the horse, or be the mob doing the
  pulling. Give a reachable loaded cell and wait for the cart itself to reach the loading area. Folkways requests
  navigation from the puller. Native pulling then moves the cart, including its lag behind corners. A player
  controlling either entity blocks the write. Use the plowing signal to engage the blades.
- Superb Warfare. This part requests native vehicle movement through the Folkways vehicle provider. Use an unlocked,
  intact non-emplacement vehicle with a colony driver assigned to seat 0 for staffed writes. Give a loaded target
  cell and watch vehicle position to confirm arrival. Acceptance only reports destination submission. Ground
  destinations here do not offer a flight plan or native manual steering inputs; the crew handles the vehicle's weapons under its vehicle profile.

None of these writes reserves the unloading area. Use fresh vehicle positions and the cargo port after movement.

## `cargo/<uuid>`
<!-- guide:parts/cargo/<uuid> -->

Transfers items to or from a Create train carriage or an AstikorCarts cargo cart.
Item stack and positive integer count. The native container defines filters, slot limits and available counts.
The suffix is a carriage entity or cart UUID. Park at the loading area, transfer exact stacks and verify the moved
count before dispatch. Do not assume all cargo on a train is in a single carriage.

- Create exposes the carriage's mounted item handler when there are accessible slots. Native mounted storage
  decides which blocks supply slots; tanks do not become item slots. The port moves with the carriage and is remote.
- AstikorCarts Redux exposes the native inventory of cargo-bearing carts as a remote port. A seat is
  distinct from the cargo port. Drawn entities without a cargo inventory, including a plow, have no cargo part.

## `plowing/<uuid>`
<!-- guide:parts/plowing/<uuid> -->

Engages or disengages an AstikorCarts Redux plow.
Write a Boolean from beside the plow. Feedback reads the native switch state. Load the tools, turn plowing on
and use `destination/<uuid>` to move the hitched plow. Wait for it to cross the ground before turning plowing off.
The blades work when the hitched plow moves horizontally. By hand, right-click the plow to toggle it or
sneak-right-click to open its tool slots.

## `tools/<uuid>`
<!-- guide:parts/tools/<uuid> -->

Transfers items into and out of the three native slots of an AstikorCarts Redux plow.
Transfer an item stack and a positive integer count from beside the plow. The native slots accept ordinary
items. Only tiered tools act on the ground, using each slot's blade position. Hoes till eligible soil,
shovels make dirt paths, and axes strip eligible wood or remove wax or oxidation from eligible copper.
The tool also clears blocks above the target from its native breakable tag. Normal tool use can wear out
and break the tool. Clearing a tagged block adds no separate durability cost.
