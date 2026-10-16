// Demo of every shared component (compositions "Kit" and "Kit-zh-Hans", 150 frames). Page 1
// (0–74): text, chips, sail build, count-up, terminal. Lane wipe at 75. Page 2 (75–149):
// window + camera + clip player, scrim, browser frame with the extension popup, light-canvas
// blend. The zh-Hans page shows the CJK typography (segmented reveal, untracked runs).
import React from 'react';
import {AbsoluteFill} from 'remotion';
import {useAssets, type NormRect} from './assets';
import {
  Background,
  BrowserFrame,
  Camera,
  Chip,
  ChipRow,
  ClipPlayer,
  CountUp,
  LaneWipe,
  SailLogo,
  Scrim,
  StillImage,
  TerminalWindow,
  TextBlock,
  WindowFrame,
  browserExtensionAnchor,
  browserScale,
  fullRect,
  typedAt,
  zoomRect,
  type TermCommand,
} from './components';
import {easeDecelerate, lerpClamp, progress} from './easing';
import {LocaleRoot, defineCopy, useCopy, type Locale} from './locale';
import type {Mode} from './theme';
import {ACCENT_LIST, DARK, TERMINAL, TYPE} from './theme';
import {DesignSequence, useDesignFrame} from './time';

const COPY = defineCopy({
  en: {
    eyebrow: 'Foundation kit',
    headline: 'Calm on the surface.\nFast underneath.',
    sub: 'Eyebrow, headline and sub with `inline code`, word by word.',
    ember: 'Fast',
    chips: ['HTTP · HTTPS', 'FTP · FTPS', 'Magnet links'],
    emphasis: 'Emphasis',
    eyebrow2: 'Frames · Camera',
    headline2: 'Windows, browsers, zooms.',
  },
  'zh-Hans': {
    eyebrow: '组件 · Kit',
    headline: '看着清爽，\n跑起来飞快。',
    sub: '眉标、标题和副标题，带 `inline code`，按词逐个出现，标点不会落到行首。',
    ember: '飞快',
    chips: ['HTTP · HTTPS', 'FTP · FTPS', '磁力链接'],
    emphasis: '强调',
    eyebrow2: '窗口 · 镜头',
    headline2: '窗口、浏览器、推镜头。',
  },
});

/**
 * LAYOUT: the inspector on desktop-dark (fractions of the clip), used until the asset pipeline
 * records its bounds as `inspector`. Tied to the app's layout: re-measure if the UI moves.
 */
const INSPECTOR: NormRect = {x: 0.58, y: 0.36, w: 0.42, h: 0.42};

const CMD: TermCommand = {
  at: 18,
  cmd: 'ketch https://downloads.example.com/runtime.tar.gz',
  cps: 2.2,
};

const PageOne: React.FC = () => {
  const frame = useDesignFrame();
  const copy = useCopy(COPY);
  return (
    <AbsoluteFill>
      <Background seed="kit-1" />
      <TextBlock
        eyebrow={copy.eyebrow}
        headline={copy.headline}
        sub={copy.sub}
        ember={copy.ember}
      />
      <ChipRow
        labels={copy.chips}
        delay={24}
        enter="drop"
        style={{position: 'absolute', left: 120, top: 520}}
      />
      <div
        style={{
          position: 'absolute',
          left: 120,
          top: 600,
          width: 740,
          display: 'flex',
          flexWrap: 'wrap',
          gap: 14,
        }}
      >
        <Chip label={copy.emphasis} emphasis delay={36} />
        {ACCENT_LIST.map((a, i) => (
          <Chip key={a.id} label={a.name} dot={a.color} delay={40 + i * 3} enter="pop" />
        ))}
        <Chip label="startDownload" mono delay={52} />
      </div>
      <div style={{position: 'absolute', right: 150, top: 96}}>
        <SailLogo start={2} height={300} />
      </div>
      <div
        style={{
          position: 'absolute',
          right: 520,
          top: 150,
          color: DARK.primary,
          textAlign: 'right',
        }}
      >
        <CountUp
          to={5.5}
          start={10}
          duration={36}
          format={(v) => `${v.toFixed(1)} MB`}
          style={{fontSize: 96, lineHeight: 1}}
        />
        <div style={{...TYPE.sub, fontSize: 22, color: DARK.tertiary, marginTop: 8}}>
          CountUp (tnum)
        </div>
      </div>
      <div style={{position: 'absolute', left: 900, top: 520}}>
        <TerminalWindow
          width={900}
          height={300}
          scale={1.4}
          lines={[
            CMD,
            {
              at: typedAt(CMD) + 3,
              text: (t) => {
                const p = Math.min(1, t / 20);
                return (
                  <>
                    <span style={{color: TERMINAL.prompt}}>[Downloading]</span>
                    {` ${String(Math.round(p * 100)).padStart(3, ' ')}%  ` +
                      `${(p * 49).toFixed(1).padStart(4, ' ')} MB / 49.0 MB`}
                  </>
                );
              },
            },
            {at: typedAt(CMD) + 24, text: 'Download completed: runtime.tar.gz', color: '#45C27A'},
            {at: typedAt(CMD) + 26, prompt: true},
          ]}
        />
      </div>
    </AbsoluteFill>
  );
};

