// S11 Languages (global 1965–2070, 105 frames at the time of writing). Left: the headline, then
// a ticker of native language names that steps up in time with the build's snare roll. Right:
// the app, large, dissolving into the canvas toward the bottom right; it changes language with
// the ticker. The window's screen is driven by the ticker's position, not by frames of its own
// (screenOpacity): it switches as the next name reaches the marker, so the name in focus and the
// window always match, whatever the hop timing. The ticker only ever rests on a name whose
// screenshot is on screen: a language without a `lang-<tag>-dark` still in the manifest is
// passed over in a longer hop and never lights up. A still added later is picked up as it lands
// (no code change). The ticker stops on the last language with a screen two beats before the cut
// (ROLL_32NDS_BEATS: local 75, global 2040 at the time of writing) and holds it to the wipe. The
// lane wipe into S12 (the cut − 9 to the cut, local 96–104) is placed by Promo.
//
// Copy and the list of languages live in ./copy/S11.ts.
import React from 'react';
import {AbsoluteFill} from 'remotion';
import {assetsFor, type AssetResolver} from '../assets';
import {Background} from '../components/Background';
import {StillImage} from '../components/StillImage';
import {TextBlock} from '../components/Text';
import {WindowFrame} from '../components/WindowFrame';
import {easeDecelerate, easeInOut, easeStandard, lerpClamp, progress} from '../easing';
import {DEFAULT_LOCALE, LocaleProvider, useCopy} from '../locale';
import {ACCENTS, FONT, type Mode} from '../theme';
import {FILM_THEMES, usePalette, useTheme} from '../theme-context';
import {useDesignFrame} from '../time';
import {BEAT, scene} from '../timing';
import {S11_COPY, S11_LANGUAGES} from './copy/S11';

// ---------------------------------------------------------------------------------------------
// Languages

/**
 * System typefaces for scripts Inter does not cover (Inter Display for the rest), and a weight
 * where the face looks light next to the others (Apple SD Gothic Neo needs 700 to match).
 */
const FACES: Record<string, {face: string; weight?: number}> = {
  'zh-Hans': {face: '"PingFang SC"'},
  'zh-Hant': {face: '"PingFang TC"'},
  ja: {face: '"Hiragino Sans"'},
  ko: {face: '"Apple SD Gothic Neo"', weight: 700},
};

/** Screens for languages that have no `lang-<tag>` still: English is the app's own screen. */
const SHOT_FALLBACK: Record<string, string> = {en: 'inspector-overview'};

interface Lang {
  tag: string;
  name: string;
  /** Still of the app in this language. */
  shot?: string;
  /** Preferred system face for the script. */
  face?: string;
  /** Font weight of the name (default 600). */
  weight?: number;
}

/** The ticker of one film: its languages, the screens it shows and where it stops. */
interface Ticker {
  /** The film's English assets, where the screens are. */
  shots: AssetResolver;
  langs: Lang[];
  /** Rows the ticker rests on: the languages with a screen, in list order. */
  focus: number[];
  /** The last of them, where the ticker stops; names after it stay dim below it. */
  last: number;
  /** When the ticker lands on each focus row (landingFrames). */
  stops: number[];
}

/**
 * The ticker of the film in `theme`. The language screens are the same in every locale's film:
 * each shows the app in its own language, whatever the film's locale (in the Chinese film,
 * English still means the English screen). So they come from the English manifest of the film's
 * theme (`lang-ja-dark`, `lang-ja-light`), where the asset pipeline records the `lang-*` stills,
 * and the window below renders them under an English LocaleProvider.
 */
const tickerOf = (theme: Mode): Ticker => {
  const shots = assetsFor(DEFAULT_LOCALE, theme);
  /** The still of the app in `tag`, or undefined while the manifest has none. */
  const shotFor = (tag: string): string | undefined => {
    const name = shots.themed(`lang-${tag}`);
    if (shots.stillInfo(name)) return name;
    const fallback = SHOT_FALLBACK[tag];
    return fallback === undefined ? undefined : shots.themed(fallback);
  };
  const langs = S11_LANGUAGES.map((l) => ({...l, shot: shotFor(l.tag), ...FACES[l.tag]}));
  const focus = langs.flatMap((l, i) => (l.shot ? [i] : []));
  return {
    shots,
    langs,
    focus,
    last: focus[focus.length - 1],
    stops: landingFrames(focus.length - 1),
  };
};

// ---------------------------------------------------------------------------------------------
// Timing (scene-local frames)

/** Scene length (timing.json); the window pushes over all of it. */
const LEN = scene('S11').len;

/**
 * The entrances start before the scene does (S10 fades out to the cut, a soft cut), so the
 * window, the ticker and the first words are already on screen on frame 0.
 */
const ENTER_AT = -6;
const TEXT_AT = ENTER_AT + 2;

/**
 * Beats before the cut at which the music's snare roll turns to thirty-seconds (audio/score.py,
 * `rolls`), where the ticker lands on its last name. Music tie: audio/score.py PICTURE_TIES
 * (ROLL_32NDS_BEATS); change both sides together.
 */
