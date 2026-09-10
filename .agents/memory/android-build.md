---
name: Android build setup
description: Non-obvious constraints for rebuilding the native Android app in this workspace.
---

The imported native Android source uses `module.toml` rather than a complete Gradle project, so local APK builds require a small Gradle wrapper configuration and a separately available Android SDK. The source also contains duplicate launcher-background color resources that must be reduced to one definition before standard Android resource merging succeeds.

**Why:** The Replit workspace does not provide Android SDK tooling by default, and AGP fails hard on duplicate resource names.

**How to apply:** Preserve the generated Gradle setup and use Android SDK platform/build-tools 34 for subsequent builds. CI must write `local.properties` from `ANDROID_HOME` before invoking Gradle. Keep the installable artifact debug-signed unless the user supplies a production signing workflow.