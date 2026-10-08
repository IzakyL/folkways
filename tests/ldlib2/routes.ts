import type { reflect } from "@izakyl/blockwright-minecraft";

type SymbolDeclaration = reflect.SymbolDeclaration;

const MODULAR_UI_SCREEN = "com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen";
const MODULAR_UI_HOLDER = "com.lowdragmc.lowdraglib2.gui.holder.IModularUIHolder";
const ABSTRACT_CONTAINER_SCREEN = "net.minecraft.client.gui.screens.inventory.AbstractContainerScreen";
const MODULAR_UI = "com.lowdragmc.lowdraglib2.gui.ui.ModularUI";
const UI = "com.lowdragmc.lowdraglib2.gui.ui.UI";
const UI_ELEMENT = "com.lowdragmc.lowdraglib2.gui.ui.UIElement";
const BASIC_STYLE = "com.lowdragmc.lowdraglib2.gui.ui.style.BasicStyle";

// ---- routes: every LDLib2 member this package touches, declared once ----
//
// The Java module reaches LDLib2 only through the paths built from these declarations (LDLib2 is not on the
// classpath it is checked against), and the adapter's `verify` checks the same declarations with reflect.symbols, so
// a build that renamed a member is reported up front instead of reading as an empty panel.

/** Where the open screen keeps its ModularUI: the screen itself, or its container menu. */
export type PanelHolder = "screen" | "container";

/** From the open screen to its ModularUI, per holder. */
const TO_MODULAR_UI: Record<PanelHolder, SymbolDeclaration[]> = {
  screen: [
    { as: "screen.modularUI", className: MODULAR_UI_SCREEN, field: "modularUI" },
  ],
  // LDLib2 mixes IModularUIHolderMenu into every AbstractContainerMenu, so this covers its own
  // ModularUIContainerMenu and a panel a mod attached to a vanilla menu alike.
  container: [
    { as: "screen.menu", className: ABSTRACT_CONTAINER_SCREEN, field: "menu" },
    {
      as: "menu.modularUI",
      className: MODULAR_UI_HOLDER,
      method: "getModularUI",
      descriptor: "()Lcom/lowdragmc/lowdraglib2/gui/ui/ModularUI;",
    },
  ],
};

const TO_ROOT_ELEMENT: SymbolDeclaration[] = [
  { as: "modularUI.ui", className: MODULAR_UI, field: "ui" },
  { as: "ui.rootElement", className: UI, field: "rootElement" },
];

const TO_LAST_HOVER: SymbolDeclaration[] = [
  {
    as: "modularUI.lastHoveredElement",
    className: MODULAR_UI,
    method: "getLastHoveredElement",
    descriptor: "()Lcom/lowdragmc/lowdraglib2/gui/ui/UIElement;",
  },
];

const TO_LAST_MOUSE_DOWN: SymbolDeclaration[] = [
  {
    as: "modularUI.lastMouseDownElement",
    className: MODULAR_UI,
    method: "getLastMouseDownElement",
    descriptor: "()Lcom/lowdragmc/lowdraglib2/gui/ui/UIElement;",
  },
];

const ELEMENT_CHILDREN: SymbolDeclaration = {
  as: "element.children",
  className: UI_ELEMENT,
  method: "getChildren",
  descriptor: "()Ljava/util/List;",
};

const ELEMENT_STYLE: SymbolDeclaration = {
  as: "element.style",
  className: UI_ELEMENT,
  method: "getStyle",
  descriptor: "()Lcom/lowdragmc/lowdraglib2/gui/ui/style/BasicStyle;",
};

