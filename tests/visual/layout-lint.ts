import { type Element, type PanelHolder, readPanel, ancestorsOf, clippingAncestors, LDLIB2_SYMBOLS, symbolPath } from "../ldlib2";
import { reflect, type MinecraftClient } from "@izakyl/blockwright-minecraft";
import { PANEL_ROUTES, contentBox } from "../shared/bw-helpers";

const MINECRAFT = "net.minecraft.client.Minecraft";

const ONE_GLYPH = 4;

const TEXT_SLACK = 2;

const SINGLE_LINE = 12;

const EDGE_SLACK = 1;

const MIN_FILL = 0.6;

const WINDOW_PREFIX = "folkways.window.";

/** Rows.WIDE_SCROLL: a scroll view whose content is reached by scrolling sideways. */
const WIDE_SCROLL = "folkways_wide_scroll";

const CHILDREN = "getChildren()";

/** How many times lint re-reads when the panel changed between its element read and its text-wrap read. */
const WRAP_READS = 3;

const ROLLING = new Set(["ROLL", "HOVER_ROLL", "WRAP"]);

const TEXTUAL = [".Label", ".TextElement"];

/** A panel element plus its text wrap mode (LDLib2 TextWrap name), when it has a text style. */
type Linted = Element & { wrap?: string };

function draws(element: Element): boolean {
  return TEXTUAL.some((kind) => element.className.endsWith(kind));
}

export type Severity = "error" | "warn";

export type Rect = { x: number; y: number; width: number; height: number };

export type Finding = {
  rule: string;
  severity: Severity;
  id: string | null;
  className: string;
  text: string | null;
  rect: Rect;
  detail: string;
};

export type LintOptions = {
  allow?: string[];
  within?: string;
  skip?: string[];
};

export type LintReport = {
  scope: string;
  elements: number;
  screen: { width: number; height: number };
  filled: number | null;
  findings: Finding[];
  errors: number;
  geometry: Array<{ id: string | null; text: string | null; className: string; depth: number } & Rect>;
};

export class LayoutProbe {
  readonly #client: MinecraftClient;
  readonly #widths = new Map<string, number>();
  #screen: { width: number; height: number } | null = null;

  constructor(client: MinecraftClient) {
    this.#client = client;
  }

