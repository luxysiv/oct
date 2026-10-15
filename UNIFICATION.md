# Hợp nhất TUI OpenCode ↔ Termux

> Nghiên cứu hai "TUI" (OpenCode TUI V2 và lớp giao diện Termux) và đề xuất
> phương án hợp nhất cho app: **Termux làm vỏ/môi trường, OpenCode làm lõi**.
> Tài liệu này là *kế hoạch*, chưa phải mô tả code đã có. Trạng thái hiện tại
> của fork (P0–P3) xem [`OPENCODE.md`](OPENCODE.md).

## 0. TL;DR (khuyến nghị)

Hợp nhất theo **3 lớp**, làm từ rẻ tới đắt, mỗi lớp đứng độc lập được:

1. **Lớp keo giao thức terminal** (giờ, vài giờ): cấu hình `cli.json` + extra-keys
   + toolbar compose + OSC/bell. Sửa ngay "Ctrl+C thoát app" → "Ctrl+C về shell".
2. **Lớp plugin trong TUI** (vài ngày): viết một **OpenTUI CLI plugin**
   (`opencode-android`) để nhét tính năng Android *vào chính TUI*: command,
   slot, dialog, toast, thông báo hệ thống; gọi `termux-api` qua MCP đã có.
3. **Lớp client Android gốc** (dài hạn, tùy chọn): một Activity native nói
   **cùng server** qua HTTP/`@opencode/client` — session model dùng chung với
   TUI, nhưng UI là widget Android (IME xịn, ảnh, chạm, share).

Không nên: viết lại TUI của OpenCode thành UI Android ngay từ đầu (phá vỡ lợi
thế "OpenCode lo UI, ta lo môi trường" và bám API đang experimental).

---

## 1. Bối cảnh: vì sao có "hai TUI"

Fork hiện tại (xem `OPENCODE.md`) đã chứng minh luận điểm **P0**: OpenCode V2 là
client–server, nên app chỉ cần làm **môi trường** (`$PREFIX`, exec/PTY/FS) + **tool**,
còn UI là cửa sổ nối vào daemon. P3 chọn cửa sổ đó là **TerminalView của Termux**
chạy TUI `opencode`.

Hệ quả là có **hai hệ UI chồng nhau**, mỗi bên có mô hình riêng:

| | OpenCode TUI | Termux |
|---|---|---|
| Bản chất | App toàn màn hình (OpenTUI + Solid, chạy trong bun) | App Android native nhân **VT emulator** |
| Sở hữu màn hình | Gần hết khung terminal (ô nhập của nó ở đáy) | `TerminalView` (Canvas) + toolbar/IME/extra-keys |
| Giao tiếp với nhau | Chỉ qua **byte ANSI/OSC/DCS trên PTY** | Không biết gì về ngữ nghĩa của TUI |
| Nguồn sự thật dữ liệu | Server OpenCode (session/agent/tool/PTY) | Tiến trình con + `TermuxService` |

Mọi "lỗi tích hợp" đều nằm ở **rìa** giữa hai hệ: Ctrl+C, gõ tiếng Việt, ảnh,
thông báo, chọn văn bản, vòng đời session. Hợp nhất = thu hẹp rìa đó, theo thứ tự
chi phí tăng dần.

---

## 2. Nghiên cứu OpenCode TUI V2

### 2.1 Kiến trúc

- **OpenTUI** (do chính nhóm làm OpenCode phát triển): lõi render **Zig**, binding
  TypeScript, hỗ trợ React/Solid, layout kiểu flexbox. OpenCode dùng
  `@opentui/core` + `@opentui/solid` + `solid-js`.
- TUI là **client** của server OpenCode (HTTP API + SSE). Server sở hữu session,
  config, plugin, quyền, tool, và **PTY bền** (`capabilities.persistentPty`).
- Cấu hình TUI tách riêng: `~/.config/opencode/cli.json` (hoặc `$XDG_CONFIG_HOME`),
  có thể ghi đè inline bằng `OPENCODE_CLI_CONFIG_CONTENT`; sửa file khi TUI đang
  chạy sẽ **reload**.
