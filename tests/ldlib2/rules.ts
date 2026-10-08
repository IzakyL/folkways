import { globMatches } from "@izakyl/blockwright-client";
import { formatDecimal as round, shortClass } from "@izakyl/blockwright-minecraft/internal";

/** One element of an LDLib2 panel, as the panel read reports it (GUI-scaled, unclipped layout). */
export interface Element {
  id?: string;
  text?: string;
  className: string;
  /** Style classes set on the element (UIElement.getClasses()). */
  classes?: string[];
  /** The menu slot an ItemSlot is bound to: its position in menu.slots, and what it holds. */
  slot?: { index: number; item?: { id: string; count?: number } };
  x: number;
  y: number;
  width: number;
  height: number;
  visible: boolean;
  active: boolean;
  displayed: boolean;
  opacity: number;
  contentX: number;
  contentY: number;
  contentWidth: number;
  contentHeight: number;
  overflowVisible: boolean;
  /** Position in the preorder walk. */
  index: number;
  depth: number;
}

/**
 * Picks one element: an exact `id` first, then `within` + `path`, then drawn `text` (substring), then
 * `className` (substring), then an `idPattern` glob; `index` narrows to one preorder position.
 */
export interface ElementSelector {
  id?: string;
  idPattern?: string;
  text?: string;
  className?: string;
  index?: number;
  /** With `path`: the id of the ancestor the path starts from. Without it the path starts at the root. */
  within?: string;
  /** Child positions walked down from `within` (or the root); `[]` is that element itself. */
  path?: readonly number[];
}

// ---- selection and hit rules (the in-game twin is Rules.java; keep them in step) ----

export function selectElement(elements: Element[], selector: ElementSelector): Element | undefined {
  const pool = selector.index === undefined ? elements : elements.filter((e) => e.index === selector.index);
  if (selector.id !== undefined) {
    return pool.find((element) => element.id === selector.id);
  }
  if (selector.path !== undefined) {
    const found = resolveAnchor(elements, selector.within, selector.path);
    return found && pool.includes(found) ? found : undefined;
  }
  if (selector.text !== undefined) {
    return pool.find((element) => (element.text ?? "").includes(selector.text!));
  }
  if (selector.className !== undefined) {
    return pool.find((element) => element.className.includes(selector.className!));
  }
  if (selector.idPattern !== undefined) {
    return pool.find((element) => element.id !== undefined && element.id !== ""
      && globMatches(selector.idPattern!, element.id));
  }
  return pool[0];
}

export interface Box {
  x: number;
  y: number;
  width: number;
  height: number;
}

export interface ClipVerdict {
  ancestor: Element;
  clip: Box;
  axis: "x" | "y" | "both";
  aim: { x: number; y: number };
  clippingAncestors: number;
}

const CLIP_EPSILON = 1 / 64;

function elementBox(element: Element): Box {
  return { x: element.x, y: element.y, width: element.width, height: element.height };
}

function contentBox(element: Element): Box {
  return { x: element.contentX, y: element.contentY, width: element.contentWidth, height: element.contentHeight };
}

export function aimPoint(element: Element): { x: number; y: number } {
  return { x: element.x + element.width / 2, y: element.y + element.height / 2 };
}

interface TreeIndex {
  parents: Int32Array;
  /** Position of each element among its parent's children. */
  positions: Int32Array;
  idCounts: Map<string, number>;
}

const TREE_INDEX = new WeakMap<Element[], TreeIndex>();

function treeIndex(elements: Element[]): TreeIndex {
  const cached = TREE_INDEX.get(elements);
  if (cached !== undefined && cached.parents.length === elements.length) {
    return cached;
  }
  const parents = new Int32Array(elements.length);
  const positions = new Int32Array(elements.length);
  const idCounts = new Map<string, number>();
  const open: number[] = [];
  const childCount = new Map<number, number>();
  for (let at = 0; at < elements.length; at++) {
    const element = elements[at]!;
    const depth = element.depth;
    open.length = depth;
    const parent = depth === 0 ? -1 : open[depth - 1] ?? -1;
    parents[at] = parent;
    const position = childCount.get(parent) ?? 0;
    positions[at] = position;
    childCount.set(parent, position + 1);
    open[depth] = at;
    if (element.id) {
      idCounts.set(element.id, (idCounts.get(element.id) ?? 0) + 1);
    }
  }
  const index = { parents, positions, idCounts };
  TREE_INDEX.set(elements, index);
  return index;
}

export function ancestorsOf(elements: Element[], target: Element): Element[] {
  const { parents } = treeIndex(elements);
  const chain: Element[] = [];
  for (let at = parents[target.index] ?? -1; at >= 0; at = parents[at]!) {
    chain.push(elements[at]!);
  }
  return chain.reverse();
}

export function childrenOf(elements: Element[], parent: Element): Element[] {
  const children: Element[] = [];
  for (let at = parent.index + 1; at < elements.length && elements[at]!.depth > parent.depth; at++) {
    if (elements[at]!.depth === parent.depth + 1) {
      children.push(elements[at]!);
    }
  }
  return children;
}

/**
 * Where an element sits relative to its nearest ancestor with a unique id (or the root when there is
 * none): the selector that still finds an id-less element after the panel is rebuilt.
 */