  async measure(text: string): Promise<number> {
    const cached = this.#widths.get(text);
    if (cached !== undefined) {
      return cached;
    }
    const result = await reflect.invoke(this.#client, {
      target: { kind: "static", className: MINECRAFT },
      path: "getInstance().font",
      method: "width",
      args: [text],
      argTypes: ["java.lang.String"],
      limits: { maxDepth: 1, maxFields: 1, maxNodes: 8 },
    });
    const width = numberOrNull(result);
    if (width === null) {
      throw new Error(
        `Could not measure the font width of ${JSON.stringify(text)}: ${MINECRAFT}.getInstance().font.width(String) returned no number: `
          + `${JSON.stringify(result).slice(0, 300)}. Without it, "text does not fit" can only guess 6px per glyph, `
          + `but CJK and narrow glyphs are not 6px, and a failure built on a guess is one nobody trusts.`,
      );
    }
    this.#widths.set(text, width);
    return width;
  }

  async screen(): Promise<{ width: number; height: number }> {
    if (this.#screen) {
      return this.#screen;
    }
    const read = async (method: string) => {
      const result = await reflect.invoke(this.#client, {
        target: { kind: "static", className: MINECRAFT },
        path: "getInstance().getWindow()",
        method,
        limits: { maxDepth: 1, maxFields: 1, maxNodes: 8 },
      });
      const value = numberOrNull(result);
      if (value === null) {
        throw new Error(`Could not get Window.${method}(): ${JSON.stringify(result).slice(0, 200)}`);
      }
      return value;
    };
    this.#screen = { width: await read("getGuiScaledWidth"), height: await read("getGuiScaledHeight") };
    return this.#screen;
  }

  /**
   * The open panel's elements, each carrying its text wrap mode. LDLib2's element read has no text style, so
   * the wraps come from a reflect query over the same tree (same holder route, same children, preorder,
   * nulls skipped), joined by preorder index. Each pair must agree on class and id; when the panel changed
   * between the two reads they do not, and both are read again.
   */
  async elements(): Promise<Linted[]> {
    let mismatch = "";
    for (let attempt = 0; attempt < WRAP_READS; attempt++) {
      const read = await readPanel(this.#client);
      if (read.truncated) {
        throw new Error(
          `the LDLib2 element tree was cut short at ${read.elements.length} elements (maxNodes / maxDepth); `
            + "a lint over a partial tree would miss whatever was cut",
        );
      }
      const nodes = await this.wraps(read.holder);
      mismatch = misaligned(read.elements, nodes);
      if (!mismatch) {
        return read.elements.map((element, i) => {
          const wrap = nodes[i].values?.wrap;
          return typeof wrap === "string" ? { ...element, wrap } : element;
        });
      }
    }
    throw new Error(`The panel kept changing between the element read and the text-wrap read: ${mismatch}`);
  }

  /** The panel's elements in preorder, as a reflect query reads them, each with its wrap and id. */
  async wraps(holder: PanelHolder): Promise<reflect.QueryNode[]> {
    const result = await reflect.query(this.#client, {
      root: { kind: "static", className: MINECRAFT, path: symbolPath(PANEL_ROUTES[holder]) },
      select: { wrap: "getTextStyle().textWrap().name()", id: ELEMENT_ID },
      expand: { path: CHILDREN },
      limits: { maxDepth: 24, maxNodes: 8_000, maxItems: 500 },
    });
    const nodes: reflect.QueryNode[] = [];
    if (result.found !== false && result.tree) {
      preorder(result.tree, nodes);
    }
    return nodes;
  }

  async lint(scope: string, options: LintOptions = {}): Promise<LintReport> {
    const all = await this.elements();
    const screen = await this.screen();
    const scoped = options.within ? subtree(all, options.within) : all;
    const elements = onScreen(scoped);
    const skip = new Set(options.skip ?? []);
    const findings: Finding[] = [];

    for (const element of elements) {
      for (const rule of RULES) {
        if (skip.has(rule.name)) {
          continue;
        }
        if (rule.name === "text-clipped" && ROLLING.has(element.wrap ?? "")) {
          continue;
        }
        const detail = await rule.check(element, elements, screen, this);
        if (detail) {
          findings.push(note(rule.name, rule.severity, element, detail));
        }
      }
    }

    const fill = skip.has("dead-width")
      ? { finding: null, filled: null }
      : deadWidth(elements);
    if (fill.finding) {
      findings.push(fill.finding);
    }

    const allowed = options.allow ?? [];
    for (const finding of findings) {
      if (finding.id && allowed.some((pattern) => globMatches(pattern, finding.id!))) {
        finding.severity = "warn";
        finding.detail += " (on the allow list, downgraded to a note)";
      }
    }

    return {
      scope,
      elements: elements.length,
      screen,
      filled: fill.filled,
      findings,
      errors: findings.filter((finding) => finding.severity === "error").length,
      geometry: elements.map((element) => ({
        id: element.id || null,
        text: element.text || null,
        className: short(element.className),
        depth: element.depth,
        x: round(element.x),
        y: round(element.y),
        width: round(element.width),
        height: round(element.height),
      })),
    };
  }
}

type Rule = {
  name: string;
  severity: Severity;
  check(
    element: Element,
    all: Element[],
    screen: { width: number; height: number },
    probe: LayoutProbe,
  ): Promise<string | null>;
};

