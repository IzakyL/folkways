import assert from "node:assert/strict";
import test from "node:test";

import {
  ancestorsOf,
  aimPoint,
  anchorOf,
  clipBox,
  clipReason,
  clipVerdict,
  clippingAncestors,
  elementsAtPoint,
  isActionable,
  isFullyRevealed,
  isHittable,
  resolveAnchor,
  selectElement,
  visibleBox,
  type Element,
} from "./rules.ts";

function element(over: Partial<Element> = {}): Element {
  const base: Element = {
    className: "com.lowdragmc.lowdraglib2.gui.ui.elements.Button",
    x: 0,
    y: 0,
    width: 40,
    height: 12,
    visible: true,
    active: true,
    displayed: true,
    opacity: 1,
    contentX: 0,
    contentY: 0,
    contentWidth: 40,
    contentHeight: 12,
    overflowVisible: true,
    index: 0,
    depth: 1,
    ...over,
  };
  return {
    ...base,
    contentX: over.contentX ?? base.x,
    contentY: over.contentY ?? base.y,
    contentWidth: over.contentWidth ?? base.width,
    contentHeight: over.contentHeight ?? base.height,
  };
}

test("selectElement matches an LDLib2 id exactly, not as a substring", () => {
  const elements = [element({ index: 0, id: "row" }), element({ index: 1, id: "row.confirm" })];
  assert.equal(selectElement(elements, { id: "row.confirm" })?.index, 1);
  assert.equal(selectElement(elements, { id: "row." }), undefined);
});

test("selectElement falls back to drawn text, then class name", () => {
  const elements = [
    element({ index: 0, text: "Confirm" }),
    element({ index: 1, className: "com.lowdragmc.lowdraglib2.gui.ui.elements.TextField" }),
  ];
  assert.equal(selectElement(elements, { text: "Conf" })?.index, 0);
  assert.equal(selectElement(elements, { className: "TextField" })?.index, 1);
});

test("selectElement narrows by index before matching", () => {
  const elements = [element({ index: 0, text: "row" }), element({ index: 1, text: "row" })];
  assert.equal(selectElement(elements, { text: "row", index: 1 })?.index, 1);
});

test("selectElement matches an id pattern with * only after the exact fields", () => {
  const elements = [
    element({ index: 0, id: "folkways.citizen.row.a1b2c3d4" }),
    element({ index: 1, id: "folkways.citizen.row.e5f6a7b8" }),
  ];
  assert.equal(selectElement(elements, { idPattern: "folkways.citizen.row.*" })?.index, 0);
  assert.equal(selectElement(elements, { idPattern: "*.e5f6a7b8" })?.index, 1);
  assert.equal(selectElement(elements, { idPattern: "folkways.zones.row.*" }), undefined);
});

test("an exact id wins over an id pattern that would match another element", () => {
  const elements = [
    element({ index: 0, id: "folkways.zones.row.0" }),
    element({ index: 1, id: "folkways.zones.row.1" }),
  ];
  assert.equal(
    selectElement(elements, { id: "folkways.zones.row.1", idPattern: "folkways.zones.row.*" })?.index,
    1,
  );
});

test("an empty id is never matched by a pattern", () => {
  const elements = [element({ index: 0, id: "" }), element({ index: 1, id: "folkways.zones.row.0" })];
  assert.equal(selectElement(elements, { idPattern: "*" })?.index, 1);
  assert.equal(selectElement(elements, { idPattern: "" }), undefined);
});

test("actionable follows the LDLib2 hit gate: displayed, visible, opaque - and active on top", () => {
  assert.equal(isActionable(element()), true);
  assert.equal(isActionable(element({ displayed: false })), false);
  assert.equal(isActionable(element({ opacity: 0 })), false);
  assert.equal(isActionable(element({ visible: false })), false);
  assert.equal(isActionable(element({ active: false })), false);
  assert.equal(isHittable(element({ active: false })), true);
  assert.equal(isHittable(element({ displayed: false })), false);
});

