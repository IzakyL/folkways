/** Splits a population into humans and the three kinds of golem; `golemShare` of it is golems, none at 0. */
export function distributePopulation(total: number, golemShare = 0.2) {
  if (!Number.isSafeInteger(total) || total < 1) throw new Error("Population must be a positive integer");
  if (!(golemShare >= 0 && golemShare < 1)) throw new Error("The golems' share of the population must be from 0 to under 1");
  const golems = Math.round(total * golemShare);
  const metal = Math.floor(golems * 0.4), humanoid = Math.floor(golems * 0.4);
  return { total, humans: total - golems, metal, humanoid, dog: golems - metal - humanoid };
}

export function populationSlots(total: number, golemShare = 0.2) {
  const population = distributePopulation(total, golemShare);
  const names = [...Array(population.metal).fill("metal"), ...Array(population.humanoid).fill("humanoid"), ...Array(population.dog).fill("dog")];
  const golems = names.map((kind, i) => ({ kind, index: Math.floor((i + 0.5) * total / names.length) }));
  const occupied = new Set(golems.map(g => g.index));
  return { ...population, golems, humanIndices: Array.from({ length: total }, (_, i) => i).filter(i => !occupied.has(i)) };
}
