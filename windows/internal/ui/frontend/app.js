// The window of Kirov VPN for Windows. It shows the snapshot the app's Go
// side keeps (the service's status, servers, their checks and settings)
// and sends the service the user's requests through the Bridge. Strings
// are Russian; everything from outside goes in as text, never as markup.
import { Call, Events } from "/wails/runtime.js";

const BRIDGE = "github.com/klausms17/vpn/windows/internal/ui.Bridge.";
const call = (method, ...args) => Call.ByName(BRIDGE + method, ...args);
const $ = (id) => document.getElementById(id);

// ipc.Status states (Android's VpnState).
const STATE = { 0: "off", 1: "connecting", 2: "on", 3: "disconnecting", 4: "error" };
const MODES = {
  ru_direct: "Всё, кроме российских сайтов",
  blocked_only: "Только заблокированные сайты",
  global: "Весь интернет",
};

let snap = null;
let clock = 0;

function el(tag, cls, text) {
  const node = document.createElement(tag);
  if (cls) node.className = cls;
  if (text !== undefined) node.textContent = text;
  return node;
}

function icon(name) {
  return $("icon-" + name).content.firstElementChild.cloneNode(true);
}

let toastTimer = 0;
function toast(text) {
  const node = $("toast");
  node.textContent = text;
  node.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { node.hidden = true; }, 4500);
}

const fail = (err) => toast((err && err.message) || String(err));

// ---------------------------------------------------------------- tabs

function show(view) {
  closeMenu();
  for (const v of document.querySelectorAll(".view")) v.hidden = v.id !== "view-" + view;
  const tab = view === "logs" ? "settings" : view;
  for (const t of document.querySelectorAll(".tab")) {
    if (t.dataset.tab === tab) t.setAttribute("aria-current", "page");
    else t.removeAttribute("aria-current");
  }
  $("views").scrollTop = 0;
  if (view === "servers") pingAllOnce();
  if (view === "logs") loadLogs();
}

for (const t of document.querySelectorAll(".tab")) {
  t.addEventListener("click", () => show(t.dataset.tab));
}

// ---------------------------------------------------------------- state

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
  return { id: p ? p.id : "", name: p ? p.name : (active && st.profileName) || "" };
}

function duration(ms) {
  const t = Math.max(0, Math.floor(ms / 1000));
  const h = Math.floor(t / 3600);
  const m = String(Math.floor((t % 3600) / 60)).padStart(2, "0");
  const sec = String(t % 60).padStart(2, "0");
  return h > 0 ? `${h}:${m}:${sec}` : `${m}:${sec}`;
}

// A server's check, graded as Android's pingGrade.
function setPing(node, p) {
  node.className = "ping";
  if (!p) {
    node.textContent = "";
  } else if (p.state === "testing") {
    node.textContent = "…";
  } else if (p.state === "ok") {
    node.textContent = `${p.ms} мс`;
    node.classList.add(p.ms < 150 ? "great" : p.ms < 400 ? "good" : p.ms < 1000 ? "fair" : "poor");
  } else {
    node.textContent = "нет связи";
    node.classList.add("failed");
  }
}

function render(s) {
  if (snap && s && s.seq < snap.seq) return;
  snap = s;
  renderHome();
  renderServers();
  renderSettings();
}

// ---------------------------------------------------------------- home

const hero = $("hero");
const power = $("power");
const title = $("title");
const detail = $("detail");
let pingedSelected = false;

