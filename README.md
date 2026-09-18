# MarvSync

Standalone Android companion for beatXP Marv Aura.

## What this build does
- Direct BLE connection, no beatXP OTP/cloud login.
- JieLi RCSP device authentication.
- JieLi watch/health initialization.
- Phone time synchronization through JieLi RTC.
- Realtime heart rate, steps, distance, calories and SpO2.
- Attempts to sync the latest sleep files and displays total sleep duration when parsable.
- MTU-aware BLE packet splitting and a serialized write queue.
- Normal Android UI with connection and sync controls.

## Build
Open the repository in GitHub and run **Actions → Build MarvSync APK → Run workflow**. The generated APK is in the workflow artifact.

The app uses the Maven-published JieLi SDK components `jldecryption`, `jl_rcsp`, and `jl_watch`.
