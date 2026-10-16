# OpenCode trên oct

> **Trạng thái `main` (từ 2026-10-14): Terminal-first (P3).** Mở app = mở
> **terminal Termux** và **tự chạy TUI `opencode`** (launcher). Client web
> (WebView) lùi về shortcut "OpenCode Web"/`am start`. Một foreground service
> (`OpenCodeDaemonService`) vẫn sở hữu daemon `opencode serve --service` trên
> loopback và **đăng ký thành dịch vụ nền dùng chung** — TUI terminal và client
> web nói cùng **một** server/session/config khi service chạy. Kèm **cầu nối tool
> `termux-api` qua MCP** để assistant gọi được notification/clipboard/battery…
> Chi tiết ở mục **P1**, **P2** và **P3** cuối file.
>
> Quá trình cài đặt (giữ từ P0): `OpenCodeSetup` (khi mở terminal) và
> `OpenCodeDaemonService` (khi mở client) chạy `OpenCodeInstaller` trong nền:
> tải sẵn thư viện còn thiếu (`glibc-repo`/`glibc-runner` + `patchelf` qua
> `apt-get`) và binary OpenCode từ npm registry (đã `patchelf` để chạy trực
> tiếp), ghi trạng thái vào `~/.opencode/state`, log vào `~/.opencode/setup.log`.
> Gõ `opencode` trong terminal chạy **TUI gốc** — wrapper
> `$PREFIX/bin/opencode` `exec` trực tiếp binary, không mở app, không `am start`,
> không `LD_PRELOAD` (glibc loader không tải được libc bionic).

## Mở app = terminal + TUI `opencode` (daemon tự cài, tự chạy)

- **Launcher là `TermuxActivity`** (terminal). Mở app → tự chạy TUI `opencode`
  trong một session.
- **Lần đầu** (chưa có wrapper `$PREFIX/bin/opencode`): mở shell đăng nhập thường;
  installer chạy nền (kể cả đăng ký MCP), lần mở sau vào thẳng TUI.
- Ngay khi `$PREFIX` (bootstrap Termux nhúng trong APK, do `TermuxActivity` sở hữu)
  sẵn sàng, `OpenCodeSetup.ensureAsync()` chạy installer trên luồng nền.
- Chưa cài → cài đầy đủ; đã cài + cache còn tươi (TTL 24h, `~/.opencode/last-update-check`)
  → fast-path im lặng (không nháy dialog/notification/toast).
- Muốn ép cài lại (test / repair): `touch ~/.opencode/reinstall` rồi mở lại app;
  marker được xoá khi installer chạy.

## Cách lần đầu cài đặt diễn ra

`OpenCodeInstaller.ensure()` là máy trạng thái có khoá (`FileChannel.tryLock`
trên `~/.opencode/install.lock`) nên không chạy trùng:

1. **Kiểm tra** phiên bản đang cài (chạy thử `~/.opencode/bin/opencode --version`,
   fallback đọc `~/.opencode/version`) + cache TTL 24h.
2. **Cài thư viện hệ thống** bằng chuỗi `apt-get` (một lệnh mỗi bước):
   `update` → `full-upgrade` → `install glibc-repo` → `update` →
   `install glibc-runner patchelf`. Có `full-upgrade` vì bootstrap trong APK cũ
   hơn kho gói. `apt-get` chạy theo kiểu bionic (giữ `LD_PRELOAD=libtermux-exec.so`
   để maintainer script chạy đúng).
3. **Tải binary** tarball từ npm registry
   (`@opencode/cli-linux-<arch>/-/cli-<arch>-<ver>.tgz`) qua `HttpURLConnection`.
4. **Kiểm tra toàn vẹn**: so `sha512-<base64>` tính bằng Java với `dist.integrity`
   trong manifest npm.
5. **Giải nén** đúng entry `package/bin/opencode` bằng trình đọc tar/gzip thuần Java
   (`OpenCodeTar`, có chống path-traversal), ra `~/.opencode/bin/opencode.new`.
6. **Patch** bằng `patchelf --set-interpreter $PREFIX/glibc/lib/ld-linux-aarch64.so.1`
   trong môi trường sạch (`LD_PRELOAD`/`LD_LIBRARY_PATH` bị bỏ). **Chỉ đổi
   interpreter, không `--set-rpath`** (đổi rpath segfault binary bun).
