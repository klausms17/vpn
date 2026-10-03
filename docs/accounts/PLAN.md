# Accounts: plan (3 Oct 2026)

**Status (3 Oct 2026):** being built, in phases (section 9). Phases A (the
server on the panel) and B (Windows) are built; C (Android) is next.

## 1. What the owner asked

- On 3 Oct 2026 the owner asked for:
  - registration and login in every app, so that a friend who signs in on a
    new device (Android, iPhone, Windows, Mac) gets the servers with
    nothing to copy;
  - after registration: «Мы отправили вам письмо для подтверждения на
    tes333t@mail.ru. Пожалуйста, завершите регистрацию, пройдя по ссылке
    из письма.»;
  - password recovery;
  - access granted by him by hand for now (payment later);
  - login optional, with the screen saying plainly what an account gives;
  - «для всех версий», the Mac app included («ты лучше меня это знаешь»).
- He was told (3 Oct): the owner gets «Новая регистрация» in Telegram and
  approves; passwords are stored only as hashes; the email is used only to
  sign in and to recover the password.

## 2. Decisions in brief

- **Links and keys keep working.** An account is a way to get a
  subscription link without copying it: once the owner approves, the
  account gets a Remnawave user, and every app signed in to it adds that
  user's subscription link as an ordinary subscription. Nothing about
  subscriptions changes.
- **The server is `server/remnawave/klaus-accounts.py`**, a Python script
  using only the standard library. It runs in its own `python:3-alpine`
  container next to `klaus-monitor`, hardened the same way (user 65534,
  read-only root, no capabilities). Nothing is built on the panel server,
  as for everything else there.
- **Storage is SQLite** in the volume `/opt/remnawave/accounts`, part of
  `klaus-panel backup`.
