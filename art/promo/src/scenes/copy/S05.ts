// S05 Full featured: every word the scene shows, per locale. The scene file lays the tiles out,
// picks the UI each one shows and times the spotlights, so copy changes happen here.
//
// Captions wrap inside a tile (about 470 px at 20 px, two lines at most; S05Features.tsx IMG_TOP
// leaves room for two). Feature names follow the app's own words in each language (Slow lane is
// 低速模式, Urgent is 紧急, the command palette is 命令面板), since the tiles show the app's UI.
//
// Each caption must agree with what its tile's UI shows. The Priorities tile shows the ISO's
// control with Normal selected (inspector-overview-dark), so its caption names the control's
// four levels (Low, Normal, High, Urgent: DownloadPriority) and says what Urgent does, rather
// than telling the viewer the task is set to Urgent.
import {defineCopy} from '../../locale';

/** The features, in the grid's reading order (left to right, top row first). */
export const FEATURE_IDS = [
  'speed',
  'priority',
  'schedule',
  'queue',
  'networks',
  'palette',
] as const;
export type FeatureId = (typeof FEATURE_IDS)[number];

/** What one tile says above its UI. */
export interface TileStrings {
  title: string;
  caption: string;
}

export interface S05Strings {
  eyebrow: string;
  headline: string;
  tiles: Record<FeatureId, TileStrings>;
}

export const S05_COPY = defineCopy<S05Strings>({
  en: {
    eyebrow: 'Full featured',
    headline: 'Power, when you want it.',
    tiles: {
      speed: {
        title: 'Speed modes',
        caption: 'Full speed, Slow lane, or Auto on a weekly schedule.',
      },
      priority: {title: 'Priorities', caption: 'Four levels. Urgent jumps the queue.'},
      schedule: {title: 'Scheduling', caption: 'Start a download at the time you pick.'},
      queue: {title: 'Smart queue', caption: 'Limits per download and per site.'},
      networks: {
        title: 'Several networks',
        caption: 'Spread one download over Wi-Fi and Ethernet.',
      },
      palette: {title: '⌘K palette', caption: 'Every command, one keystroke away.'},
    },
  },
  'zh-Hans': {
    eyebrow: '功能齐全',
    headline: '该有的，都有。',
    tiles: {
      speed: {title: '速度模式', caption: '全速、低速模式，或者按每周计划自动切。'},
      priority: {title: '优先级', caption: '分四档，紧急的直接插队。'},
      schedule: {title: '定时下载', caption: '想几点开始，就几点开始。'},
      queue: {title: '智能队列', caption: '每个下载、每个网站，都能单独限制。'},
      networks: {title: '多网络', caption: '一个下载，Wi-Fi 和网线一起跑。'},
      palette: {title: '⌘K 命令面板', caption: '所有命令，按一下就到。'},
    },
  },
});