7. **Chạy thử** `opencode.new --version`; chỉ khi chạy **trực tiếp** được mới
   **publish**: chuyển `.new` vào `~/.opencode/installed/opencode-v<version>`
   (binary đã vá chỉ được ghi **một lần**, không bao giờ bị vá đè tại chỗ) rồi
   `rename(2)` symlink `~/.opencode/bin/opencode` trỏ sang build mới (swap
   nguyên tử). Ghi `~/.opencode/version`; giữ 3 build mới nhất để rollback.
8. Luôn ghi/ghi-đè wrapper `$PREFIX/bin/opencode` (marker `opencode-native-wrapper-java-v4`).
   Wrapper `exec` trực tiếp binary đã patch: `unset LD_PRELOAD LD_LIBRARY_PATH;
   export OPENCODE_DISABLE_AUTOUPDATE=1; exec "$BIN" "$@"`.

### Theo dõi build đã cài (layout có phiên bản, 2026-10-15)

Trước đây `~/.opencode/bin/opencode` **là** binary đã vá và marker `.patched`
(`<size> <mtime>`) là cách duy nhất để biết nó còn đúng build ta vá hay không —
một heuristic dễ vỡ (mtime/size trùng hợp, file bị ghi đè…). Từ bản này:

- Mỗi release được vá **một lần** và lưu ở `~/.opencode/installed/opencode-v<version>`;
  `~/.opencode/bin/opencode` chỉ là **symlink** trỏ tới build hiện tại.
- Update **chỉ đổi symlink** (một `rename(2)`), không bao giờ ghi đè file ELF đã vá.
  Vì vậy build đang chạy được **theo dõi bằng chính symlink target**, không phải
  đoán qua marker.
- Cài đặt cũ (binary là file thường) được **adopt** một lần: chuyển vào
  `installed/` và thay bằng symlink.
- Giữ **3** build mới nhất trong `installed/` để rollback (`RETAINED_VERSIONS`).
- Marker `.patched` vẫn còn như **lớp tự vệ thứ hai** của wrapper, giờ so bằng
  `stat -L` (theo symlink) khớp với cách Java ghi (`File.length()`/`lastModified()`).
  Lệch ⇒ wrapper chạy tạm qua loader kèm cảnh báo (nhánh này gần như không còn xảy ra).

### Vì sao cần patch interpreter

`opencode service start`/TUI spawn server bằng cách hardlink `process.execPath`
rồi chạy `<hardlink> serve`. Nếu chạy qua `ld.so binary` thì `process.execPath`
= loader → lệnh thành `ld.so serve` → thoát code 127. Nhờ `PT_INTERP` trỏ đúng
loader glibc Termux và môi trường sạch, binary chạy trực tiếp nên tự spawn được
server nền. `OPENCODE_DISABLE_AUTOUPDATE=1` chặn opencode tự ghi đè binary
(bản mới sẽ chưa patch → hỏng `PT_INTERP`).

## Progress hiển thị khi cài (2026-10-13)

Khi có công việc thật (cài thư viện / tải binary / cấu hình), UI hiển thị:

- **Dialog bỏ được** (bấm back/chạm ngoài để ẩn — tải vẫn tiếp tục).
- **Notification nền có progress bar** (channel `opencode-setup`, silent):
  cập nhật theo nền kể cả khi tắt app (tiến trình sống nhờ TermuxService
  foreground service).
- **Tên từng gói thư viện đang xử lý** — parse output `apt-get`
  (`Get:…`/`Selecting…`/`Unpacking…`/`Setting up…`): "tải gói glibc-repo",
  "giải nén gói patchelf", "cấu hình gói glibc-runner".
- **% tải thực sự** của binary — đo byte theo `Content-Length`, cập nhật mỗi
  chunk: "47% (92.3 / 196.4 MB)"; không có Content-Length thì hiện "xx.x MB
  đã tải…".
- Khi xong: hủy notification progress, thông báo kết quả 15s rồi tự xoá.
- Android 13+ xin quyền `POST_NOTIFICATIONS` đúng lúc bắt đầu có việc thật.
- Fast-path (đã cài sẵn) **im lặng hoàn toàn**.

