# Window-targeted Agent 研究記錄

本文件記錄在 HyperOS 自由視窗（小窗）上，對**單一視窗**讀取與操作的實驗結果。
目前這條線暫停開發，只留下研究結論，不包含任何已實作的 Window Agent 工具。

證據等級：

- **【實測】** 在下列環境直接觀察到的結果。
- **【推論】** 由實測結果或公開資料推得，沒有直接驗證。
- **【未驗證】** 尚未嘗試，或嘗試了但沒有結論。

## 1. 測試環境與方法

| 項目 | 內容 |
|---|---|
| 裝置 | Xiaomi 14T Pro（2407FPN8EG），HyperOS，Android 16（API 36） |
| 螢幕 | 1220×2712，density 520 |
| 被測 App | Chrome（`com.android.chrome`），以 HyperOS 小窗模式開啟（windowing mode = `freeform`） |
| 執行方 | Zafiro debug 版（`com.niki914.zafiro.debug`）的 `AccessibilityService`，具 `canTakeScreenshot`、`flagRetrieveInteractiveWindows` |
| 觀察手段 | root 下的 `dumpsys window`、`dumpsys activity activities`、`dumpsys SurfaceFlinger`、`uiautomator dump --windows` |
| 探針 | 臨時的 `WindowProbe`（以 broadcast 觸發、結果寫檔），實驗完成後已移除，未提交 |

所有實驗都讓 Zafiro 自己在前景、Chrome 小窗處於 `active=false focused=false`，以模擬「非焦點視窗」。

## 2. 座標系

【實測】小窗的幾何由兩層構成：

- app 看到的邏輯視窗大小：實驗開始時為 **1220×1952**。
- SurfaceFlinger 對該視窗的縮放矩陣：實驗開始時 `scale = 0.25`，`tx=871, ty=162`；視窗被聚焦後縮放變成約 `0.3623`，`tx=758`。

換算：`螢幕座標 = (tx, ty) + app 本地座標 × scale`。

三種資料使用的座標系並不相同：

| 資料 | 座標系 |
|---|---|
| `takeScreenshotOfWindow()` 的圖像 | **視窗本地**（1220×1952），沒有縮放，也不含系統標題列 |
| `AccessibilityNodeInfo.getBoundsInScreen()`、`AccessibilityWindowInfo.getBoundsInScreen()` | **縮放後的螢幕座標** |
| `input -d 0 tap x y` | 螢幕座標，由系統再換算回 app 座標 |

【實測】小窗被聚焦時會重新排版：上方出現標題列、縮放改變，同一顆按鈕的螢幕座標隨之位移（選單鈕 `y≈215` 變成 `y≈239`）。因此座標不能快取，每次操作前都要重讀。

【推論】要把截圖上的點對應到節點，需要用視窗當下的 `bounds` 與縮放換算，而縮放值無障礙 API 沒有直接給；可由視窗邏輯大小與螢幕 bounds 的比值得到。

## 3. 實測結果

### 3.1 `takeScreenshotOfWindow(windowId)`

- 【實測】對未聚焦的小窗成功，回傳 **1220×1952** 原生解析度，畫面乾淨，只含 app 內容。
- 【實測】兩次連續呼叫，其中一次回 `failure code=3`。依常數定義這是 `ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT`，【推論】系統限流約每秒一次；精確間隔沒有量測。

### 3.2 `AccessibilityWindowInfo.getRoot()`

- 【實測】非焦點視窗仍可取得完整節點樹（本次 79 個節點，`packageName=com.android.chrome`）。
- 【實測】Zafiro 現有的 display-0 讀取已經會選到這個小窗並回傳它的樹。

### 3.3 `ACTION_CLICK`

- 【實測】不需先聚焦即可執行，`performAction=true`，Chrome 選單彈出。
- 【實測】點擊不改變 Chrome 視窗本身的邊界與焦點；彈出的 `PopupWindow` 自己成為焦點視窗。
- 【實測】對照：以 `input tap` 注入點擊，第一下只會聚焦小窗並觸發重排，位置因而失效；`ACTION_CLICK` 沒有這個問題。

### 3.4 `ACTION_SET_TEXT`

