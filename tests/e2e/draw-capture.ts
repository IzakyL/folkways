import type { media } from "@izakyl/blockwright-minecraft";

export type DrawCaptureResult = media.DrawCaptureResult;
export type DrawEntry = media.DrawEntry;
export type DrawColor = media.DrawRgba;
export type LineBox = Extract<DrawEntry, { kind: "lineBox" }>;
export type FilledBox = Extract<DrawEntry, { kind: "filledBox" }>;
export type DrawText = Extract<DrawEntry, { kind: "text" }>;
export type DrawItem = Extract<DrawEntry, { kind: "item" }>;
export type WorldText = Extract<DrawEntry, { kind: "worldText" }>;
export type WorldItem = Extract<DrawEntry, { kind: "worldItem" }>;

export function drawEntries(result: DrawCaptureResult): DrawEntry[] {
  return result.frames.flatMap((frame) => frame.entries);
}

export function lineBoxes(result: DrawCaptureResult): LineBox[] {
  return drawEntries(result)
    .filter((entry): entry is LineBox => entry.kind === "lineBox");
}

export function filledBoxes(result: DrawCaptureResult): FilledBox[] {
  return drawEntries(result)
    .filter((entry): entry is FilledBox => entry.kind === "filledBox");
}

export function drawTexts(result: DrawCaptureResult): DrawText[] {
  return drawEntries(result).filter((entry): entry is DrawText => entry.kind === "text");
}

export function drawItems(result: DrawCaptureResult): DrawItem[] {
  return drawEntries(result).filter((entry): entry is DrawItem => entry.kind === "item");
}

export function worldTexts(result: DrawCaptureResult): WorldText[] {
  return drawEntries(result).filter((entry): entry is WorldText => entry.kind === "worldText");
}

export function worldItems(result: DrawCaptureResult): WorldItem[] {
  return drawEntries(result).filter((entry): entry is WorldItem => entry.kind === "worldItem");
}

export function distanceTo(
  position: { x: number; y: number; z: number },
  anchor: readonly [number, number, number],
): number {
  return Math.hypot(position.x - anchor[0], position.y - anchor[1], position.z - anchor[2]);
}

export function summarizeBox(box: LineBox | FilledBox) {
  return {
    kind: box.kind,
    min: [round(box.min.x), round(box.min.y), round(box.min.z)],
    max: [round(box.max.x), round(box.max.y), round(box.max.z)],
    color: [round(box.color.r), round(box.color.g), round(box.color.b), round(box.color.a)],
  };
}

function round(value: number) {
  return Math.round(value * 1000) / 1000;
}

export function hueScale(
  color: DrawColor,
  rgb: readonly [number, number, number],
  tolerance = 0.002,
): number | null {
  let index = 0;
  for (let i = 1; i < 3; i++) {
    if (rgb[i] > rgb[index]) {
      index = i;
    }
  }
  const base = rgb[index];
  if (base <= 0) {
    return null;
  }
  const actual = [color.r, color.g, color.b][index];
  const scale = actual / base;
  if (!(scale > 0)) {
    return null;
  }
  const components = [color.r, color.g, color.b];
  for (let i = 0; i < 3; i++) {
    if (Math.abs(components[i] - rgb[i] * scale) > tolerance) {
      return null;
    }
  }
  return scale;
}

export function boxIsAt(
  box: LineBox | FilledBox,
  min: readonly [number, number, number],
  max: readonly [number, number, number],
  tolerance = 0.005,
): boolean {
  const actual = [box.min.x, box.min.y, box.min.z, box.max.x, box.max.y, box.max.z];
  const expected = [...min, ...max];
  return actual.every((value, i) => Math.abs(value - expected[i]) <= tolerance);
}

export function sleepMs(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