## Chạy OpenCode từ terminal

```sh
opencode            # TUI gốc trong terminal
opencode run "tóm tắt repo này"   # chạy 1 prompt rồi thoát
opencode models                   # liệt kê model
opencode auth login                # đăng nhập provider
```

Máy mới cài lần đầu: mở app một lần để tự cài; sau đó gõ `opencode` là dùng
được. Nếu binary chưa xong, wrapper báo
`opencode: chưa cài xong — mở lại app Termux để tự cài đặt.`

## Phím tắt nhanh (extra keys)

Thêm hàng phím riêng cho OpenCode vào `~/.termux/termux.properties`:

```properties
extra-keys = [['/new','/models','/sessions','/undo'],['ESC','TAB','CTRL','ALT','UP','DOWN']]
```

Rồi chạy `termux-reload-settings`.

## File trạng thái / log

| File | Nội dung |
|---|---|
| `~/.opencode/state` | trạng thái installer hiện tại (`checking`/`deps`/`download`/`configure`/`ok`/`error`) |
| `~/.opencode/setup.log` | log chi tiết quá trình cài (apt + download + patch) |
| `~/.opencode/version` | phiên bản binary đã cài |
| `~/.opencode/last-update-check` | mốc check cache (TTL 24h) |
| `~/.opencode/install.lock` | khoá chống chạy trùng installer |
| `~/.opencode/reinstall` | marker ép cài lại ở lần mở app kế tiếp |
| `~/.opencode/bin/opencode` | symlink tới build hiện tại trong `installed/` |
| `~/.opencode/installed/opencode-v<version>` | binary đã vá của từng phiên bản (giữ 3 bản mới nhất) |
| `~/.opencode/.patched` | `<size> <mtime>` của build đang chạy (lớp tự vệ của wrapper) |
| `~/.opencode/bin/termux-api-mcp.sh` | MCP stdio server cầu nối tool `termux-api` (P2) |
| `~/.config/opencode/opencode.json` | config OpenCode toàn cục; chứa đăng ký MCP `termux-api` (P2) |
| `~/.local/state/opencode/service.json` | đăng ký dịch vụ nền dùng chung (`serve --service` ghi) |
| `$PREFIX/bin/opencode` | wrapper terminal (`exec` trực tiếp binary) |

## P0 — đảo trục: OpenCode làm chủ, app làm vỏ (2026-10-14)

> Trạng thái: **đã chứng minh.** P0 chỉ thêm bằng chứng + một WebView client
> tách biệt; việc đổi luồng khởi động được làm ở **P1** (bên dưới).

Mục tiêu dài hạn: biến OpenCode thành **chủ** (daemon trung tâm sở hữu session,
agent, quyền, PTY/tool) và coi app Termux chỉ là nơi cung cấp môi trường
(`$PREFIX`, exec/PTY/FS) và tool; UI chỉ còn là cửa sổ nối vào daemon.
OpenCode V2 vốn đã là client–server nên không cần viết lại — chỉ cần thôi coi
app là chủ.

### Bằng chứng đã kiểm chứng trên thiết bị

Chạy lại tự động: `bash scripts/opencode-p0-proof.sh` (5/5 PASS trên
`opencode v2.0.28`).

| Sự thật | Bằng chứng |
|---|---|
| Daemon có sẵn web UI + API v2 | `opencode serve` = "Start the v2 API and web server" (`--hostname/--port/--cors/--service/--stdio`) |
| Web UI dùng được trên mobile | `GET /` → `<title>OpenCode</title>`, `mobile-web-app-capable` |
| Dịch vụ nền dùng chung | `opencode service start/status/stop/restart`; `/api/info` → `urls`, `capabilities.persistentPty: true` |
| API có xác thực + OpenAPI | `/openapi.json` → `401` nếu chưa auth; spec tại `/openapi.json` |
| Đăng nhập kiểu trình duyệt | `opencode pair` → link `/auth/connect/<code>`; GET với `Accept: text/html` → `302` + `Set-Cookie: opencode_session_<port>`; cookie đó mở khoá API |
| OpenCode tự chạy shell trên Termux | `POST /api/shell` → `Shell.Info` (pid, `$PREFIX/bin/bash`), `GET /api/shell/{id}/output` trả stdout/exit code |
| OpenCode sở hữu PTY bền | `POST /api/pty` → `pty.create`; `GET /api/pty` giữ session đang chạy; `DELETE /api/pty/{id}` |

