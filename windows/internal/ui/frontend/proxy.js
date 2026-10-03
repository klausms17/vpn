// A proxy another VPN program left on this computer leads to a port where
// nothing listens once that program is off, and the programs that use it
// cannot go online. While the tunnel is up, the window looks for one and
// offers to remove it: Kirov VPN needs none.
import { $, busy, call, fail, stateOf, toast } from "./util.js";

const banner = $("proxy");
const text = $("proxy-text");
const fix = $("proxy-fix");
// Looked for once a connection, and again when the window comes back.
const againMs = 60_000;
let last = null;
let lookedFor = 0;
let lookedAt = 0;

export function checkProxy(s) {
  last = s;
  if (stateOf(s) !== "on") {
    banner.hidden = true;
    return;
  }
  const since = s.status.connectedSince;
  if (since === lookedFor && Date.now() - lookedAt < againMs) return;
  lookedFor = since;
  lookedAt = Date.now();
  call("DeadProxy").then(show, () => {});
}

function show(found) {
  const addrs = (found && found.addrs) || [];
  banner.hidden = addrs.length === 0 || stateOf(last) !== "on";
  if (banner.hidden) return;
  let words = `Программы, настроенные на прокси ${addrs.join(", ")}, не выходят в интернет: его оставила другая VPN-программа, а сама уже выключена. Kirov VPN прокси не нужен.`;
  if (!found.fixable) {
    words += ` Уберите переменную ${found.machine.join(", ")} для всех пользователей: «Изменение системных переменных среды», нужны права администратора.`;
  }
  text.textContent = words;
  fix.hidden = !found.fixable;
}

fix.addEventListener("click", async () => {
  try {
    toast(await busy(fix, "Убираю…", () => call("RemoveDeadProxy")));
    lookedAt = 0;
    checkProxy(last);
  } catch (err) {
    fail(err);
  }
});

window.addEventListener("focus", () => {
  lookedAt = 0;
  checkProxy(last);
});