export function anchorOf(elements: Element[], target: Element): { within?: string; path: number[] } {
  const { parents, positions, idCounts } = treeIndex(elements);
  const path: number[] = [];
  for (let at = target.index; parents[at]! >= 0; at = parents[at]!) {
    path.unshift(positions[at]!);
    const parent = elements[parents[at]!]!;
    if (parent.id && idCounts.get(parent.id) === 1) {
      return { within: parent.id, path };
    }
  }
  return { path };
}

export function hasUniqueId(elements: Element[], element: Element): boolean {
  return element.id !== undefined && element.id !== "" && treeIndex(elements).idCounts.get(element.id) === 1;
}

export function resolveAnchor(elements: Element[], within: string | undefined, path: readonly number[]): Element | undefined {
  let current = within === undefined
    ? elements.find((element) => element.depth === 0)
    : elements.find((element) => element.id === within);
  for (const step of path) {
    current = current && childrenOf(elements, current)[step];
  }
  return current;
}

export function clippingAncestors(elements: Element[], target: Element): Element[] {
  return ancestorsOf(elements, target).filter((ancestor) => !ancestor.overflowVisible);
}

function intersect(a: Box, b: Box): Box {
  const x = Math.max(a.x, b.x);
  const y = Math.max(a.y, b.y);
  return {
    x,
    y,
    width: Math.min(a.x + a.width, b.x + b.width) - x,
    height: Math.min(a.y + a.height, b.y + b.height) - y,
  };
}

export function clipBox(elements: Element[], target: Element): Box | undefined {
  let box: Box | undefined;
  for (const ancestor of clippingAncestors(elements, target)) {
    const content = contentBox(ancestor);
    box = box === undefined ? content : intersect(box, content);
  }
  return box;
}

export function visibleBox(elements: Element[], target: Element): Box | undefined {
  const clip = clipBox(elements, target);
  const box = clip === undefined ? elementBox(target) : intersect(elementBox(target), clip);
  return box.width > 0 && box.height > 0 ? box : undefined;
}

function outside(point: number, low: number, extent: number): boolean {
  return point < low || point >= low + extent;
}

export function clipVerdict(elements: Element[], target: Element): ClipVerdict | undefined {
  const aim = aimPoint(target);
  const clipping = clippingAncestors(elements, target);
  let verdict: ClipVerdict | undefined;
  for (const ancestor of clipping) {
    const clip = contentBox(ancestor);
    const offX = outside(aim.x, clip.x, clip.width);
    const offY = outside(aim.y, clip.y, clip.height);
    if (offX || offY) {
      verdict = { ancestor, clip, axis: offX && offY ? "both" : offX ? "x" : "y", aim, clippingAncestors: clipping.length };
    }
  }
  return verdict;
}

export function elementsAtPoint(elements: Element[], { x, y }: { x: number; y: number }): Element[] {
  return elements.filter((element) => {
    if (!isHittable(element, elements)) {
      return false;
    }
    const box = visibleBox(elements, element);
    return box !== undefined && !outside(x, box.x, box.width) && !outside(y, box.y, box.height);
  });
}

export function elementLabel(elements: Element[], element: Element): string {
  if (element.id) {
    return element.id;
  }
  const named = [...ancestorsOf(elements, element)].reverse().find((ancestor) => ancestor.id);
  return named ? `${shortClass(element.className)} inside ${named.id}` : `${shortClass(element.className)}#${element.index}`;
}

export function clipReason(elements: Element[], target: Element): string | undefined {
  const verdict = clipVerdict(elements, target);
  if (verdict === undefined) {
    return undefined;
  }
  const { clip, aim } = verdict;
  const spans: string[] = [];
  if (verdict.axis !== "x") {
    spans.push(
      `viewport y ${round(clip.y)}..${round(clip.y + clip.height)}, element spans `
        + `${round(target.y)}..${round(target.y + target.height)}, so the click aims at y ${round(aim.y)}`,
    );
  }
  if (verdict.axis !== "y") {
    spans.push(
      `viewport x ${round(clip.x)}..${round(clip.x + clip.width)}, element spans `
        + `${round(target.x)}..${round(target.x + target.width)}, so the click aims at x ${round(aim.x)}`,
    );
  }
  return `not hittable because clipped by ${elementLabel(elements, verdict.ancestor)}: ${spans.join("; ")}`
    + " — the panel reports unclipped flow coordinates, so that point belongs to whatever is painted "
    + "there instead; scroll it in with scrollElementIntoView (or clickElement/hoverElement with "
    + "scrollIntoView: true) rather than aiming at coordinates nothing draws";
}

/** LDLib2's hit gate: displayed, visible, opaque, with area, and (given the tree) its centre not clipped away. */
export function isHittable(element: Element, elements?: Element[]): boolean {
  const lit = element.displayed && element.visible && element.opacity > 0 && element.width > 0 && element.height > 0;
  if (!lit) {
    return false;
  }
  return elements === undefined || clipVerdict(elements, element) === undefined;
}

export function isActionable(element: Element, elements?: Element[]): boolean {
  return isHittable(element, elements) && element.active;
}

export function isFullyRevealed(elements: Element[], target: Element): boolean {
  const clip = clipBox(elements, target);
  if (clip === undefined) {
    return true;
  }
  const box = intersect(elementBox(target), clip);
  return box.width >= Math.min(target.width, clip.width) - CLIP_EPSILON
    && box.height >= Math.min(target.height, clip.height) - CLIP_EPSILON;
}

