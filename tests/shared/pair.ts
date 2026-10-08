import { crashAwareRetry, type InstanceLaunchOptions } from "@izakyl/blockwright-client";
import { ldlib2Adapter } from "../ldlib2";
import { launchGame, ui, type Game, type MinecraftClient, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { attachFailureEvidence } from "@izakyl/blockwright-test";
import type { UiHost } from "@izakyl/blockwright-ui";
import type { TestInfo } from "@playwright/test";
import { REPO_ROOT } from "../out-paths";
import { isSeeded } from "./mod-settings";

export interface SnapshotPos {
  x: number;
  y: number;
  z: number;
}

export interface SnapshotTargets {
  containers?: Array<SnapshotPos | undefined>;
  entityTypes?: string[];
}

export interface PairOptions {
  config?: string;
  server: InstanceLaunchOptions;
  client: InstanceLaunchOptions;
  readyTimeoutMs?: { server?: number; client?: number };
  snapshot?: SnapshotTargets | (() => SnapshotTargets);
}

export interface E2EPair {
  server: MinecraftServer;
  client: MinecraftClient;
  game: Game;
  /** Vanilla screens plus LDLib2 panels; failure evidence reads the open screen through it. */
  ui: UiHost<MinecraftClient, ui.Probe>;
  failing(error: unknown): void;
  teardown(testInfo?: TestInfo): Promise<void>;
}

const DEFAULT_CONFIG = "blockwright.toml";

const LAUNCH_RETRY = crashAwareRetry(2);

function reuseSeeded(options: InstanceLaunchOptions): InstanceLaunchOptions {
  return isSeeded(options.instance) ? { reuseInstanceDir: true, ...options } : options;
}

export async function launchPair(options: PairOptions): Promise<E2EPair> {
  const game = await launchGame({
    cwd: REPO_ROOT,
    config: options.config ?? DEFAULT_CONFIG,
    server: reuseSeeded(options.server),
    client: reuseSeeded(options.client),
    readyTimeoutMs: options.readyTimeoutMs,
    retry: LAUNCH_RETRY,
  });
  const host = ui.host([ldlib2Adapter]);

  let done = false;
  let failure: unknown;
  return {
    server: game.server,
    client: game.client,
    game,
    ui: host,
    failing(error: unknown) {
      failure = error;
    },
    async teardown(testInfo?: TestInfo) {
      if (done) {
        return;
      }
      done = true;
      try {
        if (testInfo) {
          let targets: SnapshotTargets | undefined;
          try {
            targets = typeof options.snapshot === "function" ? options.snapshot() : options.snapshot;
          } catch (error) {
            await testInfo.attach("failure-snapshot-targets-error.txt", {
              body: String(error), contentType: "text/plain",
            });
          }
          await attachFailureEvidence(testInfo, game, {
            ...(failure === undefined ? {} : { error: failure }),
            ui: host,
            containers: targets?.containers ?? [],
            entityTypes: targets?.entityTypes ?? [],
          });
        }
      } finally {
        await game.close();
      }
    },
  };
}
