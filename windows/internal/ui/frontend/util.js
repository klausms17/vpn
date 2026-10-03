// Small helpers of the window: the Bridge, elements, the toast and Russian
// numbers and dates. Everything from outside goes in as text, never as
// markup.
import { Call } from "/wails/runtime.js";

const BRIDGE = "github.com/klausms17/vpn/windows/internal/ui.Bridge.";

export const call = (method, ...args) => Call.ByName(BRIDGE + method, ...args);
export const $ = (id) => document.getElementById(id);

export function el(tag, cls, text) {
  const node = document.createElement(tag);
  if (cls) node.className = cls;
  if (text !== undefined) node.textContent = text;
  return node;
}

export function icon(name) {
  return $("icon-" + name).content.firstElementChild.cloneNode(true);
}

let toastTimer = 0;
// busy turns button into a waiting one while work runs.
export async function busy(button, label, work) {
  const text = button.textContent;
  button.disabled = true;
  button.textContent = label;
  try {
    return await work();
  } finally {
    button.disabled = false;
    button.textContent = text;
  }
}

export function toast(text) {
  const node = $("toast");
  node.textContent = text;
  node.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { node.hidden = true; }, 5000);
}

export const fail = (err) => toast((err && err.message) || String(err));

// ipc.Status states (Android's VpnState).
const STATE = { 0: "off", 1: "connecting", 2: "on", 3: "disconnecting", 4: "error" };

export function stateOf(s) {
  if (!s || !s.service) return "noservice";
  if (s.outdated) return "outdated";
  return STATE[s.status.state] || "off";
}

// The server the tunnel runs (or starts, or stops), else the selected one,
// as Android's VpnStatus.shownServer.
export function shownServer(s) {
  const st = s.status;
  const active = st.state === 1 || st.state === 2 || st.state === 3;
  const id = active && st.profileId ? st.profileId : s.profiles.selectedId;
  return s.profiles.profiles.find((p) => p.id === id) || null;
}

export function duration(ms) {
  const t = Math.max(0, Math.floor(ms / 1000));
  const h = Math.floor(t / 3600);
  const m = String(Math.floor((t % 3600) / 60)).padStart(2, "0");
  const sec = String(t % 60).padStart(2, "0");
  return h > 0 ? `${h}:${m}:${sec}` : `${m}:${sec}`;
}

// "1 сервер", "3 сервера", "12 серверов".
export function plural(n, one, few, many) {
  const tens = n % 100;
  const units = n % 10;
  if (tens >= 11 && tens <= 14) return `${n} ${many}`;
  if (units === 1) return `${n} ${one}`;
  if (units >= 2 && units <= 4) return `${n} ${few}`;
  return `${n} ${many}`;
}

// Bytes in 1024 steps, as Android's formatBytes: "12,4 ГБ", "512 МБ".
export function bytes(b) {
  const units = ["Б", "КБ", "МБ", "ГБ", "ТБ"];
  let v = Math.max(0, b);
  let i = 0;
  while (v >= 1024 && i < units.length - 1) {
    v /= 1024;
    i++;
  }
  if (i === 0) return `${v} Б`;
  const n = v >= 100 ? String(Math.round(v)) : String(Math.round(v * 10) / 10).replace(".", ",");
  return `${n} ${units[i]}`;
}

const dayMonth = new Intl.DateTimeFormat("ru", { day: "numeric", month: "long" });
const clock = new Intl.DateTimeFormat("ru", { hour: "2-digit", minute: "2-digit" });
const numeric = new Intl.DateTimeFormat("ru", { day: "2-digit", month: "2-digit", year: "numeric" });

// "25 октября", with the year when it is not this one; "" for no date.
export function russianDate(ms) {
  const d = new Date(ms);
  if (!Number.isFinite(d.getTime())) return "";
  const day = dayMonth.format(d);
  return d.getFullYear() === new Date().getFullYear() ? day : `${day} ${d.getFullYear()}`;
}

// When a subscription last got a fresh list, as Android says it.
export function updatedText(ms) {
  const d = new Date(ms);
  if (!ms || !Number.isFinite(d.getTime())) return "Ещё не обновлялась";
  const today = d.toDateString() === new Date().toDateString();
  return today ? `Обновлено сегодня в ${clock.format(d)}` : `Обновлено ${numeric.format(d)} в ${clock.format(d)}`;
}
