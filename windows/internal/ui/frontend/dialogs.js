// The pop-up menu and the sheets over the window: adding servers, an
// "Add to Kirov VPN" link to confirm, renaming, and confirmations.
import { $, busy, call, el, fail, toast } from "./util.js";

// ---------------------------------------------------------------- menu

const menu = $("menu");
let menuAnchor = null;

// openMenu shows items next to anchor. An item is {label, sub, danger,
// checked, action}; checked makes it one of a choice.
export function openMenu(anchor, items, cls = "") {
  closeMenu();
  menu.className = cls ? `menu ${cls}` : "menu";
  menu.replaceChildren(...items.map((item) => {
    const b = el("button");
    b.type = "button";
    if (item.checked === undefined) {
      b.setAttribute("role", "menuitem");
    } else {
      b.setAttribute("role", "menuitemradio");
      b.setAttribute("aria-checked", String(item.checked));
    }
    if (item.danger) b.classList.add("danger");
    b.append(el("span", null, item.label));
    if (item.sub) b.append(el("small", null, item.sub));
    b.addEventListener("click", () => {
      closeMenu();
      item.action();
    });
    return b;
  }));
  menu.hidden = false;
  menuAnchor = anchor;
  anchor.setAttribute("aria-expanded", "true");
  const a = anchor.getBoundingClientRect();
  const m = menu.getBoundingClientRect();
  const wide = m.width >= a.width;
  const left = wide ? a.right - m.width : a.left;
  menu.style.left = `${Math.max(8, Math.min(left, window.innerWidth - m.width - 8))}px`;
  const below = a.bottom + 6;
  menu.style.top = `${below + m.height > window.innerHeight - 8 ? Math.max(8, a.top - m.height - 6) : below}px`;
  menu.querySelector("button").focus();
}

export function closeMenu() {
  if (menu.hidden) return;
  menu.hidden = true;
  if (menuAnchor) {
    menuAnchor.setAttribute("aria-expanded", "false");
    menuAnchor = null;
  }
}

document.addEventListener("click", (e) => {
  if (!menu.hidden && !menu.contains(e.target) && !(menuAnchor && menuAnchor.contains(e.target))) closeMenu();
});
window.addEventListener("resize", closeMenu);
menu.addEventListener("keydown", (e) => {
  if (e.key !== "ArrowDown" && e.key !== "ArrowUp") return;
  e.preventDefault();
  const items = [...menu.querySelectorAll("button")];
  const i = items.indexOf(document.activeElement);
  items[(i + (e.key === "ArrowDown" ? 1 : items.length - 1)) % items.length].focus();
});

// ---------------------------------------------------------------- sheets

let shown = null;

function open(dialog, focus) {
  closeMenu();
  dialog.hidden = false;
  shown = dialog;
  (focus || dialog.querySelector("button")).focus();
}

function close(dialog) {
  dialog.hidden = true;
  if (shown === dialog) shown = null;
}

// Escape closes the menu, else the sheet in front (as its «Отмена»).
document.addEventListener("keydown", (e) => {
  if (e.key !== "Escape") return;
  if (!menu.hidden) {
    closeMenu();
  } else if (shown) {
    shown.querySelector('[id$="-cancel"]').click();
  }
});

export const sheetOpen = () => shown !== null;

// ---------------------------------------------------------------- add

const addDialog = $("add-dialog");
const addText = $("add-text");
const addSave = $("add-save");

export function openAdd(text = "") {
  addText.value = text;
  open(addDialog, addText);
}

async function add() {
  const text = addText.value.trim();
  if (!text) {
    toast("Вставьте ссылку на подписку или ключ сервера");
    return;
  }
  try {
    toast(await busy(addSave, "Добавление…", () => call("Import", text)));
    close(addDialog);
    addText.value = "";
  } catch (err) {
    fail(err);
  }
}

$("add-cancel").addEventListener("click", () => {
  close(addDialog);
  addText.value = "";
});
addSave.addEventListener("click", add);
addText.addEventListener("keydown", (e) => {
  if (e.key === "Enter" && (e.ctrlKey || e.metaKey)) add();
});

// ---------------------------------------------------------------- link

const linkDialog = $("link-dialog");
const linkAdd = $("link-add");

// The number of the link the dialog asks about: the answer names it, so
// it never adds or drops a link that came later.
let asked = 0;

// offerLink asks before adding what an "Add to Kirov VPN" link carries;
// the link itself stays with the app's Go side.
export function offerLink(prompt) {
  if (!prompt || !prompt.present) return;
  asked = prompt.seq;
  $("link-text").textContent = prompt.host
    ? `Подписка с адреса ${prompt.host}. Добавляйте только ссылки от тех, кому доверяете.`
    : "Kirov VPN получил ключ сервера. Добавляйте только ключи от тех, кому доверяете.";
  open(linkDialog, linkAdd);
}

// A link that came after the one answered, even while it was being added,
// is asked about next.
function askNext(answered) {
  call("PendingLink").then((prompt) => {
    if (prompt.present && prompt.seq !== answered) offerLink(prompt);
  }, () => {});
}

linkAdd.addEventListener("click", async () => {
  const seq = asked;
  try {
    toast(await busy(linkAdd, "Добавление…", () => call("AddPendingLink", seq)));
    close(linkDialog);
    askNext(seq);
  } catch (err) {
    fail(err);
  }
});
$("link-cancel").addEventListener("click", () => {
  const seq = asked;
  close(linkDialog);
  call("DropPendingLink", seq).then(() => askNext(seq), () => {});
});

// ---------------------------------------------------------------- rename

const renameDialog = $("rename-dialog");
const renameInput = $("rename-input");
let renaming = "";

export function openRename(id, name) {
  renaming = id;
  renameInput.value = name;
  open(renameDialog, renameInput);
  renameInput.select();
}

async function rename() {
  try {
    await call("Rename", renaming, renameInput.value);
    close(renameDialog);
  } catch (err) {
    fail(err);
  }
}

$("rename-cancel").addEventListener("click", () => close(renameDialog));
$("rename-save").addEventListener("click", rename);
renameInput.addEventListener("keydown", (e) => {
  if (e.key === "Enter") rename();
});

// ---------------------------------------------------------------- confirm

const confirmDialog = $("confirm-dialog");
let confirmed = null;

// confirmDelete asks before deleting; action runs on «Удалить», or on the
// label given.
export function confirmDelete(title, text, action, ok = "Удалить") {
  $("confirm-title").textContent = title;
  $("confirm-text").textContent = text;
  $("confirm-ok").textContent = ok;
  confirmed = action;
  open(confirmDialog, $("confirm-cancel"));
}

$("confirm-cancel").addEventListener("click", () => close(confirmDialog));
$("confirm-ok").addEventListener("click", () => {
  close(confirmDialog);
  const action = confirmed;
  confirmed = null;
  if (action) action();
});