const RULES: Rule[] = [
  {
    name: "label-collapsed",
    severity: "error",
    async check(element) {
      if (!draws(element) || !element.text || element.width >= ONE_GLYPH) {
        return null;
      }
      return `Element has text ${JSON.stringify(element.text)} but is only ${round(element.width)}px wide: `
        + `not even one glyph fits (the narrowest needs ${ONE_GLYPH}px). Most likely flexGrow + minWidth(0) sits in a `
        + `parent with indefinite width, so flex-basis resolves to 0 and it collapses to nothing.`;
    },
  },
  {
    name: "text-clipped",
    severity: "error",
    async check(element, _all, _screen, probe) {
      if (!draws(element) || !element.text || element.width < ONE_GLYPH
          || element.height > SINGLE_LINE) {
        return null;
      }
      const needed = await probe.measure(element.text);
      if (needed <= element.width + TEXT_SLACK) {
        return null;
      }
      return `Text ${JSON.stringify(element.text)} needs ${needed}px, but the element is only ${round(element.width)}px, `
        + `so it renders truncated.`;
    },
  },
  {
    name: "zero-size",
    severity: "warn",
    async check(element) {
      if (element.width >= 1 && element.height >= 1) {
        return null;
      }
      return `Visible element is ${round(element.width)}x${round(element.height)}: it is in the tree but not on screen.`;
    },
  },
  {
    name: "h-overflow",
    severity: "error",
    async check(element, all) {
      const reachable = sidewaysReachable(all, element);
      for (const parent of clippingAncestors(all, element)) {
        if (reachable.has(parent)) {
          continue;
        }
        const clip = contentBox(parent);
        const overshoot = Math.max(
          clip.x - element.x,
          element.x + element.width - (clip.x + clip.width),
        );
        if (overshoot > EDGE_SLACK) {
          return `Overflows the content clip of ${describe(parent)} horizontally by ${round(overshoot)}px: `
            + `the overflow can be neither seen nor clicked.`;
        }
      }
      return null;
    },
  },
  {
    name: "offscreen",
    severity: "error",
    async check(element, _all, screen) {
      if (element.width < 1 || element.height < 1) {
        return null;
      }
      const out = Math.max(
        -element.x,
        -element.y,
        element.x + element.width - screen.width,
        element.y + element.height - screen.height,
      );
      if (out <= EDGE_SLACK) {
        return null;
      }
      return `${round(out)}px falls outside the ${screen.width}x${screen.height} screen.`;
    },
  },
];

function fillOf(elements: Element[]): { window: Element; filled: number } | null {
  const frame = elements
    .filter((element) => (element.id ?? "").startsWith(WINDOW_PREFIX) && element.width > 0)
    .sort((a, b) => b.width - a.width)[0];
  if (!frame) {
    return null;
  }
  let left = Infinity;
  let right = -Infinity;
  for (let i = frame.index + 1; i < elements.length && elements[i].depth > frame.depth; i++) {
    const element = elements[i];
    if (element.width < 1 || element.height < 1 || element.className.endsWith(".UIElement")) {
      continue;
    }
    left = Math.min(left, element.x);
    right = Math.max(right, element.x + element.width);
  }
  if (!Number.isFinite(left) || !Number.isFinite(right)) {
    return null;
  }
  return { window: frame, filled: (right - left) / frame.width };
}

function deadWidth(elements: Element[]): { finding: Finding | null; filled: number | null } {
  const measured = fillOf(elements);
  if (!measured) {
    return { finding: null, filled: null };
  }
  const { window: frame, filled } = measured;
  if (filled >= MIN_FILL) {
    return { finding: null, filled: round(filled) };
  }
  return {
    filled: round(filled),
    finding: {
      rule: "dead-width",
      severity: "warn",
      id: frame.id || null,
      className: short(frame.className),
      text: null,
      rect: { x: round(frame.x), y: round(frame.y), width: round(frame.width), height: round(frame.height) },
      detail: `This window is ${round(frame.width)}px wide, but visible content only fills `
        + `${(filled * 100).toFixed(0)}% (minimum ${(MIN_FILL * 100).toFixed(0)}%): the right side is dead space. `
        + `Usually a row/table container lacks widthPercent(100), or a scroll view's viewPort does not stretch content to the viewport width.`,
    },
  };
}

function subtree<T extends Element>(all: T[], id: string): T[] {
  const root = all.find((element) => element.id === id);
  if (!root) {
    throw new Error(
      `lint's within points at ${id}, but the panel has no such id. It had: `
        + JSON.stringify(all.map((element) => element.id).filter(Boolean).slice(0, 40)),
    );
  }
  const out = [root];
  for (let i = root.index + 1; i < all.length && all[i].depth > root.depth; i++) {
    out.push(all[i]);
  }
  return out;
}

