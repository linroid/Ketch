import {
  CAPTURE_PAUSED_KEY,
  loadCapturePause,
  setCapturePaused,
} from '../lib/capture-pause.js';
import { withEndpoint } from '../lib/connection.js';
import { ext, remoteOnly } from '../lib/ext.js';
import { describeStatus, describeTaskState, failureHint, taskName } from '../lib/format.js';
import { sendToKetch } from '../lib/handoff.js';
import { localizePage, t } from '../lib/i18n.js';
import { FailureKind, KetchClient } from '../lib/ketch-client.js';
import { isSupportedLinkUrl } from '../lib/request.js';
import { findInstance, loadSettings, saveSettings } from '../lib/settings.js';

const REFRESH_INTERVAL_MS = 1_500;
const REQUEST_TIMEOUT_MS = 5_000;
const MAX_TASKS = 8;
/** The instance last shown in the popup, which may differ from the default one. */
const SHOWN_INSTANCE_KEY = 'popupInstanceId';

const ICONS = {
  pause: '<path d="M8 5v14M16 5v14"/>',
  resume: '<path d="M7 4.5v15l12-7.5z"/>',
};

const $ = (id) => document.getElementById(id);

let settings;
let instance;
let online = false;
let refreshTimer;
let capturePaused = false;
/** Bumped when the shown instance changes, so late responses for the old one are dropped. */
let generation = 0;

localizePage();
init().catch((error) => showOffline(error));

async function init() {
  settings = await loadSettings();
  if (remoteOnly) {
    $('capture').closest('label').hidden = true;
    $('capture-pause').closest('div').hidden = true;
  }
  const stored = await ext.storage.local.get(SHOWN_INSTANCE_KEY);
  showInstance(findInstance(settings, stored[SHOWN_INSTANCE_KEY]).id);
  renderCapture();
  capturePaused = await loadCapturePause();
  renderCapturePause();
  $('capture-pause').disabled = false;
  $('capture-pause').addEventListener('click', onCapturePause);
  ext.storage.onChanged.addListener((changes, area) => {
    if (area !== 'local' || !changes[CAPTURE_PAUSED_KEY]) return;
    capturePaused = changes[CAPTURE_PAUSED_KEY].newValue === true;
    renderCapturePause();
  });

  $('instance-picker').addEventListener('change', (event) => {
    showInstance(event.target.value);
    ext.storage.local.set({ [SHOWN_INSTANCE_KEY]: event.target.value });
  });
  $('capture').addEventListener('change', onCaptureChanged);
  $('page-resources').addEventListener('click', async () => {
    const [tab] = await ext.tabs.query({ active: true, currentWindow: true });
    if (!Number.isInteger(tab?.id)) return;
    await ext.tabs.create({ url: ext.runtime.getURL(`resources/resources.html?tab=${tab.id}`) });
    window.close();
  });
  $('add-form').addEventListener('submit', onAdd);
  $('tasks').addEventListener('click', onTaskAction);
  $('open-app').addEventListener('click', onOpenApp);
  $('open-options').addEventListener('click', () => {
    ext.runtime.openOptionsPage();
    window.close();
  });
}

/**
 * Runs `action` with a client for the shown instance. Looking at the popup never starts the
 * Ketch app; the "Open Ketch" button and sending a download do.
 */
function withClient(action, { launch = false } = {}) {
  return withEndpoint(instance, (endpoint) => {
    return action(new KetchClient(endpoint, { timeoutMs: REQUEST_TIMEOUT_MS }));
  }, { launch });
}

function showInstance(id) {
  instance = findInstance(settings, id);
  renderPicker();
  online = false;
  $('status-dot').removeAttribute('data-state');
  $('status-text').textContent = t('status_connecting');
  $('status-hint').hidden = true;
  $('open-app').hidden = true;
  $('recent').hidden = true;
  $('tasks').replaceChildren();
  generation++;
  refresh();
}

async function refresh() {
  clearTimeout(refreshTimer);
  const current = generation;
  try {
    const [status, tasks] = await withClient((client) => Promise.all([
      online ? null : client.status(),
      client.listTasks(),
    ]));
    if (current !== generation) return;
    if (status) showOnline(status);
    renderTasks(tasks);
  } catch (error) {
    if (current !== generation) return;
    showOffline(error);
  }
  refreshTimer = setTimeout(refresh, REFRESH_INTERVAL_MS);
}

function showOnline(status) {
  online = true;
  $('status-dot').dataset.state = 'online';
  $('status-text').textContent = describeStatus(status);
  $('status-hint').hidden = true;
  $('open-app').hidden = true;
  $('recent').hidden = false;
}

function showOffline(error) {
  online = false;
  // A closed app isn't a problem: it opens when a download needs it.
  const closed = error?.kind === FailureKind.APP_NOT_RUNNING;
  if (closed) {
    $('status-dot').removeAttribute('data-state');
  } else {
    $('status-dot').dataset.state = 'offline';
  }
  $('status-text').textContent = error?.message ?? String(error);
  const hint = instance ? failureHint(error, instance) : '';
  $('status-hint').textContent = hint;
  $('status-hint').dataset.tone = closed ? 'info' : 'error';
  $('status-hint').hidden = !hint;
  $('open-app').hidden = !closed;
  $('recent').hidden = true;
}

async function onOpenApp() {
  $('open-app').disabled = true;
  $('status-text').textContent = t('status_opening_app');
  $('status-hint').hidden = true;
  try {
    await withClient((client) => client.status(), { launch: true });
  } catch (error) {
    showOffline(error);
  } finally {
    $('open-app').disabled = false;
  }
  refresh();
}

