#!/usr/bin/env node
/** Generate an unsigned macOS host project with Apple's installed converter. */
import { spawnSync } from 'node:child_process';
import { readFileSync, writeFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { build } from './build.mjs';

if (process.platform !== 'darwin') throw new Error('Safari packaging requires macOS and Xcode.');
const root = dirname(fileURLToPath(import.meta.url));
const output = build().find((target) => target.browser === 'safari');
const project = join(root, 'build', 'safari-xcode');
const result = spawnSync('xcrun', ['safari-web-extension-converter', output.dir,
  '--project-location', project, '--app-name', 'Ketch for Safari',
  '--bundle-identifier', 'com.linroid.ketch.safari', '--swift', '--macos-only',
  '--copy-resources', '--no-open', '--no-prompt', '--force'], { stdio: 'inherit' });
if (result.error) throw result.error;
if (result.status !== 0) process.exit(result.status ?? 1);
// Some converter versions derive the host id from the display name instead of --bundle-identifier.
// Keep the generated host and embedded extension in the same bundle-id hierarchy.
const projectFile = join(project, 'Ketch for Safari', 'Ketch for Safari.xcodeproj', 'project.pbxproj');
const source = readFileSync(projectFile, 'utf8')
  .replace(/PRODUCT_BUNDLE_IDENTIFIER = [^;]+;/g, (line) => line.includes('.Extension')
    ? 'PRODUCT_BUNDLE_IDENTIFIER = com.linroid.ketch.safari.Extension;'
    : 'PRODUCT_BUNDLE_IDENTIFIER = com.linroid.ketch.safari;')
  .replace(/MACOSX_DEPLOYMENT_TARGET = [^;]+;/g, 'MACOSX_DEPLOYMENT_TARGET = 13.3;');
writeFileSync(projectFile, source);
console.log(`Open ${project}/Ketch for Safari/Ketch for Safari.xcodeproj to select a signing team and run.`);
