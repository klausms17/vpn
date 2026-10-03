// The connection pane, always in sight: the big button and what it says,
// what goes through the VPN, and the server the tunnel runs.
import { $, call, duration, el, fail, shownServer, stateOf } from "./util.js";
import { flagOf, kindOf, setFlag } from "./flags.js";
import { openAdd, openMenu } from "./dialogs.js";
import { revealServer, setPing } from "./servers.js";
import { saveSettings } from "./settings.js";

export const MODES = [
  { value: "ru_direct", title: "Всё, кроме российских сайтов", sub: "Российские сайты открываются напрямую, остальное — через VPN" },
  { value: "blocked_only", title: "Только заблокированные сайты", sub: "Через VPN идёт только то, что заблокировано в России" },
  { value: "global", title: "Весь интернет", sub: "Всё идёт через VPN, кроме домашней сети" },
];

const pane = $("pane");
const power = $("power");
const title = $("state");
const detail = $("detail");
const modeButton = $("mode");
const current = $("current");
const currentPing = $("current-ping-btn");
let snap = null;
let ticking = 0;
let pingedShown = false;
let showServers = () => {};

// onShowServers sets how the pane opens the servers view.
export function onShowServers(show) {
  showServers = show;
}

export function renderPane(s) {
  snap = s;
  const state = stateOf(s);
  pane.dataset.state = state;
  const st = s && s.service ? s.status : {};
  const server = s && s.service ? shownServer(s) : null;
  const hasServers = !!(s && s.service && s.profiles.profiles.length);

  clearInterval(ticking);
  ticking = 0;
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
      text = st.message || (hasServers ? "Нажмите на кнопку, чтобы подключиться" : "Сначала добавьте серверы");
      break;
    case "connecting":
      title.textContent = "Подключение…";
      text = st.message || "";
      break;
    case "on":
      title.textContent = "Подключено";
      text = st.message || "";
      break;
    case "disconnecting":
      title.textContent = "Отключение…";
      break;
    case "error":
      title.textContent = "Не удалось подключиться";
      text = st.message || "";
      break;
  }
  if (state === "on" && !st.message && st.connectedSince) {
    const timer = el("span", "timer");
    const tick = () => { timer.textContent = duration(Date.now() - st.connectedSince); };
    tick();
    ticking = setInterval(tick, 1000);
    detail.replaceChildren(timer);
  } else {
    detail.textContent = text;
  }
  $("detail-logs").hidden = state !== "error";

  const on = state === "on" || state === "connecting";
  power.disabled = !["off", "error", "on", "connecting"].includes(state);
  power.setAttribute("aria-label", on ? "Отключить" : "Подключить");

  const settings = s && s.service ? s.settings : null;
  modeButton.disabled = !settings;
  const mode = settings && MODES.find((m) => m.value === settings.mode);
  $("mode-name").textContent = mode ? mode.title : "—";

  renderCurrent(s, server, hasServers);
}

function renderCurrent(s, server, hasServers) {
  const service = !!(s && s.service);
  current.disabled = !server;
  currentPing.disabled = !server;
  if (server) {
    const shown = flagOf(server.name);
    setFlag($("current-flag"), shown.code);
    $("current-name").textContent = shown.name;
    $("current-kind").textContent = kindOf(server);
  } else {
    setFlag($("current-flag"), "");
    $("current-name").textContent = !service ? "—" : hasServers ? "Сервер не выбран" : "Серверов пока нет";
    $("current-kind").textContent = "";
  }
  setPing($("current-ping"), server ? s.pings[server.id] : null);
  // The server shown is checked once when the window opens.
  if (!pingedShown && server && !s.pings[server.id]) {
    pingedShown = true;
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

modeButton.addEventListener("click", (e) => {
  e.stopPropagation();
  const now = snap && snap.settings ? snap.settings.mode : "";
  openMenu(modeButton, MODES.map((m) => ({
    label: m.title,
    sub: m.sub,
    checked: m.value === now,
    action: () => saveSettings((s) => { s.mode = m.value; }).catch(() => {}),
  })), "modes-menu");
});

current.addEventListener("click", () => {
  const server = snap && snap.service ? shownServer(snap) : null;
  if (!server) return;
  showServers();
  revealServer(server.id);
});

currentPing.addEventListener("click", () => {
  const server = snap && snap.service ? shownServer(snap) : null;
  if (server) call("Ping", [server.id]).catch(fail);
});
