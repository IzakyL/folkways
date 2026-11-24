## `lever`
<!-- guide:parts/lever -->

Sets and reads the power state of a vanilla lever.
Boolean. `True` powers the lever and `False` turns it off. Write the desired state from a reachable footing, then
read the boolean back and wait for the downstream mechanism's own indication. Repeating the same value does not
toggle it. Floor, wall and ceiling levers share the same operation. The lever stays in its last state until changed.
Use the graph to check where the redstone reaches. Doors, trapdoors and pistons are downstream actuators.

## `button`
<!-- guide:parts/button -->

Presses a vanilla button for its native pulse duration.
Pulse with no argument. From a reachable footing, press once and let the button reset before repeating. A press
while already powered does not restart its timer. Stone and polished blackstone buttons nominally stay on for
20 ticks, wooden buttons for 30 ticks. An arrow can keep a wooden button powered beyond the timer. Observe the
button block's powered state or downstream feedback for completion. There is no release write.

## `folkways_core:fluid`
<!-- guide:parts/folkways_core:fluid -->

Transfers fluid through a block's unsided NeoForge capability.
Fluid stack with identity and an integer amount in mB. Empty tanks have nothing to draw. The native tank defines
capacity and fluid compatibility. Transfer only what is available or accepted and confirm the actual amount moved.
This part requires an unsided handler. Each tank's processing, pumping and sided routing are native behavior.
Facility script item-transfer actions cannot transfer fluids.

## `folkways_core:energy`
<!-- guide:parts/folkways_core:energy -->

Transfers Forge Energy through a block's unsided NeoForge capability.
Positive integer FE, limited by the native storage capacity and per-operation receive/extract limits. Some
handlers only accept or only supply energy. Check the actual amount moved before relying on the charge.
Folkways requires an unsided handler and its facility script item-transfer actions cannot transfer FE.

## `folkways_core:item/down`
<!-- guide:parts/folkways_core:item/down -->

Transfers items through the block's down face.
Item stack and positive integer count, constrained by native slot filters and capacities. Use an item transfer, then
check the accepted or extracted quantity and the port's `count` if available. Select the face according to the actual
machine. For a vanilla furnace, the top feeds the input, horizontal faces feed fuel, and the bottom extracts results
and can return fuel containers. Several faces can address the same inventory. Match full item identity and components
when extracting, and allow for native automation to change stock between actions.

## `folkways_core:item/up`
<!-- guide:parts/folkways_core:item/up -->

Transfers items through the block's up face.
Item stack and positive integer count, constrained by native slot filters and capacities. Use an item transfer, then
check the accepted or extracted quantity and the port's `count` if available. Select the face according to the actual
machine. For a vanilla furnace, the top feeds the input, horizontal faces feed fuel, and the bottom extracts results
and can return fuel containers. Several faces can address the same inventory. Match full item identity and components
when extracting, and allow for native automation to change stock between actions.

## `folkways_core:item/north`
<!-- guide:parts/folkways_core:item/north -->

Transfers items through the block's north face.
Item stack and positive integer count, constrained by native slot filters and capacities. Use an item transfer, then
check the accepted or extracted quantity and the port's `count` if available. Select the face according to the actual
machine. For a vanilla furnace, the top feeds the input, horizontal faces feed fuel, and the bottom extracts results
and can return fuel containers. Several faces can address the same inventory. Match full item identity and components
when extracting, and allow for native automation to change stock between actions.

## `folkways_core:item/south`
<!-- guide:parts/folkways_core:item/south -->

Transfers items through the block's south face.
Item stack and positive integer count, constrained by native slot filters and capacities. Use an item transfer, then
check the accepted or extracted quantity and the port's `count` if available. Select the face according to the actual
machine. For a vanilla furnace, the top feeds the input, horizontal faces feed fuel, and the bottom extracts results
and can return fuel containers. Several faces can address the same inventory. Match full item identity and components
when extracting, and allow for native automation to change stock between actions.

## `folkways_core:item/west`
<!-- guide:parts/folkways_core:item/west -->

Transfers items through the block's west face.
Item stack and positive integer count, constrained by native slot filters and capacities. Use an item transfer, then
check the accepted or extracted quantity and the port's `count` if available. Select the face according to the actual
machine. For a vanilla furnace, the top feeds the input, horizontal faces feed fuel, and the bottom extracts results
and can return fuel containers. Several faces can address the same inventory. Match full item identity and components
when extracting, and allow for native automation to change stock between actions.

## `folkways_core:item/east`
<!-- guide:parts/folkways_core:item/east -->

Transfers items through the block's east face.
Item stack and positive integer count, constrained by native slot filters and capacities. Use an item transfer, then
check the accepted or extracted quantity and the port's `count` if available. Select the face according to the actual
machine. For a vanilla furnace, the top feeds the input, horizontal faces feed fuel, and the bottom extracts results
and can return fuel containers. Several faces can address the same inventory. Match full item identity and components
when extracting, and allow for native automation to change stock between actions.
