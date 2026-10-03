// The account view (docs/accounts/PLAN.md): one account per computer, held
// by the service. The window sends what the user typed and shows where the
// account stands; it never sees the session or the account's link.
import { $, busy, call } from "./util.js";

const SIGNED_IN = ["pending", "active", "rejected"];
const STATUS_TEXT = {
  pending: "Ждём, когда вам откроют доступ. Мы пришлём письмо, а серверы появятся в разделе «Серверы» сами.",
  active: "Доступ есть. Серверы аккаунта — в разделе «Серверы», они обновляются сами.",
  rejected: "Доступ не выдан. Если это ошибка, напишите тому, кто дал вам Kirov VPN.",
};
const PASSWORDS = ["account-password", "account-password2", "account-delete-password"];

let status = null;
// The address the account has, as the service shows it.
let accountEmail = "";

export function renderAccount(s) {
  const a = (s && s.account) || {};
  $("rail-account").hidden = !a.available;
  if (!a.available) return;
  const now = a.status || "";
  accountEmail = a.email || "";
  if (now !== status) {
    // A new state starts clean: no password left in a field, no old
    // message, the deletion closed.
    if (status !== null) say("");
    status = now;
    clearPasswords();
    $("account-delete-box").hidden = true;
  }
  $("account-signed-out").hidden = now !== "";
  $("account-unconfirmed").hidden = now !== "unconfirmed";
  $("account-signed-in").hidden = !SIGNED_IN.includes(now);
  if (now === "unconfirmed") {
    $("account-unconfirmed-text").textContent = `Мы отправили вам письмо для подтверждения на ${a.email}. ` +
      "Пожалуйста, завершите регистрацию, пройдя по ссылке из письма.";
  }
  if (SIGNED_IN.includes(now)) {
    $("account-shown-email").textContent = a.email;
    $("account-status-text").textContent = STATUS_TEXT[now];
    $("account-check").hidden = now === "active";
  }
  for (const b of document.querySelectorAll("#view-account button")) b.disabled = !!a.busy;
}

function clearPasswords() {
  for (const id of PASSWORDS) $(id).value = "";
}

function say(text, isError = false) {
  const note = $("account-message");
  note.textContent = text;
  note.classList.toggle("err", isError);
  note.hidden = !text;
}

// run sends one request, shows its answer or error under the view, and
// never leaves a password in a field.
async function run(button, label, work) {
  say("");
  try {
    const message = await busy(button, label, work);
    if (message) say(message);
  } catch (err) {
    say((err && err.message) || String(err), true);
  } finally {
    clearPasswords();
  }
}

const email = () => $("account-email").value.trim();

function login() {
  run($("account-login"), "Вход…", () => call("AccountLogin", email(), $("account-password").value));
}

$("account-login").addEventListener("click", login);
// The view for an unconfirmed address then says where the letter went.
$("account-register").addEventListener("click", () =>
  run($("account-register"), "Создание…", async () => {
    await call("AccountRegister", email(), $("account-password").value);
    return "";
  }));
$("account-forgot").addEventListener("click", () => {
  if (!email()) {
    say("Введите почту, на которую зарегистрирован аккаунт, и нажмите «Забыли пароль?» ещё раз.", true);
    return;
  }
  run($("account-forgot"), "Отправка…", () => call("AccountForgot", email()));
});
$("account-password").addEventListener("keydown", (e) => {
  if (e.key === "Enter") login();
});

function loginConfirmed() {
  run($("account-login2"), "Вход…", () => call("AccountLogin", accountEmail, $("account-password2").value));
}

$("account-login2").addEventListener("click", loginConfirmed);
$("account-password2").addEventListener("keydown", (e) => {
  if (e.key === "Enter") loginConfirmed();
});
$("account-resend").addEventListener("click", () =>
  run($("account-resend"), "Отправка…", () => call("AccountResend", "")));
$("account-other").addEventListener("click", () => run($("account-other"), "Минуту…", () => call("AccountLogout")));

$("account-check").addEventListener("click", () =>
  run($("account-check"), "Проверка…", async () => {
    await call("AccountCheck");
    return "";
  }));
$("account-logout").addEventListener("click", () =>
  run($("account-logout"), "Выход…", async () => {
    await call("AccountLogout");
    return "Вы вышли из аккаунта. Его серверы убраны с этого компьютера.";
  }));
$("account-delete").addEventListener("click", () => {
  $("account-delete-box").hidden = false;
  $("account-delete-password").focus();
});
$("account-delete-cancel").addEventListener("click", () => {
  $("account-delete-box").hidden = true;
  clearPasswords();
});
$("account-delete-ok").addEventListener("click", () =>
  run($("account-delete-ok"), "Удаление…", () => call("AccountDelete", $("account-delete-password").value)));
