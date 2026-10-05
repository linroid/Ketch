/** Short-lived request metadata used only to avoid handing browser-owned operations to Ketch. */
export class RequestContext {
  #entries = new Map();
  constructor(now = Date.now) { this.now = now; }

  observe(details) {
    const now = this.now();
    for (const [id, entry] of this.#entries) {
      if (now - entry.at > 30_000) this.#entries.delete(id);
    }
    this.#entries.set(details.requestId, {
      url: details.url, method: details.method, at: now,
      pageUrl: details.documentUrl || details.originUrl || details.initiator,
    });
    while (this.#entries.size > 512) this.#entries.delete(this.#entries.keys().next().value);
  }

  forDownload(item) {
    const matches = [...this.#entries.values()].filter((entry) =>
      this.now() - entry.at <= 30_000 && [item.url, item.finalUrl].includes(entry.url));
    // Ambiguous GET/POST traffic is left in the browser instead of replayed as a GET.
    const entry = matches.find((it) => it.method !== 'GET') ?? matches.at(-1);
    return entry ? { ...item, requestMethod: entry.method, pageUrl: entry.pageUrl } : item;
  }
}