test("an element with no area is not hittable, however lit up it says it is", () => {
  assert.equal(isHittable(element({ width: 0 })), false);
  assert.equal(isHittable(element({ height: 0 })), false);
  assert.equal(isHittable(element({ width: 0, height: 0 })), false);
  assert.equal(isActionable(element({ width: 0, height: 0 })), false);
});

function tree(...rows: Array<Partial<Element> & { depth: number }>): Element[] {
  return rows.map((row, index) => element({ ...row, index }));
}

const NAV_SCROLLER = () => tree(
  { depth: 0, id: "folkways.colony.root", x: 380, y: 200, width: 140, height: 280 },
  { depth: 1, id: "folkways.navigation.production", x: 396, y: 324, width: 104, height: 126,
    className: "com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView" },
  { depth: 2, x: 396, y: 324, width: 104, height: 126,
    className: "com.lowdragmc.lowdraglib2.gui.ui.UIElement" },
  { depth: 3, x: 396, y: 324, width: 94, height: 126, overflowVisible: false,
    className: "com.lowdragmc.lowdraglib2.gui.ui.UIElement" },
  { depth: 4, x: 396, y: 303, width: 94, height: 218,
    className: "com.lowdragmc.lowdraglib2.gui.ui.UIElement" },
  { depth: 5, id: "folkways.page.pasture", x: 396, y: 443, width: 94, height: 24 },
);

test("the element scrolled out of the production scroller is not hittable, however laid out it says it is", () => {
  const elements = NAV_SCROLLER();
  const pasture = elements.at(-1)!;
  assert.equal(isHittable(pasture), true);
  assert.equal(isHittable(pasture, elements), false);
  assert.equal(isActionable(pasture, elements), false);
  assert.equal(clipVerdict(elements, pasture)?.axis, "y");
});

test("the clipped verdict names the clipping ancestor and both ranges, not just 'not actionable'", () => {
  const elements = NAV_SCROLLER();
  const reason = clipReason(elements, elements.at(-1)!)!;
  assert.match(reason, /not hittable because clipped by UIElement inside folkways\.navigation\.production/);
  assert.match(reason, /viewport y 324\.\.450/);
  assert.match(reason, /element spans 443\.\.467/);
  assert.match(reason, /the click aims at y 455/);
  assert.match(reason, /scrollElementIntoView/);
});

test("scrolled in, the same element is hittable again and the aim point is its centre", () => {
  const elements = NAV_SCROLLER();
  const settled = tree(
    ...elements.slice(0, 5).map((row) => ({ ...row })),
    { ...elements[5]!, y: 422 },
  );
  const pasture = settled.at(-1)!;
  assert.equal(isHittable(pasture, settled), true);
  assert.equal(isFullyRevealed(settled, pasture), true);
  assert.deepEqual(aimPoint(pasture), { x: 443, y: 434 });
});

test("clipping is the ancestor's content box, so its padding narrows the viewport", () => {
  const elements = tree(
    { depth: 0, id: "port", x: 0, y: 100, width: 100, height: 100, overflowVisible: false,
      contentX: 5, contentY: 105, contentWidth: 90, contentHeight: 90 },
    { depth: 1, id: "row", x: 0, y: 190, width: 100, height: 12 },
  );
  const row = elements[1]!;
  assert.deepEqual(clipBox(elements, row), { x: 5, y: 105, width: 90, height: 90 });
  assert.equal(isHittable(row, elements), false, "the aim point clears the position box but not the padding");
  assert.match(clipReason(elements, row)!, /viewport y 105\.\.195/);
  assert.match(clipReason(elements, row)!, /the click aims at y 196/);
});

test("only an ancestor with overflow hidden clips; a plain container does not", () => {
  const elements = tree(
    { depth: 0, id: "group", x: 0, y: 0, width: 100, height: 20 },
    { depth: 1, id: "row", x: 0, y: 100, width: 100, height: 12 },
  );
  assert.equal(clipBox(elements, elements[1]!), undefined);
  assert.equal(isHittable(elements[1]!, elements), true);
  assert.deepEqual(clippingAncestors(elements, elements[1]!), []);
});

