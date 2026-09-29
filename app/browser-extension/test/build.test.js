import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import { releaseVersion } from '../build.mjs';

describe('releaseVersion', () => {
  test('a pre-release keeps its numeric part and adds the build number', () => {
    assert.deepEqual(releaseVersion('0.0.1-rc12', '57'), {
      version: '0.0.1.57',
      versionName: '0.0.1-rc12',
    });
  });

  test('without a build number a release keeps three parts', () => {
    assert.deepEqual(releaseVersion('1.2.3'), { version: '1.2.3', versionName: '1.2.3' });
  });

  test('rejects versions browsers cannot use', () => {
    assert.throws(() => releaseVersion('v1.2.3'), /not a version/);
    assert.throws(() => releaseVersion('1.2'), /not a version/);
    assert.throws(() => releaseVersion('1.02.3'), /0 to 65535/);
    assert.throws(() => releaseVersion('1.2.3', '70000'), /0 to 65535/);
  });
});
