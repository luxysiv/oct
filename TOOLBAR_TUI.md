# Toolbar ↔ opencode TUI — cầu nối trực tiếp qua PTY

Tài liệu này ghi lại **thiết kế + lộ trình** để nối thanh công cụ (toolbar) của `oct` với
TUI `opencode` đang chạy trong terminal, **không dùng HTTP API**. Mục đích: nhớ để làm tiếp.

> Nguyên tắc: **PTY chính là kênh giao tiếp**. App ghi byte vào pseudo-terminal
> (`TerminalSession.write(...)`) và đọc trạng thái màn hình từ `TerminalEmulator`. Không có
> giao thức riêng nào cần phát minh — chỉ cần ghi đúng byte mà TUI hiểu, và đọc đúng dấu hiệu
> mà TUI vẽ ra.

Trạng thái hiện tại: **Phần 1 đã xong** (đọc snapshot + `classify()`). Các phần còn lại ở mục
[Lộ trình](#lộ-trình).

---

## 1. Vì sao không dùng API

- API (`http://127.0.0.1:<port>` trong `~/.local/state/opencode/service.json`) có tồn tại và chạy
  được trên máy, nhưng người dùng đã chọn **không** dùng.
- PTY không cần biết port/token, không phụ thuộc daemon còn sống hay không, và hoạt động cả khi
  TUI đang ở giữa một phiên (dialog, đang generate…). Đổi lại, app phải "đoán" trạng thái bằng
  cách đọc màn hình.

---

## 2. Kiến trúc

Tách **quyết định** (thuần Java, test được trên JVM) khỏi **vào/ra terminal** (Android):

| Lớp | Vai trò | Test |
|-----|---------|------|
| `TuiSnapshot` | Dữ liệu bất biến của màn hình đang thấy (từng dòng + con trỏ + cờ + title) | ✅ thuần |
| `TuiState` | Enum trạng thái UI | ✅ thuần |
| `TuiClassifier` | Logic thuần: `snapshot → TuiState` | ✅ thuần |
| `TuiBridge` | Keo dính Android: đọc `TerminalSession` → `TuiSnapshot`; xác định session opencode; `classify(session)` | mỏng, không test |

Đường dẫn:

- `app/src/main/java/com/termux/app/opencode/TuiSnapshot.java`
- `app/src/main/java/com/termux/app/opencode/TuiState.java`
- `app/src/main/java/com/termux/app/opencode/TuiClassifier.java`
- `app/src/main/java/com/termux/app/opencode/TuiBridge.java`
- `app/src/test/java/com/termux/app/opencode/TuiClassifierTest.java`

### TuiState

```
NOT_OPENCODE  // session không phải TUI opencode
SHELL         // chưa bật alternate screen → shell/CLI thường
COMPOSER      // ô soạn thảo opencode đang hiện, rảnh → gửi an toàn
BUSY          // đang generate (Enter = steer lần chạy hiện tại)
DIALOG        // có hộp thoại/palette → TUYỆT ĐỐI không gửi Enter mù
UNKNOWN       // alt screen nhưng không nhận ra gì
```

Thứ tự phân loại trong `TuiClassifier.classify`:

1. `snapshot == null` → `UNKNOWN`
2. `!alternateBufferActive` → `SHELL`
3. có dialog → `DIALOG`  *(dialog che composer nên phải ưu tiên)*
4. có composer → `BUSY` nếu đang chạy, ngược lại `COMPOSER`
5. chỉ có dấu hiệu busy → `BUSY`
6. còn lại → `UNKNOWN`

---

## 3. Primitives (đã kiểm chứng)

Đọc:

- `TerminalSession.getEmulator()` → `TerminalEmulator`
- `TerminalEmulator.getRows()/getColumns()` — `getScreen()` (`TerminalBuffer`)
- `TerminalBuffer.getSelectedText(x1, y1, x2, y2)` — **`y2` là inclusive** (`for row <= y2`).
  → lấy 1 dòng: `getSelectedText(0, row, columns, row)` (đã dùng trong `TuiBridge.snapshot`).
- `TerminalEmulator.getCursorRow()/getCursorCol()`
- `TerminalEmulator.isAlternateBufferActive()` / `isMouseTrackingActive()` / `getTitle()`
- Session opencode: `TerminalSession.mSessionName` chứa `"opencode"` (đặt khi auto-launch trong
  `TermuxActivity`; xem `TermuxTerminalSessionActivityClient.addNewSession`).

Ghi (chưa dùng ở phần 1):

- `TerminalSession.write(byte[])`, `writeCodePoint(int)`
- `TerminalEmulator.paste(...)` — tự bọc `ESC[200~ … ESC[201~` khi app bật bracketed paste (2004)
- `TerminalEmulator.sendMouseEvent(...)`

Khả năng emulator: **có** bracketed paste (2004), SGR mouse (1000/1002/1006);
**không có** kitty CSI-u, **không có** modifyOtherKeys (log `(ignored) CSI > MODIFY RESOURCE`).

---

## 4. Byte protocol (nhớ sẵn để làm phần ghi)

| Muốn gửi | Byte |
|----------|------|
| Text (dán, tránh giật) | qua `emulator.paste(text)` → `ESC[200~ … ESC[201~` |
| Submit | `\r` (parser openCode: `\r` → `"return"` = submit) |
| Xuống dòng mới | **không** dùng `\r`/`\n`. modifyOtherKeys được parser đọc vô điều kiện → gửi `ESC[27;2;13~` (shift+enter) |
| Ctrl+Enter | `ESC[27;5;13~` |
| Alt+Enter | `ESC[27;3;13~` |
| Shift+Tab (agent trước) | `ESC[27;2;9~` |
| Chuột (điều hướng) | SGR: `ESC[<b;x;yM` (nhấn) / `m` (nhả), x/y 1-based |

`mod = 1 + shift(1) + alt(2) + ctrl(4)`. Kitty CSI-u **không dùng được** (emulator không hỗ trợ).

---

## 5. Token nhận diện màn hình (bằng chứng)

Nguồn: source openCode TUI + chuỗi nhúng trong binary `2.0.30`. Xem `TuiClassifier`.

| Trạng thái | Token (khớp không phân biệt hoa/thường) |
|-----------|------------------------------------------|
| Composer | `Ask anything`, `/ for commands, @ for context`, `Run a command`; hoặc footer có **cả** `agents` **và** `commands` trên cùng dòng (`tab agents … ctrl+p commands`) |
| Busy | `again to interrupt`; hoặc cùng dòng có `interrupt` **và** `esc` |
| Dialog (khớp cả dòng) | `Commands`, `Sessions`, `Stash`, `Themes` |
| Dialog (khớp chuỗi con) | `Select model`, `Select agent`, `MCP servers` |

Lưu ý: dialog che composer → khi dialog mở, token composer biến mất, nên ưu tiên `DIALOG` là đúng.
Token generic một từ phải khớp **cả dòng** để tránh dương tính giả với nội dung hội thoại.

---

## 6. Keybinds openCode v2 (tham chiếu nhanh)

```
input_submit=return
input_newline=shift+return, ctrl+return, alt+return, ctrl+j
agent_cycle=tab
command_list=ctrl+p
session_interrupt=escape
input_clear=ctrl+c
input_line_home/end=ctrl+a/e
input_buffer_home/end=home/end
messages_page_up/down=pageup/pagedown
dialog.select.submit=return
prompt.autocomplete.select=return
```

---

## 7. Lộ trình

- [x] **1. `TuiBridge` đọc snapshot + `classify()`** — xong. Gồm `TuiSnapshot`, `TuiState`,
      `TuiClassifier` (thuần) + `TuiBridge` (keo) + test thuần.
- [ ] **2. Submit an toàn** — nút Gửi / phím Enter chỉ gửi `\r` khi `classify()==COMPOSER`
      (hoặc `BUSY` nếu muốn steer); chặn khi `DIALOG`/`UNKNOWN`.
- [ ] **3. Nút "xuống dòng"** — gửi `ESC[27;2;13~` thay vì newline literal.
- [ ] **4. Mở ô nhập theo vùng composer** — chỉ mở compose khi chạm vào vùng ô soạn thảo,
      dùng `classify()` thay vì đoán theo dòng con trỏ.
- [ ] **5. Soi text composer vào `EditText`** — đọc lại text đang có trong ô soạn thảo TUI để
      sửa thay vì gõ lại.
- [ ] **6. Autocomplete `/command`** — xử lý xác định (mũi tên + `\r` chọn), tránh lệ thuộc timing.
- [ ] **7. Toolbar "remote"** — phím phụ gửi thẳng byte bất kỳ qua `TuiBridge` (một điểm vào duy nhất).
- [ ] **8. `clickCell`** — dùng SGR mouse để điều hướng/chạm ô trong TUI.

Thứ tự đề xuất: `2 → 3 → 4 → 7 → 6 → 5 → 8`.

Khi làm phần ghi: thêm phương thức vào `TuiBridge` (ví dụ `sendSubmit`, `sendNewline`,
`sendKey`, `pasteText`) để **mọi** kênh ghi đi qua một chỗ, rồi mới nối vào UI.

---

## 8. Rủi ro / cần kiểm chứng trên máy

- Token có thể đổi khi openCode lên phiên bản mới → giữ tất cả trong `TuiClassifier`, dễ sửa.
- Dialog title một từ (`Commands`…) phải khớp cả dòng; nếu openCode đổi cách vẽ (viền cùng dòng)
  thì cần xem lại `DIALOG_TITLES_EXACT`.
- Footer `agents`/`commands` có thể không hiện khi cửa sổ hẹp (`layout().shortcuts`).
- Chưa build chạy được trên máy này (không có JDK/Android SDK) → code viết cẩn thận; test thuần
  sẽ chạy trong CI.
