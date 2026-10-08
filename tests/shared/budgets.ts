
const MINUTES = 60_000;

export const BUDGET = {
  quick: 15 * MINUTES,
  standard: 20 * MINUTES,
  slow: 25 * MINUTES,
  heavy: 30 * MINUTES,
  bench: 40 * MINUTES,
  promo: 60 * MINUTES,
  /** Launch and corpus replay around a fuzz campaign; the campaign's own time (FUZZ_MINUTES) comes on top. */
  fuzz: 10 * MINUTES,
} as const;

export const DEFAULT_BUDGET = BUDGET.standard;
