# Fake Hermes

A stand-in for the Hermes dashboard and its `/api/ws` gateway. It runs on the host for the cascade voice end-to-end tests (spec v2 §11b). Use it instead of a real server: the e2e suite never talks to a real Hermes.

- It is Python 3, stdlib only (`fake_hermes.py`, `fakehermes/`).
- The Gradle module `:tools:fake-hermes` holds **only its self-tests**. They are JVM tests that start the server as a subprocess and drive it with the real `:hermes` classes: `HermesAuth`, `HermesGateway`, `HermesConversation` and `GatewaySessionRecovery`.
- Nothing here ships in an APK.

## Run

```bash
tools/fake-hermes/run.sh                       # port 18119, scenarios/default.json, fresh temp data dir
tools/fake-hermes/run.sh 18119 my.json /tmp/fh # port, scenario, data dir (sessions persist there)
tools/fake-hermes/run.sh 0 -- --quiet          # 0 = any free port; flags after -- go to fake_hermes.py
```

- **Ready line.** The server prints `FAKE_HERMES_LISTENING port=<n> …` once it accepts connections.
- **Login.** User `fake`, password `fake-password`. Change them with `-- --user U --password P`.
- **Address.** It binds `127.0.0.1` by default. The `hub-emulator` container uses host networking, so the emulator reaches the fake at **`http://10.0.2.2:18119`**.
  - Checked with `adb shell 'printf "GET /__fake/health HTTP/1.0\r\n\r\n" | nc -w 3 10.0.2.2 18119'`. The image has `nc` and `toybox` but no `curl`.
- **TLS (optional).** Use `-- --tls-cert cert.pem --tls-key key.pem`. To make a throwaway certificate whose SAN covers the emulator address:
  ```bash
  openssl req -x509 -newkey rsa:2048 -nodes -keyout key.pem -out cert.pem -days 7 \
    -subj "/CN=fake-hermes" -addext "subjectAltName=IP:10.0.2.2,IP:127.0.0.1"
  ```

