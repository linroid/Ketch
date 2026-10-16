// Refreshes src/facts.json from a GitHub release: `release` and the download `sizes`, in binary
// units as GitHub shows them (KB as a whole number; MB with one decimal below 10, else whole;
// a category with several files, such as the per-platform installers, as a range of whole MB).
// The other fields are left as they are; the script prints what the repository says about them
// so they can be checked and edited by hand. Prints a diff; running it twice changes nothing.
//
//   node scripts/update-facts.mjs [<tag>] [--dry-run | --check]
//   (npm run facts -- [<tag>] [--dry-run])
//
//   --dry-run  print the diff and the hand-kept facts, write nothing
//   --check    checks src/facts.json against the release it names (not the latest, unless a
//              tag is given) and the repository, writes nothing, and exits 1 when anything
//              differs or cannot be checked (npm run check); it notes a newer release
//
// Needs the GitHub CLI (`gh`), signed in or not (the repository is public), and git.
import {execFileSync} from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import {root} from './lib/remotion.mjs';

const REPO = 'linroid/Ketch';
const FACTS = path.join(root, 'src/facts.json');
const USAGE = 'usage: node scripts/update-facts.mjs [<tag>] [--dry-run | --check]';

/** Release files per size, by name (mapping archives and the web bundle are not counted). */
const CATEGORIES = {
  extension: /^ketch-extension-.+\.zip$/,
  android: /^ketch-android-.+\.apk$/,
  cli: /^ketch-cli-.+\.(tar\.gz|zip)$/,
  desktop: /^ketch-desktop-.+(\.dmg|\.msi|\.deb|-portable\.zip)$/,
};

const args = process.argv.slice(2);
const check = args.includes('--check');
const dryRun = check || args.includes('--dry-run');
const rest = args.filter((a) => a !== '--dry-run' && a !== '--check');
if (rest.length > 1 || rest.some((a) => a.startsWith('-'))) {
  console.error(USAGE);
  process.exit(2);
}
const text = fs.readFileSync(FACTS, 'utf8');
const old = JSON.parse(text);
/** The release to read: the one named, else for --check the film's own, else the latest. */
const tag = rest[0] ?? (check ? `v${old.release}` : undefined);

// ---------------------------------------------------------------------------------------------
// Sizes

const KiB = 1024;
const MiB = 1024 * 1024;

/** One file's size label: "89 KB", "5.5 MB", "62 MB". */
const label = (bytes) => {
  if (bytes < MiB) return `${Math.round(bytes / KiB)} KB`;
  const mb = bytes / MiB;
  return mb < 9.95 ? `${mb.toFixed(1)} MB` : `${Math.round(mb)} MB`;
};

/** Label for a category: one size, or "min–max" in whole units when the files differ. */
const categoryLabel = (sizes) => {
  const lo = Math.min(...sizes);
  const hi = Math.max(...sizes);
  if (label(lo) === label(hi)) return label(lo);
  const [unit, div] = hi < MiB ? ['KB', KiB] : ['MB', MiB];
  const a = Math.round(lo / div);
  const b = Math.round(hi / div);
  return a === b ? `${a} ${unit}` : `${a}–${b} ${unit}`;
};