const ROLL_32NDS_BEATS = 2;
/**
 * Where the ticker lands on its last name: ROLL_32NDS_BEATS before the cut. Local 75 at 105
 * frames (global 2040 at the time of writing).
 */
const LAST_LANDING = LEN - ROLL_32NDS_BEATS * BEAT;

/**
 * Frames at which the ticker lands on each FOCUS row. The roll runs under the whole scene:
 * eighths, then sixteenths from four beats before the cut (local 45), then thirty-seconds from
 * LAST_LANDING to the wipe. The first name rests from the start; the hops land on the beats from
 * BEAT to LAST_LANDING and, when there are more hops than beats, also on the off-beat eighths,
 * added from the end of the roll backward, so the ticker speeds up with it and always lands on
 * its last name at LAST_LANDING. At 105 frames six screens land on every beat, ten on every
 * eighth.
 */
const landingFrames = (hops: number): number[] => {
  const beats = Array.from({length: LAST_LANDING / BEAT}, (_, k) => (k + 1) * BEAT);
  const offBeatsLatestFirst = beats
    .slice(1)
    .reverse()
    .map((b) => Math.round(b - BEAT / 2));
  const slots = [...beats, ...offBeatsLatestFirst.slice(0, Math.max(0, hops - beats.length))].sort(
    (a, b) => a - b,
  );
  if (hops <= slots.length) return [0, ...slots.slice(slots.length - hops)];
  // More names than eighths: spread them evenly over the same span.
  return [
    0,
    ...Array.from({length: hops}, (_, k) =>
      Math.round(BEAT + ((LAST_LANDING - BEAT) * k) / (hops - 1)),
    ),
  ];
};
/** One-row hop length; a hop over a name takes HOP_EXTRA more per name passed. */
const HOP = 7;
const HOP_EXTRA = 4;
/**
 * How far the ticker moves (in rows) while the window changes screen. A hop does most of its
 * move in its first frames (easeStandard), so the change is timed by the ticker's position, not
 * by frames: the next screen fades in over the SWAP_ROWS around the hop's half-way point, where
 * its name becomes the one nearest the marker (screenOpacity). A HOP-frame hop steps from 0.33
 * to 0.67 of a row between two frames, so at 0.3 the screen cuts cleanly on the frame its name
 * takes the marker; a wider swap leaves a faint ghost of the other language on both frames (two
 * languages overlaid read as a smudge), and slower hops would crossfade over a frame or two.
 */
const SWAP_ROWS = 0.3;

// ---------------------------------------------------------------------------------------------
// Layout (px in the 1920×1080 frame)

/**
 * LAYOUT: the text column (headline and sub, top left from y 110), left of the window. Its
 * copy breaks its lines with `\n` (see ./copy/S11.ts) and ends above the ticker's top fade
 * (about y 470) in every locale: two headline lines and a sub of one line in English, two in
 * Chinese.
 */
const TEXT_W = 680;

/**
 * LAYOUT: the ticker. The focused row's centre, row pitch, type size, focus marker and name x;
 * the focused row sits low enough that the rows above it fade out under the text column.
 */
const FOCUS_Y = 676;
const PITCH = 80;
const NAME_SIZE = 56;
const TICKER_X = 120;
const NAME_X = 152;
/**
 * LAYOUT: the ticker's clip box, wide enough for the longest name ("Português (Brasil)", about
 * 430 px at NAME_SIZE) and clear of the window's left edge (WIN.left).
 */
const TICKER_W = 760;
/** Rows visible above and below the focused one (the rest are clipped by the ticker's mask). */
const HALF_ROWS = 2.6;

/**
 * LAYOUT: the window, left/top fixed while its width pushes slowly, its right and bottom
 * dissolving into the canvas. Framed on the 1280 × 800 dp `lang-*` stills, so the sidebar and the
 * downloads table's name, size and progress columns stay clear of the dissolve.
 */
const WIN = {left: 800, top: 150, w0: 1700, w1: 1756};
const FADE_RIGHT = [1540, 1920];
const FADE_BOTTOM = [850, 1080];

/** Each film's ticker, by its theme. */
const TICKERS = Object.fromEntries(FILM_THEMES.map((t) => [t, tickerOf(t)])) as Record<
  Mode,
  Ticker
>;

/** Continuous position of `ticker` (0 = the first row focused) at `frame`. */
const tickerPos = ({focus, stops}: Ticker, frame: number): number => {
  let pos = focus[0];
  for (let k = 1; k < focus.length; k++) {
    const rows = focus[k] - focus[k - 1];
    const len = Math.min(HOP + HOP_EXTRA * (rows - 1), stops[k] - stops[k - 1]);
    pos += rows * progress(frame, stops[k] - len, len, easeStandard);
  }
  return pos;
};

