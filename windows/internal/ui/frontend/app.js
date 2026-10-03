// The window of Kirov VPN for Windows. It shows the snapshot the app's Go
// side keeps (the service's status, servers and subscriptions, their
// checks and the settings) and sends the service the user's requests
// through the Bridge.
import { Events } from "/wails/runtime.js";
import { $, call, fail } from "./util.js";
import { closeMenu, offerLink, openAdd, sheetOpen } from "./dialogs.js";
import { renderServers, serversShown } from "./servers.js";
import { onShowServers, renderPane } from "./pane.js";
import { onSaved, renderSettings } from "./settings.js";
import { loadJournal } from "./journal.js";

let snap = null;

function render(s) {
  if (snap && s && s.seq < snap.seq) return;
  snap = s;
  renderServers(s);
  renderPane(s);
  renderSettings(s);
}

// ---------------------------------------------------------------- views

function show(view) {
  closeMenu();
  for (const v of document.querySelectorAll(".view")) v.hidden = v.id !== `view-${view}`;
  for (const b of document.querySelectorAll(".rail-btn[data-view]")) {
    if (b.dataset.view === view) b.setAttribute("aria-current", "page");
    else b.removeAttribute("aria-current");
  }
  if (view === "servers") serversShown();
  if (view === "journal") loadJournal();
}

for (const b of document.querySelectorAll(".rail-btn[data-view]")) {
  b.addEventListener("click", () => show(b.dataset.view));
}
$("rail-add").addEventListener("click", () => openAdd());
$("detail-logs").addEventListener("click", () => show("journal"));
onShowServers(() => show("servers"));
onSaved(render);

// Ctrl+V anywhere outside a text field adds what was copied.
document.addEventListener("paste", (e) => {
  const target = e.target;
  if (sheetOpen() || (target && (target.closest("input, textarea")))) return;
  const text = e.clipboardData ? e.clipboardData.getData("text").trim() : "";
  if (text) {
    e.preventDefault();
    openAdd(text);
  }
});

// ---------------------------------------------------------------- start

Events.On("snapshot", (ev) => render(ev.data));
// An "Add to Kirov VPN" link that opened or reached this window.
Events.On("link", (ev) => offerLink(ev.data));
call("Snapshot").then((s) => {
  render(s);
  serversShown();
}, fail);
call("PendingLink").then(offerLink, () => {});
call("Version").then((v) => { $("version").textContent = `Версия ${v}`; }, () => {});
call("Loaded").catch(() => {});
