// S07 All your devices: every word the scene shows, per locale, plus the device names, which are
// the same in every locale. The scene file only lays it out and times it, so copy changes happen
// here.
//
// Line breaks (`\n`) are part of the copy: the sub's break is chosen for its 760 px column and
// the window that rises under it, so check a still after changing it (README.md, "Single
// frames").
import {defineCopy} from '../../locale';

/** The devices the pills name, top to bottom: the ones the All devices window lists. */
export const DEVICE_IDS = ['thisMac', 'studio', 'homeServer'] as const;
export type DeviceId = (typeof DEVICE_IDS)[number];

export interface S07Strings {
  eyebrow: string;
  headline: string;
  sub: string;
  /**
   * The window's name for the device it runs on, as the app says it in this language
   * (`device_this_mac`), so the pill matches the window's sidebar.
   */
  thisMac: string;
}

export const S07_COPY = defineCopy<S07Strings>({
  en: {
    eyebrow: 'All your devices',
    headline: 'Pair once.\nRun them all from one.',
    sub: 'Scan a QR code, then watch and control every device’s downloads from one place.',
    thisMac: 'This Mac',
  },
  'zh-Hans': {
    eyebrow: '多设备',
    headline: '配对一次，\n所有设备一手掌握。',
    // break after the second comma: the short last line stays clear of the window below it
    sub: '扫个二维码就配好了，所有设备的下载，\n在一个地方就能看、能管。',
    thisMac: '这台 Mac',
  },
});

/**
 * Names of the other devices: data the window shows as it is in every language (ShowcaseData.kt
 * in app/shared's jvmTest names them), so they are not copy.
 */
export const S07_SHARED = {
  deviceNames: {studio: 'Studio', homeServer: 'Home server'},
} as const;
