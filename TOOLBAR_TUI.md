# Toolbar ↔ opencode TUI — cầu nối trực tiếp qua PTY

Tài liệu này ghi lại **thiết kế + lộ trình** để nối thanh công cụ (toolbar) của `oct` với
TUI `opencode` đang chạy trong terminal, **không dùng HTTP API**. Mục đích: nhớ để làm tiếp.

> Nguyên tắc: **PTY chính là kênh giao tiếp**. App ghi byte vào pseudo-terminal
> (`TerminalSession.write(...)`) và đọc trạng thái màn hình từ `TerminalEmulator`. Không có
> giao thức riêng nào cần phát minh — chỉ cần ghi đúng byte mà TUI hiểu, và đọc đúng dấu hiệu
> mà TUI vẽ ra.

Trạng thái hiện tại: **Phần 1 đã xong** (đọc snapshot + `classify()`), và **cách nhận diện ô nhập
đã được làm lại** (2026-10-16): không còn tin con trỏ "sống" — dùng dòng caret mà chính TUI khai
báo qua `ESC[?25h` (xem [mục 3b](#3b-dòng-caret-do-tui-tự-khai-báo--phát-kiện-2026-10-16)). Các
phần còn lại ở mục [Lộ trình](#lộ-trình).

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
- `TerminalEmulator.getDeclaredCursorRow()/getDeclaredCursorCol()` — dòng/cột caret mà app tự khai
  báo bằng `ESC[?25h` (xem [mục 3b](#3b-dòng-caret-do-tui-tự-khai-báo--phát-kiện-2026-10-16)); trả
  `-1` khi chưa biết hoặc khi terminal đã đổi kích thước kể từ lúc ghi.
- `TerminalEmulator.isAlternateBufferActive()` / `isMouseTrackingActive()` / `getTitle()`
- Session opencode: `TerminalSession.mSessionName` chứa `"opencode"` (đặt khi auto-launch trong
  `TermuxActivity`; xem `TermuxTerminalSessionActivityClient.addNewSession`).

Ghi (chưa dùng ở phần 1):

- `TerminalSession.write(byte[])`, `writeCodePoint(int)`
- `TerminalEmulator.paste(...)` — tự bọc `ESC[200~ … ESC[201~` khi app bật bracketed paste (2004)
- `TerminalEmulator.sendMouseEvent(...)`

Khả năng emulator: **có** bracketed paste (2004), SGR mouse (1000/1002/1006);
**không có** kitty CSI-u, **không có** modifyOtherKeys (log `(ignored) CSI > MODIFY RESOURCE`).

### 3b. Dòng caret do TUI tự khai báo — phát kiện 2026-10-16

#### Vấn đề: "khi được khi không"

Đường nhanh cũ so **dòng chạm** với `emulator.getCursorRow()` (con trỏ sống). Nó trượt thường
xuyên vì OpenTUI **không** giữ con trỏ ở caret liên tục. Chạy `opencode` TUI trong PTY thật
(`script -q -c "stty rows 24 cols 80; opencode" cap.log`, 24×80) rồi soi byte thô cho thấy cấu
trúc **mỗi frame**:

```
ESC[?2026l ESC[?2026h            ← synchronized output (emulator bỏ qua)
ESC[?25l                          ← ẩn con trỏ
ESC[1;1H <SGR> ... ESC[1;22H ...  ← quét con trỏ khắp màn hình để vẽ
ESC[15;18H                        ← park đúng ô caret
ESC[?25h                          ← mới hiện con trỏ
```

Bằng chứng đếm trên một capture 24×80 (~6 s, 3397 lệnh CUP):

| Đo | Giá trị |
|---|---|
| `ESC[?25h` (hiện) | 317 |
| `ESC[?25l` (ẩn) | 319 |
| `ESC[<row>;<col>H` rồi ngay `ESC[?25h` | **304 lần = `15;18`** (ô caret), 12 lần `14;7`, 1 lần `14;18` |
| CUP trung bình mỗi show | ~10.7 |

⇒ Trong ~96% frame, byte ngay trước `?25h` là `CUP <caret>`; ~4% frame kết thúc ở dòng khác (frame
chuyển tiếp). Vì vậy đọc con trỏ **sống** tại thời điểm chạm sẽ trúng phần lớn nhưng trượt phần còn
lại — đúng kiểu "khi được khi không". (Tỉ lệ frame không phải xác suất chính xác, vì việc lấy mẫu
không rơi đều theo thời gian: ranh giới mỗi lần `read()` từ PTY có thể cắt ngang một frame, để lại
con trỏ sống ở dòng đang vẽ.) Khi TUI bận/đang animate (spinner) nó redraw ~50–60 fps (capture khác:
636 toggle `?25` trong 6 s), còn lúc rảnh chỉ còn nhịp nháy ~2 Hz.

Hai nguồn sai khác đã kiểm chứng thêm:

- **Resize làm con trỏ "park" theo hình học cũ.** `TerminalEmulator.resizeScreen()` **dịch chuyển**
  `mCursorRow` theo reflow. Khi bàn phím ẩn/hiện hoặc app quay lại foreground, view đổi số dòng ⇒
  con trỏ sống bị reflow đổi chỗ, trong khi TUI (có thể đang bị OS "đóng băng") chưa vẽ lại ⇒ dòng
  chạm không còn khớp.
- **Dialog chiếm caret.** Ctrl+P mở palette: caret nhảy sang dòng selection của palette (đo được
  `ESC[10;15H ESC[?25h`, row 10), rồi trả về `15;7` khi đóng ⇒ nếu dùng dòng caret phải loại trừ
  `DIALOG`.

Đã thử phương án "để TUI tự trả lời" (gửi SGR mouse click rồi xem TUI có dời caret tới chỗ chạm
không): **không dùng được** — click vào ô composer không làm caret đổi chỗ (composer vốn đã focus),
nên không có tín hiệu phân biệt với click vào transcript.

#### Cách làm mới

`TerminalEmulator` ghi lại vị trí con trỏ **ngay khi xử lý `ESC[?25h`**:

- thêm `mDeclaredCursorRow/Col` + `mDeclaredCursorRows/Columns` (kích thước lúc ghi);
- `case 25` khi `setting == true` (hiện) ⇒ ghi `mCursorRow/mCursorCol` + kích thước hiện tại;
- getter trả `-1` khi: chưa từng ghi, **hoặc** `mRows/mColumns` đã đổi (giá trị thuộc hình học cũ);
- xoá khi `reset()`/RIS và khi **đổi screen buffer** (1049/1047/47) — caret thuộc buffer vừa rời.

Ba lớp quyết định trong `TermuxActivity.isTerminalComposeTap` (sau khi loại `DIALOG`):

1. `isTerminalCursorLineTap` — chỉ khi **alt-buffer** đang bật, so dòng chạm với **declared** row
   (fallback về con trỏ sống khi chưa có); shell (main buffer) vẫn dùng con trỏ sống vì ở đó con trỏ
   vốn đứng đúng dấu nhắc.
2. `TuiClassifier.isComposerRowAt(snapshot, row, caretRow)` — neo dải composer vào caret do app
   khai báo (không cần placeholder/footer) ⇒ xử lý được draft dài / cửa sổ hẹp che marker. Chỉ nhận
   caret làm mốc khi nó nằm ở **dải đáy** (`caretRow >= rows - band`), nên caret giữa màn hình
   (dialog/list toàn màn hình) không bị nhầm là ô nhập.
3. `isComposerRow` (neo theo footer/placeholder) rồi `isComposerBandRow` — như cũ.

Ưu điểm: bám đúng "app tự nói caret ở đâu", không đoán theo thời điểm, và tự vô hiệu khi hình học
đổi để rơi về nhận diện theo nội dung.

#### Kiểm chứng lại (proof script)

Hợp đồng byte ở trên là hành vi của TUI, nên nó có thể đổi khi openCode/OpenTUI lên phiên bản mới.
`scripts/opencode-tui-cursor-proof.sh` chạy lại đúng thí nghiệm này trên máy (PTY thật qua `script`,
không cần API, không cần build APK) và tự kiểm:

1. TUI có bật/tắt con trỏ (`ESC[?25l`/`ESC[?25h`) và dùng synchronized output (`ESC[?2026h/l`);
2. phần lớn `ESC[?25h` được đứng ngay trước bởi `ESC[<row>;<col>H` (đích con trỏ mà
   `getDeclaredCursorRow()` ghi lại) — ngưỡng đặt ở 80%, thực đo idle thấy **220/220**;
3. khi rảnh, dòng caret khai báo là **một dòng cố định ở nửa dưới** màn hình;
4. khi gõ, **cột** caret khai báo đổi theo draft còn **dòng** giữ nguyên.

Chạy: `bash scripts/opencode-tui-cursor-proof.sh` (giữ capture trong `$PREFIX/tmp/oc-tui-cursor.<pid>/`).

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
- [x] **4. Mở ô nhập theo vùng composer** — xong, đã làm lại (2026-10-16).
      Bản đầu dùng đường nhanh theo con trỏ **sống** rồi dự phòng nhận diện vùng composer từ màn
      hình (`TuiClassifier.isComposerRow`); con trỏ sống không ổn định (xem
      [mục 3b](#3b-dòng-caret-do-tui-tự-khai-báo--phát-kiện-2026-10-16)).
      Bản hiện tại: `TermuxActivity.isTerminalCursorLineTap()` so dòng chạm với **dòng caret do TUI
      khai báo** (`TerminalEmulator.getDeclaredCursorRow()`, từ `ESC[?25h`), rồi
      `TuiClassifier.isComposerRowAt(snapshot, row, caretRow)` neo dải composer vào caret đó, sau
      cùng mới tới `isComposerRow` / `isComposerBandRow`. Vẫn chặn khi `classify()==DIALOG`.
      Đường nhanh theo con trỏ sống giữ làm fallback khi chưa có declared row (non-OpenTUI/shell).
      Test: `DecSetTest.testShowCursorDeclaresCaretRow`,
      `TuiClassifierTest.composerRowAt*`.
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
- `isComposerRowAt` chỉ nhận caret làm mốc khi caret nằm ở **dải đáy**; dialog/list neo đáy mà
  không có title nhận diện được vẫn có thể lọt. Đổi lại, nó bỏ hẳn phụ thuộc placeholder/footer.
- Khi TUI không phát `ESC[?25h` (app khác OpenTUI) thì `getDeclaredCursorRow()` trả `-1` và mọi
  thứ rơi về con trỏ sống + nhận diện theo nội dung như cũ.
- Footer `agents`/`commands` có thể không hiện khi cửa sổ hẹp (`layout().shortcuts`).
- Vùng composer (`TuiClassifier.isComposerRow`) là ước lượng theo mốc neo, không đo chính xác
  chiều cao khung: bản nháp dài có thể vượt `min(rows/3, 12)` dòng. Khi có caret do TUI khai báo,
  `isComposerRowAt` neo đúng vào caret nên không còn phụ thuộc ước lượng này.
- Chưa build chạy được trên máy này (không có JDK/Android SDK) → code viết cẩn thận; test thuần
  sẽ chạy trong CI.