function renderPicker() {
  const multiple = settings.instances.length > 1;
  const picker = $('instance-picker');
  picker.hidden = !multiple;
  $('instance-name').hidden = multiple;
  $('instance-name').textContent = instance.name;
  picker.replaceChildren(...settings.instances.map((it) => new Option(
    it.id === settings.defaultInstanceId ? t('instance_name_default', it.name) : it.name,
    it.id,
  )));
  picker.value = instance.id;
}

function renderCapture() {
  $('capture').checked = settings.interceptDownloads;
  const target = $('capture-target');
  target.hidden = settings.instances.length < 2;
  target.textContent = t('popup_capture_target', findInstance(settings).name);
}

async function onCaptureChanged(event) {
  // Reload first so a change made in the options page meanwhile is kept.
  const latest = await loadSettings();
  settings = await saveSettings({ ...latest, interceptDownloads: event.target.checked });
  renderCapture();
}

function renderCapturePause() {
  $('capture-pause').textContent = capturePaused
    ? t('popup_resume_capture') : t('popup_pause_capture');
  const status = $('capture-pause-status');
  status.hidden = !capturePaused;
  status.textContent = capturePaused ? t('popup_capture_paused') : '';
}

async function onCapturePause() {
  $('capture-pause').disabled = true;
  try {
    capturePaused = await setCapturePaused(!capturePaused);
    renderCapturePause();
  } catch {
    $('capture-pause-status').hidden = false;
    $('capture-pause-status').textContent = t('popup_capture_pause_failed');
  } finally {
    $('capture-pause').disabled = false;
  }
}

async function onAdd(event) {
  event.preventDefault();
  const input = $('add-url');
  const url = input.value.trim();
  if (!url) return;
  if (!isSupportedLinkUrl(url)) {
    showAddMessage(t('popup_unsupported_link'), 'error');
    return;
  }
  $('add-button').disabled = true;
  try {
    const current = await loadSettings();
    if (current.confirmDownloads) {
      const response = await ext.runtime.sendMessage({ type: 'review-open',
        instanceId: instance.id, download: { url } });
      if (!response?.ok) throw new Error(response?.message || t('review_unavailable'));
      window.close();
      return;
    }
    const task = await sendToKetch(instance, { url }, current, { timeoutMs: REQUEST_TIMEOUT_MS });
    input.value = '';
    showAddMessage(t('popup_added', taskName(task)), 'success');
    refresh();
  } catch (error) {
    showAddMessage(error.message, 'error');
  } finally {
    $('add-button').disabled = false;
  }
}

function showAddMessage(text, tone) {
  const message = $('add-message');
  message.textContent = text;
  message.dataset.tone = tone;
  message.hidden = false;
}

async function onTaskAction(event) {
  const button = event.target.closest('button[data-action]');
  if (!button) return;
  const taskId = button.closest('li').dataset.taskId;
  button.disabled = true;
  try {
    if (button.dataset.action === 'pause') {
      await withClient((client) => client.pauseTask(taskId));
    } else {
      await withClient((client) => client.resumeTask(taskId));
    }
  } catch (error) {
    showAddMessage(error.message, 'error');
  }
  refresh();
}

/** Updates the list in place, so a button under the pointer survives each refresh. */
function renderTasks(tasks) {
  const recent = [...tasks]
    .sort((a, b) => Date.parse(b.createdAt) - Date.parse(a.createdAt))
    .slice(0, MAX_TASKS);
  const list = $('tasks');
  const existing = new Map([...list.children].map((item) => [item.dataset.taskId, item]));
  recent.forEach((task, index) => {
    const item = existing.get(task.taskId) ?? createTaskItem(task.taskId);
    existing.delete(task.taskId);
    updateTaskItem(item, task);
    if (list.children[index] !== item) list.insertBefore(item, list.children[index] ?? null);
  });
  existing.forEach((item) => item.remove());
  $('tasks-empty').hidden = recent.length > 0;
}

function createTaskItem(taskId) {
  const item = document.createElement('li');
  item.className = 'task';
  item.dataset.taskId = taskId;
  const name = document.createElement('div');
  name.className = 'task-name';
  const button = document.createElement('button');
  button.type = 'button';
  button.className = 'icon';
  const progress = document.createElement('div');
  progress.className = 'progress';
  progress.append(document.createElement('span'));
  const state = document.createElement('div');
  state.className = 'task-state';
  item.append(name, button, progress, state);
  return item;
}

function updateTaskItem(item, task) {
  const [name, button, progress, state] = item.children;
  const view = describeTaskState(task);
  item.dataset.tone = view.tone;
  name.textContent = taskName(task);
  name.title = task.request?.url ?? '';
  state.textContent = view.text;
  state.title = view.text;
  progress.hidden = view.progress === null;
  progress.firstElementChild.style.width = `${Math.round((view.progress ?? 0) * 100)}%`;

  const action = view.canPause ? 'pause' : view.canResume ? 'resume' : null;
  button.hidden = action === null;
  if (action && button.dataset.action !== action) {
    button.dataset.action = action;
    button.title = action === 'pause' ? t('task_pause') : t('task_resume');
    button.setAttribute('aria-label', button.title);
    button.innerHTML = `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" ` +
      `stroke-width="2" stroke-linejoin="round" aria-hidden="true">${ICONS[action]}</svg>`;
  }
  button.disabled = false;
}
