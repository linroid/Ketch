// Numbers the film states about Ketch. The values live in facts.json: `npm run facts` refreshes
// the release and the download sizes from the latest GitHub release; the other fields are edited
// by hand when they change (see README.md, "Update numbers after a release"). Copy interpolates
// these values; it never repeats them as literals.
import factsJson from './facts.json';

/** Download size labels as GitHub shows them (binary units), e.g. "89 KB", "33–39 MB". */
export interface Sizes {
  /** Zipped browser extension. */
  extension: string;
  /** Android APK. */
  android: string;
  /** CLI archives, smallest–largest across platforms. */
  cli: string;
  /** Desktop installers (.dmg, .msi, .deb, portable zip), smallest–largest. */
  desktop: string;
}

/** Shape of facts.json. */
export interface Facts {
  /** Release the sizes come from, without the leading "v" (e.g. "0.1.0"). */
  release: string;
  sizes: Sizes;
  /** Most connections per download the apps offer (`IntakeState.MAX_CONNECTIONS` in app:shared). */
  maxConnections: number;
  /** Tools `ketch mcp` exposes (`KetchToolSet` in library:mcp). */
  mcpTools: number;
  /** App languages (docs/development/localization.md#languages). */
  languages: number;
  /** Test count as the film shows it, rounded down, e.g. "4,000+". */
  tests: string;
}

export const FACTS: Facts = factsJson satisfies Facts;

/** A parsed size label: "5.5 MB" or the range "33–39 MB". */
export interface SizeLabel {
  /** Lower end (equal to `to` for a single size). */
  from: number;
  to: number;
  unit: string;
  /** Decimal places as written ("5.5" has 1). */
  decimals: number;
}

const SIZE_RE = /^([\d.]+)(?:\s*[–-]\s*([\d.]+))?\s*([A-Za-z]+)$/;

/** Parses a size label from facts.json. Throws on anything else, so a typo fails loudly. */
export const parseSize = (label: string): SizeLabel => {
  const m = label.trim().match(SIZE_RE);
  if (!m) throw new Error(`Not a size label: "${label}" (expected e.g. "5.5 MB" or "33–39 MB")`);
  const decimals = (m[1].split('.')[1] ?? '').length;
  const from = Number(m[1]);
  return {from, to: m[2] === undefined ? from : Number(m[2]), unit: m[3], decimals};
};

/** One of the release sizes, parsed. */
export const size = (key: keyof Sizes): SizeLabel => parseSize(FACTS.sizes[key]);

/**
 * The label of `s` scaled by `t` (0..1), with the label's own decimals: for count-ups.
 * `formatSize(size('android'), 1)` is "5.5 MB"; a range keeps its en dash ("33–39 MB").
 */
export const formatSize = (s: SizeLabel, t = 1): string => {
  const f = (v: number) => (v * t).toFixed(s.decimals);
  return s.from === s.to ? `${f(s.to)} ${s.unit}` : `${f(s.from)}–${f(s.to)} ${s.unit}`;
};