function renderHome() {
  const s = snap;
  const state = stateOf(s);
  hero.dataset.state = state;
  const st = s ? s.status : {};
  const server = s && s.service ? shownServer(s) : { id: "", name: "" };
  const hasServers = !!(s && s.service && s.profiles.profiles.length);

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
      text = st.message || server.name;
      break;
    case "on": {
      title.textContent = "Подключено";
      const tick = () => {
        const since = st.connectedSince ? ` · ${duration(Date.now() - st.connectedSince)}` : "";
        detail.textContent = st.message || `${server.name}${since}`;
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
  $("hero-logs").hidden = state !== "error";

  const on = state === "on" || state === "connecting";
  power.disabled = !["off", "error", "on", "connecting"].includes(state);
  power.setAttribute("aria-label", on ? "Отключить" : "Подключить");

  $("home-server-name").textContent = !(s && s.service) ? "—" : server.name || (hasServers ? "Не выбран" : "Нет серверов");
  setPing($("home-ping"), s && server.id ? s.pings[server.id] : null);
  $("home-add").hidden = !(s && s.service) || hasServers;
  if (!pingedSelected && s && s.service && server.id && !s.pings[server.id]) {
    pingedSelected = true;
    call("Ping", [server.id]).catch(() => {});
  }
}

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
$("home-server").addEventListener("click", () => show("servers"));
$("home-mode").addEventListener("click", () => show("settings"));
$("home-add").addEventListener("click", openAdd);
$("hero-logs").addEventListener("click", () => show("logs"));

// ---------------------------------------------------------------- servers

const serverList = $("server-list");
const rows = new Map();
let pingedAll = false;

function protocolText(p) {
  const proto = (p.protocol || "").toUpperCase();
  return p.security && p.security !== "none" ? `${proto} · ${p.security.toUpperCase()}` : proto;
}

function serverRow(id) {
  const row = el("div", "row server");
  row.setAttribute("role", "radio");
  row.tabIndex = 0;
  const text = el("span", "row-text");
  const name = el("span", "row-title");
  const sub = el("span", "row-sub");
  text.append(name, sub);
  const ping = el("span", "ping");
  const more = el("button", "more");
  more.type = "button";
  more.setAttribute("aria-label", "Действия с сервером");
  more.append(icon("more"));
  row.append(el("span", "dot"), text, ping, more);
  row.addEventListener("click", (e) => {
    if (!e.target.closest(".more")) select(id);
  });
  row.addEventListener("keydown", (e) => {
    if (e.target === row && (e.key === "Enter" || e.key === " ")) {
      e.preventDefault();
      select(id);
    }
  });
  more.addEventListener("click", (e) => {
    e.stopPropagation();
    openMenu(id, more);
  });
  return { row, name, sub, ping };
}

function renderServers() {
  const s = snap;
  const service = !!(s && s.service);
  const profiles = service ? s.profiles.profiles : [];
  $("servers-empty").hidden = !service || profiles.length > 0;
  serverList.hidden = profiles.length === 0;
  $("ping-all").disabled = profiles.length === 0;
  $("servers-add").disabled = !service;
  const kept = new Set();
  for (const p of profiles) {
    kept.add(p.id);
    let r = rows.get(p.id);
    if (!r) {
      r = serverRow(p.id);
      rows.set(p.id, r);
    }
    r.name.textContent = p.name;
    r.sub.textContent = protocolText(p);
    r.row.setAttribute("aria-checked", String(p.id === s.profiles.selectedId));
    setPing(r.ping, s.pings[p.id]);
    serverList.appendChild(r.row);
  }
  for (const [id, r] of rows) {
    if (!kept.has(id)) {
      r.row.remove();
      rows.delete(id);
    }
  }
}

function select(id) {
  if (snap && snap.profiles.selectedId !== id) call("Select", id).catch(fail);
}

// Each window checks every server once when its list is first shown.
function pingAllOnce() {
  if (pingedAll || !snap || !snap.service || !snap.profiles.profiles.length) return;
  pingedAll = true;
  call("Ping", []).catch(() => {});
}

$("ping-all").addEventListener("click", () => {
  pingedAll = true;
  call("Ping", []).catch(fail);
});
$("servers-add").addEventListener("click", openAdd);
$("servers-empty-add").addEventListener("click", openAdd);

// The menu of a server's row.
const menu = $("row-menu");
let menuFor = "";

function openMenu(id, anchor) {
  menuFor = id;
  menu.hidden = false;
  const a = anchor.getBoundingClientRect();
  const m = menu.getBoundingClientRect();
  menu.style.left = `${Math.max(8, Math.min(a.right - m.width, window.innerWidth - m.width - 8))}px`;
  const below = a.bottom + 4;
  menu.style.top = `${below + m.height > window.innerHeight - 8 ? a.top - m.height - 4 : below}px`;
  menu.querySelector("button").focus();
}

function closeMenu() {
  menu.hidden = true;
  menuFor = "";
}

menu.addEventListener("click", (e) => {
  const act = e.target.closest("button")?.dataset.act;
  const id = menuFor;
  closeMenu();
  if (act === "ping") call("Ping", [id]).catch(fail);
  if (act === "rename") openRename(id);
  if (act === "delete") openDelete(id);
});
document.addEventListener("click", (e) => {
  if (!menu.hidden && !menu.contains(e.target)) closeMenu();
});
$("views").addEventListener("scroll", closeMenu);

function profile(id) {
  return snap && snap.profiles.profiles.find((p) => p.id === id);
}

// ---------------------------------------------------------------- sheets

function openSheet(sheet, focus) {
  closeMenu();
  sheet.hidden = false;
  if (focus) focus.focus();
}

const addSheet = $("add-sheet");
const keyInput = $("key");
const addSave = $("add-save");

function openAdd() {
  openSheet(addSheet, keyInput);
}

function closeAdd() {
  addSheet.hidden = true;
  keyInput.value = "";
}

async function saveKey() {
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

$("add-cancel").addEventListener("click", closeAdd);
addSave.addEventListener("click", saveKey);
keyInput.addEventListener("keydown", (e) => {
  if (e.key === "Enter" && (e.ctrlKey || e.metaKey)) saveKey();
});

const renameSheet = $("rename-sheet");
const renameInput = $("rename-input");
let renaming = "";

function openRename(id) {
  const p = profile(id);
  if (!p) return;
  renaming = id;
  renameInput.value = p.name;
  openSheet(renameSheet, renameInput);
  renameInput.select();
}

async function saveName() {
  try {
    await call("Rename", renaming, renameInput.value);
    renameSheet.hidden = true;
  } catch (err) {
    fail(err);
  }
}

$("rename-cancel").addEventListener("click", () => { renameSheet.hidden = true; });
$("rename-save").addEventListener("click", saveName);
renameInput.addEventListener("keydown", (e) => {
  if (e.key === "Enter") saveName();
});

const deleteSheet = $("delete-sheet");
let deleting = "";

function openDelete(id) {
  const p = profile(id);
  if (!p) return;
  deleting = id;
  $("delete-text").textContent = `«${p.name}» будет удалён с этого компьютера.`;
  openSheet(deleteSheet, $("delete-cancel"));
}

$("delete-cancel").addEventListener("click", () => { deleteSheet.hidden = true; });
$("delete-confirm").addEventListener("click", async () => {
  deleteSheet.hidden = true;
  try {
    await call("Delete", deleting);
  } catch (err) {
    fail(err);
  }
});

document.addEventListener("keydown", (e) => {
  if (e.key !== "Escape") return;
  closeMenu();
  if (!addSheet.hidden) closeAdd();
  renameSheet.hidden = true;
  deleteSheet.hidden = true;
});

// ---------------------------------------------------------------- settings

// Saves go one after another, each built on the one before, so that two
// quick changes both stay.
let saving = Promise.resolve();
let unsaved = null;

// save sends the settings with change made; on a refusal the controls go
// back to what is saved.
function save(change) {
  if (!snap || !snap.settings) return Promise.reject(new Error("Нет связи со службой Kirov VPN"));
  const next = structuredClone(unsaved || snap.settings);
  change(next);
  unsaved = next;
  const done = saving.then(() => call("SaveSettings", next));
  saving = done.catch(() => {});
  return done.then((saved) => {
    // The state with the settings as saved, so the next change builds on it.
    if (saved) render(saved);
    if (unsaved === next) unsaved = null;
  }, (err) => {
    unsaved = null;
    fail(err);
    render(snap);
    throw err;
  });
}

for (const radio of document.querySelectorAll('input[name="mode"]')) {
  radio.addEventListener("change", () => save((s) => { s.mode = radio.value; }).catch(() => {}));
}
$("torrents").addEventListener("change", (e) => save((s) => { s.torrentsDirect = e.target.checked; }).catch(() => {}));
$("autoconnect").addEventListener("change", (e) => save((s) => { s.autoConnect = e.target.checked; }).catch(() => {}));

// An editor of one list of the settings: sites typed in, or programs
// picked from the disk.
function editor(details) {
  const key = details.dataset.list;
  const list = el("ul", "entries");
  const line = el("div", "add-line");
  const add = (value) => save((s) => { s[key] = [...(s[key] || []), value]; });
  if (details.dataset.kind === "site") {
    const input = el("input");
    input.type = "text";
    input.placeholder = "example.com";
    input.spellcheck = false;
    input.autocomplete = "off";
    input.setAttribute("aria-label", "Сайт или адрес");
    const button = el("button", "btn small", "Добавить");
    button.type = "button";
    const submit = () => {
      const value = input.value.trim();
      if (value) add(value).then(() => { input.value = ""; }, () => {});
    };
    button.addEventListener("click", submit);
    input.addEventListener("keydown", (e) => {
      if (e.key === "Enter") submit();
    });
    line.append(input, button);
  } else {
    const button = el("button", "btn small", "Выбрать программу…");
    button.type = "button";
    button.addEventListener("click", async () => {
      let name;
      try {
        name = await call("PickProgram");
      } catch (err) {
        fail(err);
        return;
      }
      if (name) add(name).catch(() => {});
    });
    line.append(button);
  }
  details.querySelector(".editor-body").append(list, line);
  const count = details.querySelector(".count");
  let shown = "";
  return (items, enabled) => {
    for (const c of line.querySelectorAll("input, button")) c.disabled = !enabled;
    const now = JSON.stringify(items);
    if (now === shown) return;
    shown = now;
    count.textContent = items.length ? String(items.length) : "";
    list.replaceChildren(...items.map((item) => {
      const entry = el("li", "entry");
      const remove = el("button");
      remove.type = "button";
      remove.setAttribute("aria-label", `Убрать ${item}`);
      remove.append(icon("remove"));
      remove.addEventListener("click", () => {
        save((s) => { s[key] = (s[key] || []).filter((x) => x !== item); }).catch(() => {});
      });
      entry.append(el("span", null, item), remove);
      return entry;
    }));
  };
}

const editors = [...document.querySelectorAll("details.editor")].map((d) => ({ key: d.dataset.list, render: editor(d) }));

function renderSettings() {
  const st = snap && snap.service ? snap.settings : null;
  for (const radio of document.querySelectorAll('input[name="mode"]')) {
    radio.checked = !!st && radio.value === st.mode;
    radio.disabled = !st;
  }
  for (const [id, field] of [["torrents", "torrentsDirect"], ["autoconnect", "autoConnect"]]) {
    $(id).checked = !!st && st[field];
    $(id).disabled = !st;
  }
  for (const ed of editors) ed.render(st ? st[ed.key] || [] : [], !!st);
  $("home-mode-name").textContent = st ? MODES[st.mode] || "—" : "—";
}

// ---------------------------------------------------------------- journal

let shownLogs = null;

async function loadLogs() {
  const box = $("logs");
  box.replaceChildren(el("p", "note", "Загрузка…"));
  const logs = await call("Logs").catch((err) => ({ sections: [{ title: "Журнал", text: (err && err.message) || String(err) }] }));
  if (!logs.sections) return;
  shownLogs = logs;
  box.replaceChildren(...logs.sections.flatMap((s) => [el("h3", "log-title", s.title), el("pre", "log", s.text.trim() || "Пусто")]));
  // The newest lines are at the end.
  for (const pre of box.querySelectorAll("pre")) pre.scrollTop = pre.scrollHeight;
}

$("open-logs").addEventListener("click", () => show("logs"));
$("logs-back").addEventListener("click", () => show("settings"));
$("logs-refresh").addEventListener("click", loadLogs);
$("logs-copy").addEventListener("click", async () => {
  if (!shownLogs) return;
  const text = shownLogs.sections.map((s) => `=== ${s.title} ===\n${s.text.trim()}`).join("\n\n");
  const copied = await call("Copy", text).catch(() => false);
  toast(copied ? "Журнал скопирован: вставьте его в сообщение" : "Не удалось скопировать");
});

// ---------------------------------------------------------------- start

Events.On("snapshot", (ev) => render(ev.data));
call("Snapshot").then(render, fail);
call("Version").then((v) => { $("version").textContent = `Kirov VPN ${v}`; }, () => {});
call("Loaded").catch(() => {});
