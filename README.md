# ReelLimit ⏳🚫

**ReelLimit** is a productivity and digital wellbeing Android application built to break doomscrolling habits on addictive social media apps (Instagram, Facebook, and YouTube).

---

## 🎯 How It Works

* **Real-time App Detection**: Uses Android's `AccessibilityService` (`TYPE_WINDOW_STATE_CHANGED`) for event-driven, zero-polling foreground app monitoring that preserves battery life.
* **5-Minute Floating Alert**: When continuous usage of a monitored app reaches 5 minutes, ReelLimit draws a floating dialog over the active app (`SYSTEM_ALERT_WINDOW`) warning you that your limit has been reached, with an "OK" button.
* **6-Minute Auto-Exit**: If usage reaches 6 minutes (1 minute after the warning), the app safely executes `performGlobalAction(GLOBAL_ACTION_HOME)` to close the app and return you to the Android home screen.
* **Smart Reset**: If you close or switch away from the app before hitting the limit, the timer instantly resets with no disruption.
* **Quick Demo Mode**: An in-app switch lets you test the full warning and exit flow with a 10-second alert and 15-second auto-exit.

---

## 📱 Monitored Applications

* Instagram (`com.instagram.android`)
* Facebook (`com.facebook.katana` & `com.facebook.lite`)
* YouTube (`com.google.android.youtube`)
* TikTok (`com.zhiliaoapp.musically`)

---

## 🛠️ Tech Stack & Architecture

* **Language**: Kotlin 2.2
* **UI**: Jetpack Compose & Material 3
* **System APIs**:
  * `AccessibilityService` for foreground package observation & system home navigation
  * `WindowManager` for system application overlay rendering (`TYPE_APPLICATION_OVERLAY`)
* **Minimum SDK**: 24 (Android 7.0+)
* **Target SDK**: 36 (Android 15+)

---

## 🚀 Building & Installing

### Prerequisites
* Android SDK (API 34 or 36)
* JDK 17 or JDK 21

### Build APK
```bash
./gradlew assembleDebug
```
The output APK will be generated at:
`app/build/outputs/apk/debug/app-debug.apk`

### Install on Device
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 🔒 Permissions Required

1. **Display Over Other Apps** (`SYSTEM_ALERT_WINDOW`): Allows displaying the floating alert banner on top of social apps.
2. **Accessibility Service** (`BIND_ACCESSIBILITY_SERVICE`): Allows detecting when target apps are opened/closed and returning to the home screen when time expires.