- `Ctrl+P` → **Open settings** ngay trong TUI.
- Chế độ rút gọn: **`opencode mini`** (giao diện tối giản, có tham số session/model/
  agent/replay).

### 2.2 Điểm mở rộng quan trọng nhất: **CLI plugin (TUI plugin)**

Đây là "cửa chính" để nhét thứ Android vào TUI. Plugin là TS/JSX, **chạy local
trong tiến trình TUI**, khai báo qua `cli.json` (`plugins`) hoặc được **tự phát
hiện** ở `<config>/plugins/<name>/tui.ts` và `<project>/.opencode/plugins/<name>/tui.ts`.
Entry TUI là `./tui` trong `package.json`.

`setup(context)` nhận: config, app metadata, location, `context.client` (client
đầy đủ tới server), cache dữ liệu, theme tokens, renderer, và **UI API**:

| API | Dùng để làm gì cho Android |
|---|---|
| `context.keymap.layer(...)` | Thêm command vào palette / slash / keybind (vd `/android-share`) |
| `context.ui.toast.show(...)` | Toast trong TUI |
| `context.ui.dialog.{alert,confirm,prompt,select}` | Hộp thoại native-trong-TUI |
| `context.ui.router.register(...)` / `navigate(...)` | Trang (route) riêng |
| `context.ui.slot({...})` | Chèn JSX vào `app`, `home.footer(.status)`, `prompt.footer(.status/.file)`, `session.composer.top`, `sidebar.content`, `sidebar.footer`, `session.panel` |
| `context.ui.tabs.*` | Quản lý tab session |
| `context.ui.panel.open(...)` | Panel toàn màn hình do plugin sở hữu |
| `context.markdown.registerCodeBlockRenderer(lang, fn)` | Renderer cho khối code |
| `context.storage.store/memory(...)` | Lưu trạng thái plugin |
| `context.data.*` | Đọc session/message/permission/model/provider/MCP/skill… (sync + invalidate) |
| `context.client.*` | Gọi thẳng API server (kể cả remote) |
| `context.attention.notify({...})` | **Thông báo hệ thống + âm thanh** theo trạng thái focus |

Ngoài ra `cli.json` có sẵn: `attention.notifications/sound`, `tabs.*`, `theme`,
`cursor`, `mouse`, `scroll`, `prompt.paste`, `terminal.title/copy`, `session.*`,
`mini.*`, **`keybinds.*`** (mọi command ID, kể cả `app.exit`).

### 2.3 Cái **không** mở rộng được

- Không có API để OpenCode "hỏi" app Android chọn file/ảnh/chụp hình (không có
  intent bridge). Muốn có phải làm **ở phía Termux** (MCP/CLI bridge) rồi plugin
  gọi vào.
- Không render widget Android (Slider, Switch, danh sách native…); mọi thứ là
  hộp/text trong terminal.
- Không có kênh ngược từ TUI → Termux ngoài ANSI/OSC (trừ khi dùng một bridge
  riêng, vd socket + `termux-am`).
- Keybind gõ phím cứng: TUI nhận **byte**, nên mọi phím đặc biệt phải do Termux
  phát đúng mã.

---

## 3. Nghiên cứu "TUI" phía Termux (thực chất là app nhân VT)

### 3.1 Các module

- `terminal-emulator/` — **VT parser** thuần: `TerminalEmulator` (ESC/CSI/OSC/DCS/APC),
  `TerminalBuffer`/`TerminalRow` (grid), `TerminalColors`/`TextStyle`, `TerminalSession`,
  `KeyHandler` (map phím → escape), `TerminalSixel`, `TerminalBitmap`, JNI `termux.c`
  (tạo PTY + `createSubprocess`).
- `terminal-view/` — `TerminalView` (Android `View`), `TerminalRenderer` (vẽ Canvas),
  `GestureAndScaleRecognizer` (tap/scroll/zoom), text selection.
- `app/` — `TermuxActivity` (khung: drawer session, terminal view, toolbar),
  `TermuxService` (foreground service sở hữu session), `terminal/io/*`
  (`TermuxTerminalExtraKeys`, `TerminalToolbarViewPager`), `opencode/*` (installer,
  daemon, MCP, setup).