- 【實測】對 Chrome 網址列成功，讀回文字為新值。
- 【實測】操作後視窗焦點轉到 Chrome（`mCurrentFocus` 由 Zafiro 變成 Chrome）。【推論】這是 Chrome 網址列在被設值時自行要求焦點，其他 App 的輸入框行為未知。
- 【未驗證】其他 App 的輸入框是否也會搶焦點；輸入法狀態的變化沒有量測。

### 3.5 `ACTION_SCROLL_FORWARD / BACKWARD`

- 【實測】兩個方向皆 `performAction=true`，頁面確實捲動（一次約 145 px，螢幕座標）。
- 【實測】無障礙樹的更新有延遲：動作後等 1.2 秒讀到的仍是舊內容，下一次讀取才看到新位置。
- 【實測】焦點變化不穩定：有幾次 Chrome 取走系統焦點，也有一次焦點留在 Zafiro。原因未查明。

### 3.6 PopupWindow

- 【實測】Chrome 選單是獨立的無障礙視窗，標題「彈出式視窗」、`packageName=com.android.chrome`。
- 【實測】`AccessibilityWindowInfo.getParent()` 不一定可靠：同樣的操作，一次是 `parent=<Chrome 視窗 id>`，一次是 `parent=null`。
- 【實測】彈出瞬間視窗列表可能暫時不完整。
- 【推論】可靠的辨識方式是同套件、application 類型、標題與 layer，而不是 parent。

### 3.7 小窗移動／縮放

- 【實測】以 `am task resize <taskId> l t r b` 改變任務邊界後，無障礙視窗 id 不變、標題不變，螢幕 bounds 改變。
- 【實測】移動後重新取 root 並點擊仍然成功。
- 限制：只測了一次 `am task resize`，沒有測使用者手動拖曳、旋轉、重新進入小窗。
- 【實測】對小窗按 `BACK` 會把整個小窗任務關掉（在 Chrome 無其他可返回內容時），是一個副作用風險。

### 3.8 人機並行

- 【實測】Zafiro 在前景且輸入法開啟時，對 Chrome 發出捲動，同時對 Zafiro 區域以 `input swipe` 操作：Chrome 有捲動，Zafiro 的輸入框與鍵盤維持在前景，沒有觀察到互相干擾。
- 限制：使用者操作是以 `input` 模擬，不是真人同時操作；沒有量測 Zafiro 畫面是否被正確捲動；樣本只有數次。
- 風險：3.4 與 3.5 中 Chrome 取走焦點時，使用者正在主 App 打字會遇到輸入焦點被切走。

## 4. 輸入注入研究

【實測】`input -d 0 tap` 可以對小窗注入點擊，但走的是螢幕座標，且受 3.3 所述的聚焦與重排影響。

公開資料（【推論】，未在本裝置驗證）：

- Android 的觸控派發依觸控位置命中視窗；沒有公開 API 可以「不給座標、直接把觸控送進指定視窗」。
- 注入需要簽章權限 `INJECT_EVENTS`；Android 13 起有 targeted injection，只在目標視窗屬於指定 UID 時送達，這是安全檢查而非路由。HyperOS 上 shell UID 的 helper 注入先前實測被拒。

未完成的研究方向，全部【未驗證】：

1. 在目標 App 行程內以 LSPosed 掛鉤，直接對 DecorView `dispatchTouchEvent`（本地座標）。
2. 以 SurfaceFlinger layer 截圖取得更可控的單視窗畫面。
3. `AccessibilityService.dispatchGesture` 指定 display／視窗的行為。
4. 輸入法的 `InputConnection` 對非焦點視窗的行為。

## 5. 結論與建議

可行的組合（【實測】成立）：

- 以 `window_id` 讀樹與截圖，原生解析度，不需聚焦。
- 以節點 `performAction` 點擊、設文字、捲動，不依賴座標。

必須處理的問題：

- 動作可能令目標視窗取走系統焦點，需要回報。
- 無障礙樹更新延遲，需要輪詢驗證，而不是固定等待。
- 座標每次重讀，不快取。

需要保持焦點獨立或避免干擾使用者時，優先改用虛擬螢幕（已實作），而不是在實體螢幕的小窗上操作。
