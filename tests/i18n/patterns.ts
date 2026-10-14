// Built-in patterns keep language dictionaries as string literals. Read them without executing world queries.
function balanced(source: string, start: number): string {
  let depth = 0, quote = "", escaped = false;
  for (let at = start; at < source.length; at++) {
    const char = source[at];
    if (quote) {
      if (escaped) escaped = false;
      else if (char === "\\") escaped = true;
      else if (char === quote) quote = "";
    } else if (char === '"' || char === "'") quote = char;
    else if ("([{".includes(char)) depth++;
    else if (")]}".includes(char) && --depth === 0) return source.slice(start, at + 1);
  }
  throw new Error("unclosed pattern expression");
}

function splitArguments(source: string): string[] {
  const args: string[] = [];
  let from = 0;
  for (let at = 0; at < source.length; at++) {
    const char = source[at];
    if ("([{".includes(char)) at += balanced(source, at).length - 1;
    else if (char === '"' || char === "'") {
      const quote = char;
      for (at++; at < source.length; at++) {
        if (source[at] === "\\") at++;
        else if (source[at] === quote) break;
      }
    } else if (char === ",") {
      args.push(source.slice(from, at).trim());
      from = at + 1;
    }
  }
  const last = source.slice(from).trim();
  if (last) args.push(last);
  return args;
}

export function calls(source: string, name: string): { index: number; args: string[] }[] {
  return [...source.matchAll(new RegExp(`\\b${name}\\s*\\(`, "g"))].map(match => {
    const start = source.indexOf("(", match.index);
    return { index: match.index!, args: splitArguments(balanced(source, start).slice(1, -1)) };
  });
}

export function patternWords(source: string): Record<string, Record<string, string>> | undefined {
  const match = /^lang\s*=\s*\{/m.exec(source);
  if (!match) return undefined;
  const start = source.indexOf("{", match.index);
  // Starlark allows trailing commas; skip quoted strings while removing them.
  const json = balanced(source, start).replace(/("(?:\\.|[^"\\])*")|,(\s*[}\]])/g,
    (_, quoted, closing) => quoted ?? closing);
  return JSON.parse(json);
}
