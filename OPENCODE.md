# OpenCode trên oct

> **Trạng thái `main` (từ 2026-10-13): terminal-first.** Bản chat native đã bị
> loại hoàn toàn khỏi nhánh `main` (không `OpenCodeChatActivity`, không wrapper
> `am start`, không backend `serve` nhúng). Mở app = mở terminal Termux thuần;
> `OpenCodeSetup` tự chạy `OpenCodeInstaller` trong nền ngay khi app mở lần đầu:
> tải sẵn thư viện còn thiếu (`glibc-repo`/`glibc-runner` + `patchelf` qua
> `apt-get`) và binary OpenCode từ npm registry (đã `patchelf` để chạy trực
> tiếp), ghi trạng thái vào `~/.opencode/state`, log vào `~/.opencode/setup.log`.
> Sau đó gõ `opencode` trong terminal chạy **TUI gốc** — wrapper
> `$PREFIX/bin/opencode` `exec` trực tiếp binary, không mở app, không `am start`,
> không `LD_PRELOAD` (glibc loader không tải được libc bionic).

## Mở app = terminal thuần, tự cài OpenCode

- **Launcher duy nhất là `TermuxActivity`**: mở app là mở terminal Termux như gốc.
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