// The servers view: each subscription and the own keys in a group, each
// server with its flag, kind and check; search, folding and the menus.
import { $, bytes, call, el, fail, icon, plural, russianDate, stateOf, toast, updatedText } from "./util.js";
import { flagBox, flagOf, kindOf, setFlag } from "./flags.js";
import { closeMenu, confirmDelete, openAdd, openMenu, openRename } from "./dialogs.js";

const OWN = "own";
const FOLDED_KEY = "kirov.folded";

const groupsBox = $("groups");
const search = $("search");
const groupNodes = new Map();
const rowNodes = new Map();
let snap = null;
let pinged = false;
let folded = new Set();
try {
  folded = new Set(JSON.parse(localStorage.getItem(FOLDED_KEY) || "[]"));
} catch {
  // A private profile keeps nothing: every group starts open.
}

// The groups in the order shown: subscriptions as added, then own keys
// (servers of a deleted subscription count as own, as on Android).
function groupsOf(s) {
  const subs = s.profiles.subscriptions || [];
  const groups = subs.map((sub) => ({ id: sub.id, sub, servers: [] }));
  const byID = new Map(groups.map((g) => [g.id, g]));
  const own = { id: OWN, sub: null, servers: [] };
  for (const p of s.profiles.profiles) (byID.get(p.subscriptionId) || own).servers.push(p);
  if (own.servers.length) groups.push(own);
  return groups;
}

export function renderServers(s) {
  snap = s;
  const service = !!(s && s.service);
  const groups = service ? groupsOf(s) : [];
  const servers = service ? s.profiles.profiles.length : 0;
  const subs = service ? (s.profiles.subscriptions || []) : [];
  $("servers-empty").hidden = !service || groups.length > 0;
  $("ping-all").disabled = servers === 0;
  $("servers-add").disabled = !service;
  search.disabled = servers === 0;
  const refreshAll = $("refresh-all");
  refreshAll.disabled = subs.length === 0;
  refreshAll.classList.toggle("busy", subs.some((sub) => sub.updating));

  const keptGroups = new Set();
  const keptRows = new Set();
  groups.forEach((g, gi) => {
    keptGroups.add(g.id);
    let node = groupNodes.get(g.id);
    if (!node) {
      node = groupNode(g.id);
      groupNodes.set(g.id, node);
    }
    updateGroup(node, g);
    place(groupsBox, node.root, gi);
    g.servers.forEach((p, i) => {
      keptRows.add(p.id);
      let row = rowNodes.get(p.id);
      if (!row) {
        row = rowNode(p.id);
        rowNodes.set(p.id, row);
      }
      updateRow(row, p, s, !g.sub);
      place(node.rows, row.root, i);
    });
  });
  for (const [id, node] of groupNodes) {
    if (!keptGroups.has(id)) {
      node.root.remove();
      groupNodes.delete(id);
    }
  }
  for (const [id, row] of rowNodes) {
    if (!keptRows.has(id)) {
      row.root.remove();
      rowNodes.delete(id);
    }
  }
  filter();
}

// place puts node at index of parent, moving it only when it is not there
// already, so focus and hover stay.
function place(parent, node, index) {
  if (parent.children[index] !== node) parent.insertBefore(node, parent.children[index] || null);
}

// ---------------------------------------------------------------- groups

function groupNode(id) {
  const root = el("section", "group");
  const head = el("div", "group-head");
  const fold = el("button", "fold");
  fold.type = "button";
  fold.append(icon("chevron"));
  const text = el("div", "group-text");
  const title = el("span", "group-title");
  const meta = el("span", "group-meta");
  text.append(title, meta);
  const ping = ghost("gauge", "Проверить серверы");
  const refresh = ghost("refresh", "Обновить подписку");
  const more = ghost("more", "Действия");
  more.setAttribute("aria-haspopup", "menu");
  head.append(fold, text, ping, refresh, more);
  const usage = el("div", "usage");
  const bar = el("span");
  usage.append(bar);
  const notice = el("p", "group-note notice");
  const announce = el("p", "group-note");
  const error = el("p", "group-note error");
  const rows = el("div", "rows");
  rows.setAttribute("role", "radiogroup");
  root.append(head, usage, notice, announce, error, rows);

  const node = { root, fold, title, meta, ping, refresh, more, usage, bar, notice, announce, error, rows, group: null };
  fold.addEventListener("click", () => toggleFold(node, id));
  head.addEventListener("dblclick", (e) => {
    if (!e.target.closest("button")) toggleFold(node, id);
  });
  ping.addEventListener("click", () => pingGroup(node.group));
  refresh.addEventListener("click", () => refreshSubscription(id));
  more.addEventListener("click", (e) => {
    e.stopPropagation();
    groupMenu(more, node.group);
  });
  return node;
}

