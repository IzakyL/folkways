import assert from "node:assert/strict";
import test from "node:test";
import { busy, call, calls, emptyScene, pick, take, type Spot } from "./hotspots.ts";

const spot = (name: string, x: number, station?: string): Spot => ({ name, station, eye: { x, y: 90, z: 20 },
  target: { x, y: 70, z: 0 }, box: { minX: x - 10, maxX: x + 10, minZ: -10, maxZ: 10 } });
const FIELD = spot("field", 0), YARD = spot("yard", 100), CITY = spot("city", 200, "City");
const options = { taken: new Set<string>(), shown: new Map<string, number>(), wall: 0, least: 2 };

test("the spot with the most work in hand is picked, and ended work no longer counts", () => {
  const scene = emptyScene();
  take(scene, ["100,H", "100,W,STARTED,a,TillNode,0,70,0", "100,W,STARTED,b,TillNode,2,70,1", "100,W,STARTED,c,TillNode,100,70,0",
    "100,W,STARTED,d,PackCraftNode,101,70,0", "100,W,STARTED,e,PackCraftNode,99,70,0"]);
  assert.equal(pick([FIELD, YARD], scene, options)?.spot.name, "yard");
  take(scene, ["400,H", "400,W,FINISHED,c,TillNode,100,70,0", "400,W,FINISHED,d,PackCraftNode,101,70,0"]);
  assert.equal(pick([FIELD, YARD], scene, options)?.spot.name, "field");
  assert.deepEqual(busy(FIELD, scene).centre, { x: 1, y: 70, z: 0.5 });
});

test("a spot on the air is passed over, one shown a moment ago counts for less, and a quiet town gives none", () => {
  const scene = emptyScene();
  take(scene, ["100,W,STARTED,a,TillNode,0,70,0", "100,W,STARTED,b,TillNode,2,70,1", "100,W,STARTED,f,TillNode,3,70,1",
    "100,W,STARTED,c,TillNode,100,70,0", "100,W,STARTED,d,TillNode,101,70,0", "400,H"]);
  assert.equal(pick([FIELD, YARD], scene, options)?.spot.name, "field");
  assert.equal(pick([FIELD, YARD], scene, { ...options, taken: new Set(["field"]) })?.spot.name, "yard");
  assert.equal(pick([FIELD, YARD], scene, { ...options, shown: new Map([["field", 0]]) })?.spot.name, "yard");
  assert.equal(pick([FIELD, YARD], emptyScene(), options), undefined);
});

test("a train's call is followed from its way in to after it has gone, and trains are listed nearest first", () => {
  const scene = emptyScene();
  take(scene, ["100,T,t1,Mine,,0,0,0", "100,T,t2,City,,0,0,0", "200,T,t1,,City,400,0.5,2", "200,T,t2,,Mine,300,0.5,0"]);
  assert.deepEqual(calls([FIELD, CITY, spot("mine", 300, "Mine")], scene).map((c) => [c.spot.name, c.train, c.distance]),
    [["mine", "t2", 300], ["city", "t1", 400]]);
  assert.deepEqual(calls([FIELD], scene), []);
  assert.deepEqual(call(scene, "t1", "City"), { state: "coming", distance: 400 });
  assert.deepEqual(call(scene, "t1", "Mine"), { state: "gone", ticks: 0 });
  take(scene, ["1100,T,t1,City,,0,0,2"]);
  assert.deepEqual(call(scene, "t1", "City"), { state: "standing" });
  assert.deepEqual(calls([CITY], scene), []);
  take(scene, ["1300,T,t1,,Mine,500,0.3,0", "1400,H"]);
  assert.deepEqual(call(scene, "t1", "City"), { state: "gone", ticks: 100 });
  assert.equal(call(scene, "t9", "City").state, "gone");
});

test("a spot counts for less the more stand idle on it, and not at all with more idle than at work", () => {
  const scene = emptyScene();
  take(scene, ["100,H", "100,W,STARTED,a,TillNode,0,70,0", "100,W,STARTED,b,TillNode,2,70,1",
    "100,W,STARTED,c,PackCraftNode,100,70,0", "100,W,STARTED,d,PackCraftNode,101,70,0", "100,W,STARTED,e,PackCraftNode,99,70,0",
    "100,I,100,70,2", "100,I,102,70,2", "100,I,98,70,-3"]);
  assert.equal(busy(YARD, scene).idle, 3);
  assert.equal(pick([FIELD, YARD], scene, options)?.spot.name, "field");
  take(scene, ["120,H"]);
  assert.equal(pick([FIELD, YARD], scene, options)?.spot.name, "yard");
  take(scene, ["140,H", ...Array.from({ length: 6 }, (_, i) => `140,I,${98 + i},70,0`), "140,I,0,70,0"]);
  assert.equal(pick([YARD], scene, options), undefined);
});

test("a haul counts as it begins and not while it is carried, and a spot beside the one on the air is passed over", () => {
  const scene = emptyScene();
  take(scene, ["100,W,STARTED,h1,TransferNode,0,70,0", "100,W,STARTED,h2,TransferNode,0,70,0", "100,H"]);
  assert.equal(busy(FIELD, scene).score, 1);
  take(scene, ["400,H", "400,W,STARTED,a,TillNode,0,70,0", "400,W,STARTED,b,TillNode,1,70,0", "400,W,STARTED,c,TillNode,2,70,0"]);
  assert.equal(busy(FIELD, scene).score, 4.5);
  assert.equal(pick([FIELD, spot("beside", 20)], scene, { ...options, taken: new Set(["beside"]) }), undefined);
});
