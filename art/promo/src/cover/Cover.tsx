// Upload covers for the video platforms (YouTube/Bilibili 16:9, 小红书 3:4), separate from the
// film's first frame (src/Thumbnail.tsx): a big headline beside the real app UI, in each film's
// theme (the light film's covers show the light app on the light canvas). Render at 2× (the
// command in src/cover/index.tsx) for 3840×2160 and 2160×2880.
import React from 'react';
import {AbsoluteFill, Img, staticFile} from 'remotion';
import {assetsFor} from '../assets';
import {Background} from '../components/Background';
import '../fonts';
import type {Locale} from '../locale';
import {EMBER, FONT, alpha, palette, windowShadow, type Mode, type Palette} from '../theme';
import {ThemeProvider} from '../theme-context';

export type CoverFormat = 'wide' | 'tall';

interface CoverCopy {
  /** Headline lines; the last one gets the ember gradient. */
  headline: string[];
  chips: string[];
  platforms: string;
}

const COPY: Record<Locale, CoverCopy> = {
  en: {
    headline: ['One downloader.', 'Every device.'],
    chips: ['Free & open source', 'Torrents & magnets', 'AI search'],
    platforms: 'macOS · Windows · Linux · Android · iOS · CLI',
  },
  'zh-Hans': {
    headline: ['全平台', '下载神器'],
    chips: ['免费开源', '多线程 · BT · 磁力', 'AI 帮你找'],
    platforms: 'macOS · Windows · Linux · 安卓 · iOS · 命令行',
  },
};

/**
 * LAYOUT: real UI moments picked from the recorded clips, as clip time in design frames (30 fps,
 * src/time.tsx; the manifest's `fps` picks the clip's frame, so a 60 fps clip shows the same
 * moment): the desktop window with the ISO's Connections lanes, and the framed iPhone on the
 * same download.
 */
const DESKTOP_AT = 260;
const PHONE_AT = 150;
/** The clips, in every theme; a cover shows its film's (`desktop-dark`, `desktop-light`). */
const DESKTOP_CLIP = 'desktop';
const PHONE_CLIP = 'ios-lanes';

/**
 * The cover's look on each film's canvas: the chips' fill and rim (the first one is the accent
 * pill in both), the glass rim around the window, and the phone's drop shadow.
 */
const LOOK = {
  dark: {
    chipFill: 'rgba(255,255,255,0.08)',
    chipRim: 'rgba(255,255,255,0.14)',
    windowRim: 'rgba(255,255,255,0.12)',
    phoneShadow:
      'drop-shadow(0 30px 60px rgba(0,0,0,0.55)) drop-shadow(0 6px 14px rgba(0,0,0,0.4))',
    glow: 1,
  },
  light: {
    chipFill: 'rgba(255,255,255,0.75)',
    chipRim: 'rgba(20,26,38,0.10)',
    windowRim: 'rgba(20,26,38,0.08)',
    phoneShadow:
      'drop-shadow(0 30px 60px rgba(15,23,42,0.18)) drop-shadow(0 6px 14px rgba(15,23,42,0.12))',
    glow: 0.7,
  },
};

/**
 * The image of clip `base` in `theme` at clip time `at` (design frames), in `locale` (English as
 * the fallback).
 */
const clip = (locale: Locale, theme: Mode, base: string, at: number): string => {
  const assets = assetsFor(locale, theme);
  return staticFile(assets.clipFramePath(assets.themed(base), at));
};

/** height / width of clip `base` in `theme`, from the locale's manifest. */
const clipAspect = (locale: Locale, theme: Mode, base: string): number => {
  const assets = assetsFor(locale, theme);
  const {width, height} = assets.assetSize(assets.themed(base));
  return height / width;
};

const isCJK = (locale: Locale) => locale !== 'en';

/** A cover's theme: the film's, its palette and LOOK. */
interface Themed {
  theme: Mode;
  p: Palette;
  look: (typeof LOOK)[Mode];
}

const Brand: React.FC<{size: number; t: Themed}> = ({size, t}) => (
  <div style={{display: 'flex', alignItems: 'center', gap: size * 0.32}}>
    <Img
      src={staticFile('brand/icon-app.svg')}
      style={{width: size * 1.15, height: size * 1.15, marginLeft: -size * 0.08}}
    />
    <div
      style={{
        fontFamily: FONT.display,
        fontWeight: 700,
        fontSize: size,
        letterSpacing: '-0.03em',
        color: t.p.primary,
      }}
    >
      Ketch
    </div>
  </div>
);

const Headline: React.FC<{locale: Locale; size: number; t: Themed}> = ({locale, size, t}) => {
  const lines = COPY[locale].headline;
  const cjk = isCJK(locale);
  return (
    <div
      style={{
        fontFamily: FONT.display,
        fontWeight: 700,
        fontSize: size,
        lineHeight: cjk ? 1.12 : 1.0,
        letterSpacing: cjk ? 0 : '-0.045em',
        color: t.p.primary,
        fontSynthesis: 'none',
      }}
    >
      {lines.map((line, i) => (
        <div
          key={i}
          style={
            i === lines.length - 1
              ? {
                  backgroundImage: EMBER.gradient,
                  WebkitBackgroundClip: 'text',
                  backgroundClip: 'text',
                  color: 'transparent',
                  paddingBottom: '0.06em',
                }
              : undefined
          }
        >
          {line}
        </div>
      ))}
    </div>
  );
};