function ghost(name, label) {
  const b = el("button", "icon-btn ghost");
  b.type = "button";
  b.title = label;
  b.setAttribute("aria-label", label);
  b.append(icon(name));
  return b;
}

function updateGroup(node, g) {
  node.group = g;
  const sub = g.sub;
  node.root.dataset.folded = String(folded.has(g.id));
  node.fold.setAttribute("aria-expanded", String(!folded.has(g.id)));
  node.fold.setAttribute("aria-label", folded.has(g.id) ? "Развернуть" : "Свернуть");
  node.title.textContent = sub ? sub.name : "Мои ключи";
  node.rows.setAttribute("aria-label", node.title.textContent);

  const parts = [plural(g.servers.length, "сервер", "сервера", "серверов")];
  let expired = false;
  if (sub) {
    if (sub.total > 0) parts.push(`${bytes(sub.used || 0)} из ${bytes(sub.total)}`);
    else if (sub.used > 0) parts.push(bytes(sub.used));
    const until = sub.expire > 0 ? russianDate(sub.expire * 1000) : "";
    if (until) {
      expired = sub.expire * 1000 < Date.now();
      parts.push(`${expired ? "истекла" : "до"} ${until}`);
    }
  }
  node.meta.textContent = parts.join(" · ");
  node.meta.classList.toggle("expired", expired);

  node.usage.hidden = !(sub && sub.total > 0);
  if (sub && sub.total > 0) {
    const used = Math.min(1, Math.max(0.02, (sub.used || 0) / sub.total));
    node.bar.style.width = `${used * 100}%`;
    node.usage.classList.toggle("full", used > 0.9);
  }
  note(node.notice, sub && sub.notice);
  note(node.announce, sub && sub.announce);
  note(node.error, sub && sub.lastError ? `Не удалось обновить: ${sub.lastError}` : "");

  node.refresh.hidden = !sub;
  node.ping.disabled = g.servers.length === 0;
  if (sub) {
    node.refresh.classList.toggle("busy", !!sub.updating);
    node.refresh.disabled = !!sub.updating;
    node.refresh.title = `Обновить подписку. ${updatedText(sub.updatedAt)}`;
  }
}

function note(node, text) {
  node.textContent = text || "";
  node.hidden = !text;
}

function toggleFold(node, id) {
  if (folded.has(id)) folded.delete(id);
  else folded.add(id);
  try {
    localStorage.setItem(FOLDED_KEY, JSON.stringify([...folded]));
  } catch {
    // Kept for this window only.
  }
  updateGroup(node, node.group);
}

function groupMenu(anchor, g) {
  const items = [{ label: "Проверить серверы", action: () => pingGroup(g) }];
  if (g.sub) {
    items.unshift({ label: "Обновить подписку", action: () => refreshSubscription(g.id) });
    // The account's servers go with the account.
    items.push(g.sub.account ? {
      label: "Выйти из аккаунта",
      danger: true,
      action: () => confirmDelete("Выйти из аккаунта?",
        `Серверы аккаунта (${g.servers.length}) будут убраны с этого компьютера. Чтобы вернуть их, войдите снова.`,
        () => call("AccountLogout").catch(fail), "Выйти"),
    } : {
      label: "Удалить подписку",
      danger: true,
      action: () => confirmDelete("Удалить подписку?",
        `Подписка «${g.sub.name}» и её серверы (${g.servers.length}) будут удалены с этого компьютера.`,
        () => call("DeleteSubscription", g.id).catch(fail)),
    });
  }
  openMenu(anchor, items);
}

function pingGroup(g) {
  if (g && g.servers.length) call("Ping", g.servers.map((p) => p.id)).catch(fail);
}

async function refreshSubscription(id) {
  try {
    toast(await call("Refresh", id));
  } catch (err) {
    fail(err);
  }
}

// ---------------------------------------------------------------- servers

