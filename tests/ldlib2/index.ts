// LDLib2 panels in a Minecraft client: the UI adapter, and the element-level waits and actions it is built on.
// The default export is what `blockwright-mcp --ui-adapter tests/ldlib2/index.ts` loads.
import { ldlib2Adapter } from "./adapter.ts";

export default ldlib2Adapter;
export { ldlib2Adapter, createLdlib2Adapter, ROLE_CLASS_PREFIX, type Ldlib2Adapter, type Ldlib2AdapterOptions } from "./adapter.ts";
export { LDLIB2, LDLIB2_JAVA_ROOT } from "./module.ts";
export {
  readPanel,
  panelElements,
  elementsOf,
  HOVER_REFRESH_FRAMES,
  waitForElement,
  scrollElementIntoView,
  hoverElement,
  clickElement,
  typeIntoElement,
  type PanelRead,
  type PanelReadOptions,
  type WaitForElementOptions,
  type ScrollIntoViewOptions,
  type HoverElementOptions,
  type HoverElementResult,
  type ClickElementOptions,
  type ClickElementResult,
  type TypeIntoElementOptions,
} from "./panel.ts";
export { LDLIB2_SYMBOLS, symbolPath, type PanelHolder } from "./routes.ts";
export {
  selectElement,
  aimPoint,
  ancestorsOf,
  childrenOf,
  clippingAncestors,
  visibleBox,
  isActionable,
  isHittable,
  elementsAtPoint,
  elementLabel,
  type Box,
  type Element,
  type ElementSelector,
} from "./rules.ts";
