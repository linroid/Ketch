import { withEndpoint } from '../lib/connection.js';
import { ext } from '../lib/ext.js';
import { failureHint, formatBytes, nameFromUrl, withHint } from '../lib/format.js';
import { localizePage, t } from '../lib/i18n.js';
import { FailureKind, KetchClient } from '../lib/ketch-client.js';
import { fileNameFromPath, isTorrentFile, isTorrentUrl } from '../lib/request.js';
import {
  canDownloadDirectly, categoryFor, deviceFolders, knownDeviceFolders, readReview,
  recentFolders, rememberDeviceFolders, reviewDestination,
} from '../lib/review.js';
import { loadSettings } from '../lib/settings.js';

/** Checking a device never opens the Ketch app; sending does. */
const PROBE_TIMEOUT_MS = 4_000;

const KINDS = {
  archive: ['zip', '7z', 'rar', 'tar', 'gz', 'tgz', 'bz2', 'xz', 'zst', 'iso', 'dmg'],
  video: ['mp4', 'mkv', 'mov', 'avi', 'webm', 'flv', 'wmv', 'm4v', 'm3u8', 'mpd', 'ts'],
  audio: ['mp3', 'flac', 'wav', 'aac', 'ogg', 'm4a', 'opus'],
  image: ['jpg', 'jpeg', 'png', 'gif', 'webp', 'svg', 'avif', 'heic', 'bmp', 'tiff'],
  document: ['pdf', 'doc', 'docx', 'xls', 'xlsx', 'ppt', 'pptx', 'txt', 'md', 'epub', 'csv'],
  app: ['exe', 'msi', 'apk', 'pkg', 'deb', 'rpm', 'appimage', 'jar'],
};

const ICONS = {
  app: '<path d="M4 5.5A1.5 1.5 0 0 1 5.5 4h13A1.5 1.5 0 0 1 20 5.5V15H4z"/>' +
    '<path d="M2.5 18.5h19"/>',
  server: '<rect x="4" y="4" width="16" height="7" rx="1.5"/>' +
    '<rect x="4" y="13" width="16" height="7" rx="1.5"/><path d="M8 7.5h.01M8 16.5h.01"/>',
  auto: '<path d="M12 3.5 13.8 8.2 18.5 10l-4.7 1.8L12 16.5l-1.8-4.7L5.5 10l4.7-1.8z"/>' +
    '<path d="M18.5 15.5v4M16.5 17.5h4"/>',
  category: '<path d="M3 7.5A1.5 1.5 0 0 1 4.5 6h4.4l2 2h8.6A1.5 1.5 0 0 1 21 9.5v8a1.5 1.5 0 0 1' +
    '-1.5 1.5h-15A1.5 1.5 0 0 1 3 17.5z"/>',
  recent: '<circle cx="12" cy="12" r="8.5"/><path d="M12 7.5V12l3 2"/>',
  magnet: '<path d="M6 4v7a6 6 0 0 0 12 0V4h-4v7a2 2 0 0 1-4 0V4z"/><path d="M6 8h4M14 8h4"/>',
};

const $ = (id) => document.getElementById(id);
const id = new URL(location.href).searchParams.get('id');
let entry;
let settings;
let torrent = false;
let busy = false;
/** Folders last used on the selected device, most recent first. */
let recent = [];
/** Folders each device last reported, for devices that can't be asked now. */
const known = new Map();
/** Where Auto saves on the selected device, once it has said. */
let autoPath;
/** The Auto path filled into the folder field for editing, until it is changed or left. */
let autoFilled;
/** What each device answered when asked for its status, by instance id. */
const probes = new Map();

localizePage();
$('form').addEventListener('submit', onSend);
$('cancel').addEventListener('click', onCancel);
$('direct').addEventListener('click', onDirect);
$('name').addEventListener('input', () => { validate(); renderFolders(); });
$('folder').addEventListener('input', () => { renderFolders(); validate(); });
$('folder').addEventListener('focus', fillAutoFolder);
$('folder').addEventListener('blur', () => {
  // Left as filled in, the folder is still the device's choice.
  if (autoFilled !== undefined && $('folder').value === autoFilled) $('folder').value = '';
  autoFilled = undefined;
  renderFolders();
});
$('devices').addEventListener('change', () => renderDevice());
document.addEventListener('keydown', (event) => {
  if (event.key === 'Escape' && !busy) onCancel();
});
// Over a page, the review lives in a frame the page's overlay sizes and removes.
const embedded = window.parent !== window;
// The review ends when this page does: closed, or its overlay removed with the page.
ext.runtime.connect({ name: `review:${id}` });
tellOverlay('ready');
init().catch((error) => {
  setBusy(true);
  showStatus(error.message, 'error');
});