- **Passwords are hashed with scrypt** (N = 2^15, r = 8, p = 3: 32 MiB, as
  strong as OWASP's other settings), at most two at a time. Tokens are 256
  random bits and only their SHA-256 is stored.
- **Mail goes out over SMTP**, with any provider. To start, a separate Gmail
  account with an app password costs nothing and needs no domain
  (`klaus-panel mail-setup`). A provider on the owner's own domain can
  replace it later without code changes.
- **Telegram stays send-only.** The owner's message about a new
  registration carries a button that opens a one-time page on the
  subscription address with «Выдать доступ» and «Отклонить»; the message is
  edited with the outcome. Nothing reads the bot's updates, so
  `telegram-setup`, and Remnawave's own notices on the same bot, are
  undisturbed. `klaus-panel account-approve` and the other commands do the
  same from the server.
- **No client IP, not even in memory.** The privacy rule of the panel
  stays: Caddy hands 127.0.0.1 to the service. Friends behind the VPN
  share the nodes' addresses anyway, so an IP limit would hurt them. The
  limits are per address, per account and per day instead (section 5).
- **The service holds a Remnawave token that can only create and delete
  users** (the scopes are checked by the e2e test, as the monitor's are).
  Users are created with exactly the body of `klaus-panel add-user`, so
  `tidy-users` leaves them alone.
- **Apps know the account server from the build:** the repository variable
  `ACCOUNT_URL` (the subscription address, such as `https://sub.example`).
  Without it the account entry is hidden. The address is not in the source.

## 3. How it works for a friend

1. **Register** (any app): email and password («не короче 8 символов»).
   The app shows the owner's text: «Мы отправили вам письмо для
   подтверждения на …». The email has a link to a page on the subscription
   address with one button, «Подтвердить почту». A button, not the link
   itself, because mail scanners open links.
2. **Wait.** After confirming, the friend sees «Почта подтверждена. Осталось
   дождаться, когда владелец откроет доступ». The owner gets a Telegram
   message with a button to his decision page.
3. **Access.** On «Выдать доступ» the service creates the Remnawave user
   and emails the friend «Доступ к Kirov VPN открыт». Every app signed in
   to the account adds the subscription the next time it checks (when it
   opens, or every few minutes while its account screen shows «Ждём»).
4. **Another device:** sign in with the same email and password; the
   subscription appears. The panel's device limit applies, as for links.
5. **Forgot the password:** «Забыли пароль?» emails a link to a page with
   the new password twice. A reset signs every device out. For an account
   whose email was never confirmed, the reset confirms it too.
6. **Sign out** removes the account's subscription and its servers from
   that device. **Delete the account** (password asked again) removes the
   account and the Remnawave user made for it; Apple requires this in any
   app that offers sign-up.
7. **Without an account** everything works as before.

The apps say what an account is for in one place, the account screen:

> С аккаунтом VPN работает на всех ваших устройствах: телефоне,
> компьютере, iPhone и Mac. Войдите с одной почтой, и серверы появятся
> сами, без ссылок и ключей. Забыли пароль — восстановите его по почте.
> Без аккаунта всё работает как раньше.

## 4. Server

### 4.1 Deployment

- Container `klaus-accounts`, image `python:3-alpine`, command
  `python3 -u /app/klaus-accounts.py serve`, the script mounted read-only,
  `env_file: klaus-accounts.env`, volume `./accounts:/data` (owned by
  65534, mode 700), a health check on `/account/health`, a memory limit.
- Caddy, on the subscription address only, before the catch-all:
  `handle /account/*` → `reverse_proxy klaus-accounts:8081` with
  `X-Forwarded-For 127.0.0.1`, and a 64 KB request body limit.
- `klaus-panel` runs the owner's commands inside the container
  (`docker exec klaus-accounts python3 /app/klaus-accounts.py …`), so the
  database has one owner and no port is opened for administration.
- `klaus-accounts.env`: `PUBLIC_URL` (`https://SUB_DOMAIN`), `PANEL_API`,
  `PANEL_TOKEN`, `PANEL_SQUAD` (the `KlausVPN` squad), the `TELEGRAM_*`
  values the monitor has, and `SMTP_HOST`, `SMTP_PORT`, `SMTP_USER`,
  `SMTP_PASSWORD`, `SMTP_FROM`, `MAIL_DAILY_LIMIT`. Written by
  `klaus-panel setup`, `telegram-setup` and `mail-setup`.

### 4.2 API for the apps

All under `https://SUB_DOMAIN/account/v1/`, JSON in and out,
`Cache-Control: no-store`. Errors are `{"error": "<Russian text to show>",
"code": "<code>"}`, with `"retryAfter"` (seconds) on 429.

| Request | Body | Answer |
|---|---|---|
| `POST register` | `email`, `password` | 202; 400 `bad_email`, `weak_password`; 429 `too_often`; 503 `mail_off` |
| `POST resend` | `email` | 202; 400; 429; 503 |
| `POST login` | `email`, `password`, `device` | 200 `{token, account}`; 401 `bad_login`; 403 `unconfirmed`; 429 `locked` |
| `GET me` | Bearer token | 200 `{account}`; 401 `signed_out` |
| `POST logout` | Bearer token | 200 |
| `POST forgot` | `email` | 202; 400; 429; 503 |
| `POST delete` | Bearer token, `password` | 200; 401 `bad_login`, `signed_out` |

`account` is `{"email", "status": "pending" | "active" | "rejected",
"subscriptionUrl"}`; the link only when active.

- **No address can be found out:** register, resend and forgot answer the
  same whether the address has an account or not. Registering a taken
  address emails its owner instead («аккаунт уже есть, войдите или
  восстановите пароль»). A login for an unknown address costs the same
  hash time as for a known one.
- A registration on a not yet confirmed address replaces its password,
  but each confirmation link carries the password hash of its own
  registration, so a stranger's later attempt cannot take over the
  account when its owner clicks his link.

### 4.3 Pages (from the emails and the owner's Telegram)

`GET /account/confirm?t=`, `/account/reset?t=` and `/account/decide?t=`
show a page; only its form's POST acts. The pages are plain HTML in the
look of `klaus-page.html`, without scripts, with
`Content-Security-Policy: default-src 'none'; style-src 'sha256-…';
form-action 'self'; frame-ancestors 'none'; base-uri 'none'`,
`Referrer-Policy: no-referrer` and `X-Robots-Tag: noindex`.

### 4.4 Data

| Table | Columns | Kept until |
|---|---|---|
| `accounts` | id, email, password hash, status, created, confirmed, decided, panel user, subscription link, whether the service created that user, Telegram message id | deleted by the friend or the owner; unconfirmed: 2 days after the last registration; rejected: 30 days after the decision |
| `sessions` | token hash, account, device name, created, last used (to the day) | sign-out, a password reset, 180 days unused |
| `tokens` | token hash, account, kind (confirm, reset, decide), password hash (confirm), expiry | used, or expired: confirm 2 days, reset 1 hour, decide 14 days |
| `counters` | mails sent per day | 7 days |

No IP, no log of requests. The service's own log says what happened
("confirmed", "approved"), never an address, a token or a link.

### 4.5 Mail

- Sent by one worker from a queue, three tries; a failure is logged
  without the address.
- Plain text with an HTML alternative, in Russian: confirm, already
  registered, access granted, reset.
- `MAIL_DAILY_LIMIT` (300 by default, under Gmail's 500) stops sending for
  the rest of the UTC day, and the owner is told once in Telegram.

### 4.6 Remnawave

- `POST /api/users` with `klaus-panel add-user`'s body (`expireAt`
  2099-12-31, no traffic limit, `NO_RESET`, the `KlausVPN` squad) and
  `description: "klaus-accounts"`; the answer's `subscriptionUrl` is the
  link.
- The username comes from the address's local part: lower case,
  `[a-z0-9_-]`, cut to 24, plus `-` and four random hex characters
  (`ivan_petrov-3f2a`). Remnawave allows `^[A-Za-z0-9_-]{3,36}$`.
- `klaus-panel account-link EMAIL USER` gives an account an existing
  friend's user instead (his link, traffic and devices stay); deleting the
  account then only unlinks it.

## 5. Security

- **Threats:** guessing passwords, flooding registrations to make the
  server mail strangers or use up the day's mail, finding out who has an
  account, taking an account over through a reset or a confirmation, a
  stolen database, and a leaked token in a URL.
- **Limits** (no IP):
  - mail per address: one per 2 minutes, five per day (429 with
    `retryAfter`, the same for every address);
  - mail per day: `MAIL_DAILY_LIMIT`;
  - logins per address, known or not: five free failures, then waits of
    1, 2, 4, 8 and 15 minutes;
  - two password hashes at a time, 32 requests at a time, 8 KB bodies,
    15-second socket timeouts.
- Tokens in links are single-use and short-lived, and pages send no
  `Referer`. A reset or a password change ends every session.
- The service never sees a key of the panel beyond creating and deleting
  users; it cannot list users or read their links.
- A stolen database gives emails, scrypt hashes, and the subscription
  links of account users; the backups are kept like the panel's.

## 6. Apps

### 6.1 Shared rules

- An «Аккаунт» entry with the text of section 3. Screens: sign in (email,
  password, «Войти», «Создать аккаунт», «Забыли пароль?»), create, «письмо
  отправлено» (with «Отправить ещё раз»), waiting, signed in (the email,
  «Выйти», «Удалить аккаунт»).
- The session token is the only secret an app stores; the password never.
- When the account is active, the app adds its subscription with the flag
  `account: true`; a subscription with the same link already there is
  taken over. A changed link replaces the old one and keeps the servers'
  ids.
- `me` is asked when the app opens and every 5 minutes while the account
  screen shows «Ждём». A 401 signs the app out and removes the account's
  subscription.
- Requests go directly first, then, only when the server was not reached,
  through the selected server (Android) or the running tunnel (Windows).
- Never log the email, the password, the token or the link.

### 6.2 Android

- `libxray.Request(method, url, userAgent, headersJSON, body,
  timeoutMs, proxyConfigJSON)` returning the status and a body of at most
  64 KB, because today's fetch is GET only and drops error bodies. Its
  errors name only the host.
- `data/AccountApi.kt`, `data/AccountStore.kt` (state in
  `filesDir/data/account.json`; the token sealed with an AndroidKeyStore
  AES-GCM key in `noBackupFilesDir`), `ui/AccountSession.kt` (app-scoped,
  a `StateFlow`), `ui/screens/AccountScreen.kt`, a first «Аккаунт» section
  in Settings, `Subscription.account`. JVM tests with fakes.

### 6.3 Windows

- The service holds the token (`data\account.json`, DPAPI) and does every
  request; the window never sees the token or the link. One account per PC,
  like the servers; every window shows its email.
- `libxray/client/account`: the API client and state, without root
  libxray (requests are injected), shared later with iPhone and Mac.
- Pipe protocol 4: ops `accountRegister`, `accountLogin`, `accountForgot`,
  `accountResend`, `accountLogout`, `accountDelete`, `accountCheck`, and an
  `account` event. One request at a time.
- The window: an «Аккаунт» view in the rail.
- CI: the test server answers as the accounts service next to its
  subscription, `kirovctl account`, `account-login` (JSON on stdin) and
  `account-logout`, a smoke-test group. The API address comes from the
  build; an HKLM value that only administrators can set overrides it for
  the test.

### 6.4 iPhone and Mac

- Both use `libxray/client/account` through a gomobile facade
  (`libxray/mobile`; the root package cannot import `client/*`), with the
  token in the Keychain.
- iPhone: in phases 5–6 of `docs/ios/PLAN.md`.
- Mac: a new app, planned after the accounts (`docs/mac/PLAN.md`), with
  the account from its first version.

## 7. Owner steps (when phase A is merged)

1. A mailbox for sending: a new Gmail account, two-step verification on,
   an app password (Google Account → Security → App passwords). Then on
   the panel: `klaus-panel mail-setup` (the password is asked hidden) and a
   test letter to himself.
2. `klaus-panel telegram-setup` with a new bot token, if not done yet
   (CLAUDE.md, next steps 5 and 6).
3. The repository variable `ACCOUNT_URL` = the subscription address
   (Settings → Secrets and variables → Actions → Variables).
4. Update the panel: `cd ~/vpn && git pull && sudo bash
   server/remnawave/install-panel.sh`.

## 8. Tests

- `server/remnawave/test/accounts_test.py` (unittest, in CI's core job
  with the monitor tests): the flows, the limits, no enumeration, the
  confirmation race of 4.2, page escaping, data retention, a fake SMTP and
  Telegram, a fake Remnawave.
- `run-local.sh`: register through Caddy, read the letter from a mail sink
  in `mock-apis.py`, confirm, approve through the decision page, sign in,
  check that the link serves servers and that `tidy-users` leaves the user
  alone, reset, delete; the token cannot list users; no address or token
  in the logs; idempotent re-run; backup and restore.
- Apps: JVM tests (Android), Go tests (`libxray/client/account`, the
  service), the Windows smoke test.

## 9. Phases

- **A. Server and panel:** the script, install, Caddy, `klaus-panel`
  commands, backup, the e2e test, the owner's guide (section 12 of
  `docs/README.ru.md`).
- **B. Windows:** `libxray.Request`, `libxray/client/account`, the service
  and the window.
- **C. Android.**
- **D. iPhone and Mac.**