/** What every element is read for; each route must resolve on this LDLib2 build. */
export const ELEMENT_ROUTES: Record<string, SymbolDeclaration[]> = {
  id: [{ as: "element.id", className: UI_ELEMENT, method: "getId", descriptor: "()Ljava/lang/String;" }],
  x: [{ as: "element.positionX", className: UI_ELEMENT, method: "getPositionX", descriptor: "()F" }],
  y: [{ as: "element.positionY", className: UI_ELEMENT, method: "getPositionY", descriptor: "()F" }],
  width: [{ as: "element.sizeWidth", className: UI_ELEMENT, method: "getSizeWidth", descriptor: "()F" }],
  height: [{ as: "element.sizeHeight", className: UI_ELEMENT, method: "getSizeHeight", descriptor: "()F" }],
  contentX: [{ as: "element.contentX", className: UI_ELEMENT, method: "getContentX", descriptor: "()F" }],
  contentY: [{ as: "element.contentY", className: UI_ELEMENT, method: "getContentY", descriptor: "()F" }],
  contentWidth: [{ as: "element.contentWidth", className: UI_ELEMENT, method: "getContentWidth", descriptor: "()F" }],
  contentHeight: [{ as: "element.contentHeight", className: UI_ELEMENT, method: "getContentHeight", descriptor: "()F" }],
  visible: [{ as: "element.isVisible", className: UI_ELEMENT, method: "isVisible", descriptor: "()Z" }],
  active: [{ as: "element.isActive", className: UI_ELEMENT, method: "isActive", descriptor: "()Z" }],
  displayed: [{ as: "element.isDisplayed", className: UI_ELEMENT, method: "isDisplayed", descriptor: "()Z" }],
  classes: [{ as: "element.classes", className: UI_ELEMENT, method: "getClasses", descriptor: "()Ljava/util/Set;" }],
  opacity: [ELEMENT_STYLE, { as: "style.opacity", className: BASIC_STYLE, method: "opacity", descriptor: "()F" }],
  overflowVisible: [
    ELEMENT_STYLE,
    { as: "style.overflowVisible", className: BASIC_STYLE, method: "overflowVisible", descriptor: "()Z" },
  ],
};

/** Read where the element has them and absent elsewhere (subclass members), so they are not declared symbols. */
const BEST_EFFORT_SELECT: Record<string, string> = {
  text: "text.getText().getString()",
  ownText: "getText().getString()",
  slotIndex: "getSlot().index",
  slotItem: "getSlot().getItem().getItemHolder().getRegisteredName()",
  slotCount: "getSlot().getItem().getCount()",
};

export function symbolSegment(symbol: SymbolDeclaration): string {
  if (symbol.field !== undefined) {
    return symbol.field;
  }
  if (symbol.method !== undefined) {
    return `${symbol.method}()`;
  }
  throw new Error(
    `@izakyl/blockwright-ldlib2 symbol ${symbol.as ?? symbol.className} declares neither a field nor a method, `
      + "so no path segment can be built from it",
  );
}

export function symbolPath(route: readonly SymbolDeclaration[]): string {
  return route.map(symbolSegment).join(".");
}

/** Every LDLib2 member the routes walk; the adapter's `verify` checks them all. */
export const LDLIB2_SYMBOLS: SymbolDeclaration[] = dedupe([
  ...TO_MODULAR_UI.screen,
  ...TO_MODULAR_UI.container,
  ...TO_ROOT_ELEMENT,
  ...TO_LAST_MOUSE_DOWN,
  ...TO_LAST_HOVER,
  ELEMENT_CHILDREN,
  ...Object.values(ELEMENT_ROUTES).flat(),
]);

function dedupe(symbols: SymbolDeclaration[]): SymbolDeclaration[] {
  return [...new Set(symbols)];
}

/** The paths the Java module walks (see Panel.java): all built from the declarations above. */
export interface PanelRoutes {
  /** From the open screen to its ModularUI, tried in this order. */
  holders: Record<PanelHolder, string>;
  /** From the ModularUI to the root element. */
  root: string;
  /** From an element to its children. */
  children: string;
  select: Record<string, string>;
}

export const PANEL_ROUTES: PanelRoutes = {
  holders: {
    screen: symbolPath(TO_MODULAR_UI.screen),
    container: symbolPath(TO_MODULAR_UI.container),
  },
  root: symbolPath(TO_ROOT_ELEMENT),
  children: symbolSegment(ELEMENT_CHILDREN),
  select: {
    ...Object.fromEntries(Object.entries(ELEMENT_ROUTES).map(([name, route]) => [name, symbolPath(route)])),
    ...BEST_EFFORT_SELECT,
  },
};

export const HOVERED_PATH = symbolPath(TO_LAST_HOVER);
export const MOUSE_DOWN_PATH = symbolPath(TO_LAST_MOUSE_DOWN);

