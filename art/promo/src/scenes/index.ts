// Scene registry used by Root (standalone compositions) and Promo (the film).
import type React from 'react';
import type {SceneId} from '../timing';
import {S01Intro} from './S01Intro';
import {S02ModernUI} from './S02ModernUI';
import {S03Lanes} from './S03Lanes';
import {S04AnyLink} from './S04AnyLink';
import {S05Features} from './S05Features';
import {S06CrossPlatform} from './S06CrossPlatform';
import {S07Devices} from './S07Devices';
import {S08Discover} from './S08Discover';
import {S09Agents} from './S09Agents';
import {S10Light} from './S10Light';
import {S11Languages} from './S11Languages';
import {S12OpenSource} from './S12OpenSource';

export const SCENE_COMPONENTS: Record<SceneId, React.FC> = {
  S01: S01Intro,
  S02: S02ModernUI,
  S03: S03Lanes,
  S04: S04AnyLink,
  S05: S05Features,
  S06: S06CrossPlatform,
  S07: S07Devices,
  S08: S08Discover,
  S09: S09Agents,
  S10: S10Light,
  S11: S11Languages,
  S12: S12OpenSource,
};
