import { ui } from "@izakyl/blockwright-minecraft";
import { uiWait } from "@izakyl/blockwright-minecraft/internal";
import { UI_ROLES, resolveRole } from "@izakyl/blockwright-ui";
import type { RoleGuess, RoleRule, UiAction, UiNode, UiRole, UiSelector } from "@izakyl/blockwright-ui";
import {
  clickElement,
  elementsOf,
  hoverElement,
  missingMembers,
  panelProbe,
  scrollElementIntoView,
  typeIntoElement,
  waitForElement,
  type PanelRead,
} from "./panel.ts";
import type { PanelHolder } from "./routes.ts";
import { anchorOf, hasUniqueId, isHittable, selectElement, visibleBox, type Element, type ElementSelector } from "./rules.ts";

const ADAPTER = "ldlib2";
// Generated specs live in tests/e2e, beside this helper.
const FROM = "../ldlib2";

/** A style class that states an element's role outright, e.g. `bw-role-button`. */
export const ROLE_CLASS_PREFIX = "bw-role-";

const GUESSES: RoleGuess = [
  [/ItemSlot|FluidSlot/, "slot"],
  [/TextField|TextArea/, "textbox"],
  [/Toggle|Switch|CheckBox|Checkbox/, "checkbox"],
  [/Slider/, "slider"],
  [/Scroller|ScrollView/, "scroll"],
  [/Tab/, "tab"],
  [/Button/, "button"],
  [/Selector|List/, "list"],
  [/Label|TextElement/, "text"],
  [/Image|Texture/, "image"],
];

export interface Ldlib2AdapterOptions {
  /** What elements are, where neither a `bw-role-*` style class nor the class name says. */
  roles?: RoleRule[];
}

export interface Ldlib2UiState {
  holder: PanelHolder;
  elements: Element[];
  /** UiNode of each element, by Element.index. */
  nodes: UiNode[];
  /** Element.index by node key. */
  byKey: Map<string, number>;
}

/** The LDLib2 adapter: a UI adapter for the Minecraft platform that always checks the build and acts itself. */
export type Ldlib2Adapter = ui.Adapter<Ldlib2UiState> & Required<Pick<ui.Adapter<Ldlib2UiState>, "verify" | "act">>;

function keyOf(elements: Element[], element: Element): string {
  if (hasUniqueId(elements, element)) {
    return `#${element.id}`;
  }
  const { within, path } = anchorOf(elements, element);
  return `${within === undefined ? "" : `#${within}`}/${path.join("/")}`;
}

function declaredRole(element: Element): UiRole | undefined {
  for (const style of element.classes ?? []) {
    if (style.startsWith(ROLE_CLASS_PREFIX)) {
      const role = style.slice(ROLE_CLASS_PREFIX.length) as UiRole;
      if (UI_ROLES.includes(role)) {
        return role;
      }
    }
  }
  return undefined;
}

function selectorOf(selector: UiSelector): ElementSelector {
  return selector as unknown as ElementSelector;
}

function clickOptions(action: Extract<UiAction, { kind: "click" }>) {
  return {
    ...(action.button && action.button !== "left" ? { button: action.button } : {}),
    ...(action.modifiers?.length ? { modifiers: [...action.modifiers] } : {}),
  };
}

function describeClick(action: Extract<UiAction, { kind: "click" }>): string {
  const modifiers = action.modifiers?.length ? `${action.modifiers.join("+")}+` : "";
  const button = action.button && action.button !== "left" ? `${action.button} ` : "";
  return `${modifiers}${button}clicked`;
}

/**
 * The LDLib2 adapter: reads the open panel in the game (one probe tries every holder), names elements by
 * unique id or by the nearest uniquely id'd ancestor plus a child path, and acts with LDLib2's own
 * semantics: waiting in the game until the target is actionable, scrolling it into view, and checking the
 * press lands on it.
 */