test("half out of the viewport still counts if the centre the click aims at is inside", () => {
  const elements = tree(
    { depth: 0, id: "port", x: 0, y: 0, width: 100, height: 100, overflowVisible: false },
    { depth: 1, id: "row", x: 0, y: 85, width: 100, height: 20 },
  );
  const row = elements[1]!;
  assert.equal(isHittable(row, elements), true);
  assert.equal(isFullyRevealed(elements, row), false);
  assert.deepEqual(visibleBox(elements, row), { x: 0, y: 85, width: 100, height: 15 });
});

test("flush with the viewport edge is inside, one pixel past it is not", () => {
  const port = { depth: 0, id: "port", x: 0, y: 0, width: 100, height: 100, overflowVisible: false };
  const flush = tree(port, { depth: 1, id: "row", x: 0, y: 76, width: 100, height: 24 });
  assert.equal(isHittable(flush[1]!, flush), true);
  assert.equal(isFullyRevealed(flush, flush[1]!), true);

  const past = tree(port, { depth: 1, id: "row", x: 0, y: 77, width: 100, height: 24 });
  assert.equal(isFullyRevealed(past, past[1]!), false);
  assert.equal(isHittable(past[1]!, past), true);

  const halfPast = tree(port, { depth: 1, id: "row", x: 0, y: 88, width: 100, height: 24 });
  assert.equal(isHittable(halfPast[1]!, halfPast), false);

  const onTheLine = tree(port, { depth: 1, id: "row", x: 0, y: 90, width: 100, height: 20 });
  assert.equal(aimPoint(onTheLine[1]!).y, 100);
  assert.equal(isHittable(onTheLine[1]!, onTheLine), false, "y = 100 is past a 0..100 viewport");
});

test("nested viewports all clip, and the inner one is named as the immediate cause", () => {
  const elements = tree(
    { depth: 0, id: "outer", x: 0, y: 0, width: 200, height: 100, overflowVisible: false },
    { depth: 1, id: "inner", x: 0, y: 0, width: 200, height: 60, overflowVisible: false },
    { depth: 2, id: "row", x: 0, y: 62, width: 200, height: 20 },
  );
  const row = elements[2]!;
  assert.deepEqual(clipBox(elements, row), { x: 0, y: 0, width: 200, height: 60 });
  assert.equal(isHittable(row, elements), false);
  assert.match(clipReason(elements, row)!, /clipped by inner: viewport y 0\.\.60/);
});

test("a row inside a viewport that is itself scrolled away is clipped by the outer one", () => {
  const elements = tree(
    { depth: 0, id: "outer", x: 0, y: 0, width: 200, height: 100, overflowVisible: false },
    { depth: 1, id: "inner", x: 0, y: 140, width: 200, height: 60, overflowVisible: false },
    { depth: 2, id: "row", x: 0, y: 150, width: 200, height: 20 },
  );
  const row = elements[2]!;
  assert.equal(clipBox(elements, row)!.height <= 0, true);
  assert.equal(visibleBox(elements, row), undefined);
  assert.equal(isHittable(row, elements), false);
  assert.match(clipReason(elements, row)!, /clipped by outer: viewport y 0\.\.100/);
});

test("elementsAtPoint answers with what is painted at a pixel, clipping included", () => {
  const elements = tree(
    { depth: 0, id: "port", x: 0, y: 0, width: 100, height: 100, overflowVisible: false },
    { depth: 1, id: "visible.row", x: 0, y: 40, width: 100, height: 20 },
    { depth: 1, id: "scrolled.row", x: 0, y: 140, width: 100, height: 20 },
  );
  assert.deepEqual(elementsAtPoint(elements, { x: 50, y: 50 }).map((e) => e.id), ["port", "visible.row"]);
  assert.deepEqual(elementsAtPoint(elements, { x: 50, y: 150 }).map((e) => e.id), []);
});

test("ancestorsOf walks the preorder dump back up by depth, outermost first", () => {
  const elements = NAV_SCROLLER();
  assert.deepEqual(
    ancestorsOf(elements, elements.at(-1)!).map((e) => e.depth),
    [0, 1, 2, 3, 4],
  );
  assert.deepEqual(
    clippingAncestors(elements, elements.at(-1)!).map((e) => e.depth),
    [3],
  );
});