**The app will not accept `http://10.0.2.2` as it stands** (S-F2a's side):
- `HermesEndpoint.parse` allows cleartext only for `*.ts.net` and `100.64.0.0/10`.
- `live/src/main/res/xml/network_security_config.xml` allows cleartext only for `ts.net`.

An e2e build needs one of these, in debug or androidTest only:
1. A debug network-security config that permits `10.0.2.2`, plus an endpoint built without the cleartext rule. `HermesEndpoint.forTesting` is `internal` to `:hermes`.
2. `https://10.0.2.2` with the TLS mode above and a debug trust anchor for that certificate. `parse` accepts https.

The self-tests avoid the problem by using `http://fake-hermes.ts.net:<port>` with an OkHttp `Dns` that maps that name to 127.0.0.1.

## Self-tests

```bash
JAVA_HOME=/tmp/folio-toolchain/java ANDROID_HOME=/tmp/folio-toolchain/android-sdk \
  ./gradlew :tools:fake-hermes:testDebugUnitTest
```

They need `python3` on the PATH. The suites:
- `FakeHermesTurnTest`:
  - login, ready and voice status;
  - a refused login and the refresh;
  - a full prompt with tools, interim text and streamed text;
  - approval (once and deny) and clarify round trips;
  - a failing turn;
  - 4000 and 4001;
  - `config.get busy`.
- `FakeHermesBusyTest`:
  - `queued: true`;
  - interrupt, which redirects;
  - `interrupt_style` queue, which queues and interrupts;
  - steer;
  - `session.interrupt`;
  - 4009 retries.
- `FakeHermesRecoveryTest`:
  - the probe on never-prompted, ended and running sessions;
  - 4007;
  - a submit lost after or before the server stored it;
  - double-submit detection;
  - admin restart;
  - `kill -9` and a new process on the same data dir.
- `FakeHermesDropTest`:
  - a drop (abort and clean close), then reconnect and resume;
  - no events without a resume;
  - a stall, then the heartbeat;
  - an open approval delivered again after a drop;
  - refused tickets and upgrades.
- `DefaultScenarioTest`: the shipped rules.
- `GoldenCallTest`: a whole Bill call through `CascadeCore` and `CascadePorts.gateway` (the app's wiring) with a scripted Jev, writer and voice. The card goes Starting → Running → Done, one prompt, the result spoken.

Set `FAKE_HERMES_ECHO=1` to see the server's log in the test output.

## What it serves

| Path | Behaviour |
|---|---|
| `POST /auth/password-login` | `{provider:"basic", username, password, next}` returns 200 `{ok:true, next:"/"}` and sets the cookies `hermes_session_at` (Max-Age 43200), `hermes_session_rt` (2592000) and `hermes_session_provider=basic`, each `HttpOnly; SameSite=Lax; Path=/`. Failures: 401 `{"detail":"Invalid credentials"}`; 404 `{"detail":"Unknown provider"}`; 422 when there is no `provider`. |
| `POST /auth/native/refresh` | `{refresh_token, provider}` returns `{access_token, refresh_token, token_type:"Bearer", expires_at, provider, user_id}`. Refresh tokens rotate, single use. Failures: 400 `refresh_token required`; 401 `{"error":"session_expired","detail":"Refresh token expired or invalid; start a new sign-in."}`. |
| `POST /api/auth/ws-ticket` | Bearer, or else the access cookie. Returns `{ticket, ttl_seconds:30}`; each ticket is single use. Failure: 401 `{error:"unauthenticated", reason:"no_cookie", …}` or `{error:"session_expired", reason:"invalid_or_expired_session", …}`. |
| `GET /api/audio/voice-live/status` | `{ok:true, mode:"gpt-live", available:false, reason:"no OpenAI API key …", model, voice}`, the real shape of a server with no OpenAI key. The cascade does not use it. |
| `POST /api/audio/voice-live/session` | 503: there is no voice-live here. |
| `GET /api/ws?ticket=` | WebSocket upgrade. The ticket may also come as the subprotocol `hermes-gateway-ticket.<t>`. A bad, used or expired ticket gets HTTP 403. |

Gateway (JSON-RPC 2.0, one object per WebSocket message):
- **`gateway.ready`:** the first frame, `{skin:{}, change_events, heartbeat, replay_epoch}`. Its `replay_epoch` changes on every restart.
- **`gateway.ping`:** answered with `{ok:true}`. The server never pings.
- **Events:** `{type, session_id: <runtime id>, payload?, seq}`, with `seq` per session.
- **`client.capabilities {server_requests}`:** returns the real method list. A socket that never sends `server_requests:true` gets no approval or clarify requests; the fake withdraws them.
- **`session.create`:** `{session_id: 8 hex, stored_session_id: "YYYYMMDD_HHMMSS_6hex", message_count:0, messages:[], info}`, then a `session.info` event. Nothing is stored until the first prompt.
- **`session.resume {session_id: stored, omit_messages, inline_images, …}`:**
  - **Live** (this process has it): the same runtime id, plus `running`, `status` (idle/working/waiting), `inflight {user, assistant, streaming}`, `queued {user}` and `open_requests [{id, method, params}]` when they apply.
  - **Cold** (after a restart): a new runtime id, `status:"idle"`, `inflight:null`.
  - **Messages:** `messages[]` holds `{role, text, timestamp}` and tool rows `{role:"tool", name, context}`.
  - **Unknown or never-prompted** after a restart: **4007 "session not found"**.
  - The socket becomes a viewer of the session.
- **`prompt.submit {session_id, text, surface?, voice_context?, queued?}`:**
  - **Idle:** returns `{status:"streaming", user_row_id}`. The user row is stored **before** the turn starts, as the real server does.
  - **Unknown runtime id:** 4001.
  - **Busy:** `queued:true` or busy mode `queue` gives `{status:"queued"}`; the user row is stored now and the text runs after the live turn. Queued texts merge as `"a\n\nb"`.
  - **Busy mode `interrupt` (the default):** the real agent supports redirect, so the live turn absorbs the text, which is stored as a user row, and the reply is `{status:"redirected"}`. With `interrupt_style: "queue"` the reply is `queued` and the live turn is interrupted.
  - **Busy mode `steer`:** `{status:"steered"}`.
- **`session.interrupt`:** always `{status:"interrupted"}`. It clears the queue, withdraws open requests (`request.cancel`, reason `interrupted`), and the turn ends with `message.complete {status:"interrupted"}`.
- **`config.get {key:"busy"}`:** returns `{value: "interrupt"|"queue"|"steer"}`. Any other key gets 4002.
- **Turn events:**
  1. `message.start` (no payload);
  2. `tool.start {tool_id, name, context}` and `tool.complete {tool_id, name, args, duration_s, result, summary}`;
  3. `message.interim {text, already_streamed:false}`, `status.update`, `error {message}`;
  4. `message.delta {text}`;
  5. `message.complete {text, usage, status, error?, recoverable?}`.
- **Server requests:** `{id:"srq-<12 hex>", method:"approval"|"clarify", params:{session_id:<runtime id>, …}}`.
  - **Approval** params: `request_id, command, description, pattern_key(s), allow_permanent, allow_session, choices`.
  - **Clarify** params: `questions[{qid, question, choices|null, multi_select}]`.
  - **Answers:** a response frame with the same id: `{choice, all}` or `{answers}`. An empty clarify result, or an error, counts as no answer.
- **Unknown methods and params:** an unknown method gets -32601. Unknown param keys get 4000, because the real params models forbid extras. Unparseable JSON gets -32700.

## Scenario file

```json
{
  "name": "default",
  "busy_mode": "interrupt",            // interrupt | queue | steer (config.get busy)
  "interrupt_style": "redirect",       // redirect (real agent) | queue (older agents: queue + interrupt)
  "stream": {"chunk_words": 4, "chunk_delay_ms": 60},
  "rules": [
    {
      "name": "stock_check",
      "match": ["stock", "how many"],  // any of these, case-insensitive substring
      "all": [],                       // optional: all of these
      "regex": null,                   // optional
      "stream": {"chunk_delay_ms": 20},// optional per-rule override
      "steps": [
        {"tool": "s4l_ops_get_sku", "context": "Looking up", "ms": 900, "summary": "found"},
        {"interim": "Checking the warehouse too."},
        {"status": "Still going", "kind": "status"},
        {"warning": "a non-fatal error event"},
        {"wait_ms": 2000},
        {"approval": {"command": "…", "description": "…", "choices": ["once","session","always","deny"]},
         "deny_reply": "OK, I did not send it.", "timeout_ms": 300000},
        {"clarify": {"questions": [{"qid": "channel", "question": "Email or phone?", "choices": ["email","phone"]}]},
         "cancel_reply": "OK, leaving it."},
        {"fail": "The tool crashed."},                     // message.complete status error
        {"fail": {"message": "…", "error_event": true}},    // plus an error event first
        {"hang": true}                                      // never ends until interrupted
      ],
      "reply": "Text with {prompt}, {choice} and {answer.channel}"
    }
  ],
  "fallback": {"steps": [], "reply": "Done."}
}
```

(JSON has no comments: the ones above are documentation only.)

- **Rules:** the first matching rule wins; otherwise `fallback` runs.
- **Replies:** the reply streams as `message.delta` chunks of `chunk_words` words. A denied approval or a cancelled clarify replaces the reply.
- **Timeouts:** an approval or clarify that times out sends `request.cancel` (reason `timeout`) and is treated as no answer.
- **Shipped scenarios:**
  - `scenarios/default.json` has made-up stock checks, emails (with an approval), supplier chases (with a clarify), summaries, a slow deep dive, a failure and a fallback. No real data.
  - `scenarios/test.json` is the fast one the self-tests use.

## Admin and assertion API

These live under `/__fake/` on the same port, with no auth; keep the bind on 127.0.0.1. Bodies are JSON.

| Call | Effect |
|---|---|
| `GET /__fake/health` | `{ok, port, scenario, boot, busy_mode, interrupt_style, data_dir}` |
| `POST /__fake/faults {…}` | Sets faults; merged with the current ones; `false`, `0` or `null` clears one. `GET` lists them; `DELETE` clears them all and un-stalls. Full list below. |
| `POST /__fake/config` | `{busy_mode?, interrupt_style?, access_ttl_s?, expire_access_tokens?}` |
| `POST /__fake/scenario` | `{path}` or a whole scenario object: swaps the rules live |
| `POST /__fake/next` | `{rule: {...}, match?: "text"}`: a one-shot rule for the next prompt (that contains `match`) |
| `POST /__fake/drop` | `{mode: "abort" \| "close", session?: stored}`. `abort` ends TCP with no close frame, so the client sees a failure; `close` sends close 1001. With `session`, only that session's viewers are dropped. |
| `POST /__fake/stall` | `{on: true, ms?: n}` holds every WebSocket frame in both directions (HTTP still answers). `{on:false}`, or `ms` running out, releases them. |
| `POST /__fake/restart` | As a process restart on the same data dir. Sockets abort and running turns die silently. Prompted sessions, tokens and the received log stay. Never-prompted sessions, tickets, faults and queues go. Runtime ids are new. |
| `POST /__fake/reset` | Wipes everything, persisted state included |
| `GET /__fake/sessions` | Per session: ids, `persisted`, `running`, `viewers`, `messages`, `queued`, `open_requests`, `dropped_events`, and `received`, which is everything the client sent about it |
| `GET /__fake/received?session=&kind=&method=` | The raw received log. Kinds: `http`, `rpc`, `response`, `prompt`, `resume`, `interrupt`, `server_request`, `turn_end`, `parse_error`. |
| `GET /__fake/assert/prompts?session=` | `{sessions: {stored: [{ts, text, outcome, counted, queued, surface, voice_context_chars, boot, busy_mode}]}, count}` |
| `GET /__fake/assert/double-submits` | `{ok, same_session:[{session, text, count, ts}], cross_session:[{text, sessions}]}` |
| `GET /__fake/assert/interrupts` | `{interrupts:[{ts, session, boot, running}], count}` |

Faults for `POST /__fake/faults`:
- `refuse_logins`: `true` or `401`, `429`, `503`.
- `refuse_refresh`: `true` or `503`.
- `refuse_tickets`: `true` or `503`.
- `refuse_upgrade`: answers 403 at the upgrade.
- `no_ready`: no `gateway.ready` after the upgrade.
- `no_pong`: pings go unanswered.
- `busy_errors: n`: the next n busy submits answer 4009.
- `fail_resume: n`: the next n resumes answer 5000.
- `reap_on_drop`: a detached idle session loses its runtime id, so the next submit gets 4001.
- `lose_submits: {count, mode, drop}`: the server reads the submit and never replies.
  - `mode: "after_store"` (the default) stores the prompt and runs the turn, so a resend would be a double.
  - `mode: "before_store"` drops the prompt, so a resend is right.
  - `drop` (default `true`) aborts the socket straight after.

**Outcomes** in `assert/prompts`:
- Accepted: `streaming`, `queued`, `redirected`, `steered`.
- Lost: `lost_after_store:<status>`, `lost_before_store`.
- Refused: `error:4009`.

`counted` is true when the server took the prompt in. Double-submit detection uses only counted prompts, with the exact text. The cascade uses one session per task, so a resend into a new session shows up under `cross_session`.

**Persistence.** The data dir holds `sessions/<stored>.json`, `tokens.json` and `received.jsonl`. Each is written temp-then-rename with fsync, so `kill -9` plus `run.sh` on the same data dir behaves like a server restart. The received log and the assertions span restarts; each entry carries its `boot` id.

## Where the fake differs from the real server

Checked read-only against the deployed source on 2026-10-13: `hermes_cli/dashboard_auth/`, `tui_gateway/{ws,server,server_requests,methods_session,methods_prompt,methods_config,session_auto_continue,prompt_turn}.py`, `agent/interrupt_control.py`.

**Auth**
- No real rate limit. The real server allows 10 logins per 60 s per IP; here 429 comes only from `refuse_logins: 429`.
- No PKCE or native broker.
- No transparent refresh: the real server, given an expired access cookie and a valid refresh cookie on `/api/*`, refreshes and sets new cookies on that response.
- Cookies are never `__Host-`/`__Secure-` prefixed, even with `--tls`.
- Tokens are opaque strings, not the real format.
- A bad ticket gets HTTP 403, which is what uvicorn turns the real pre-accept close (4401) into. There is no origin check (4403) and no legacy `?token=`.

**Events**
- There is no `reasoning.delta`, `tool.generating`, `session.usage`, `session.title` or `rendered`.
- `message.delta` is not coalesced on a 33 ms timer.
- `usage` holds made-up counts.

**Sessions**
- `session.create` never fails its build, so there is no `error` event.
- The `info` object is minimal.
- `session.resume`:
  - no lookup by title;
  - no `defer_history`/`hydrating`, and no 4130 or 4009 "settling";
  - no `pending_approval`, `auto_continue` or `todo_state`;
  - `status` is never `starting`.
- Detached sessions are never reaped unless `reap_on_drop` is set.
- A `prompt.submit` from a socket that is not a viewer does not make it one. This is assumed, not verified.

**Busy handling**
- Redirect always succeeds. The real one falls back to queue-and-interrupt when the model request has just finished. During tool execution it degrades to a steer and still answers `redirected`.
- Steered text is not stored as a user row. This is unverified on the real server.
- On interrupt, the partial assistant text is stored as a row. This is unverified.
- After a restart, queued text is not run again. The real server may auto-continue.

**Errors**
- 4009 comes only from `busy_errors`. On the real server it comes from truncation params, settling or a running subagent.
- `voice_stopped` (the server's own voice mode) is not modelled.
- `config.get` knows only `busy`.

**Requests**
- `clarify.lock` and `request.answer` are not implemented.
- An approval for a socket without `server_requests` is treated as denied.

**Notes for client code** (seen while building this; these are not fake bugs):
- In interrupt mode the real server answers **`redirected`**, not `queued`, for text-only prompts, because the deployed agent sets `_supports_active_turn_redirect`.
- `session.resume` `status` on a live session is `working`/`waiting`/`idle`, never `streaming`. `:hermes`' `FakeSessions` uses `streaming`; the client only displays it.
- `SessionProbe.prompts` lists a running turn's text twice: once as the stored user row, once as `inflight.user`. Matching is unaffected.
- The real approval path sends no `tool_name`.