/** A release's tag and files, read with gh (the latest when `name` is undefined). */
const readRelease = (name) => {
  try {
    return JSON.parse(
      execFileSync(
        'gh',
        ['release', 'view', ...(name ? [name] : []), '--repo', REPO, '--json', 'tagName,assets'],
        {encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe']},
      ),
    );
  } catch (e) {
    console.error(`Could not read the ${name ?? 'latest'} release of ${REPO} with gh:`);
    console.error(String(e.stderr || e.message).trim());
    console.error('Install the GitHub CLI (brew install gh) and check the tag.');
    process.exit(1);
  }
};
const release = readRelease(tag);

if (release.assets.length === 0) {
  console.error(`${release.tagName} has no files yet; nothing written.`);
  console.error(
    'Its release workflow may still be uploading them: run this again once it is done, or name ' +
      `the release the film shows (npm run facts -- v${old.release}).`,
  );
  process.exit(1);
}

const sizes = {};
for (const [key, re] of Object.entries(CATEGORIES)) {
  const files = release.assets.filter((a) => re.test(a.name));
  if (files.length === 0) {
    console.error(`${release.tagName} has no ${key} file (${re}); nothing written.`);
    console.error('Check CATEGORIES in this script against the release workflow.');
    process.exit(1);
  }
  sizes[key] = categoryLabel(files.map((f) => f.size));
  console.log(
    `${key.padEnd(10)} ${sizes[key].padEnd(10)}`,
    files.map((f) => `${f.name} (${f.size} B)`).join(', '),
  );
}

// ---------------------------------------------------------------------------------------------
// Write

const next = {...old, release: release.tagName.replace(/^v/, ''), sizes: {...old.sizes, ...sizes}};

const flat = (o, prefix = '') =>
  Object.entries(o).flatMap(([k, v]) =>
    v && typeof v === 'object' ? flat(v, `${prefix}${k}.`) : [[`${prefix}${k}`, v]],
  );
const before = Object.fromEntries(flat(old));
const changes = flat(next).filter(([k, v]) => before[k] !== v);

console.log('');
if (changes.length === 0) {
  console.log(`src/facts.json is up to date with ${release.tagName}.`);
} else {
  for (const [k, v] of changes)
    console.log(`- ${k}: ${JSON.stringify(before[k])}\n+ ${k}: ${JSON.stringify(v)}`);
  if (dryRun) {
    console.log('(dry run: nothing written)');
  } else {
    // Keep the file's style: one line if it was one line, else two-space indents.
    const oneLine = text.trim().split('\n').length === 1;
    fs.writeFileSync(FACTS, `${oneLine ? JSON.stringify(next) : JSON.stringify(next, null, 2)}\n`);
    console.log('src/facts.json updated.');
  }
}

// ---------------------------------------------------------------------------------------------
// Hand-kept facts, checked against the repository (read only; edit facts.json if they differ)

const repo = path.resolve(root, '../..');
const read = (p) => {
  try {
    return fs.readFileSync(path.join(repo, p), 'utf8');
  } catch {
    return undefined;
  }
};
/**
 * Files git tracks under `pathspec`, relative to the repository (undefined without git). Only
 * tracked files count: a checkout can hold other checkouts (.claude/worktrees/ holds whole
 * copies of the repository), build output and caches, none of which are the project's tests.
 */
const tracked = (pathspec) => {
  try {
    return execFileSync('git', ['-C', repo, 'ls-files', '-z', '--', pathspec], {
      encoding: 'utf8',
      maxBuffer: 64 * 1024 * 1024,
      stdio: ['ignore', 'pipe', 'ignore'],
    })
      .split('\0')
      .filter(Boolean);
  } catch {
    return undefined;
  }
};

/** Occurrences of `re` (global) in the tracked files under `pathspec` whose name matches `name`. */
const countIn = (pathspec, name, re) =>
  tracked(pathspec)
    ?.filter((f) => name.test(f))
    .reduce((n, f) => n + (read(f)?.match(re)?.length ?? 0), 0);

const checks = [
  {
    key: 'maxConnections',
    where: 'IntakeState.MAX_CONNECTIONS',
    value: Number(
      read('app/shared/src/commonMain/kotlin/com/linroid/ketch/app/state/IntakeState.kt')?.match(
        /MAX_CONNECTIONS = (\d+)/,
      )?.[1],
    ),
  },
  {
    key: 'mcpTools',
    where: 'TextTool( in KetchToolSet.kt',
    value: read('library/mcp/src/main/kotlin/com/linroid/ketch/mcp/KetchToolSet.kt')?.match(
      /TextTool\(/g,
    )?.length,
  },
  {
    key: 'languages',
    where: 'values* folders of the app strings',
    value: (() => {
      try {
        return fs
          .readdirSync(path.join(repo, 'app/shared/src/commonMain/composeResources'))
          .filter((d) => /^values(-|$)/.test(d)).length;
      } catch {
        return undefined;
      }
    })(),
  },
  {
    key: 'tests',
    where: 'tracked files: @Test in Kotlin + test( in the extension, rounded down',
    value: (() => {
      const kt = countIn('*.kt', /\.kt$/, /@Test\b/g);
      const js = countIn('app/browser-extension/test', /\.test\.m?js$/, /^\s*(test|it)\(/gm);
      if (kt === undefined || js === undefined) return undefined;
      const n = kt + js;
      if (n >= 1000) return `${(Math.floor(n / 1000) * 1000).toLocaleString('en-US')}+`;
      return n > 0 ? String(n) : undefined;
    })(),
  },
];

console.log('\nHand-kept facts (facts.json / the repository says):');
let handDiffers = false;
for (const c of checks) {
  const ok = c.value === undefined ? '?' : c.value === next[c.key] ? 'ok' : 'DIFFERS';
  if (ok !== 'ok') handDiffers = true;
  console.log(
    `  ${c.key.padEnd(15)} ${String(next[c.key]).padEnd(7)} ${String(c.value ?? 'n/a').padEnd(7)}`,
    `${ok.padEnd(8)} (${c.where})`,
  );
}

if (check && !rest[0]) {
  const latest = readRelease().tagName;
  if (latest !== release.tagName)
    console.log(
      `\nNote: ${latest} is the latest release; the film shows ${release.tagName}. ` +
        'npm run facts moves it to the latest once that release has all its files.',
    );
}

if (check && (changes.length > 0 || handDiffers)) {
  console.error(
    '\nsrc/facts.json is not up to date: run npm run facts, edit any hand-kept fact that ' +
      'DIFFERS, and fix this script where one says ? (its source moved).',
  );
  process.exit(1);
}
