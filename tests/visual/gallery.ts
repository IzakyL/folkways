import path from "node:path";

export function gallery(docs: any[]): string {
  const escape = (value: unknown) => String(value ?? "").replace(/[&<>"']/g,
    char => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[char]!);
  let total = 0;
  const sections = docs.map(doc => {
    const cards = (doc.entries ?? []).filter(entry => entry.kind === "shot").map(entry => {
      total++;
      const href = [doc.scope, path.basename(entry.path)].map(encodeURIComponent).join("/");
      const captured = entry.gates?.status === "captured" && entry.bytes > 0;
      return `<figure data-search="${escape(`${doc.scope} ${entry.name} ${entry.subject}`)}">
        <figcaption><strong>${escape(entry.name)}</strong><p>${escape(entry.subject)}</p>
        <small>${escape(captured ? "Image captured" : "Screenshot failed")} · ${escape(doc.generated_at)}</small></figcaption>
        ${captured ? `<a href="${href}" target="_blank"><img loading="lazy" src="${href}" alt="${escape(entry.subject)}"></a>` : `<pre>${escape(entry.gates?.reason)}</pre>`}
      </figure>`;
    }).join("");
    return `<section><h2>${escape(doc.scope)}</h2>${cards || "<p>No images</p>"}</section>`;
  }).join("");
  return `<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
    <title>Folkways UI images</title><style>
    body{margin:0;background:#172126;color:#f0eee6;font:15px system-ui}header{position:sticky;top:0;background:#202b30;padding:16px;z-index:1}
    h1{font-size:22px;margin:0 0 8px}input{padding:8px;width:min(90%,480px)}main{padding:16px}section{display:grid;grid-template-columns:repeat(auto-fit,minmax(min(100%,520px),1fr));gap:16px}
    h2{grid-column:1/-1}figure{margin:0;background:#26363b;border:1px solid #63736e;overflow:hidden}figcaption{padding:12px;overflow-wrap:anywhere}p{margin:6px 0}small{color:#b8c5be}img{width:100%;display:block}figure[hidden]{display:none}
    </style><header><h1>Folkways UI · ${total} images</h1><p>Images only; visuals are reviewed by a person.</p>
    <input id="filter" placeholder="Search pages, windows or states" aria-label="Search images"></header><main>${sections}</main>
    <script>document.getElementById('filter').addEventListener('input',event=>{const q=event.target.value.toLowerCase();document.querySelectorAll('figure').forEach(card=>card.hidden=!card.dataset.search.toLowerCase().includes(q));});</script></html>`;
}