function rowNode(id) {
  const root = el("div", "srv");
  root.setAttribute("role", "radio");
  root.tabIndex = 0;
  const flag = flagBox();
  const text = el("span", "srv-text");
  const name = el("span", "srv-name");
  const kind = el("span", "srv-kind");
  text.append(name, kind);
  const ping = el("span", "ping");
  const more = ghost("more", "Действия с сервером");
  more.classList.add("more");
  more.setAttribute("aria-haspopup", "menu");
  root.append(flag, text, ping, more);
  const row = { root, flag, name, kind, ping, more, profile: null, own: false };

  root.addEventListener("click", (e) => {
    if (!e.target.closest(".more")) select(id);
  });
  // A double click also connects, as in other desktop VPN apps.
  root.addEventListener("dblclick", (e) => {
    if (e.target.closest(".more")) return;
    const state = stateOf(snap);
    if (state === "off" || state === "error") call("Connect").catch(fail);
  });
  root.addEventListener("keydown", (e) => {
    if (e.target === root && (e.key === "Enter" || e.key === " ")) {
      e.preventDefault();
      select(id);
    }
  });
  more.addEventListener("click", (e) => {
    e.stopPropagation();
    serverMenu(more, row);
  });
  return row;
}

function updateRow(row, p, s, own) {
  row.profile = p;
  row.own = own;
  const shown = flagOf(p.name);
  setFlag(row.flag, shown.code);
  row.name.textContent = shown.name;
  row.root.dataset.name = shown.name.toLowerCase();
  row.kind.textContent = kindOf(p);
  row.root.setAttribute("aria-checked", String(p.id === s.profiles.selectedId));
  setPing(row.ping, s.pings[p.id]);
}

function serverMenu(anchor, row) {
  const p = row.profile;
  const items = [
    { label: "Проверить отклик", action: () => call("Ping", [p.id]).catch(fail) },
    { label: "Переименовать", action: () => openRename(p.id, p.name) },
  ];
  // A subscription's servers come back with its next refresh.
  if (row.own) {
    items.push({
      label: "Удалить",
      danger: true,
      action: () => confirmDelete("Удалить сервер?", `«${flagOf(p.name).name}» будет удалён с этого компьютера.`,
        () => call("Delete", p.id).catch(fail)),
    });
  }
  openMenu(anchor, items);
}

function select(id) {
  if (snap && snap.profiles.selectedId !== id) call("Select", id).catch(fail);
}

// A server's check, graded as Android's pingGrade.
export function setPing(node, p) {
  node.className = "ping";
  if (!p) {
    node.textContent = "";
  } else if (p.state === "testing") {
    node.textContent = "…";
    node.classList.add("testing");
  } else if (p.state === "ok") {
    node.textContent = `${p.ms} мс`;
    node.classList.add(p.ms < 150 ? "great" : p.ms < 400 ? "good" : p.ms < 1000 ? "fair" : "poor");
  } else {
    node.textContent = "нет связи";
    node.classList.add("failed");
  }
}

// ---------------------------------------------------------------- search

function filter() {
  const q = search.value.trim().toLowerCase();
  groupsBox.dataset.searching = String(q !== "");
  let any = false;
  for (const node of groupNodes.values()) {
    let shown = 0;
    for (const row of node.rows.children) {
      row.hidden = q !== "" && !row.dataset.name.includes(q);
      if (!row.hidden) shown++;
    }
    node.root.hidden = q !== "" && shown === 0;
    if (!node.root.hidden) any = true;
  }
  $("nothing-found").hidden = q === "" || any;
}

search.addEventListener("input", filter);
search.addEventListener("keydown", (e) => {
  if (e.key === "Escape" && search.value) {
    e.stopPropagation();
    search.value = "";
    filter();
  }
});

// ---------------------------------------------------------------- view

// Each window checks every server once when its list is first shown.
export function serversShown() {
  if (pinged || !snap || !snap.service || !snap.profiles.profiles.length) return;
  pinged = true;
  call("Ping", []).catch(() => {});
}

// revealServer scrolls to server id, opening its group.
export function revealServer(id) {
  const row = rowNodes.get(id);
  if (!row) return;
  for (const [gid, node] of groupNodes) {
    if (node.rows.contains(row.root) && folded.has(gid)) toggleFold(node, gid);
  }
  row.root.scrollIntoView({ block: "nearest" });
  row.root.focus({ preventScroll: true });
}

$("ping-all").addEventListener("click", () => {
  pinged = true;
  call("Ping", []).catch(fail);
});
$("refresh-all").addEventListener("click", () => refreshSubscription(""));
$("servers-add").addEventListener("click", () => openAdd());
$("servers-empty-add").addEventListener("click", () => openAdd());
$("servers-scroll").addEventListener("scroll", closeMenu);