Lưu ý: `POST /api/pair` gọi thuần HTTP bị **401**; phải lấy link qua CLI
(`opencode pair`) hoặc qua ngữ cảnh đã xác thực.

### P1 — daemon foreground + client launcher (2026-10-14)

Ba mảnh, đều trong `com.termux.app.opencode`:

| Thành phần | Vai trò |
|---|---|
| `OpenCodeDaemon` | bọc tiến trình `opencode serve` (Android-free): chạy `serve --hostname 127.0.0.1 --port 0` (P2: thêm `--service` và đọc mật khẩu từ file trạng thái khi server không in ra), đọc stdout, parse `server listening on <url>` + `server password <pw>`; `stop()` = SIGTERM |
| `OpenCodeDaemonService` | foreground service **sở hữu** daemon: cài đặt → chạy → wakelock + notification → restart backoff; trả `START_STICKY` |

**Xác thực của client (khác P0).** Instance `serve` do app sở hữu in ra mật khẩu
riêng, dùng qua **HTTP Basic** `opencode:<pw>`:

1. `POST /api/pair` với `Authorization: Basic …` → `{"code":"…"}`;
2. WebView mở `/auth/connect/<code>` → `302` + cookie `opencode_session_<port>`
   → UI web đã đăng nhập.

Daemon restart → mật khẩu mới → client tự pair lại (khoá theo `url#password`).

**Vòng đời / nền.** Daemon thoát bất ngờ → restart backoff `1s,2s,5s,10s,30s`.
Khi ready: giữ `PARTIAL_WAKE_LOCK` + notification ongoing "OpenCode server"
(có action **Exit** để dừng daemon). Xin miễn tối ưu pin
(`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`) như `TermuxService`.

**Chạy thử:**

```sh
am start -n com.termux/com.termux.app.TermuxActivity
# mở icon app = terminal + TUI `opencode` (launcher)
```

Log daemon ở `~/.opencode/daemon.log`. Từ **P2** instance này chạy thêm cờ
`--service` nên trở thành **dịch vụ nền dùng chung** (terminal TUI và client web
nói cùng một server); xem mục **P2** ngay dưới.

### P2 — hợp nhất daemon + cầu nối tool `termux-api` (2026-10-14)

Chạy lại tự động: `bash scripts/opencode-p2-proof.sh` (17/17 PASS trên
`opencode v2.0.28`).

#### A. Hợp nhất daemon: app sở hữu **chính** dịch vụ nền

`OpenCodeDaemon` giờ chạy `opencode serve --service --hostname 127.0.0.1 --port 0`:

- vẫn là **tiến trình con trực tiếp** của foreground service (không daemonize) →
  `stop()` = SIGTERM sạch;
- `--service` khiến OpenCode tự ghi sổ đăng ký dịch vụ
  (`~/.local/state/opencode/service.json` = `{id,version,url,pid,password}` +
  `~/.config/opencode/service.json` = `{password}`), nên `opencode` TUI,
  `opencode api`, `opencode pair` … đều trỏ vào **cùng** instance của app;
- điểm khác biệt: chế độ `--service` **không in** dòng `server password` ra
  stdout. `OpenCodeDaemon` đọc mật khẩu từ file trạng thái (khớp theo `url` để
  bỏ qua đăng ký cũ); dòng `server password` trên stdout vẫn được nhận nếu có;
- dừng sạch → OpenCode tự xoá đăng ký (`opencode service status` → `stopped`).

Kiểm chứng: `scripts/opencode-p2-proof.sh` phần **A** dựng một HOME tạm (không
đụng đăng ký thật): service chạy foreground, không in mật khẩu, file trạng thái
khớp url/mật khẩu, `opencode service status` trỏ đúng instance, Basic auth +
`/api/pair` chạy qua service, SIGTERM xoá đăng ký.

