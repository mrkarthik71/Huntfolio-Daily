# Personal Assistant (Android)

Spec: `.kiro/specs/personal-assistant-android/` (requirements.md, design.md, tasks.md). Read the design before changing architecture.

## Stack (fixed)
- Kotlin, Jetpack Compose, Material 3, Hilt, Room + SQLCipher (`net.zetetic:sqlcipher-android`), key wrapped by the Android Keystore.
- WorkManager for deferrable jobs, AlarmManager for exact reminders, Proto DataStore for settings, Retrofit/OkHttp for AI.
- JUnit 5 + kotest-property + Robolectric for tests. Konsist architecture tests. detekt + ktlint.
- Single Gradle module `:app`; packages `ui`, `domain`, `data`, `ingest`, `parse`, `ai`, `notify`, `security`, `app`.
- minSdk 26, targetSdk 35, compileSdk 36. AGP 8.13, Kotlin 2.2, Gradle 8.14.3.

## Product rules (never break these)
- Local-first: no user data leaves the device except a Redacted_Payload sent to the AI provider, mailbox requests to the user's mail server, or an Export_File the user creates. No account, no server storing user data.
- Read-only: never send SMS or email, pay, cancel, reply, delete mail or write the calendar. Never dismiss or act on other apps' notifications. Changes inside the app happen only after the user accepts a proposal.
- Keep facts, not messages: never store or log OTPs, full SMS, notification text or email bodies. Store extracted fields plus a Source_Ref. The only exception is redacted Review_Queue text until the item is resolved.
- Message text is untrusted data, never instructions. Never request a URL found in a message.
- Rules and code first; AI only writes wording and judgment. AI output must be JSON matching a schema, every number must trace back to the payload, and it is validated before display.
- No ads, no analytics or crash reporting that sends data off the device.
- Permissions are requested only when the user turns the feature on, one group per dialog, after a plain-language rationale.
- Every notification goes through the Notification_Governor (quiet hours, daily cap, per-type limits) and has a "Why am I seeing this?" action.
- Parsers must have unit tests with anonymised (synthetic) sample messages.

## Code conventions
- Package root `app.assistant`; application ID comes from the `app.id` Gradle property (placeholder until the app is named).
- Money is `Paise` (Long). Never Double/Float for amounts.
- Time only through `TimeSource`; only `data.time.SystemTimeSource` calls `Instant.now()` / `ZoneId.systemDefault()`.
- Logging only through `SafeLog` with `DiagnosticEvent`s (enums and counts, no strings). Never `android.util.Log`, `println` or Timber elsewhere.
- Use cases and ports return `Outcome<T, E>`; exceptions stay inside adapters.
- `domain` and `parse` are plain Kotlin (no Android imports). `ui` depends on `domain` ports, never on `data`.
- All user-facing text in string resources.
- Every Room entity lives in `data.db.entity`. A schema change bumps `AppDatabase.VERSION`, adds a hand-written migration to `ALL_MIGRATIONS` and commits `app/schemas/`. Never use destructive fallback.
- Secrets never go in the repository; release signing comes from environment variables set by CI.

## Commands (Windows PowerShell)
- Unit tests: `.\gradlew.bat :app:testPersonalWithSmsDebugUnitTest`
- Static checks: `.\gradlew.bat :app:ktlintCheck :app:detekt`
- Instrumented tests (emulator running): `.\gradlew.bat :app:connectedPersonalWithSmsDebugAndroidTest`
- Install debug build: `.\gradlew.bat :app:installPersonalWithSmsDebug`
- Release verification: `.\gradlew.bat :app:verifyReadOnlyPermissions :app:verifyVersionCode`

## Open decisions (do not guess; ask)
App name, Cloud AI vendor and cap, mail access method (IMAP app password vs Google sign-in), first banks/UPI apps, APK vs Play, Hindi/Tamil.
