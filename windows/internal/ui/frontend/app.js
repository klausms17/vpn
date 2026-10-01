// The window of Kirov VPN for Windows. It shows the snapshot the app's Go
// side keeps (the service's status and servers) and sends the service the
// user's requests through the Bridge. Strings are Russian.
import { Call, Events } from "/wails/runtime.js";

const BRIDGE = "github.com/klausms17/vpn/windows/internal/ui.Bridge.";
const call = (method, ...args) => Call.ByName(BRIDGE + method, ...args);
const $ = (id) => document.getElementById(id);

// ipc.Status states (Android's VpnState).
const STATE = { 0: "off", 1: "connecting", 2: "on", 3: "disconnecting", 4: "error" };

const hero = $("hero");
const power = $("power");
const title = $("title");
const detail = $("detail");
const serverName = $("server-name");
const addSheet = $("add-sheet");
const keyInput = $("key");
const addSave = $("add-save");

let snap = null;
let clock = 0;

function stateOf(s) {
  if (!s || !s.service) return "noservice";
  if (s.outdated) return "outdated";
  return STATE[s.status.state] || "off";
}

// The server the tunnel runs (or is starting, or stopping), else the
// selected one, as Android's VpnStatus.shownServer.
function shownServer(s) {
  const st = s.status;
  const active = st.state === 1 || st.state === 2 || st.state === 3;
  const id = active && st.profileId ? st.profileId : s.profiles.selectedId;
  const p = s.profiles.profiles.find((x) => x.id === id);
  return p ? p.name : (active && st.profileName) || "";
}

function duration(ms) {
  const t = Math.max(0, Math.floor(ms / 1000));
  const h = Math.floor(t / 3600);
  const m = String(Math.floor((t % 3600) / 60)).padStart(2, "0");
  const sec = String(t % 60).padStart(2, "0");
  return h > 0 ? `${h}:${m}:${sec}` : `${m}:${sec}`;
}

function render(s) {
  if (snap && s && s.seq < snap.seq) return;
  snap = s;
  const state = stateOf(s);
  hero.dataset.state = state;
  const st = s ? s.status : {};
  const server = s && s.service ? shownServer(s) : "";
  const hasServers = !!(s && s.profiles.profiles.length);
  serverName.textContent = server || (hasServers ? "Не выбран" : "Нет серверов — добавьте ключ");

  clearInterval(clock);
  clock = 0;
  let text = "";
  switch (state) {
    case "noservice":
      title.textContent = "Нет связи со службой";
      text = "Служба Kirov VPN не запущена. Перезагрузите компьютер или переустановите Kirov VPN.";
      break;
    case "outdated":
      title.textContent = "Kirov VPN обновился";
      text = "Закройте его в трее («Выход») и откройте снова.";
      break;
    case "off":
      title.textContent = "Отключено";
      text = st.message || (hasServers ? "Нажмите на кнопку, чтобы подключиться" : "Сначала добавьте ключ сервера");
      break;
    case "connecting":
      title.textContent = "Подключение…";
      text = st.message || server;
      break;
    case "on": {
      title.textContent = "Подключено";
      const tick = () => {
        const since = st.connectedSince ? ` · ${duration(Date.now() - st.connectedSince)}` : "";
        detail.textContent = st.message || `${server}${since}`;
      };
      tick();
      if (!st.message && st.connectedSince) clock = setInterval(tick, 1000);
      break;
    }
    case "disconnecting":
      title.textContent = "Отключение…";
      break;
    case "error":
      title.textContent = "Не удалось подключиться";
      text = st.message || "";
      break;
  }
  if (state !== "on") detail.textContent = text;

  const on = state === "on" || state === "connecting";
  power.disabled = !["off", "error", "on", "connecting"].includes(state);
  power.setAttribute("aria-label", on ? "Отключить" : "Подключить");
}

let toastTimer = 0;
function toast(text) {
  const el = $("toast");
  el.textContent = text;
  el.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { el.hidden = true; }, 4500);
}

const fail = (err) => toast((err && err.message) || String(err));

power.addEventListener("click", () => {
  const state = stateOf(snap);
  if (state === "on" || state === "connecting") {
    call("Disconnect").catch(fail);
  } else if (snap && !snap.profiles.profiles.length) {
    openAdd();
  } else {
    call("Connect").catch(fail);
  }
});

function openAdd() {
  addSheet.hidden = false;
  keyInput.focus();
}

function closeAdd() {
  addSheet.hidden = true;
  keyInput.value = "";
}

async function save() {
  const text = keyInput.value.trim();
  if (!text) {
    toast("Вставьте ключ сервера");
    return;
  }
  addSave.disabled = true;
  addSave.textContent = "Добавление…";
  try {
    toast(await call("Import", text));
    closeAdd();
  } catch (err) {
    fail(err);
  } finally {
    addSave.disabled = false;
    addSave.textContent = "Добавить";
  }
}

$("add-open").addEventListener("click", openAdd);
$("add-cancel").addEventListener("click", closeAdd);
addSave.addEventListener("click", save);
keyInput.addEventListener("keydown", (e) => {
  if (e.key === "Enter" && (e.ctrlKey || e.metaKey)) save();
  if (e.key === "Escape") closeAdd();
});

// A link or file dropped on the window must not replace the page.
for (const type of ["dragover", "drop"]) {
  window.addEventListener(type, (e) => e.preventDefault());
}

Events.On("snapshot", (ev) => render(ev.data));
call("Snapshot").then(render, fail);
call("Version").then((v) => { $("version").textContent = `Версия ${v}`; }, () => {});
call("Loaded").catch(() => {});
