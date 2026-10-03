// The journal: the ends of the service's, the core's and the window's
// logs, masked by the Go side, with «Скопировать».
import { $, call, el, toast } from "./util.js";

let shown = null;

export async function loadJournal() {
  const box = $("logs");
  box.replaceChildren(el("p", "note", "Загрузка…"));
  const logs = await call("Logs").catch((err) => ({ sections: [{ title: "Журнал", text: (err && err.message) || String(err) }] }));
  if (!logs.sections) return;
  shown = logs;
  box.replaceChildren(...logs.sections.map((s) => {
    const card = el("div", "card log-card");
    const pre = el("pre", "log", s.text.trim() || "Пусто");
    card.append(el("h3", "log-title", s.title), pre);
    return card;
  }));
  // The newest lines are at the end.
  for (const pre of box.querySelectorAll("pre")) pre.scrollTop = pre.scrollHeight;
}

$("logs-refresh").addEventListener("click", loadJournal);
$("logs-copy").addEventListener("click", async () => {
  if (!shown) return;
  const text = shown.sections.map((s) => `=== ${s.title} ===\n${s.text.trim()}`).join("\n\n");
  const copied = await call("Copy", text).catch(() => false);
  toast(copied ? "Журнал скопирован: вставьте его в сообщение" : "Не удалось скопировать");
});