### 3.2 Giao thức mà Termux đã hỗ trợ (điểm nối với TUI)

| Nhóm | Hỗ trợ trong `TerminalEmulator` |
|---|---|
| Tiêu đề | OSC 0/1/2 → `setTitle` |
| Màu | OSC 4/10/11/12/104/110–112 (đọc & đặt màu) |
| **Clipboard** | **OSC 52** (Base64 → `onCopyTextToClipboard`) |
| **Ảnh** | **OSC 1337** (iTerm `File=`/`MultipartFile=`) + **Sixel** (DCS) |
| Chuột | DECSET 1000/1002/1003/1006 (gửi SGR mouse), 1005 bỏ qua |
| Bracketed paste | DECSET 2004 (qua `TerminalEmulator.paste()`) |
| Chuông | BEL → `onBell` (dùng cho attention) |
| Màn hình phụ, cuộn, wrap, … | đầy đủ theo xterm |

### 3.3 Đầu vào (input) — điểm đau truyền thống

- `TerminalView` dùng `InputType.TYPE_NULL` → IME **không có vùng soạn thảo/gợi ý**,
  tiếng Việt (Telex/VNI) gõ kém.
- **Extra keys** (`~/.termux/termux.properties` `extra-keys`) gửi phím đặc biệt
  (ESC/TAB/CTRL/ALT/←/→… và cả macro như `/new`).
- **Ô nhập ở trang 2 toolbar** (`TerminalToolbarViewPager`) là chỗ gõ tiếng Việt
  tốt: dùng IME Android đầy đủ, rồi **bơm bracketed paste** vào PTY
  (`TerminalEmulator.paste()`) + `\r` để submit — đã sửa ở P3.
- **Chạm dòng con trỏ** để lộ ô nhập: `TerminalCursorLine` so dòng chạm với dòng
  con trỏ *tại ACTION_UP* (đã sửa 2026-10-15).

### 3.4 Vòng đời session (chỗ sinh ra lỗi "Ctrl+C thoát app")

App mở từ launcher → tạo session chạy **trực tiếp** `$PREFIX/bin/opencode` (wrapper
`exec` binary). Khi TUI thoát (mã `0`/`130`), `TermuxTerminalSessionActivityClient.onSessionFinished`
**tự xoá session**; hết session ⇒ `TermuxActivity.finish()` ⇒ **thoát app**.

→ Muốn "Ctrl+C về shell" phải can thiệp **cả hai đầu**: (a) keybind `app.exit` của
OpenCode, (b) cách phiên terminal kết thúc. Chi tiết ở §6.1.

### 3.5 Giới hạn phía Termux

- Không hiểu ngữ nghĩa TUI (không biết "ô nhập của OpenCode" ở đâu) — mọi thứ
  suy ra từ hình học (dòng con trỏ) và escape sequence.
- Không có widget native; không có intent bridge mặc định cho chọn ảnh/file.
- Không xử lý OSC 9/777 (notification protocol của một số terminal) — cần kiểm
  chứng cách OpenCode phát "attention" trên Termux (nhiều khả năng chỉ kêu BEL).

---

## 4. Ma trận "ai làm được gì" (khoảng trống cần bắc cầu)

| Năng lực | OpenCode TUI | Termux | Trạng thái |
|---|---|---|---|
| Gõ tiếng Việt/IME đầy đủ | kém (nhận byte) | ô nhập toolbar tốt | **đã ghép** (paste) |
| Gửi prompt nhiều dòng | nhận PasteMsg | `sendComposedText` | **đã ghép** |
| Ctrl+C | `app.exit` (mặc định gồm ctrl+c) | xoá session → thoát app | **cần sửa** |
| Thông báo khi xong/lỗi | `attention.notify` | BEL/BEL→rung? OSC 9? | thiếu |
| Ảnh (preview/đính kèm) | sixel/iTerm? | OSC 1337 + sixel | chưa xác minh |
| Chọn file/ảnh để đính kèm | không có | không có intent bridge | thiếu |
| Chia sẻ kết quả ra app khác | không có | `termux-share`/`termux-clipboard` | bắc qua MCP |
| Thông báo Android | `attention` | service notification | cần cầu nối |
| Chạm/scroll | mouse reporting | gesture→mouse | đã có |
| Session bền giữa các client | server sở hữu | PTY/exec | đã có |
| UI phong phú (widget, ảnh, danh sách) | hộp/text terminal | **native được** (chưa dùng) | thiếu |