Ghi chú: nếu đã có sẵn một dịch vụ nền khác đang chạy, instance của app vẫn khởi
động bình thường và **ghi đè đăng ký** (instance cũ thành mồ côi nhưng không lỗi);
các lệnh `opencode` mới sẽ nối vào instance của app.

#### B. Cầu nối tool `termux-api` qua MCP

App nhúng một **MCP stdio server** viết bằng bash+awk (không cần node/python/jq —
chỉ dùng thứ Termux có sẵn): `app/src/main/assets/opencode/termux-api-mcp.sh`.

- `OpenCodeDaemonService.ensureMcpBridge()` copy script ra
  `~/.opencode/bin/termux-api-mcp.sh` (đặt quyền thực thi) rồi **đăng ký** vào
  config toàn cục bằng `opencode mcp add --global termux-api -- $PREFIX/bin/bash <script>`
  (chỉ chạy khi entry chưa có). Config ghi vào `~/.config/opencode/opencode.json`:
  `{"mcp":{"servers":{"termux-api":{"type":"local","command":["…/bash","…/termux-api-mcp.sh"]}}}}`.
  Đăng ký kèm **interpreter tường minh** vì Termux không có `/usr/bin/env`, nên
  spawn theo shebang sẽ lỗi `NotFound: ChildProcess.spawn`; `bash <script>` thì chắc chắn chạy.
- Script nói JSON-RPC 2.0 **một dòng/message** (transport stdio): `initialize`,
  `notifications/initialized`, `ping`, `tools/list`, `tools/call`; giải mã tham số
  kiểu chuỗi (nháy kép, `\n`, `\t`, `\uXXXX`).
- Tool phơi ra: `termux_notification`, `termux_toast`, `termux_clipboard_get`,
  `termux_clipboard_set`, `termux_battery_status`, `termux_vibrate`,
  `termux_tts_speak`, `termux_location` — đều gọi thẳng `termux-*`.
- **Cần** gói `termux-api` (`pkg install termux-api`) + app **Termux:API**. Khi
  thiếu, mỗi tool trả lỗi có hướng dẫn thay vì làm hỏng phiên.

Kiểm chứng: `scripts/opencode-p2-proof.sh` phần **B** (chạy script trực tiếp, stub
`termux-*` để kiểm tra dispatch + giải mã tham số, và khung một-dòng-JSON).

> **Bật nhanh:** `pkg install termux-api` rồi cài app Termux:API. Từ **P3** MCP
> được `OpenCodeSetup` đăng ký khi mở terminal (không cần mở client web); kiểm tra
> bằng `opencode mcp list`.

### P3 — terminal-first: mở app = TUI `opencode` (2026-10-14)

- **Launcher** là `TermuxActivity`; mở app → tự chạy TUI `opencode` trong một
  session.
- Installer chạy nền qua `OpenCodeSetup.ensureAsync()`; `OpenCodeSetup` đồng bộ
  đăng ký MCP sau khi cài thành công để TUI có tool ngay.
- `OpenCodeDaemonService` vẫn tồn tại để `opencode` trong terminal nối vào cùng
  một dịch vụ nền dùng chung khi cần.

Đánh đổi: dùng TUI **không** khởi động `OpenCodeDaemonService`, nên server dùng
chung do chính CLI quản lý (`opencode serve --service` chạy nền). Khi mở client web,
service của app mới khởi động và ghi đè đăng ký — TUI mới nối vào instance của app.

Kiểm chứng: biên dịch/cài qua CI; trên máy: mở app khi đã cài CLI → vào thẳng TUI,
lần đầu (chưa cài) → shell rồi tự cài.

### Nhập tiếng Việt trong TUI (ô nhập toolbar, 2026-10-14)

- Gõ trực tiếp trong TUI kém vì terminal dùng `InputType.TYPE_NULL` (IME không có
  vùng soạn thảo/gợi ý). Ô nhập native ở trang 2 của toolbar vẫn là chỗ gõ tốt
  (IME Android đầy đủ, Telex/VNI), nhưng trước đây nó đẩy text vào PTY bằng
  `session.write(text)` trần:
  - TUI `opencode` **bật bracketed paste** (DECSET 2004) nên text bị xử lý như gõ
    từng ký tự → mỗi ký tự re-render → **giật**.
  - Không lọc `ESC`/ký tự điều khiển C1, không chuẩn hoá newline → TUI có thể hiểu
    nhầm thành phím (tự submit/huỷ) → **lỗi**.