/**
 * Opacity of the screen of ticker row `row` (a FOCUS row) at ticker position `pos`: 0 until the
 * ticker nears the half-way point onto that row, 1 once past it. Screens stack in FOCUS order,
 * each over the last, so the newest one landed shows. Names a hop passes over have no screen
 * and the window keeps the last one until the next name with a screen reaches the marker.
 */
const screenOpacity = (row: number, pos: number): number =>
  lerpClamp(pos, [row - 0.5 - SWAP_ROWS / 2, row - 0.5 + SWAP_ROWS / 2], [0, 1]);

/** Ticker row opacity by distance from the focus (in rows). */
const rowOpacity = (d: number): number =>
  d <= 1 ? 1 - 0.6 * d : d <= 2 ? 0.4 - 0.22 * (d - 1) : Math.max(0, 0.18 - 0.18 * (d - 2));

export const S11Languages: React.FC = () => {
  const frame = useDesignFrame();
  const copy = useCopy(S11_COPY);
  const ticker = TICKERS[useTheme()];
  const p = usePalette();
  const {shots, langs, focus, last} = ticker;
  const pos = tickerPos(ticker, frame);
  const tickerIn = progress(frame, ENTER_AT + 2, 10, easeStandard);

  const winRise = progress(frame, ENTER_AT, 24, easeDecelerate);
  const winFade = progress(frame, ENTER_AT, 12, easeStandard);
  const w = WIN.w0 + (WIN.w1 - WIN.w0) * progress(frame, 0, LEN, easeInOut);
  const h = w / shots.assetAspect(langs[focus[0]].shot!);

  return (
    <AbsoluteFill>
      <Background seed="s11" />

      {/* The app, dissolving toward the bottom right (mask in screen px keeps its left shadow). */}
      <AbsoluteFill
        style={{
          maskImage: `linear-gradient(to right, #000 ${FADE_RIGHT[0]}px, transparent ${FADE_RIGHT[1]}px), linear-gradient(to bottom, #000 ${FADE_BOTTOM[0]}px, transparent ${FADE_BOTTOM[1]}px)`,
          maskComposite: 'intersect',
          WebkitMaskComposite: 'source-in',
          opacity: winFade,
        }}
      >
        <div
          style={{
            position: 'absolute',
            left: WIN.left,
            top: WIN.top,
            transform: `translate3d(0, ${((1 - winRise) * 56).toFixed(2)}px, 0)`,
          }}
        >
          <WindowFrame width={w} height={h}>
            <LocaleProvider locale={DEFAULT_LOCALE}>
              {focus.map((i, k) => {
                // The first screen is the base layer; the others swap in as the ticker passes.
                const opacity = k === 0 ? 1 : screenOpacity(i, pos);
                // Only the screens that show are mounted, so a frame decodes two dense stills at
                // most, not all ten.
                return opacity <= 0 ? null : (
                  <StillImage
                    key={langs[i].tag}
                    name={langs[i].shot!}
                    style={{position: 'absolute', inset: 0, opacity}}
                  />
                );
              })}
            </LocaleProvider>
          </WindowFrame>
        </div>
      </AbsoluteFill>

      <TextBlock headline={copy.headline} sub={copy.sub} delay={TEXT_AT} width={TEXT_W} />

      {/* Ticker */}
      <div
        style={{
          position: 'absolute',
          left: 0,
          top: FOCUS_Y - HALF_ROWS * PITCH,
          width: TICKER_W,
          height: 2 * HALF_ROWS * PITCH,
          overflow: 'hidden',
          opacity: tickerIn,
          maskImage:
            'linear-gradient(to bottom, transparent 0%, #000 22%, #000 78%, transparent 100%)',
        }}
      >
        {langs.map((l, i) => {
          const d = Math.abs(i - pos);
          if (d > HALF_ROWS) return null;
          const y = HALF_ROWS * PITCH + (i - pos) * PITCH - PITCH / 2;
          return (
            <div
              key={l.tag}
              lang={l.tag}
              style={{
                position: 'absolute',
                left: NAME_X,
                top: y,
                height: PITCH,
                lineHeight: `${PITCH}px`,
                fontFamily: l.face ? `${l.face}, ${FONT.display}` : FONT.display,
                fontWeight: l.weight ?? 600,
                fontSize: NAME_SIZE,
                letterSpacing: l.face ? '0' : '-0.025em',
                color: p.primary,
                whiteSpace: 'nowrap',
                // A name the ticker passes over never lights up as if it were in focus.
                opacity: l.shot || i > last ? rowOpacity(d) : Math.min(0.4, rowOpacity(d)),
              }}
            >
              {l.name}
            </div>
          );
        })}
      </div>
      {/* Focus marker */}
      <div
        style={{
          position: 'absolute',
          left: TICKER_X,
          top: FOCUS_Y - 24,
          width: 5,
          height: 48,
          borderRadius: 3,
          background: ACCENTS.signalBright,
          opacity: tickerIn,
          transform: `scaleY(${lerpClamp(frame, [ENTER_AT + 2, ENTER_AT + 14], [0.3, 1], easeDecelerate).toFixed(3)})`,
        }}
      />
    </AbsoluteFill>
  );
};
