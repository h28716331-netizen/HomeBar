# 小白条 HomeBar

给 Android 16（targetSdk 36 / minSdk 30）用的 iOS 风格底部指示条。
**只叠加一根条，不改系统任何动画与手势。**

**[⬇ 直接下载 APK](https://github.com/h28716331-netizen/HomeBar/releases/latest)** ·
不用装 Android Studio，装上后在无障碍里开启服务即可。

英文说明：[README.md](README.md)

---

## ⚠️ 先看这段：1.0.0 出了什么事故

1.0.0 装上去之后手势失效、屏幕几乎没法操作。原因是这一行：

```kotlin
info.motionEventSources = InputDevice.SOURCE_TOUCHSCREEN   // ← 致命
```

当时的想法是「被动观察触摸坐标」。但官方文档写得很清楚：

> **`MotionEvent`s from sources in `getMotionEventSources()`
> are not sent to the rest of the system.**

意思是：**一旦声明了某个触摸来源，来自该来源的事件会被从输入链路里摘走**，
不再派发给 SystemUI、Launcher 或任何应用。等于把整块屏幕的触摸关掉了。
这跟「被动观察」完全相反，是理解错误，不是 ROM 的锅。

**1.1.0 已彻底删除**：`setMotionEventSources`、`onMotionEvent` 覆写、
`canRequestTouchExplorationMode`、`FLAG_REQUEST_TOUCH_EXPLORATION_MODE`。
全仓库可以搜索这几个名字，除了注释里的说明，代码中一处都没有。

### 手机已经被 1.0.0 搞乱了怎么办

按顺序试，任一条生效即可：

1. **通知栏**（1.1.0 才有）：找到「小白条运行中」→「暂停」或「彻底关闭」。
2. **安全模式**：长按电源键 → 长按「关机」→ 重启进安全模式。
   安全模式会临时停用所有第三方无障碍服务，之后正常重启即可恢复。
3. **无障碍快捷键**：如果设过，同时按住「音量上 + 音量下」3 秒可开关无障碍服务。
4. **ADB**（最彻底）：
   ```bash
   adb shell settings put secure enabled_accessibility_services ""
   adb shell settings put secure accessibility_enabled 0
   ```
5. **直接卸载**：
   ```bash
   adb uninstall com.homebar.debug
   ```

装 1.1.0 覆盖安装即可修好，不需要先卸载。

---

## 触摸感知是怎么做的

判断「用户碰了屏幕」需要信号，但任何会「拿走事件」的手段都不能用。

### 主通道：1×1 像素的触摸探测窗口

挂一个 **1×1 像素**、不可聚焦、非模态的悬浮窗口，打开
`FLAG_WATCH_OUTSIDE_TOUCH`：

> 落在窗口**外面**的触摸，会把按下点以 `ACTION_OUTSIDE`
> 通知给该窗口，**同时事件照常送给它下面的应用**。

窗口类型是 `TYPE_ACCESSIBILITY_OVERLAY`。查 AOSP
`WindowManagerPolicy.getWindowLayerFromTypeLw()`：它的层级是 **31**，
而 `TYPE_NAVIGATION_BAR` 是 **24** —— 探测窗口压在导航栏之上，
所以任何位置的触摸（包括最底部的导航栏/手势区）都能感知到。

窗口只有 1 个像素大，所以就算某个 ROM 把落在窗口内的触摸判定成被本窗口消费，
**最多也只影响屏幕上那 1 个像素** —— 一个可以被证明的、可忽略的上界。

### 备用通道：无障碍触摸交互事件

订阅 `TYPE_TOUCH_INTERACTION_START / END`。但 AOSP
`AccessibilityWindowManager` 里这两个回调的注释写得很清楚：

> Callbacks from AccessibilityManagerService **when touch explorer turn on**
> and motion down detected.

也就是说只有**系统开启触摸探索**时才会派发。本应用刻意不开启触摸探索
（那会改变整机触摸行为），所以这条通道正常收不到东西，留着只是个别 ROM 的兜底。

### ⚠️ 为什么没有「仅屏幕底部弹跳」

因为 **Android 在输入分发层面禁止跨应用读取触摸坐标**。
AOSP `InputDispatcher.cpp`：

```cpp
// Check whether windows listening for outside touches are owned by the same UID.
// If the owner has a different UID, then we will not reveal coordinate information
// to this window.
if (targetWindow->getInfo()->ownerUid != foregroundWindowUid) {
    target.flags |= InputTarget::Flags::ZERO_COORDS;
}
```

`InputTargetFlags.h` 对 `ZERO_COORDS` 的注释：

> will be zeroed out to avoid revealing information to an application. This is used
> in conjunction with FLAG_DISPATCH_AS_OUTSIDE **to prevent apps not sharing the same
> UID from watching all touches**.

结论：

| 场景 | 能拿到什么 |
|---|---|
| 触摸落在**小白条自己的界面**上 | 真实的屏幕坐标 |
| 触摸落在**任何其他应用 / 桌面**上 | 只有「有人碰了屏幕」，坐标被强制清零成 `(0,0)` |

所以在别的应用里，我们根本不可能知道手指在不在底部。
唯一能拿坐标的办法是去抢输入事件 —— 那会把整块屏幕的触摸搞坏
（1.0.0 就是这么翻车的），绝对不能做。

因此弹跳只能退而求其次：**每次触摸屏幕都弹**。

### 兜底

个别定制 ROM 若连 `ACTION_OUTSIDE` 都不派发，指示条会退化为
**常显 + 不弹跳**，功能少一点，但**绝不影响手机使用**。
设置页的「触摸信号自检」会实时显示主通道、坐标公开情况、备用通道三项状态。

---

## 功能

- **尺寸 / 位置**：宽 60–320dp、粗细 2–16dp、圆角、距底距离、水平偏移、不透明度、柔光描边
- **跟随背景变黑白**：无障碍截图采样指示条周围窄带（避开自身像素），
  算 WCAG 相对亮度，带阈值 + 迟滞防抖 + 黑白渐变；也可强制纯白 / 纯黑
- **沉浸自动隐藏**：无操作 N 秒隐藏（默认 2s，500ms–15s 可调），一碰屏幕出现
- **手势保护**：上滑退出应用 / 回桌面时不会把条带出来（延迟唤醒 + 可撤销，延迟可调）
- **桌面与最近任务隐藏**：在桌面、最近任务上收起，进入任意应用后自动淡入
- **通知中心 / 控制中心隐藏**：下拉通知栏、展开控制中心时收起，收回后淡入
- **锁屏隐藏**：熄屏、锁屏界面、锁屏上的通知面板都不显示，真正解锁后淡入
  三者都**不读屏幕内容、不申请「使用情况访问」、不碰触摸**
- **触摸弹跳**：**点一下小白条**，它向上轻弹一下再落回原位；幅度、时长（速度）、
  速度曲线全部可调。也可切回「碰屏幕就弹」的旧模式
  （跨应用拿不到触摸坐标，旧模式下无法只对底部生效，原因见上文）
- **速度曲线**：线性 / 减速 / 加速 / 缓入缓出 / 回弹 / 预备回弹 /
  弹簧（阻尼可调）/ 弹跳 / 自定义贝塞尔，界面里配了**实时曲线图**
- **出现消失动画**：时长 + 曲线独立可调，另有缩放过渡、位移过渡（滑入距离可调），
  默认「300ms + 弹簧曲线」，比瞬现瞬隐柔和很多
- **实时预览**：含浅 / 深背景切换、出现 / 隐藏 / 弹跳 / 演示沉浸按钮
- **安全与急救**：应用内一键暂停；通知栏常驻「暂停 / 彻底关闭」

---

## 系统界面隐藏是怎么判定的

两个开关、两条独立判定，最后取「或」：任一命中就收起。

### 桌面与最近任务

只回答一个问题：**现在的前台窗口属于哪个包？** 数据来自无障碍
`TYPE_WINDOW_STATE_CHANGED` 事件里自带的 `packageName`，再和
`queryIntentActivities(ACTION_MAIN + CATEGORY_HOME)` 得到的桌面包名集合比对。

- 命中 → 走正常的消失动画收起（和「无操作自动隐藏」同一条动画，观感一致，不突兀）
- 不命中 → 走出现动画淡入
- 进入桌面有 **120ms 确认延迟**，吃掉回桌面途中夹着的中间窗口事件，避免「先显示再隐藏」闪一下
- 离开桌面**立即生效**，进应用马上就能看到条

Pixel Launcher 的「最近任务」本身就在 Launcher 里，所以这个开关同时也覆盖它。

### 通知中心与控制中心

通知栏是 SystemUI 的 `TYPE_SYSTEM` 窗口，**不是 Activity**，
下拉时不一定有 `TYPE_WINDOW_STATE_CHANGED`，所以不能只靠包名。

改成看无障碍窗口列表（`getWindows()`），三条规则**任一命中**即认为面板展开：

| 规则 | 判据 | 为什么可靠 |
|---|---|---|
| A 铺满屏幕的面板 | 宽 ≥ 80%、高 ≥ 28% 的 `TYPE_SYSTEM` 窗口 | 状态栏、导航栏、音量条都远小于此 |
| B 焦点面板 | 拿到输入焦点（或正在被交互）的系统窗口，宽 ≥ 55%、高 ≥ 18% | 面板展开时会抢走焦点；状态栏 / 导航栏都是 `FLAG_NOT_FOCUSABLE`，永远拿不到焦点 |
| C 顶部宽面板 | 顶边贴屏幕顶端、宽 ≥ 90%、高 ≥ 15% | 覆盖「下拉动画中途」「分屏式控制中心」这类 bounds 只报了触摸区的 ROM |

只靠事件不够，v1.4.0 补了两层兜底：

- **主动轮询**：每 250ms 复查一次窗口列表（只读几个窗口的元数据，成本可忽略）。
  个别 ROM 在「从顶部继续下拉切控制中心」「单手模式下拉」时一个窗口事件都不派发，
  没有轮询就永远发现不了面板已经展开。
- **动画补测**：收到窗口变化事件后，在 0/60/130/220/340/500/700ms 各测一次。
  面板展开动画约 250–350ms，只在事件到达那一瞬间测一次，测到的是「刚冒头那一帧」，
  高度还不够阈值，之后又没有新事件，状态就永远停在「未展开」。
- **触摸时先核对**：下拉通知栏的那一下本身也是一次触摸，先核一次再决定要不要弹，
  避免「先弹出来再收回去」闪一下。

另外，`serviceInfo` 的标志位在 `onServiceConnected()` 里会用 `setServiceInfo()` 再补一遍：
无障碍配置只在**开启服务那一刻**被系统读取缓存，从老版本直接升级 APK 并不会重新读，
缓存里没有 `flagRetrieveInteractiveWindows` 时 `getWindows()` 会一直返回空 ——
这正是 1.3.0「面板展开了、条还在」的另一个原因。

### 锁屏（1.7.0）

三个系统广播驱动，不做轮询：

| 广播 | 动作 |
|---|---|
| `ACTION_SCREEN_OFF` | 立即视为锁定 —— 顺带让条不出现在 AOD 息屏显示上 |
| `ACTION_SCREEN_ON` | 问一次 `KeyguardManager.isKeyguardLocked()` |
| `ACTION_USER_PRESENT` | 用户真正解锁了，恢复显示 |

**亮屏 ≠ 解锁**：很多 ROM 亮屏后停在锁屏界面，屏幕是亮的，但还没解锁。
只看 `SCREEN_ON` 会把条画在锁屏上。

`isKeyguardLocked()` 不需要任何权限，不弹授权框，只能回答
「锁屏现在是否盖在屏幕上」，读不到任何内容。

`ACTION_USER_PRESENT` 并不是所有 ROM 都会派发 —— 1.7.0 只靠它就翻过车：
解锁一次之后条再也不出现。所以补了三层兜底：

| 触发 | 作用 |
|---|---|
| 锁屏且亮屏时每 400ms 轮询一次 | 收不到广播也能发现已解锁 |
| 任何非 SystemUI 应用的 `TYPE_WINDOW_STATE_CHANGED` | 解锁后必然有窗口变化 |
| 任意一次触摸 | 解锁后第一下触摸立刻复核 |

轮询在「取消抑制」的瞬间就停，正常使用下没有额外开销。

### 刻意避开的做法

| 做法 | 为什么不用 |
|---|---|
| `UsageStatsManager` 查前台应用 | 需要「使用情况访问」权限，得让用户手动去设置里授权 |
| `canRetrieveWindowContent` + 读窗口内容 | 系统会弹「此服务可查看屏幕内容」，权限面变大 |
| 靠触摸坐标判断 | 跨应用拿不到坐标（AOSP `ZERO_COORDS`），而抢事件会把触摸搞坏 |

通知栏判定确实用到了 `getWindows()`，但只加了 `flagRetrieveInteractiveWindows`，
**没有** `canRetrieveWindowContent` —— 拿到的只是窗口元数据（类型、bounds、层级），
不包含任何窗口内容，也不会触发那条「可查看屏幕内容」的授权提示。

**降级行为**：如果 `queryIntentActivities` 返回空（个别 ROM 收紧包可见性），
`homePackages` 就是空集合，桌面判定永远为 false —— 桌面隐藏自动失效，
但绝不会出现「在应用里被误隐藏」。宁可不生效，也不乱来。

在抑制态下触摸不会把条唤出来（`onTouchDown()` 直接返回），
所以不会出现「一碰桌面它又冒出来」。

---

## 手势保护（1.6.0）

问题：从底部上滑退出应用 / 回桌面，手指落下的那一下也算「触摸」，条会先冒出来，
等桌面判定生效再收回去 —— 手势里闪一下，很脏。

难点：**按下的那一刻不可能知道这是不是手势**。跨应用拿不到坐标（`ZERO_COORDS`），
唯一的判断依据是「松手之后前台变成了谁」，而那要等手势动画结束（约 300ms）。

所以改成「延迟唤醒 + 可撤销」：

```
触摸 DOWN ──► 挂一个 wakeDelayMs 的延迟任务（不显示）
                    │
                    ├─ 期间判定回桌面 ──► 撤销任务，条自始至终没出现
                    └─ 超时 ─────────► 正常淡入
```

- 如果条已经淡入了一点点（进度 < 0.45），判定回桌面时**直接归零**而不是淡出，
  连那条「小尾巴」也不留
- 回桌面判定走的是「立即撤销 + 120ms 确认抑制」两段：
  撤销是立刻的，抑制确认仍保留 120ms 防抖
- `wakeDelayMs` 默认 300ms（手势动画量级），0–700ms 可调，设成 0 等于关掉这个特性

代价：碰屏幕后条出现会慢 `wakeDelayMs`。想更灵敏就把延迟调小，想更干净就调大。

---

## 为什么不会改掉系统原有动画

| | EdgeGesture 类做法 | 本项目 |
|---|---|---|
| 悬浮层 | 参与手势判定 / 替换导航栏 | `TYPE_ACCESSIBILITY_OVERLAY` |
| 绘制窗口 | 可触摸，抢输入 | **永久 `FLAG_NOT_TOUCHABLE`**，输入系统直接跳过 |
| 点击感应（1.5.0） | 拦截整片底部区域 | 单独一层**只有条那么大**的透明窗口，条隐藏时立刻恢复不可触摸 |
| 触摸感知 | 靠拦截获得 | 1×1 像素探测窗口顺带收通知，事件照常送下去 |
| 其他 | 改动画缩放、注入手势 | 全都不做 |

返回手势、上滑回桌面、预测性返回动画完全由 SystemUI / Launcher 原样处理。

### 点击感应层（1.5.0）

「只在点击小白条时弹跳」必须真的收到触摸，而绘制窗口比条大一圈
（上方要留弹跳 + 滑入 + 柔光的余量，约 30dp）。如果直接让绘制窗口可触摸，
条上方那 30dp 就成了「点了没反应」的死区。

所以拆成两层：

| 层 | 尺寸 | 触摸 | 作用 |
|---|---|---|---|
| 绘制层 `BarView` | 条 + 四周余量（约 150×50dp） | **不可触摸** | 只负责画（含弹跳位移） |
| 感应层 `BarTouchTarget` | 条 + 横 10dp / 纵 7dp 外扩 | 可触摸 | 只负责判定「点没点中条」 |

安全约束：

- 只在「条可见且未被抑制」时摘掉 `FLAG_NOT_TOUCHABLE`；条一开始淡出就立刻加回去，
  不会在屏幕底部长期留一块看不见的触摸区
- `FLAG_NOT_TOUCH_MODAL`：窗口外的触摸照常送给下面的应用
- `FLAG_NOT_FOCUSABLE`：不抢焦点、不影响输入法
- 只把「按下后没移动、600ms 内抬起」算点击；滑动一律不算，手势导航的上滑由系统优先接管
- 设置里可一键关掉，关掉后回到「结构上完全不可触摸」的最保守形态

---

## 权限

- **无障碍服务**：必需的。只订阅触摸交互事件 + 画一根不可触摸的条 + 1×1 探测窗口。
- **`POST_NOTIFICATIONS`**：仅用于通知栏里的「暂停 / 彻底关闭」紧急出口。
  不授予也不影响主功能。

不申请 `SYSTEM_ALERT_WINDOW`（走无障碍悬浮层），
不申请任何输入注入能力，不声明任何触摸事件来源。

另外 Manifest 里声明了一段 `<queries>`（`ACTION_MAIN` + `CATEGORY_HOME`）。
这是 Android 11+ 的**包可见性声明，不是权限**：不声明的话
`queryIntentActivities` 会返回空列表，桌面隐藏就无法判断。
它只允许本应用「看见有哪些桌面应用」，不涉及读取任何应用的内容。

---

## 目录结构

```
app/src/main/java/com/homebar/
├── MainActivity.kt                     入口（含通知权限申请）
├── core/
│   ├── BarConfig.kt                    全部可调参数 + 预设
│   ├── BarPrefs.kt                     参数持久化 + 暂停开关 + 服务状态查询
│   ├── Easing.kt                       缓动曲线库（贝塞尔 / 弹簧 / 回弹 / 弹跳）
│   └── TouchDiagnostics.kt             触摸信号自检状态（只记录，不参与决策）
├── overlay/
│   ├── BarView.kt                      指示条自绘（出现/消失/弹跳全在一次 draw 里完成）
│   ├── BarOverlayController.kt         窗口管理 + 动画调度
│   ├── ScreenColorSampler.kt           背景取色（整块读取 + 内存降采样）
│   └── TouchProbe.kt                   1×1 像素触摸探测窗口
├── service/
│   └── HomeBarAccessibilityService.kt  服务主体 + 紧急停用通知
└── ui/
    ├── HomeBarApp.kt                   设置页（含安全与急救、触摸感知自检）
    ├── BarPreview.kt                   实时预览
    ├── Components.kt                   通用控件 + 曲线图
    └── Theme.kt                        主题
```

---

## 本地编译

```bash
export JAVA_HOME=/path/to/jdk-21
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

首次构建需要联网拉依赖。本仓库的 `settings.gradle.kts` 配了阿里云 Maven 镜像，
如果你的网络能直连 Google Maven，可以把镜像仓库删掉。

---

## 已知边界

1. **系统自带的导航条还在**，本项目不去动它（动它就等于改系统行为）。
   想只看到这一根，可在「设置 → 系统 → 手势」里关掉系统手势提示条。
2. **无法做「仅屏幕底部弹跳」**。这是 Android 输入分发层的隐私保护
   （跨 UID 的 `ACTION_OUTSIDE` 会被打上 `ZERO_COORDS`，坐标强制清零），
   不是本项目可以绕过的。详见上文。个别 ROM 若不派发 `ACTION_OUTSIDE`，
   则连「有人碰了屏幕」都收不到，指示条会退化为常显 + 不弹跳。
3. **取色靠无障碍截图**，间隔越短越跟手但越耗电，默认 1200ms，
   且只在指示条可见时采样。系统对截图本身有 333ms 的频率限制。