- Sửa (`TerminalToolbarViewPager.sendComposedText`): đẩy text qua
  `TerminalEmulator.paste()` — tự bọc `\033[200~ … \033[201~` khi app bật 2004,
  **lọc `ESC`/C1**, đổi newline → `\r`. TUI nhận **một `PasteMsg`** = một lần
  render (mượt), newline là literal (đa dòng). Sau đó ghi `\r` ngoài marker để
  submit.
- Ô nhập đổi sang **đa dòng** (`textMultiLine`, `maxLines=8`) + **nút Gửi** riêng
  (vì Enter của IME xuống dòng, không submit). Gửi xong ô nhập **tự thu về trang
  phím phụ** (xem mục dưới).
- Workaround không cần build: gõ tiếng Việt nơi khác → copy → bấm extra-key
  **PASTE** (nút ⎘, cũng đi qua `paste()`) → dán mượt, an toàn.
- Hướng xa hơn (chưa làm): gửi prompt thẳng qua API `POST /api/session/{id}/prompt`
  (schema `Session.Inbox.UserPayload { text, files }`), lấy session qua
  `GET /api/session/active` — bỏ hẳn injection vào terminal (route đang
  **experimental**).

### Chạm dòng nhập của TUI để mở ô nhập (2026-10-14)

- Trong TUI (`opencode`), con trỏ luôn nằm trong ô nhập lệnh của app. Chạm đúng
  **dòng con trỏ** ⇒ ô nhập ("chat") tự trượt ra, **chiếm chỗ** ở đáy như trước và
  **mở sẵn IME** (gõ được tiếng Việt); chạm chỗ khác vẫn mở bàn phím terminal như
  thường.
- Nhận diện "dòng nhập": `TermuxActivity.isTerminalCursorLineTap()` so **chỉ số
  dòng** của điểm chạm (`TerminalView.getColumnAndRow(e, false)`) với dòng mà con
  trỏ được vẽ (`emulator.getCursorRow() - TerminalView.getTopRow()`, theo
  `TerminalRenderer.render`). Áp dụng cho **mọi phiên** terminal (không riêng
  openTUI).
- Sau khi **Gửi**, ô nhập **tự thu về trang phím phụ** và ẩn bàn phím
  (`KeyboardUtils.hideSoftKeyboard` + `ViewPager.setCurrentItem(0)`); terminal lấy
  lại dòng đã chiếm.
- Vẫn mở/đóng tay được: **nhấn giữ** nút bàn phím (toggle toolbar) hoặc **vuốt**
  sang trang ô nhập.

### Chạm dòng nhập: nhận diện tức thời, hết "khi được khi không" (2026-10-15)

Trước đây chạm dòng nhập chỉ **khi được khi không**. Ba nguyên nhân, đã sửa:

1. **Callback muộn và có thể bị bỏ hẳn.** `TermuxTerminalViewClient.onSingleTapUp`
   được nối vào `GestureDetector.onSingleTapConfirmed` — chỉ chạy **sau**
   double-tap timeout (~300 ms) và **không chạy** khi tap hoá ra là nửa đầu của
   double tap (người dùng bấm hai lần vì lần đầu có vẻ "không ăn"). Đã thêm hook
   **tức thời** `TerminalViewClient.onSingleTapUpImmediate()`, nối vào
   `SimpleOnGestureListener.onSingleTapUp` (bắn ngay ở ACTION_UP); nhận diện dòng
   nhập chuyển hẳn sang hook này nên **luôn chạy**, kể cả khi bị đọc thành double
   tap. Callback muộn vẫn giữ URL-on-tap + bật bàn phím terminal như cũ.
