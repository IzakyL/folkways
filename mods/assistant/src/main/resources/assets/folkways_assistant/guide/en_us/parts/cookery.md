## `input`
<!-- guide:parts/input -->

Transfers recipe items through a Kaleidoscope Cookery appliance's input.
Item stack and positive integer count. The chopping board accepts one item with a matching recipe via its native
put interaction, only when the board and result space are free. Its native chopping and result collection are
separate actions from insertion. For other appliances this part uses the unsided native item handler when present,
including native insertion and extraction checks. Discovery is possible even when there is no such handler.

The candidate applies to the chopping board, stockpot, pot, bamboo tray, teapot, stove, shawarma spit, steamer,
and millstone. Native heating, processing, cutting and serving still require their own recipe and interaction.
Use the handle-side footing (across facing on stockpot and steamer, south on teapot), transfer the input, check
the receipt, then use the native appliance process and inspect its result before collecting. The port does not
ignite a stove, carry out the recipe, or guarantee an extraction path for served dishes.

### Native processing

- Chopping board. Put in one recipe item, use the native cutting tool until results occupy the board, then collect
  the results. Use the board's input and result state to distinguish a blocked board from an empty one.
- Stockpot. Supply a native soup base and ingredients. Provide the required heat and lid, then wait for the
  stockpot's result and serve with the recipe's container. Its status and remaining servings are separate from
  the item transfer. Pot recipes have their own oil, ingredient, heat and cooking interactions. Use the finished
  product and its carrier requirements to decide when and how to collect.
- Bamboo tray. The native tray works slot by slot. Wait for the completion state of the slot before extracting
  its result. Steamer processing requires heat and runs on its stacked trays, so check the actual tray result.
- Teapot. Supply tea fluid and a matching ingredient, provide heat, and wait for the native status and result.
  The item input does not fill fluid or serve the tea.
- Stove and shawarma spit. A stove supplies heat through its ignition state. The spit accepts a cooking item via
  its native interaction and ticks until it is cooked. Observe the native cooked state before taking the item.
- Millstone. Load a matching input and power the native millstone, for example with a bound entity. Native
  progress and output slots distinguish milling from completion. Collect output before starting the next batch.

These native statuses are not additional readings on the generic `input` part. Use an applicable native routine,
observable stock change or offered reading to confirm completion. Recipe-dependent processing has no shared tick limit.
