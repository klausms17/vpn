// The settings view: what goes through the VPN, the user's own sites and
// programs, starting with Windows.
import { $, call, el, fail, icon } from "./util.js";

let snap = null;
let rendered = () => {};

// onSaved sets what shows the state a save returns.
export function onSaved(show) {
  rendered = show;
}

// Saves go one after another, each built on the one before, so that two
// quick changes both stay.
let saving = Promise.resolve();
let unsaved = null;

// saveSettings sends the settings with change made; on a refusal the
// controls go back to what is saved.
export function saveSettings(change) {
  if (!snap || !snap.settings) return Promise.reject(new Error("Нет связи со службой Kirov VPN"));
  const next = structuredClone(unsaved || snap.settings);
  change(next);
  unsaved = next;
  const done = saving.then(() => call("SaveSettings", next));
  saving = done.catch(() => {});
  return done.then((saved) => {
    // The state with the settings as saved, so the next change builds on it.
    if (saved) rendered(saved);
    if (unsaved === next) unsaved = null;
  }, (err) => {
    unsaved = null;
    fail(err);
    rendered(snap);
    throw err;
  });
}

for (const radio of document.querySelectorAll('input[name="mode"]')) {
  radio.addEventListener("change", () => saveSettings((s) => { s.mode = radio.value; }).catch(() => {}));
}
$("torrents").addEventListener("change", (e) => saveSettings((s) => { s.torrentsDirect = e.target.checked; }).catch(() => {}));
$("autoconnect").addEventListener("change", (e) => saveSettings((s) => { s.autoConnect = e.target.checked; }).catch(() => {}));

// An editor of one list of the settings: sites typed in, or programs
// picked from the disk.
function editor(details) {
  const key = details.dataset.list;
  const list = el("ul", "entries");
  const line = el("div", "add-line");
  const add = (value) => saveSettings((s) => { s[key] = [...(s[key] || []), value]; });
  if (details.dataset.kind === "site") {
    const input = el("input");
    input.type = "text";
    input.placeholder = "example.com";
    input.spellcheck = false;
    input.autocomplete = "off";
    input.setAttribute("aria-label", "Сайт или адрес");
    const button = el("button", "btn plain", "Добавить");
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
    const button = el("button", "btn plain", "Выбрать программу…");
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
        saveSettings((s) => { s[key] = (s[key] || []).filter((x) => x !== item); }).catch(() => {});
      });
      entry.append(el("span", null, item), remove);
      return entry;
    }));
  };
}

const editors = [...document.querySelectorAll("details.editor")].map((d) => ({ key: d.dataset.list, render: editor(d) }));

export function renderSettings(s) {
  snap = s;
  const st = s && s.service ? s.settings : null;
  for (const radio of document.querySelectorAll('input[name="mode"]')) {
    radio.checked = !!st && radio.value === st.mode;
    radio.disabled = !st;
  }
  for (const [id, field] of [["torrents", "torrentsDirect"], ["autoconnect", "autoConnect"]]) {
    $(id).checked = !!st && st[field];
    $(id).disabled = !st;
  }
  for (const ed of editors) ed.render(st ? st[ed.key] || [] : [], !!st);
}
