# Google Account Reset Automator

An Android automation tool that watches for a connection to a specific vehicle Bluetooth device
(or a dock event) and switches the Google app back to your primary account — fixing Gemini/Assistant
voice commands on Android Auto after they get silently switched to a secondary (e.g. Google Workspace)
account.

## Overview
On BT connect or dock, the app shows a notification offering to automatically switch the Google app
back to your configured primary account. The switch is driven by an `AccessibilityService` that taps
through the Google app's own account-switcher UI — no root, no ADB pairing, and no clearing of app data.

### Core Architecture
- **`MainActivity.kt`**: Compose-based frontend to handle runtime Bluetooth permissions, configure the
  target vehicle and primary account email via `SharedPreferences`, show whether the Accessibility
  Service is enabled (with a link to Settings, since Android has no runtime prompt for it), and provide
  manual diagnostic execution.
- **`AutomatorReceiver.kt`**: A manifest-registered broadcast receiver listening for
  `BluetoothDevice.ACTION_ACL_CONNECTED` and `Intent.ACTION_DOCK_EVENT`. It matches the connected device
  by Bluetooth MAC address against the saved target car, then shows a notification with explicit
  Switch / Open Google App / Dismiss choices.
- **`AccountSwitchAccessibilityService.kt`**: Launches the Google app and walks its accessibility node
  tree to open the account switcher and tap the row matching the saved primary email.

---

## Install

1. Download the APK from the [latest release](../../releases/latest).
2. Open it on your phone. Android will ask you to allow installs from that source (your browser or
   file manager) the first time.
3. Follow the one-time device setup below.

Requires Android 11 (API 30) or newer.

---

## Build & Environment Setup

### Prerequisites
1. **Android Studio** (Ladybug or newer recommended)
2. **Android SDK 36**
3. **Java Development Kit (JDK) 17**

### Step 1: Clone and Project Initialization
Open the project folder inside Android Studio. Ensure your project level `build.gradle.kts` configuration
aligns with standard Android Gradle Plugin (AGP) version dependencies.

### Step 2: One-Time Device Setup
After installing the app:
1. Set your primary account email and pick the target car from the paired Bluetooth device list.
2. Enable the service under **Settings → Accessibility → Google Account Reset Automator** — Android has
   no in-app permission dialog for this, it must be granted manually.