---

## 5. Các phương án hợp nhất

### Phương án A — Keo giao thức terminal (chi phí: thấp)

Giữ nguyên TUI là hộp đen; chỉ chuẩn hoá **cấu hình + phím + paste + OSC**.

- `cli.json`: ghim `keybinds.app.exit`, `mouse`, `scroll`, `cursor`, `theme.mode`.
- `~/.termux/termux.properties`: thêm trang extra-keys riêng cho OpenCode.
- Dùng ô nhập toolbar (đã có) + OSC 52 (copy) + BEL (cảnh báo).
- Sửa vòng đời session để Ctrl+C/mã 0/130 **rơi về shell**, không thoát app.

✅ Nhanh, ít rủi ro, không phụ thuộc API. ❌ Không thêm được UI/tính năng mới.

### Phương án B — OpenTUI CLI plugin `opencode-android` (chi phí: vừa)

Nhét tính năng Android **vào trong TUI** bằng plugin TS/JSX:

- `/android-notify`, `/android-clipboard`, `/android-share`, `/android-battery`…
  (gọi `termux-api` qua MCP đã đăng ký ở P2, hoặc gọi thẳng `termux-*`).
- **Slot**: `sidebar.footer`/`home.footer.status` hiện trạng thái (pin, kết nối,
  MCP bridge), `session.composer.top` hiện nút/chú thích.
- **Attention**: override `attention.notify` để bắn thông báo Android thật khi
  TUI mất focus (cầu nối qua MCP/`termux-notification`).
- **Dialog/route**: chọn model/provider, xem diff, “phiên gần đây” bằng API UI của
  OpenCode.
- Khai báo ở `<config>/plugins/opencode-android/tui.ts` (tự phát hiện), cài kèm app.

✅ Ở đúng tầng OpenCode, portable, không hack terminal. ❌ Không có widget native;
vẫn chỉ chạy khi có TUI; phải bám API plugin V2.

### Phương án C — Client Android gốc trên API server (chi phí: lớn)

Termux giữ vai trò môi trường; UI là **Activity native** (Compose) nói cùng server:

- Dùng `@opencode/client` (JS) hoặc OkHttp + OpenAPI (`/openapi.json`) từ Java/Kotlin.
- Session list, transcript, composer IME xịn, đính kèm ảnh/file (intent Android),
  permission UI native, diff viewer, model picker, thông báo Android.
- TUI vẫn tồn tại như "chế độ terminal"; **cùng server** ⇒ mở session ở đâu cũng thấy.
- Lưu ý: WebView client từng có đã bị gỡ (commit `579bbde3`); hướng native thay
  thế WebView, hoặc WebView + Custom Tab nếu cần nhanh.

✅ Trải nghiệm mobile tốt nhất; đúng luận điểm P0 (UI chỉ là cửa sổ). ❌ Tốn công;
phải theo dõi API (có phần experimental); nguy cơ lệch tính năng với TUI.

### Phương án D — **3 lớp hợp nhất** (khuyến nghị)

A → B → C, làm tới đâu chắc tới đó; lớp sau không phá lớp trước vì **server là
nguồn sự thật chung**. TUI là "client hạng nhất", client native là "client hạng
nhất", cùng nhìn một session store.

---

## 6. Thiết kế đề xuất (phương án D)

### 6.1 Lớp 1 — keo giao thức (làm ngay)

**(a) Ctrl+C trở về shell, không thoát app.** Sửa hai đầu:

1. **OpenCode**: bỏ `ctrl+c` khỏi `app.exit` để Ctrl+C không "thoát TUI" bất ngờ;
   đổi lối thoát có chủ đích sang `<leader>q`. Ghi vào `~/.config/opencode/cli.json`:
   ```json
   {
     "$schema": "https://opencode.ai/v2/cli.json",
     "keybinds": {
       "app.exit": ["<leader>q", "ctrl+d"]
     }
   }
   ```
   (Dùng `OPENCODE_CLI_CONFIG_CONTENT` nếu muốn app tự set khi chạy TUI.)