const ELEMENT_ID = symbolPath(LDLIB2_SYMBOLS.filter((symbol) => symbol.as === "element.id"));

function preorder(node: reflect.QueryNode | null | undefined, into: reflect.QueryNode[]): void {
  if (!node) {
    return;
  }
  into.push(node);
  for (const child of node.children ?? []) {
    preorder(child, into);
  }
}

/** Why the element read and the reflect read do not describe the same tree, or "" when they do. */
function misaligned(elements: Element[], nodes: reflect.QueryNode[]): string {
  if (nodes.length !== elements.length) {
    return `${elements.length} elements, but the wrap query walked ${nodes.length} nodes`;
  }
  for (let i = 0; i < elements.length; i++) {
    const node = nodes[i];
    const id = node.values?.id;
    const nodeId = typeof id === "string" && id !== "" ? id : undefined;
    const elementId = elements[i].id || undefined;
    if (node.class !== elements[i].className || nodeId !== elementId) {
      return `element ${i} is ${describe(elements[i])}, but the wrap query found ${node.class}${nodeId ? `#${nodeId}` : ""}`;
    }
  }
  return "";
}

function numberOrNull(result: unknown): number | null {
  try {
    return reflect.number(result);
  } catch {
    return null;
  }
}

function shown(element: Element): boolean {
  return element.displayed && element.visible && element.opacity > 0;
}

/**
 * The clips an element may overflow sideways because it sits in a wide scroll view (Rows.wideBox): that
 * scroll view, everything around it, and its viewPort and viewContainer (the two levels LDLib2's
 * ScrollerView puts between itself and its content). Clips deeper inside the content still count.
 */
function sidewaysReachable(all: Element[], element: Element): Set<Element> {
  const chain = ancestorsOf(all, element);
  let scroller = -1;
  chain.forEach((one, at) => {
    if (one.classes?.includes(WIDE_SCROLL)) scroller = at;
  });
  return new Set(scroller < 0 ? [] : chain.slice(0, scroller + 3));
}

function onScreen(elements: Linted[]): Linted[] {
  const out: Linted[] = [];
  const stack: Array<{ depth: number; shown: boolean }> = [];
  for (const element of elements) {
    while (stack.length > 0 && stack[stack.length - 1].depth >= element.depth) {
      stack.pop();
    }
    const parentShown = stack.length === 0 || stack[stack.length - 1].shown;
    const here = parentShown && shown(element);
    stack.push({ depth: element.depth, shown: here });
    if (here) {
      out.push({ ...element, index: out.length });
    }
  }
  return out;
}

function note(rule: string, severity: Severity, element: Element, detail: string): Finding {
  return {
    rule,
    severity,
    id: element.id || null,
    className: short(element.className),
    text: element.text || null,
    rect: { x: round(element.x), y: round(element.y), width: round(element.width), height: round(element.height) },
    detail,
  };
}

function describe(element: Element): string {
  return element.id ? `${short(element.className)}#${element.id}` : short(element.className);
}

function short(className: string): string {
  return className.slice(className.lastIndexOf(".") + 1);
}

function round(value: number): number {
  return Math.round(value * 10) / 10;
}

function globMatches(pattern: string, value: string): boolean {
  const regex = new RegExp(
    "^" + pattern.split("*").map((part) => part.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")).join(".*") + "$",
  );
  return regex.test(value);
}

export function formatReport(report: LintReport): string {
  if (report.findings.length === 0) {
    return `${report.scope}: ${report.elements} elements, layout clean`;
  }
  const lines = report.findings.map((finding) => {
    const where = `${finding.rule} [${finding.severity}] ${finding.id ?? finding.className}`;
    const rect = `@${finding.rect.x},${finding.rect.y} ${finding.rect.width}×${finding.rect.height}`;
    return `  · ${where} ${rect}\n    ${finding.detail}`;
  });
  return `${report.scope}: ${report.elements} elements, ${report.errors} errors / `
    + `${report.findings.length - report.errors} notes\n${lines.join("\n")}`;
}