const PageTwo: React.FC = () => {
  const frame = useDesignFrame();
  const copy = useCopy(COPY);
  const {assetAspect, assetBoundsPx, assetSize} = useAssets();
  const src = assetSize('desktop-dark');
  const vw = 1120;
  const vh = vw / (src.width / src.height);
  const light = lerpClamp(frame, [44, 64], [0, 1]);
  const enter = progress(frame, 0, 24, easeDecelerate);
  const bw = 560;
  const bh = 420;
  const s = browserScale(bw);
  const anchor = browserExtensionAnchor(bw, s);
  const pw = 210;
  return (
    <AbsoluteFill>
      <Background seed="kit-2" lightMix={light} />
      <TextBlock
        eyebrow={copy.eyebrow2}
        headline={copy.headline2}
        delay={2}
        lightMix={light}
        y={90}
        headlineSize={64}
      />
      <div
        style={{
          position: 'absolute',
          left: 120,
          top: 270,
          opacity: enter,
          transform: `translateY(${(1 - enter) * 40}px)`,
        }}
      >
        <WindowFrame width={vw} height={vh} mode={light > 0.5 ? 'light' : 'dark'}>
          <Camera
            source={src}
            viewport={{width: vw, height: vh}}
            keys={[
              {at: 6, rect: fullRect(src)},
              {at: 40, rect: zoomRect(assetBoundsPx('desktop-dark', 'inspector', INSPECTOR), 1)},
            ]}
          >
            <ClipPlayer name="desktop-dark" frame={frame} />
          </Camera>
          <Scrim from="bottom" extent="40%" strength={0.7} />
        </WindowFrame>
      </div>
      <div style={{position: 'absolute', left: 1300, top: 300, opacity: enter}}>
        <BrowserFrame
          width={bw}
          height={bh}
          url="ketch.example/app"
          extension
          extensionActive
          mode={light > 0.5 ? 'light' : 'dark'}
        >
          <StillImage name="web-dark" style={{objectPosition: 'left top'}} />
        </BrowserFrame>
        <div
          style={{
            position: 'absolute',
            left: anchor.x - pw + 30,
            top: anchor.y + 4,
            width: pw,
            height: pw / assetAspect('popup-dark'),
            borderRadius: 10,
            overflow: 'hidden',
            boxShadow: '0 20px 50px -10px rgba(0,0,0,0.65), inset 0 0 0 1px rgba(255,255,255,0.1)',
          }}
        >
          <StillImage name="popup-dark" />
        </div>
      </div>
    </AbsoluteFill>
  );
};

/** Length in design frames: two pages of 75. */
export const KIT_LENGTH = 150;

export const Kit: React.FC<{locale: Locale; theme?: Mode; fps?: number}> = ({locale, theme}) => (
  <LocaleRoot locale={locale} theme={theme}>
    <DesignSequence durationInFrames={75}>
      <PageOne />
    </DesignSequence>
    <DesignSequence from={75} durationInFrames={75}>
      <PageTwo />
    </DesignSequence>
    <LaneWipe at={75} />
  </LocaleRoot>
);