2. **Termux**: khi session TUI kết thúc (mã `0`/`130`) mà đây là session auto-launch
   của OpenCode, **không** xoá-rồi-thoát; thay vào đó rơi về shell. **Đã chọn cách
   sạch** (2026-10-15): auto-launch TUI **trong một login shell**, tức phiên chạy
   `bash -l -c '<wrapper>; exec bash -l'` thay vì chạy thẳng wrapper. TUI thoát ⇒
   về prompt trong **cùng phiên**, giữ scrollback. (Cách dự phòng — nhận diện
   session wrapper trong `onSessionFinished` rồi tạo session shell mới — không cần
   nữa.) Cả hai đều chặn `finishActivityIfNotFinishing()` khi hết session.

**(b) Extra keys cho OpenCode** (`~/.termux/termux.properties`):
```properties
extra-keys = [['ESC','TAB','CTRL','ALT','UP','DOWN','LEFT','RIGHT'],['/new','/models','/sessions','/undo','PASTE']]
```

**(c) Ghi chú tương thích**: `session.sidebar`/`tabs.mode`/`cursor.style` ghim theo
kích thước màn hình; `terminal.copy: select` để chọn là copy ngay.

### 6.2 Lớp 2 — plugin `opencode-android` (OpenTUI CLI plugin)

Cấu trúc gói (đặt trong repo, cài vào `<config>/plugins/opencode-android/`):

```
opencode-android/
  package.json         # exports: { ".": "./src/index.ts", "./tui": "./src/tui.tsx" }
  src/index.ts         # plugin server-side (rỗng cũng được)
  src/tui.tsx          # giao diện trong TUI
```

`src/tui.tsx` (phác thảo):

```tsx
import { Plugin, usePlugin } from "@opencode/plugin/tui"

export default Plugin.define({
  id: "app.android",
  setup(context) {
    // 1) Command gọi Android qua MCP termux-api (đã đăng ký ở P2)
    context.keymap.layer(() => ({
      mode: "global",
      commands: [
        {
          id: "android.notify",
          title: "Android: thông báo",
          group: "Android",
          bind: "ctrl+g",
          palette: true,
          slash: { name: "android-notify" },
          run: async () => {
            // context.client.plugin... hoặc gọi tool termux_notification qua biến
            context.ui.toast.show({ message: "Đã gửi thông báo Android", variant: "success" })
          },
        },
        {
          id: "android.share",
          title: "Android: chia sẻ kết quả",
          group: "Android",
          slash: { name: "android-share" },
          run: async () => { /* context.data.session.* -> termux-share */ },
        },
      ],
      bindings: ["android.notify", "android.share"],
    }))

    // 2) Slot: hiện trạng thái Android cạnh footer
    context.ui.slot({
      append: "home.footer.status",
      render: () => <text>ANDROID</text>,
    })

    // 3) Attention -> thông báo hệ thống thật khi mất focus
    //    (bọc context.attention.notify; hoặc cấu hình attention.* trong cli.json)
  },
})
```

Bắc cầu Android: **đã có** `termux-api` MCP (P2) và `OpenCodeMcp.ensure()`. Plugin
gọi tool MCP `termux_notification`, `termux_clipboard_set`, `termux_vibrate`… —
không cần thêm native code.

### 6.3 Lớp 3 — client Android gốc (giai đoạn sau)

- Thêm `OpenCodeClientActivity` (Compose) + tầng `opencode/client/` trong app:
  - `OpenCodeApi` (OkHttp + model từ OpenAPI; hoặc nhúng `@opencode/client` chạy
    trong node/bun — nặng, khuyến nghị OkHttp trước).
  - Session list/transcript/composer; `POST /api/session/{id}/prompt` để gửi
    (đường đã ghi chú "hướng xa hơn" ở P3, bỏ hẳn injection terminal).
  - Permission/diff/ảnh dùng widget Android; đính kèm file/ảnh bằng intent.