2. **Đọc hình học muộn → lệch dòng.** Bản cũ so dòng ở callback muộn; lúc đó tap đã
   bị `TerminalView.onUp` gửi cho TUI dưới dạng **mouse click** (TUI `opencode` bật
   mouse reporting) nên TUI có thể đã đổi vị trí con trỏ / cuộn / vẽ lại, cộng thêm
   IME có thể đã resize view → `isTerminalCursorLineTap()` so nhầm dòng. Giờ so
   **ngay tại ACTION_UP** (trước `onUp`) nên hình học vẫn đúng như lúc ngón tay
   chạm. Khi hook tức thời đã xử lý tap: `onUp` **không** gửi click cho terminal
   nữa, và callback muộn của tap đó **bị bỏ qua** — tránh `requestFocus()` của
   terminal **giật lại focus** (kèm IME) từ ô nhập vừa hiện.
3. **Toolbar đang ẩn thì ô nhập chưa tồn tại.** `ViewPager` chỉ dựng page 1 trong
   layout pass, nên khi toolbar `GONE` thì `findViewById(...)` trả `null` → không
   focus được gì (tap "bị nuốt" im lặng). `focusTerminalToolbarTextInput()` giờ
   chờ layout (`addOnLayoutChangeListener`) để focus + mở IME.

Phần ánh xạ dòng vẫn là `TerminalCursorLine.isCursorLine(...)` (có unit test); công thức
giữ nguyên. Trước đây chỉ đổi **thời điểm** lấy mẫu (ngay tại `ACTION_UP`, trước `onUp`); từ
2026-10-16 đổi cả **nguồn dòng**: thay `getCursorRow()` (con trỏ sống, chỉ đúng ở cuối frame) bằng
`getDeclaredCursorRow()` — dòng caret mà TUI khai báo bằng `ESC[?25h` (xem
[`TOOLBAR_TUI.md`](TOOLBAR_TUI.md) mục 3b).

### Đổi nhãn (giữ package)

`TERMUX_APP_NAME` trong `app/src/main/res/values/strings.xml` và
`termux-shared/.../strings.xml` đổi `Termux` → `OpenCode`; package vẫn
`com.termux`. (Manifest placeholder `TERMUX_APP_NAME` trong `app/build.gradle`
không được manifest nào dùng, chỉ cập nhật cho nhất quán.)

### Gia cố installer + wrapper (2026-10-14)

Rà soát 5 điểm gãy khi `opencode upgrade` / cập nhật glibc ghi đè runtime:

1. **Wrapper tự chữa lành**: `$PREFIX/bin/opencode` kiểm tra
   `~/.opencode/.patched` (do Java ghi `<size> <mtime>`) với
   `stat -c '%s %Y'` của binary. Lệch ⇒ chạy binary qua loader tường minh
   (`ld.so --library-path "$GLIBCLIB" "$BIN"`), vốn chạy được cả khi interpreter
   đã bị ghi đè — kèm cảnh báo bảo mở app để vá lại; khớp ⇒ `exec "$BIN"`.
2. **Kiểm tra loader**: thiếu `$PREFIX/glibc/lib/ld-linux-aarch64.so.1` ⇒ thoát
   `127` kèm thông báo thay vì lỗi "not found" khó hiểu.
3. **Đổi binary 1 bước nguyên tử**: binary được vá vào `installed/opencode-v<version>`
   rồi `bin/opencode` (symlink) được swap bằng `rename(2)` — không có khoảng không
   binary, và ELF đã vá không bao giờ bị ghi đè tại chỗ (xem mục "Theo dõi build đã cài").
4. **`.patched` + `opencode service restart`**: sau khi thay binary, Java ghi lại
   `.patched` và (khi đây là **update**, tức bản cũ chạy được) gọi
   `opencode service restart` best-effort để dịch vụ nền không phục vụ bản cũ.
   Khi binary cũ chạy trực tiếp được nhưng marker lệch, marker cũng được ghi lại
   ở nhánh "đã mới nhất".
5. **Ghim phiên bản**: `~/.opencode/pin` (một `x.y.z`) giữ app ở đúng bản, kể cả
   **hạ cấp**; có pin thì bỏ qua cache "vừa kiểm tra" (luôn đối chiếu registry).
   `NpmSource.version()` thêm để tra đúng version; thiếu endpoint vẫn dùng được
   tên package mặc định `@opencode/cli`.

