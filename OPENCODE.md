# OpenCode trên oct

> **Trạng thái `main` (từ 2026-10-14): OpenCode-first (P1).** Mở app = mở
> **client OpenCode** (WebView launcher). Một foreground service
> (`OpenCodeDaemonService`) sở hữu daemon `opencode serve` trên loopback, giữ nó
> chạy nền/foreground và tự restart. Terminal Termux vẫn nguyên vẹn, vào qua
> shortcut "OpenCode Terminal"/`am start`. Chi tiết ở mục **P1** cuối file.
>
> Quá trình cài đặt (giữ từ P0): `OpenCodeSetup` (khi mở terminal) và
> `OpenCodeDaemonService` (khi mở client) chạy `OpenCodeInstaller` trong nền:
> tải sẵn thư viện còn thiếu (`glibc-repo`/`glibc-runner` + `patchelf` qua
> `apt-get`) và binary OpenCode từ npm registry (đã `patchelf` để chạy trực
> tiếp), ghi trạng thái vào `~/.opencode/state`, log vào `~/.opencode/setup.log`.
> Gõ `opencode` trong terminal chạy **TUI gốc** — wrapper
> `$PREFIX/bin/opencode` `exec` trực tiếp binary, không mở app, không `am start`,
> không `LD_PRELOAD` (glibc loader không tải được libc bionic).

## Mở app = client OpenCode (daemon tự cài, tự chạy)

- **Launcher là `OpenCodeClientActivity`** (client WebView, P1); terminal Termux
  mở qua shortcut "OpenCode Terminal" hoặc `am start` vào `TermuxActivity`.
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
   **rename đè nguyên tử** (`.new` → `opencode`, giữ `.old` để rollback) và ghi
   `~/.opencode/version`.
8. Luôn ghi/ghi-đè wrapper `$PREFIX/bin/opencode` (marker `opencode-native-wrapper-java-v1`).
   Wrapper `exec` trực tiếp binary đã patch: `unset LD_PRELOAD LD_LIBRARY_PATH;
   export OPENCODE_DISABLE_AUTOUPDATE=1; exec "$BIN" "$@"`.

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
| `~/.opencode/bin/opencode` | binary đã patch |
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
| `OpenCodeDaemon` | bọc tiến trình `opencode serve` (Android-free): chạy `serve --hostname 127.0.0.1 --port 0`, đọc stdout, parse `server listening on <url>` + `server password <pw>`; `stop()` = SIGTERM |
| `OpenCodeDaemonService` | foreground service **sở hữu** daemon: cài đặt → chạy → wakelock + notification → restart backoff; trả `START_STICKY` |
| `OpenCodeClientActivity` | launcher client: start/bind service, chờ `Info`, pair rồi `loadUrl` web UI |

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
am start -n com.termux/com.termux.app.opencode.OpenCodeClientActivity
# hoặc chỉ mở icon app — đây là launcher
```

Log daemon ở `~/.opencode/daemon.log`. Chưa đổi: đây là instance `serve` riêng
của app; terminal TUI vẫn nối vào dịch vụ dùng chung (`opencode service`). Hợp
nhất hai cái (và tool bridge `termux-api` qua MCP) để lại cho P2.

### Đổi nhãn (giữ package)

`TERMUX_APP_NAME` trong `app/src/main/res/values/strings.xml` và
`termux-shared/.../strings.xml` đổi `Termux` → `OpenCode`; package vẫn
`com.termux`. (Manifest placeholder `TERMUX_APP_NAME` trong `app/build.gradle`
không được manifest nào dùng, chỉ cập nhật cho nhất quán.)

### Chưa làm / rủi ro còn lại

- **Chưa build được trên máy**: Termux này không có JDK/Android SDK
  (`java: not found`, `ANDROID_HOME` trống) → APK phải build ở CI/máy khác.
- WebView cũ (máy API thấp) có thể thiếu ES module → cần Custom Tab/native client.
- **P2**: tool bridge `termux-api` qua MCP, hợp nhất daemon của app với dịch vụ
  dùng chung, và cân nhắc Custom Tab/native client cho máy WebView cũ.