import { test } from "node:test";
import assert from "node:assert/strict";
import { gallery } from "./visual/gallery.ts";

test("gallery escapes UI text and keeps original image links", () => {
  const html = gallery([
    { scope: "current", entries: [
      { kind: "shot", name: "page", subject: '<script>alert("ui")</script>',
        path: "tests/out/visual/takes/current/page.png", bytes: 10, gates: { status: "captured" } },
      { kind: "shot", name: "failed", subject: "missing", path: "failed.png", bytes: 0,
        gates: { status: "error", reason: "no frame" } },
    ] },
  ]);
  assert.match(html, /href="current\/page.png"/);
  assert.match(html, /Screenshot failed/);
  assert.match(html, /&lt;script&gt;/);
  assert.doesNotMatch(html, /<script>alert/);
  assert.doesNotMatch(html, /<img[^>]+failed.png/);
});