async function init() {
  [entry, settings] = await Promise.all([readReview(id), loadSettings()]);
  if (!entry || entry.state !== 'pending') throw new Error(t('review_unavailable'));
  const { download } = entry;
  torrent = isTorrentUrl(download.url) || isTorrentFile(download);
  const media = /\.(m3u8|mpd)(?:[?#]|$)/i.test(download.url);
  const suggested = fileNameFromPath(download.fileName);
  const urlName = nameFromUrl(download.url);

  $('name').hidden = torrent;
  $('title').hidden = !torrent;
  $('title').textContent = torrent ? suggested || urlName : '';
  $('torrent-note').hidden = !torrent;
  $('name').value = torrent || media ? '' : suggested;
  $('name').placeholder = urlName;
  renderBadge();
  renderMeta();

  await Promise.all(settings.instances.map(async (instance) => {
    const folders = await knownDeviceFolders(instance.id);
    if (folders) known.set(instance.id, folders);
  }));
  renderDevices();
  $('direct').hidden = !canDownloadDirectly(download);
  $('send').disabled = false;
  validate();
  await renderDevice();
  if (!torrent) {
    // Select the name without its extension, ready to be typed over.
    const name = $('name');
    name.focus();
    const dot = name.value.lastIndexOf('.');
    name.setSelectionRange(0, dot > 0 ? dot : name.value.length);
  } else {
    $('send').focus();
  }
  for (const instance of settings.instances) probe(instance);
}

function selectedInstance() {
  const value = new FormData($('form')).get('device');
  return settings.instances.find((it) => it.id === value) ?? settings.instances[0];
}

/** One card per device, the review's device first selected. */
function renderDevices() {
  const devices = $('devices');
  devices.toggleAttribute('data-single', settings.instances.length < 2);
  const template = $('device-template').content;
  devices.replaceChildren(...settings.instances.map((instance) => {
    const card = template.firstElementChild.cloneNode(true);
    const radio = card.querySelector('input');
    radio.value = instance.id;
    radio.checked = instance.id === entry.instanceId;
    card.dataset.instance = instance.id;
    card.querySelector('.device-icon').innerHTML = icon(instance.type === 'app' ? 'app' : 'server');
    card.querySelector('.device-name').textContent = instance.name;
    if (instance.url) card.title = instance.url;
    return card;
  }));
  if (!devices.querySelector('input:checked')) devices.querySelector('input').checked = true;
  for (const instance of settings.instances) renderProbe(instance);
}

async function probe(instance) {
  let result;
  try {
    const status = await withEndpoint(instance, (endpoint) =>
      new KetchClient(endpoint, { timeoutMs: PROBE_TIMEOUT_MS }).status(), { launch: false });
    result = { state: 'online', status };
    rememberDeviceFolders(instance.id, status);
  } catch (error) {
    result = { state: error?.kind === FailureKind.APP_NOT_RUNNING ? 'closed' : 'offline', error };
  }
  probes.set(instance.id, result);
  renderProbe(instance);
  if (instance.id === selectedInstance().id) renderDevice();
}

function renderProbe(instance) {
  const card = $('devices').querySelector(`[data-instance="${CSS.escape(instance.id)}"]`);
  if (!card) return;
  const result = probes.get(instance.id);
  const dot = card.querySelector('.status-dot');
  const text = card.querySelector('.device-status');
  dot.removeAttribute('data-state');
  text.removeAttribute('title');
  if (!result) {
    text.textContent = t('dialog_device_checking');
  } else if (result.state === 'online') {
    dot.dataset.state = 'online';
    const free = result.status.system?.usableSpace;
    text.textContent = free > 0 ? t('dialog_device_free', formatBytes(free))
      : t('dialog_device_ready');
    text.title = result.status.system?.downloadDirectory ?? '';
  } else if (result.state === 'closed') {
    text.textContent = t('dialog_device_app_closed');
  } else {
    dot.dataset.state = 'offline';
    text.textContent = t('dialog_device_offline');
    text.title = result.error?.message ?? '';
  }
}

/** Folder suggestions and warnings for the selected device. */
async function renderDevice() {
  const instance = selectedInstance();
  const status = probes.get(instance.id)?.status;
  // Why the selected device can't be reached, and what to do about it.
  const probe = probes.get(instance.id);
  const problem = $('device-problem');
  problem.hidden = probe?.state !== 'offline';
  if (!problem.hidden) {
    problem.textContent = withHint(probe.error?.message ?? t('dialog_device_offline'),
      failureHint(probe.error, instance));
  }
  const folders = await recentFolders(instance.id);
  if (instance.id !== selectedInstance().id) return;
  recent = folders;
  renderFolders();
  const free = status?.system?.usableSpace;
  const size = entry.download.size;
  const short = Number.isFinite(size) && Number.isFinite(free) && free >= 0 && size > free;
  $('space-warning').hidden = !short;
  if (short) {
    $('space-warning').textContent = t('dialog_space_warning', formatBytes(free), instance.name);
  }
  fitWindow();
}

/**
 * Quick folder options for the selected device: Auto, which leaves the folder to the device and
 * names the category folder it would sort this file into, the device's category folders, then
 * the folders last used there.
 */
function renderFolders() {
  const instanceId = selectedInstance().id;
  const folders = deviceFolders(probes.get(instanceId)?.status) ?? known.get(instanceId);
  // An Android content:// tree is no path: only the device can find its category folders.
  const directory = isFolderUri(folders?.directory) ? undefined : folders?.directory;
  const categories = folders?.categories ?? [];
  const auto = torrent ? undefined : categoryFor(categories, $('name').value ||
    $('name').placeholder, entry.download.mime, hostOf(entry.download.url));
  const join = (folder) => `${directory.replace(/[\\/]+$/, '')}/${folder.trim()}`;
  autoPath = directory && auto ? join(auto.folder) : directory || undefined;
  $('folder').placeholder = autoPath ?? t('dialog_folder_default');

  const current = folderValue().trim();
  const chip = (kind, label, value, title) => {
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'chip';
    button.dataset.kind = kind;
    button.innerHTML = icon(kind);
    const text = document.createElement('span');
    text.textContent = label;
    button.append(text);
    button.title = title;
    button.setAttribute('aria-pressed', String(current === value));
    button.addEventListener('click', () => {
      $('folder').value = value;
      renderFolders();
      validate();
    });
    return button;
  };
  const chips = [chip('auto', auto ? t('dialog_folder_auto_match', lastSegment(auto.folder))
    : t('dialog_folder_auto'), '', t('dialog_folder_auto_detail'))];
  const shown = new Set(['']);
  if (directory) {
    for (const category of categories) {
      const value = join(category.folder);
      if (shown.has(value)) continue;
      shown.add(value);
      chips.push(chip('category', lastSegment(category.folder), value, value));
    }
  }
  for (const folder of recent) {
    if (shown.has(folder)) continue;
    shown.add(folder);
    chips.push(chip('recent', lastSegment(folder), folder,
      t('dialog_folder_recent', folder)));
  }
  $('folders').replaceChildren(...chips);
  fitWindow();
}

/** With Auto selected, puts the path it saves to in the field, ready to be edited. */
function fillAutoFolder() {
  const field = $('folder');
  if (field.value || !autoPath) return;
  field.value = autoPath;
  autoFilled = autoPath;
  field.setSelectionRange(autoPath.length, autoPath.length);
  field.scrollLeft = field.scrollWidth;
}

/** The folder to send: empty, the device's choice, while it is Auto's path unchanged. */
function folderValue() {
  const value = $('folder').value;
  return value === autoFilled ? '' : value;
}

function isFolderUri(folder) {
  return /^[a-z][a-z\d+.-]*:\/\//i.test(folder ?? '');
}

function hostOf(url) {
  try {
    return new URL(url).hostname;
  } catch {
    return undefined;
  }
}

function renderBadge() {
  const badge = $('badge');
  const { url } = entry.download;
  if (/^magnet:/i.test(url)) {
    badge.dataset.kind = 'torrent';
    badge.innerHTML = icon('magnet');
    return;
  }
  const name = torrent ? 'file.torrent' : $('name').value || $('name').placeholder;
  const extension = /\.([a-z0-9]{1,8})$/i.exec(name)?.[1]?.toLowerCase();
  badge.dataset.kind = extension === 'torrent' ? 'torrent'
    : Object.keys(KINDS).find((kind) => KINDS[kind].includes(extension)) ?? 'file';
  badge.textContent = extension ? extension.slice(0, 4).toUpperCase() : '';
  if (!extension) badge.innerHTML = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" ' +
    'stroke-width="1.8" stroke-linejoin="round"><path d="M6 3h8l4 4v14H6z"/>' +
    '<path d="M14 3v4h4"/></svg>';
}

function renderMeta() {
  const { url, size } = entry.download;
  const parts = [];
  if (Number.isFinite(size) && size > 0) parts.push(formatBytes(size));
  if (/^magnet:/i.test(url)) {
    parts.push(t('task_magnet_link'));
  } else {
    try {
      parts.push(new URL(url).host);
    } catch {
      // Shown as the full link below.
    }
  }
  $('meta').textContent = parts.join(' · ') || url;
  $('meta').title = url;
}

/** Marks a name or folder the device would refuse, before anything is sent. */
function validate() {
  if (!entry) return false;
  renderBadge();
  let valid = true;
  try {
    reviewDestination(entry.download, $('name').value, folderValue());
  } catch {
    valid = false;
  }
  $('name').setAttribute('aria-invalid', String(!valid));
  $('name-error').hidden = valid;
  $('send').disabled = busy || !valid;
  fitWindow();
  return valid;
}

async function onSend(event) {
  event.preventDefault();
  if (busy || !validate()) return;
  const instance = selectedInstance();
  setBusy(true);
  showStatus(probes.get(instance.id)?.state === 'closed' ? t('status_opening_app')
    : t('dialog_sending'));
  try {
    const response = await ext.runtime.sendMessage({ type: 'review-send', id,
      instanceId: instance.id, fileName: $('name').value, folder: folderValue() });
    if (!response?.ok) {
      showStatus(withHint(response?.message || t('review_unavailable'),
        failureHint({ kind: response?.kind }, instance)), 'error');
      if (response?.kind !== 'uncertain') setBusy(false);
      return;
    }
    showStatus(t('notify_download_added'));
    closeDialog();
  } catch {
    // If the background disappeared, do not offer a second submission without its state.
    showStatus(t('error_handoff_uncertain'), 'error');
  }
}

async function onCancel() {
  setBusy(true);
  await ext.runtime.sendMessage({ type: 'review-cancel', id }).catch(() => {});
  closeDialog();
}

async function onDirect() {
  setBusy(true);
  const response = await ext.runtime.sendMessage({ type: 'review-direct', id,
    fileName: $('name').value }).catch(() => undefined);
  if (response?.ok) {
    closeDialog();
    return;
  }
  showStatus(response?.message || t('notify_direct_failed'), 'error');
  setBusy(false);
}

function setBusy(value) {
  busy = value;
  for (const element of $('form').elements) element.disabled = value;
  if (!value) validate();
}

function showStatus(text, tone) {
  const status = $('status');
  status.textContent = text;
  status.hidden = !text;
  if (tone) status.dataset.tone = tone;
  else status.removeAttribute('data-tone');
  fitWindow();
}

let fitting;

/** Sizes the review window to its content, within the screen. */
function fitWindow() {
  if (embedded) {
    cancelAnimationFrame(fitting);
    fitting = requestAnimationFrame(() =>
      tellOverlay('resize', { height: $('form').getBoundingClientRect().height }));
    return;
  }
  if (!ext.windows?.getCurrent) return;
  cancelAnimationFrame(fitting);
  fitting = requestAnimationFrame(async () => {
    try {
      const current = await ext.windows.getCurrent();
      if (current.type !== 'popup') return;
      const frame = window.outerHeight - window.innerHeight;
      const height = Math.min(screen.availHeight - 48,
        Math.ceil($('form').getBoundingClientRect().height) + frame);
      if (Math.abs(height - window.outerHeight) > 2) {
        await ext.windows.update(current.id, { height });
      }
    } catch {
      // The window keeps its size and scrolls.
    }
  });
}

/** Messages the overlay holding this frame; nothing in a window. */
function tellOverlay(action, details = {}) {
  if (embedded) window.parent.postMessage({ ketchReview: true, action, ...details }, '*');
}

function closeDialog() {
  if (embedded) tellOverlay('close');
  else window.close();
}

function icon(name) {
  return '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" ' +
    `stroke-linecap="round" stroke-linejoin="round">${ICONS[name]}</svg>`;
}

function lastSegment(path) {
  return path.replace(/[\\/]+$/, '').split(/[\\/]/).pop() || path;
}
