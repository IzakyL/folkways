
const MINUTES = 60_000;

export const BUDGET = {
  quick: 15 * MINUTES,
  standard: 20 * MINUTES,
  slow: 25 * MINUTES,
  heavy: 30 * MINUTES,
  bench: 60 * MINUTES,
  promo: 60 * MINUTES,
} as const;

export const DEFAULT_BUDGET = BUDGET.standard;
