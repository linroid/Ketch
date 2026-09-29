import { ext } from '../lib/ext.js';
import { describeStatus, describeTaskState, failureHint, taskName } from '../lib/format.js';
import { sendToKetch } from '../lib/handoff.js';
import { KetchClient } from '../lib/ketch-client.js';
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
let client;
let online = false;
let refreshTimer;
/** Bumped when the shown instance changes, so late responses for the old one are dropped. */
let generation = 0;

init().catch((error) => showOffline(error));

async function init() {
  settings = await loadSettings();
  const stored = await ext.storage.local.get(SHOWN_INSTANCE_KEY);
  showInstance(findInstance(settings, stored[SHOWN_INSTANCE_KEY]).id);
  renderCapture();

  $('instance-picker').addEventListener('change', (event) => {
    showInstance(event.target.value);
    ext.storage.local.set({ [SHOWN_INSTANCE_KEY]: event.target.value });
  });
  $('capture').addEventListener('change', onCaptureChanged);
  $('add-form').addEventListener('submit', onAdd);
  $('tasks').addEventListener('click', onTaskAction);
  $('open-options').addEventListener('click', () => {
    ext.runtime.openOptionsPage();
    window.close();
  });
}

function showInstance(id) {
  instance = findInstance(settings, id);
  client = new KetchClient(instance, { timeoutMs: REQUEST_TIMEOUT_MS });
  renderPicker();
  online = false;
  $('status-dot').removeAttribute('data-state');
  $('status-text').textContent = 'Connecting…';
  $('status-hint').hidden = true;
  $('recent').hidden = true;
  $('tasks').replaceChildren();
  generation++;
  refresh();
}

async function refresh() {
  clearTimeout(refreshTimer);
  const current = generation;
  try {
    const [status, tasks] = await Promise.all([
      online ? null : client.status(),
      client.listTasks(),
    ]);
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
  $('recent').hidden = false;
}

function showOffline(error) {
  online = false;
  $('status-dot').dataset.state = 'offline';
  $('status-text').textContent = error?.message ?? String(error);
  const hint = instance ? failureHint(error, instance) : '';
  $('status-hint').textContent = hint;
  $('status-hint').hidden = !hint;
  $('recent').hidden = true;
}

function renderPicker() {
  const multiple = settings.instances.length > 1;
  const picker = $('instance-picker');
  picker.hidden = !multiple;
  $('instance-name').hidden = multiple;
  $('instance-name').textContent = instance.name;
  picker.replaceChildren(...settings.instances.map((it) => new Option(
    it.id === settings.defaultInstanceId ? `${it.name} (default)` : it.name,
    it.id,
  )));
  picker.value = instance.id;
}

function renderCapture() {
  $('capture').checked = settings.interceptDownloads;
  const target = $('capture-target');
  target.hidden = settings.instances.length < 2;
  target.textContent = `Sent to ${findInstance(settings).name}`;
}

async function onCaptureChanged(event) {
  // Reload first so a change made in the options page meanwhile is kept.
  const latest = await loadSettings();
  settings = await saveSettings({ ...latest, interceptDownloads: event.target.checked });
  renderCapture();
}

async function onAdd(event) {
  event.preventDefault();
  const input = $('add-url');
  const url = input.value.trim();
  if (!url) return;
  if (!isSupportedLinkUrl(url)) {
    showAddMessage('Enter an http, https, ftp or magnet link.', 'error');
    return;
  }
  $('add-button').disabled = true;
  try {
    const task = await sendToKetch(instance, { url }, settings, { timeoutMs: REQUEST_TIMEOUT_MS });
    input.value = '';
    showAddMessage(`Added ${taskName(task)}`, 'success');
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
      await client.pauseTask(taskId);
    } else {
      await client.resumeTask(taskId);
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
    button.title = action === 'pause' ? 'Pause' : 'Resume';
    button.setAttribute('aria-label', button.title);
    button.innerHTML = `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" ` +
      `stroke-width="2" stroke-linejoin="round" aria-hidden="true">${ICONS[action]}</svg>`;
  }
  button.disabled = false;
}