**Lưu ý môi trường**: bỏ `LD_PRELOAD` cho tiến trình glibc làm mất
`libtermux-exec` của các tiến trình con ⇒ shebang `#!/usr/bin/env …` hỏng
(`/usr/bin/env` không tồn tại). Đã kiểm chứng và ghi lại trong
[`ENVIRONMENT.md`](ENVIRONMENT.md).

### Cầu nối Toolbar ↔ TUI không dùng API (2026-10-16)

- Đã tách riêng thiết kế + lộ trình vào **`TOOLBAR_TUI.md`**: nối thanh công cụ với TUI
  `opencode` **trực tiếp qua PTY** (ghi byte, đọc màn hình), **không** dùng HTTP API.
- **Phần 1 xong**: `TuiBridge` (đọc snapshot từ `TerminalEmulator` + xác định session
  `opencode`) và logic thuần `TuiSnapshot`/`TuiState`/`TuiClassifier` + test JVM
  (`app/src/test/java/com/termux/app/opencode/TuiClassifierTest.java`).
- **Nhận diện ô nhập làm lại (2026-10-16)**: bỏ phụ thuộc con trỏ "sống" — nguồn gốc của
  kiểu "khi được khi không". Chạy TUI trong PTY thật rồi soi byte cho thấy OpenTUI vẽ mỗi
  frame theo `?25l` → quét CUP khắp màn hình → `CUP <caret>` → `?25h`: byte ngay trước
  `?25h` là ô caret (304/317 lần trong một capture), còn con trỏ sống chỉ đúng vào lúc
  cuối frame (và bị reflow đổi chỗ khi resize). `TerminalEmulator` giờ ghi lại vị trí đó
  (`getDeclaredCursorRow()`, vô hiệu khi kích thước đổi / reset / đổi buffer);
  `TermuxActivity.isTerminalCursorLineTap()` + `TuiClassifier.isComposerRowAt()` dùng nó
  để mở ô nhập; nhận diện theo nội dung (`isComposerRow`/`isComposerBandRow`) giữ làm dự
  phòng. Chi tiết + bằng chứng ở [`TOOLBAR_TUI.md`](TOOLBAR_TUI.md) mục 3b, kiểm chứng lại
  bằng `scripts/opencode-tui-cursor-proof.sh` (chạy TUI trong PTY thật, 7/7 PASS).
- **Phần 4 xong (bản sửa lỗi chuyển ô nhập)**: chạm vào ô input của openTUI giờ **luôn** chuyển
  sang ô nhập của Termux, kể cả sau khi ẩn bàn phím → background → mở lại app. Thêm
  `TermuxActivity.isTerminalComposeTap()` (đường nhanh theo dòng caret do TUI khai báo + dự phòng
  nhận diện vùng composer từ màn hình qua `TuiClassifier.isComposerRow()`/`isComposerRowAt()`),
  dùng trong `TermuxTerminalViewClient.onSingleTapUpImmediate()`. Không dùng cú chạm khi `DIALOG`
  đang mở.
- **Phần 2–3, 5–8 còn lại** (submit an toàn, nút xuống dòng, soi text composer, autocomplete
  `/command`, toolbar "remote", `clickCell`) — xem checklist trong `TOOLBAR_TUI.md`.

### Chưa làm / rủi ro còn lại

- **Chưa build được trên máy**: Termux này không có JDK/Android SDK
  (`java: not found`, `ANDROID_HOME` trống) → APK phải build ở CI/máy khác.
- WebView cũ (máy API thấp) có thể thiếu ES module → cần Custom Tab/native client.
- **P2 xong**: đã hợp nhất daemon (app sở hữu dịch vụ dùng chung) và thêm cầu nối
  tool `termux-api` qua MCP.
- **P3 xong**: mở app = terminal + TUI `opencode`; client web lùi về shortcut; MCP
  được đăng ký từ đường terminal.
- **P4 (ứng viên)**: cầu nối tool **trong tiến trình app** (gọi thẳng API Android —
  giàu hơn `termux-*` CLI, không cần app Termux:API); khởi động daemon app khi mở
  terminal để dùng **đúng một** server/session cho cả TUI lẫn web; tự cài
  `termux-api`; dọn instance dịch vụ mồ côi khi ghi đè đăng ký; Custom Tab/native
  client cho WebView cũ.