const Chips: React.FC<{locale: Locale; size: number; t: Themed}> = ({locale, size, t}) => (
  <div style={{display: 'flex', flexWrap: 'wrap', gap: size * 0.5}}>
    {COPY[locale].chips.map((c, i) => (
      <div
        key={c}
        style={{
          fontFamily: FONT.sans,
          fontWeight: 600,
          fontSize: size,
          color: i === 0 ? '#FFFFFF' : t.p.primary,
          padding: `${size * 0.42}px ${size * 0.85}px`,
          borderRadius: 999,
          background: i === 0 ? alpha(t.p.accentFill, 0.9) : t.look.chipFill,
          border: `1.5px solid ${i === 0 ? alpha('#8E9BFF', 0.6) : t.look.chipRim}`,
          whiteSpace: 'nowrap',
        }}
      >
        {c}
      </div>
    ))}
  </div>
);

/** The desktop window, tilted toward the text, with a soft ember glow behind it. */
const Desktop: React.FC<{locale: Locale; width: number; tilt: number; t: Themed}> = ({
  locale,
  width,
  tilt,
  t,
}) => {
  const a = assetsFor(locale, t.theme);
  const name = a.themed(DESKTOP_CLIP);
  const src = a.assetSize(name);
  const height = width * clipAspect(locale, t.theme, DESKTOP_CLIP);
  // The app's 14 dp window corners, at the clip's dp width (its px over its density).
  const dpWidth = src.width / a.assetDensity(name);
  const radius = (14 * width) / dpWidth;
  return (
    <div style={{width, height, perspective: 2400}}>
      <div
        style={{
          width,
          height,
          borderRadius: radius,
          overflow: 'hidden',
          transform: `rotateY(${-tilt}deg) rotateX(${tilt * 0.35}deg)`,
          transformOrigin: 'left center',
          boxShadow: `${windowShadow(t.theme, width / 1300)}, 0 0 0 1px ${t.look.windowRim}`,
        }}
      >
        <Img src={clip(locale, t.theme, DESKTOP_CLIP, DESKTOP_AT)} style={{width, height}} />
      </div>
    </div>
  );
};

const Phone: React.FC<{locale: Locale; height: number; t: Themed}> = ({locale, height, t}) => (
  <Img
    src={clip(locale, t.theme, PHONE_CLIP, PHONE_AT)}
    style={{
      height,
      width: height / clipAspect(locale, t.theme, PHONE_CLIP),
      filter: t.look.phoneShadow,
    }}
  />
);

const Glow: React.FC<{x: number; y: number; r: number; t: Themed}> = ({x, y, r, t}) => (
  <div
    style={{
      position: 'absolute',
      left: x - r,
      top: y - r,
      width: r * 2,
      height: r * 2,
      borderRadius: '50%',
      opacity: t.look.glow,
      background: `radial-gradient(circle, ${alpha(EMBER.from, 0.32)} 0%, ${alpha(
        EMBER.to,
        0.16,
      )} 38%, transparent 70%)`,
    }}
  />
);

export const Cover: React.FC<{locale: Locale; format: CoverFormat; theme?: Mode}> = ({
  locale,
  format,
  theme = 'dark',
}) => {
  const cjk = isCJK(locale);
  const t: Themed = {theme, p: palette(theme), look: LOOK[theme]};
  const root: React.CSSProperties = {fontFamily: FONT.sans};
  if (format === 'wide') {
    // 1920 × 1080: text column on the left, devices bleeding off the right.
    return (
      <ThemeProvider theme={theme}>
        <AbsoluteFill style={root} lang={locale}>
          <Background seed="cover" frame={0} intensity={1.25} />
          <Glow x={1420} y={560} r={720} t={t} />
          <div style={{position: 'absolute', left: 860, top: 170}}>
            <Desktop locale={locale} width={1240} tilt={10} t={t} />
          </div>
          <div style={{position: 'absolute', left: 1500, top: 360}}>
            <Phone locale={locale} height={720} t={t} />
          </div>
          <div
            style={{
              position: 'absolute',
              left: 110,
              top: 96,
              bottom: 96,
              width: cjk ? 760 : 800,
              display: 'flex',
              flexDirection: 'column',
              justifyContent: 'space-between',
            }}
          >
            <Brand size={64} t={t} />
            <Headline locale={locale} size={cjk ? 168 : 132} t={t} />
            <div style={{display: 'flex', flexDirection: 'column', gap: 30}}>
              <Chips locale={locale} size={30} t={t} />
              <div style={{fontSize: 26, fontWeight: 500, color: t.p.secondary}}>
                {COPY[locale].platforms}
              </div>
            </div>
          </div>
        </AbsoluteFill>
      </ThemeProvider>
    );
  }
  // 1080 × 1440 (3:4): text on top, devices below. LAYOUT: the English headline is one line
  // shorter than the Chinese one, so its devices sit higher.
  const devicesTop = cjk ? 820 : 720;
  return (
    <ThemeProvider theme={theme}>
      <AbsoluteFill style={root} lang={locale}>
        <Background seed="cover" frame={0} intensity={1.25} />
        <Glow x={700} y={1080} r={640} t={t} />
        <div style={{position: 'absolute', left: 150, top: devicesTop}}>
          <Desktop locale={locale} width={1180} tilt={8} t={t} />
        </div>
        <div style={{position: 'absolute', left: 700, top: devicesTop + 80}}>
          <Phone locale={locale} height={600} t={t} />
        </div>
        <div
          style={{
            position: 'absolute',
            left: 84,
            right: 84,
            top: 84,
            display: 'flex',
            flexDirection: 'column',
            gap: 46,
          }}
        >
          <Brand size={58} t={t} />
          <Headline locale={locale} size={cjk ? 172 : 128} t={t} />
          <Chips locale={locale} size={30} t={t} />
          <div style={{fontSize: 25, fontWeight: 500, color: t.p.secondary}}>
            {COPY[locale].platforms}
          </div>
        </div>
      </AbsoluteFill>
    </ThemeProvider>
  );
};