export function createLdlib2Adapter(options: Ldlib2AdapterOptions = {}): Ldlib2Adapter {
  const toTree = (elements: Element[]): { roots: UiNode[]; nodes: UiNode[] } => {
    const roots: UiNode[] = [];
    const nodes: UiNode[] = [];
    const open: UiNode[] = [];
    for (const element of elements) {
      const shown = element.displayed && element.visible && element.opacity > 0;
      const visible = shown ? visibleBox(elements, element) : undefined;
      const node: UiNode = {
        key: keyOf(elements, element),
        role: declaredRole(element)
          ?? resolveRole(options.roles, GUESSES, { className: element.className, id: element.id }),
        ...(element.text ? { name: element.text } : element.id ? { name: element.id } : element.slot?.item ? { name: element.slot.item.id } : {}),
        className: element.className,
        bounds: { x: element.x, y: element.y, width: element.width, height: element.height },
        ...(visible ? { visibleBounds: visible } : {}),
        states: { enabled: element.active, visible: shown, hittable: isHittable(element, elements) },
        ...(element.slot?.item ? { item: element.slot.item } : {}),
        children: [],
      };
      nodes.push(node);
      open.length = element.depth;
      (element.depth === 0 ? roots : open[element.depth - 1]!.children).push(node);
      open[element.depth] = node;
    }
    return { roots, nodes };
  };

  return {
    id: ADAPTER,
    summary: "LDLib2 panels: elements by id, or by the nearest id'd ancestor plus child path; "
      + "clipped elements are scrolled into view and every press is checked against LDLib2's own hit",

    verify: (client, callOptions) => missingMembers(client, callOptions),

    read: () => ({
      probes: { panel: panelProbe() },
      parse(outcomes) {
        const outcome = outcomes.panel;
        if (!outcome) {
          return undefined;
        }
        if (outcome.error) {
          throw new Error(`reading the LDLib2 panel failed (${outcome.error.code}): ${outcome.error.message}`);
        }
        const read = outcome.ok as PanelRead | null;
        // No screen, or a screen that holds no ModularUI on any holder route: not this adapter's.
        if (!read) {
          return undefined;
        }
        const elements = elementsOf(read);
        const { roots, nodes } = toTree(elements);
        // A slot LDLib2 lays out itself sits in menu.slots at coordinates the container screen does not
        // draw it at; the vanilla entry for it would only mislead.
        const bound = new Set(elements.flatMap((element) => (element.slot ? [ui.menuSlotKey(element.slot.index)] : [])));
        return {
          adapter: ADAPTER,
          roots,
          state: { holder: read.holder, elements, nodes, byKey: new Map(nodes.map((node, at) => [node.key, at])) },
          supersedes: (adapter, node) => adapter === ui.ADAPTER_ID && bound.has(node.key),
        };
      },
    }),

    locate(layer, node) {
      const { elements, byKey } = layer.state;
      const element = elements[byKey.get(node.key)!]!;
      if (hasUniqueId(elements, element)) {
        return { selector: { id: element.id }, fragile: false };
      }
      const { within, path } = anchorOf(elements, element);
      return { selector: { ...(within === undefined ? {} : { within }), path }, fragile: within === undefined };
    },

    match(layer, selector) {
      const element = selectElement(layer.state.elements, selectorOf(selector));
      return element && layer.state.nodes[element.index];
    },

    async act(client, selector, action, { timeoutMs, signal }) {
      const target = selectorOf(selector);
      const label = JSON.stringify(selector);
      const common = {
        scrollIntoView: true,
        ...(timeoutMs !== undefined ? { timeoutMs } : {}),
        ...(signal ? { signal } : {}),
      };
      switch (action.kind) {
        case "click": {
          if (action.double) {
            // clickElement verifies one press; a double click is left to the host's pointer.
            return undefined;
          }
          const done = await clickElement(client, target, { ...common, ...clickOptions(action) });
          return { summary: `${describeClick(action)} ${label}; LDLib2 took the mouse-down inside it`, point: { x: done.x, y: done.y } };
        }
        case "hover": {
          const hovered = await hoverElement(client, target, common);
          return { summary: `hovering ${label}`, point: { x: hovered.x, y: hovered.y } };
        }
        case "scrollIntoView":
          await scrollElementIntoView(client, target, {
            ...(timeoutMs !== undefined ? { timeoutMs } : {}),
            ...(signal ? { signal } : {}),
          });
          return { summary: `${label} is scrolled into view` };
        case "type": {
          const done = await typeIntoElement(client, target, action.text, { ...common, ...(action.submit ? { submit: true } : {}) });
          return {
            summary: `typed ${JSON.stringify(action.text)} into ${label}${action.submit ? " and pressed enter" : ""}`,
            point: { x: done.x, y: done.y },
          };
        }
        case "scroll":
          return undefined;
      }
    },

    wait(client, selector, state, { timeoutMs, signal }) {
      return uiWait(() => waitForElement(client, selectorOf(selector), {
        state,
        ...(timeoutMs !== undefined ? { timeoutMs } : {}),
        ...(signal ? { signal } : {}),
      }));
    },

    codegen(selector, action) {
      const target = JSON.stringify(selector);
      const imports = (...names: string[]) => [{ from: FROM, names }];
      switch (action.kind) {
        case "click":
          if (action.double) {
            return undefined;
          }
          return {
            imports: imports("clickElement"),
            code: `await clickElement(client, ${target}, ${JSON.stringify({ scrollIntoView: true, ...clickOptions(action) })});`,
          };
        case "hover":
          return { imports: imports("hoverElement"), code: `await hoverElement(client, ${target}, { scrollIntoView: true });` };
        case "scrollIntoView":
          return { imports: imports("scrollElementIntoView"), code: `await scrollElementIntoView(client, ${target});` };
        case "type":
          return {
            imports: imports("typeIntoElement"),
            code: `await typeIntoElement(client, ${target}, ${JSON.stringify(action.text)}, `
              + `${JSON.stringify({ scrollIntoView: true, ...(action.submit ? { submit: true } : {}) })});`,
          };
        case "scroll":
          return undefined;
      }
    },
  };
}

/** The LDLib2 adapter with default roles. */
export const ldlib2Adapter: Ldlib2Adapter = createLdlib2Adapter();