- Dùng `Service.discover/ensure` của `@opencode/client` (hoặc đọc
  `~/.local/state/opencode/service.json` qua `OpenCodeDaemonService`) để nối **cùng**
  server mà TUI đang dùng.
- `TermuxActivity` giữ nguyên; hai Activity mở cùng server, chung session.

### 6.4 Luồng dữ liệu (sau hợp nhất)

```
        ┌─────────────────────────── OpenCode server (nguồn sự thật) ──────────┐
        │  session · message · permission · agent · tool · PTY bền             │
        └───────▲───────────────────────────▲───────────────────────▲─────────┘
                │ HTTP/SSE                  │ HTTP/SSE              │ stdio/MCP
   ┌────────────┴───────────┐   ┌───────────┴────────────┐   ┌──────┴─────────┐
   │ OpenCode TUI (terminal)│   │ Client Android gốc     │   │ termux-api MCP │
   │ + plugin opencode-     │   │ (Compose, lớp 3)       │   │ (bash+awk)     │
   │   android (lớp 2)      │   │                        │   │                │
   └────────────┬───────────┘   └───────────┬────────────┘   └──────┬─────────┘
                │ byte ANSI/OSC/DCS          │ intent/widget                │ termux-*
        ┌───────┴────────────────────────────┴───────────────────────────────┐
        │ TerminalView (VT emulator) + TermuxService (PTY/exec) + extra-keys  │  ← Termux
        └────────────────────────────────────────────────────────────────────┘
```

---

## 7. Lộ trình & tiêu chí nghiệm thu

| Bước | Nội dung | Nghiệm thu |
|---|---|---|
| L1.1 | Keybind `app.exit` + cli.json ghim | Ctrl+C trong TUI **không** thoát app *(đã đạt nhờ L1.2 — Ctrl+C thoát TUI rồi về shell)* |
| L1.2 | Session auto-launch rơi về shell | Thoát TUI (leader+q hoặc Ctrl+C) ⇒ prompt shell, app còn mở — **✅ đã làm 2026-10-15** |
| L1.3 | Extra-keys + toolbar compose | Gõ tiếng Việt 1 lần ra (paste), không giật |
| L2.1 | Plugin `opencode-android` load | `/android-notify` xuất hiện trong palette/slash |
| L2.2 | Slot trạng thái + attention | Có slot; khi mất focus ⇒ notification Android |
| L3.1 | Activity native đọc `/api/info` + service.json | Mở được session hiện tại của TUI |
| L3.2 | Gửi prompt qua API | Prompt hiện ở **cả** TUI lẫn client native |

## 8. Rủi ro / câu hỏi mở

- **API V2 đang đổi**: lớp 3 phải bám `/openapi.json` + `@opencode/client`, tránh
  tự đoán shape; có phần `experimental`.
- **Attention trên Termux**: cần kiểm chứng OpenCode phát gì (BEL / OSC 9 / 777) và
  Termux có bắt được không; nếu không, phải cầu qua MCP.
- **Ảnh trong TUI**: xác minh OpenCode dùng sixel hay OSC 1337; Termux đã hỗ trợ cả
  hai nhưng đường render có thể khác.
- **Plugin API + version pin**: plugin `./tui` phụ thuộc `@opentui/*`/`solid-js`
  phiên bản khớp TUI; cần kẹp phiên bản hoặc feature-detect.
- **Chọn file/ảnh**: không có intent bridge → phải làm bridge Termux (MCP tool
  `termux-pick-file`/`termux-camera-photo`) trước khi plugin TUI dùng được.
- **Build trên máy**: repo này không có JDK/SDK (xem `OPENCODE.md`), APK build ở CI.

## 9. Việc làm ngay

1. ~~**Sửa Ctrl+C → shell** (L1.1 + L1.2)~~ — **đã làm 2026-10-15**: launcher bọc
   TUI trong login shell (`bash -l -c '<wrapper>; exec bash -l'`), TUI thoát ⇒ về
   prompt shell trong cùng session. Xem `OPENCODE.md`.
2. Sinh `cli.json` mặc định (theme/cursor/mouse/keybinds) từ app khi chạy TUI.
3. Dựng khung plugin `opencode-android` + một command `/android-notify` chạy được.
