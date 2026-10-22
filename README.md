# HomeBar

**An iOS-style home indicator for Android — implemented as a pure accessibility overlay.**

[![Platform](https://img.shields.io/badge/platform-Android%2011%2B-3DDC84?logo=android&logoColor=white)](#requirements)
[![targetSdk](https://img.shields.io/badge/targetSdk-36-blue)](#requirements)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.x-7F52FF?logo=kotlin&logoColor=white)](#requirements)
[![License](https://img.shields.io/badge/license-MIT-green)](LICENSE)

HomeBar draws a single rounded bar near the bottom of the screen and nothing else.
It does **not** replace the navigation bar, intercept gestures, hook the input pipeline,
or alter any system animation. Back gestures, swipe-up-to-home, and predictive back
keep running exactly as the system ships them.

> 中文文档：[README.zh-CN.md](README.zh-CN.md)

---

## Why this project exists

Most "iOS gesture bar" apps on Android take one of two shortcuts:

1. **Replace the navigation bar** — which drags the whole gesture pipeline along with it,
   and changes system animation behaviour as a side effect.
2. **Intercept touch input** — declare a motion event source, listen to raw touches,
   and replay them. This is the shortcut that breaks phones.

HomeBar was written after the second shortcut was tried, shipped, and broke a device.
The full post-mortem is below, because the mistake is easy to repeat and worth documenting.

---

## ⚠️ Post-mortem: how v1.0.0 broke a phone

v1.0.0 shipped with this line:

```kotlin
info.motionEventSources = InputDevice.SOURCE_TOUCHSCREEN   // ← fatal
```

The intent was innocent: "passively observe touch coordinates." The documentation says
the opposite:

> **`MotionEvent`s from sources in `getMotionEventSources()`
> are not sent to the rest of the system.**

Declaring a motion event source **removes those events from the input pipeline entirely**.
They stop being delivered to SystemUI, the launcher, and every app. The result: gestures
dead, screen effectively unusable. This was a misreading of the API, not a ROM bug.

**Removed completely in v1.1.0** — `setMotionEventSources`, the `onMotionEvent` override,
`canRequestTouchExplorationMode`, and `FLAG_REQUEST_TOUCH_EXPLORATION_MODE`.
Grep the repository: outside of comments explaining why, none of them appear.

### If a device is already stuck

Try in order; stop at the first one that works.

1. **Notification shade** (v1.1.0+) — open the persistent "HomeBar running" notification →
   *Pause* or *Turn off*.
2. **Safe mode** — hold power → long-press *Power off* → reboot to safe mode.
   Third-party accessibility services are disabled there; a normal reboot restores things.
3. **Accessibility shortcut** — if enabled, hold *Volume Up + Volume Down* for 3 seconds.
4. **ADB** — the most reliable route:
   ```bash
   adb shell settings put secure enabled_accessibility_services ""
   adb shell settings put secure accessibility_enabled 0
   ```
5. **Uninstall**:
   ```bash
   adb uninstall com.homebar.debug
   ```

Installing v1.1.0 or later over the top also fixes it; uninstalling first is not required.

---

## Features

| | |
|---|---|
| **Geometry** | Width 60–320 dp, thickness 2–16 dp, corner radius, bottom margin, horizontal offset, opacity, soft glow |
| **Adaptive colour** | Samples a thin band around the bar via accessibility screenshot, computes WCAG relative luminance, switches black/white with hysteresis and a cross-fade. Can also be pinned to pure white or pure black |
| **Immersive auto-hide** | Fades out after N seconds of inactivity (default 2 s, 500 ms–15 s). Any touch brings it back |
| **Gesture guard** | Swipe-up-to-home does not flash the bar. Wake is delayed and cancellable, delay configurable |
| **Hide on home & recents** | Collapses on the launcher and in recents, fades in inside any app |
| **Hide on notification shade & quick settings** | Collapses when the shade or control centre expands, fades back on dismiss |
| **Tap bounce** | Tap the bar itself and it springs up and settles back. Amplitude, duration, and easing are all adjustable |
| **Easing curves** | Linear, decelerate, accelerate, ease-in-out, back, anticipate, spring (damped), bounce, custom cubic Bézier — with a live curve plot in the settings UI |
| **Show / hide animation** | Independent duration and curve, plus optional scale and slide transitions |
| **Live preview** | Light/dark background toggle, plus buttons to trigger appear, hide, bounce, and the immersive demo |
| **Emergency controls** | In-app pause switch; persistent notification with *Pause* and *Turn off* |

---

## How touch sensing works

The app needs to know *that* the screen was touched. Every mechanism that could take
ownership of an event is off the table, so this is the part that required the most care.

### Primary channel: a 1×1 px probe window

A **1×1 pixel**, non-focusable, non-modal overlay window with
`FLAG_WATCH_OUTSIDE_TOUCH`:

> Touches landing **outside** the window are reported to it as `ACTION_OUTSIDE`,
> **while the event continues to be delivered to the app underneath.**

The window type is `TYPE_ACCESSIBILITY_OVERLAY`. In AOSP
`WindowManagerPolicy.getWindowLayerFromTypeLw()` its layer is **31**, versus **24** for
`TYPE_NAVIGATION_BAR` — the probe sits above the navigation bar, so touches anywhere on
screen (including the very bottom gesture area) are observed.

Because the window is one pixel, even if some ROM decides that a touch inside it belongs
to this window, **the blast radius is exactly one pixel** — a provable, negligible upper bound.

### Fallback channel: accessibility touch interaction events

`TYPE_TOUCH_INTERACTION_START` / `END` are subscribed, but the AOSP
`AccessibilityWindowManager` comment is explicit:

> Callbacks from AccessibilityManagerService **when touch explorer turn on**
> and motion down detected.

They are only dispatched while touch exploration is enabled. HomeBar deliberately does
**not** enable it (that changes device-wide touch behaviour), so this channel normally
receives nothing and exists purely as a fallback for unusual ROMs.

### Why there is no "bottom-only" bounce

**Android forbids cross-app touch coordinates at the input dispatch layer.**
From AOSP `InputDispatcher.cpp`:

```cpp
// Check whether windows listening for outside touches are owned by the same UID.
// If the owner has a different UID, then we will not reveal coordinate information
// to this window.
if (targetWindow->getInfo()->ownerUid != foregroundWindowUid) {
    target.flags |= InputTarget::Flags::ZERO_COORDS;
}
```

And `InputTargetFlags.h` on `ZERO_COORDS`:

> will be zeroed out to avoid revealing information to an application. This is used
> in conjunction with FLAG_DISPATCH_AS_OUTSIDE **to prevent apps not sharing the same
> UID from watching all touches**.

| Where the touch lands | What the app receives |
|---|---|
| On HomeBar's own window | Real screen coordinates |
| On any other app or the launcher | Only "someone touched the screen"; coordinates forced to `(0, 0)` |

So outside its own surface, the app cannot know whether a finger is near the bottom.
The only way to obtain coordinates is to seize input events — the v1.0.0 failure mode.

Hence the design: **tap the bar to bounce** (v1.5.0), or opt into the older
"bounce on any touch" behaviour.

### Degradation

On ROMs that do not dispatch `ACTION_OUTSIDE` at all, the bar falls back to
**always visible, no bounce**. Fewer features, but it cannot interfere with the device.
The settings screen includes a live touch-signal self-test showing the primary channel,
coordinate disclosure status, and fallback channel.

---

## How UI suppression is decided

Two independent checks, OR'd together: either one collapses the bar.

### Home and recents

One question only: **which package owns the current foreground window?** The answer comes
from the `packageName` carried by `TYPE_WINDOW_STATE_CHANGED`, compared against the set
returned by `queryIntentActivities(ACTION_MAIN + CATEGORY_HOME)`.

- Match → collapse using the normal hide animation, identical to auto-hide
- No match → fade in
- Entering home has a **120 ms confirmation delay** to swallow intermediate window events,
  avoiding a show-then-hide flicker
- Leaving home takes effect **immediately**

Pixel Launcher's recents live inside the launcher, so this covers recents too.

### Notification shade and quick settings

The shade is a SystemUI `TYPE_SYSTEM` window, **not an Activity**, and often emits no
`TYPE_WINDOW_STATE_CHANGED`. Package name alone is insufficient, so the app inspects the
accessibility window list (`getWindows()`) with three rules — **any one** means expanded:

| Rule | Criterion | Why it holds |
|---|---|---|
| A — full-screen panel | `TYPE_SYSTEM` window, width ≥ 80 %, height ≥ 28 % | Status bar, nav bar, volume panel are all far smaller |
| B — focused panel | System window holding input focus (or being interacted with), width ≥ 55 %, height ≥ 18 % | Expanding panels take focus; status/nav bars are `FLAG_NOT_FOCUSABLE` and never can |
| C — top-edge wide panel | Top edge at screen top, width ≥ 90 %, height ≥ 15 % | Covers ROMs that report only the touch region during the pull animation |

Events alone are not enough, so v1.4.0 added three layers of fallback:

- **Polling** every 250 ms. Some ROMs dispatch no window event at all when you continue
  pulling from the top into control centre, or use one-handed pull-down.
- **Re-sampling during the animation** at 0/60/130/220/340/500/700 ms. The expand
  animation runs ~250–350 ms; a single sample on event arrival catches the first frame,
  before the panel is tall enough, and no further event follows.
- **Check before bouncing.** The pull-down gesture is itself a touch, so the state is
  re-evaluated before deciding to bounce — no "bounce, then retract".

Separately, `setServiceInfo()` re-applies the service flags inside `onServiceConnected()`:
accessibility configuration is read and cached **when the service is enabled**. Upgrading
the APK does not re-read it, so a user upgrading from an older build would have no
`flagRetrieveInteractiveWindows` in the cache and `getWindows()` would return empty forever.

### Deliberately avoided

| Approach | Why not |
|---|---|
| `UsageStatsManager` for the foreground app | Requires the *Usage access* permission, a manual trip to Settings |
| `canRetrieveWindowContent` + reading window content | Triggers the "this service can view screen content" warning; vastly larger privilege surface |
| Touch coordinates | Unavailable cross-app (AOSP `ZERO_COORDS`), and seizing events breaks the device |

Shade detection does call `getWindows()`, but only with `flagRetrieveInteractiveWindows` —
**not** `canRetrieveWindowContent`. It receives window metadata (type, bounds, layer) and
no content, and does not trigger the screen-content consent dialog.

**Degradation:** if `queryIntentActivities` returns empty (some ROMs restrict package
visibility), the home package set is empty, home detection is always false, and hide-on-home
silently does nothing. It never hides inside apps by mistake — inert is better than wrong.

While suppressed, touches do not wake the bar (`onTouchDown()` returns early), so it will
not pop up the moment you touch the launcher.

---

## Gesture guard (v1.5.0 → v1.6.0)

**Problem:** the finger-down of a swipe-up-to-home is also a touch. The bar appears, then
the home check kicks in and retracts it — a visible flash inside the gesture.

**Constraint:** at the moment of touch-down, a swipe and a tap are indistinguishable.
Coordinates are unavailable cross-app, and the only real signal — "the foreground changed
to the launcher" — arrives after the gesture animation, roughly 300 ms later.

So the wake itself is made cancellable:

```
touch DOWN ──► schedule wake in wakeDelayMs (do not show)
                    │
                    ├─ home detected meanwhile ──► cancel, bar never appeared
                    └─ timeout ─────────────────► fade in normally
```

- If the bar has already faded in slightly (progress < 0.45), a home detection **snaps it
  to zero** instead of fading out, leaving no trailing sliver
- Home detection is split into "cancel immediately" + "confirm suppression after 120 ms"
- `wakeDelayMs` defaults to 300 ms (the gesture animation timescale), adjustable 0–700 ms;
  setting it to 0 disables the feature

**Cost:** the bar appears `wakeDelayMs` later after a touch. Lower it for responsiveness,
raise it for cleanliness.

---

## Why system animations are untouched

| | Typical EdgeGesture-style app | HomeBar |
|---|---|---|
| Overlay layer | Participates in gesture detection / replaces nav bar | `TYPE_ACCESSIBILITY_OVERLAY` |
| Drawing window | Touchable, competes for input | **Permanently `FLAG_NOT_TOUCHABLE`** — the input system skips it |
| Tap sensing (v1.5.0) | Intercepts the whole bottom region | A separate transparent window **the size of the bar**, restored to non-touchable the moment the bar hides |
| Touch awareness | Obtained by interception | 1×1 px probe window receives a side-channel notification; events flow through unchanged |
| Other | Animation scale changes, injected gestures | None of it |

Back gestures, swipe-to-home, and predictive back are handled entirely by SystemUI / Launcher.

### The tap-sensing layer

"Bounce only when the bar is tapped" requires genuinely receiving the touch, but the drawing
window is larger than the bar (about 30 dp of headroom for bounce travel, slide-in, and glow).
Making that window touchable would turn 30 dp above the bar into a dead zone.

So it is split in two:

| Layer | Size | Touchable | Role |
|---|---|---|---|
| `BarView` (drawing) | Bar + margins, ~150 × 50 dp | **No** | Renders only, including bounce displacement |
| `BarTouchTarget` (sensing) | Bar + 10 dp horizontal / 7 dp vertical | Yes | Decides only whether the bar was hit |

Safety constraints:

- `FLAG_NOT_TOUCHABLE` is removed **only** while the bar is visible and unsuppressed;
  it goes back on the instant the bar starts fading out, so no invisible touch region is
  left behind at the bottom of the screen
- `FLAG_NOT_TOUCH_MODAL` — touches outside the window go to the app below
- `FLAG_NOT_FOCUSABLE` — no focus stealing, no IME interference
- Only "down, no movement beyond slop, up within 600 ms" counts as a tap; drags never do,
  so gesture navigation keeps priority
- Can be disabled in settings, returning to the structurally non-touchable form

---

## Permissions

- **Accessibility service** — required. Used to subscribe to touch interaction events, draw
  one non-touchable overlay, and run the 1×1 px probe.
- **`POST_NOTIFICATIONS`** — only for the persistent *Pause* / *Turn off* notification.
  Denying it does not affect core functionality.

Not requested: `SYSTEM_ALERT_WINDOW` (accessibility overlays are used instead), any input
injection capability, or any motion event source.

The manifest declares a `<queries>` element (`ACTION_MAIN` + `CATEGORY_HOME`). This is an
Android 11+ **package visibility declaration, not a permission**: without it
`queryIntentActivities` returns an empty list and home detection cannot work. It grants
sight of which launcher apps exist, nothing more.

---

## Requirements

| | |
|---|---|
| minSdk | 30 (Android 11) |
| targetSdk / compileSdk | 36 (Android 16) |
| JDK | 21 |
| AGP | 8.11.1 |
| UI | Jetpack Compose + Material 3 |

## Build

```bash
export JAVA_HOME=/path/to/jdk-21
./gradlew assembleDebug
# output: app/build/outputs/apk/debug/app-debug.apk
```

The first build needs network access for dependencies. `settings.gradle.kts` points at an
Aliyun Maven mirror; delete those repository entries if you can reach Google Maven directly.

`local.properties` is git-ignored — set `sdk.dir` to your local Android SDK.

---

## Project structure

```
app/src/main/java/com/homebar/
├── MainActivity.kt                     Entry point (includes notification permission request)
├── core/
│   ├── BarConfig.kt                    All tunables + presets
│   ├── BarPrefs.kt                     Persistence, pause switch, service state
│   ├── Easing.kt                       Easing library (Bézier / spring / back / bounce)
│   └── TouchDiagnostics.kt             Touch-signal self-test state (observe only)
├── overlay/
│   ├── BarView.kt                      Custom-drawn bar (appear/hide/bounce in one draw pass)
│   ├── BarOverlayController.kt         Window management + animation scheduling
│   ├── BarTouchTarget.kt               Tap-sensing window (v1.5.0)
│   ├── ScreenColorSampler.kt           Background sampling (single read + in-memory downsample)
│   └── TouchProbe.kt                   1×1 px touch probe window
├── service/
│   └── HomeBarAccessibilityService.kt  Service core, UI suppression, emergency notification
└── ui/
    ├── HomeBarApp.kt                   Settings screen (safety panel, touch self-test)
    ├── BarPreview.kt                   Live preview
    ├── Components.kt                   Shared controls + curve plot
    └── Theme.kt                        Theme
```

---

## Known limitations

1. **The system navigation bar is still there.** HomeBar does not touch it — doing so would
   mean changing system behaviour. To see only this bar, disable the system gesture hint in
   *Settings → System → Gestures*.
2. **No bottom-only bounce.** This is Android's cross-UID input privacy protection
   (`ZERO_COORDS`), not something an app can work around. On ROMs that dispatch no
   `ACTION_OUTSIDE` at all, the bar degrades to always-visible without bounce.
3. **Colour sampling uses accessibility screenshots.** Shorter intervals track faster but
   cost battery; default is 1200 ms, and sampling only runs while the bar is visible. The
   system itself rate-limits screenshots to roughly one per 333 ms.

---

## License

[MIT](LICENSE)
