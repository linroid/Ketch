You are implementing an “AI Resource Finder” agent in Kotlin using the Koog framework.
The agent helps users discover downloadable files from the internet and from a user-defined site allowlist,
while primarily protecting users from device-harmful downloads.

Mission
- Accept a user request describing a resource to find.
- Plan: understand what the resource is, expected file types, and likely official sources.
- Search the web or within a customized website list.
- Iteratively open relevant pages and extract downloadable file links.
- Show every step to the user (a visible trace).
- Output a final list of downloadable files with basic metadata.
- Apply a DEVICE SAFETY filter to avoid dangerous/suspicious files.
- Apply a MINIMAL abuse guardrail: do not assist with clearly illegal/pirated acquisition; redirect to official sources.

Tools you can call (implement as Koog tools)
1) searchWeb(query, maxResults) -> [{title, url, snippet, source}]
2) searchSites(allowlist, query, maxResults) -> same, restricted to user sites
3) fetchPage(url) -> {finalUrl, status, contentType, text, links:[{text,url}]}
4) headUrl(url) -> {finalUrl, status, contentType, contentLength, lastModified, etag}
5) extractDownloads(pageText, baseUrl) -> [{url, anchorText, surroundingText}]
6) validateUrl(url, allowlist) -> {ok, reason, isPrivateNet, schemeOk, domainOk}
7) emitStep(title, details) -> void (stream steps to user)

Network safety constraints (MUST)
- Block localhost/private network SSRF targets and file://
- Timeout + max bytes for fetchPage
- Rate limiting and page fetch budgets
- Do not auto-download; only list candidates

User input
- userQuery: String
- allowlistSites: List<String> optional
- desiredFileTypes: List<String> optional
- maxCandidates: Int default 10

Step trace requirements
Use emitStep() to show:
- Understanding & assumptions
- Plan (3–6 steps)
- Queries used
- Pages opened and why
- Which candidates were accepted/rejected and why
- Stopping reason

PRIMARY FILTER: Device safety (what “safe only” means)
Only list files that are unlikely to be harmful to a user’s device.
Apply these heuristics (explain rejections in steps):
- Prefer official domains, reputable hosts, HTTPS
- Avoid URL shorteners and “free-download” aggregators
- Avoid downloads that require running unknown installers
- High-risk extensions require strong trust:
  - .exe/.msi/.dmg/.pkg/.apk are allowed ONLY if from official vendor/project release pages or well-known trusted distribution channels
- Reject suspicious signals:
  - “crack/keygen/patch/loader” contexts (often malware)
  - password-protected archives from untrusted sources
  - mismatched content-type vs extension
  - multiple redirects to ad/locker domains
  - unknown file hosts with aggressive popups/redirect patterns (if detectable)
- Prefer sources that provide checksums/signatures (bonus)

MINIMAL guardrail (still required)
- If the user explicitly requests pirated/illegal content (e.g., copyrighted movies/software cracks),
  do NOT provide direct download links. Instead:
  - explain you can’t help obtain illegal copies
  - provide official purchase/download pages or legitimate alternatives
    This guardrail should be narrow; do not over-block normal open-source or legitimate resources.

Agent algorithm
1) Understand request
- Summarize what the user likely wants: expected deliverables, formats, keywords, synonyms.
- Ask up to 2 clarifying questions ONLY if absolutely necessary; otherwise proceed with best assumptions.
- emitStep("Understanding", ...)

2) Plan
- emitStep("Plan", ...)
- Include budgets and stopping conditions.

3) Discover
- If allowlistSites provided: prioritize searchSites() then fallback to searchWeb() if needed.
- For top results:
  - validateUrl -> fetchPage -> extractDownloads
  - Follow only a small number of relevant internal links within the same trusted domain
  - For each candidate download link:
    - validateUrl
    - headUrl to fetch metadata
    - score relevance and device safety

4) Score & filter
- Relevance score: matches file type, name keywords, version, platform, “release/assets” context.
- Device safety score: trust of domain, presence of checksum/signature, https, minimal redirects.
- Output ONLY candidates above a safety threshold.

5) Final output
   Return:
- A human-readable summary plus machine-parseable JSON:
  [
  {
  "name": "...",
  "url": "...",
  "fileType": "zip|pdf|apk|...",
  "sourcePageUrl": "...",
  "sizeBytes": 12345,
  "lastModified": "...",
  "description": "...",
  "confidence": 0.0-1.0,
  "deviceSafetyNotes": "why considered safe"
  }
  ]
  If none found:
- Explain what was tried and why candidates were rejected (device safety reasons),
- Provide safe next steps (official pages, docs).

Implementation notes (Koog)
- Use a planner step producing a structured plan object.
- Use an executor loop calling tools and emitting steps.
- Use a finalizer step that outputs strict JSON and validate it.
- NEVER let fetched page text directly cause downloads; user must confirm.

Now implement:
- Koog agent, tool interfaces, discovery loop with budgets, device-safety filtering, JSON output validation, and tests for validateUrl() + safety rules.
