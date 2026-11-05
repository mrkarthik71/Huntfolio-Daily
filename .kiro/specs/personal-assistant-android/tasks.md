# Implementation Plan: personal-assistant-android

## Overview

The plan builds the Assistant_App in Kotlin, phase by phase, following the phase map in `requirements.md` and the architecture in `design.md`. Each phase ends with a checkpoint so it can be built, installed on the developer's phone and used before the next phase starts.

Within each phase, pure Kotlin code (value types, engines, parsers, policy functions) comes first, then Room persistence and Android adapters, then Compose UI and wiring. Every task builds on earlier tasks; nothing is left unconnected.

Conventions used by every task:

- Package root is the placeholder `app.assistant` and the application ID comes from the Gradle property `app.id` (placeholder `app.assistant.placeholder`), so the app name (Open decision 1) is a one-place change. Source paths below are relative to `app/src/main/kotlin/app/assistant/`, tests to `app/src/test/kotlin/app/assistant/`.
- No code outside `data.time` reads the clock or the default zone. No code outside `security.log` logs. Money is `Paise` (Long). These rules are enforced by the Konsist tests from task 5.3.
- Each phase that adds Room entities bumps the database version, adds a hand-written `Migration`, commits the exported schema in `app/schemas/`, and adds a case to the migration test from task 3.3.
- Tasks marked "BLOCKED on Open decision N" must not be guessed. The executor stops at that task and asks the user for the decision.

## Tasks

- [x] 1. Phase 0 Setup: toolchain check and project scaffolding
  - [x] 1.1 Check the toolchain and create the Gradle skeleton
    - Check before writing any build file: `java -version` reports JDK 17; `ANDROID_HOME` or `ANDROID_SDK_ROOT` is set and the SDK contains `platforms;android-35` and a recent `build-tools`; a system `gradle` exists to generate the wrapper (or a wrapper is already present). If anything is missing, stop and report exactly what to install and where; do not continue with a broken setup.
    - Create `settings.gradle.kts` (includes `:app` and the included build `build-logic`), `gradle.properties` (phase flags `phase.calendar`, `phase.money`, `phase.subscriptions`, `phase.ai`, `phase.email`, `phase.polish`, all `false` except the current phase, plus `app.id`), and the Gradle wrapper (`gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.properties`) pinned to one Gradle version
    - Create `gradle/libs.versions.toml` with current stable versions (look them up; mark any uncertain ones with a comment) for AGP, Kotlin, Compose BOM, Material 3, Hilt, Room, `net.zetetic:sqlcipher-android`, Proto DataStore, WorkManager, Glance, androidx.biometric, Retrofit, OkHttp, kotlinx.serialization, Tink, Bouncy Castle, Konsist, kotest-property, JUnit 5, Robolectric, detekt and ktlint
    - Create `build-logic/convention` with the convention plugins `assistant.android.application`, `assistant.android.room` (schema export to `app/schemas`), `assistant.android.hilt` and `assistant.jvm.library`
    - _Requirements: 10.1, 10.2_

  - [x] 1.2 Create the single `:app` module with flavours and build types
    - Apply the convention plugins; `minSdk 26`, `compileSdk` and `targetSdk 35` (done with `compileSdk 36`, because current AndroidX releases require it; `targetSdk` stays 35)
    - Flavour dimension `audience` with `personal` and `shared`; flavour dimension `sms` with `withSms` and `smsFree`. The `withSms` source set (`app/src/withSms/`) holds its own manifest with `READ_SMS` and `RECEIVE_SMS`; `smsFree` declares no SMS permission
    - Generate a `FeatureFlags` object per build from the `phase.*` Gradle properties, and `@bool/phase_*` resources for `android:enabled` on manifest components of each phase
    - Build types `debug` and `release`. Release uses R8 with `proguard-rules.pro` containing `-assumenosideeffects class android.util.Log { *; }`, and a signing config read from environment variables; a task wired before `assembleRelease` fails the build when any signing value is missing
    - Create only the packages that receive code in this task (`app`); later tasks create the other packages of the design's layout as they add code
    - _Requirements: 10.1, 10.4, 10.5, 67.2, 3.5_

  - [x] 1.3 Harden the manifest and add the app entry points
    - `android:allowBackup="false"` and a `dataExtractionRules` XML file that excludes every domain for both cloud backup and device transfer
    - `networkSecurityConfig` with `cleartextTrafficPermitted="false"`; every component `exported="false"` except the launcher activity
    - `AssistantApplication` annotated `@HiltAndroidApp`; single-activity `MainActivity` (Compose) that sets `FLAG_SECURE` in `onCreate`
    - `res/values/strings.xml` with the placeholder app name; all user-facing text goes into string resources from this task onward
    - _Requirements: 1.8, 6.5, 2.1, 72.3_

  - [x] 1.4 Set up the unit test toolchain
    - JUnit 5 platform for unit tests, JUnit Vintage engine with Robolectric, kotest-property, kotlinx-coroutines-test
    - `testing/PropertyDefaults.kt` with the project default `PropTestConfig(iterations = 100)` and a 1,000-iteration config for parser, dedupe and redactor properties
    - One smoke test proving `gradlew.bat testPersonalWithSmsDebugUnitTest` runs both a JUnit 5 test and a Robolectric test
    - _Requirements: 10.2_

- [x] 2. Phase 0 Setup: domain building blocks
  - [x] 2.1 Implement result types, time and dispatcher ports
    - `domain/error`: `Outcome`, `FieldErrors`, `ValidationError` as in the design
    - `domain/port`: `TimeSource` (with `zoneChanges`) and `DispatcherProvider`
    - `data/time/SystemTimeSource`: the only class that calls `Instant.now()` and `ZoneId.systemDefault()`; emits zone changes from `TIMEZONE_CHANGED` and `TIME_SET`
    - Hilt module binding both ports plus an `ApplicationScope` (`SupervisorJob`) qualifier; test fakes `FakeTimeSource` and a test `DispatcherProvider`
    - _Requirements: 19.4, 22.5_

  - [x] 2.2 Implement value types and money formatting
    - `domain/model`: `Paise` (with `MIN_VALID`, `MAX_VALID`, `isValid`, `addExact`), `SignedPaise`, `Quantity` (hundredths), `SourceRef`, typed IDs, `InstantRange` (half-open, touching ranges do not overlap), `LocalTimeRange` (`spansMidnight`), and the enums from the Data Models section (`DataSource` with prefixes, `NotificationType`, `TxnType`, `SourceKind`, `SubscriptionInterval`, and the rest)
    - `MoneyFormatter` with Indian grouping (`₹1,23,456.78`)
    - _Requirements: 11.1, 28.2, 33.3, 38.2, 23.3_

  - [x] 2.3 Write unit tests for value types
    - `Paise` bounds and overflow, `InstantRange.overlaps` for touching and nested ranges, `LocalTimeRange` across midnight, `MoneyFormatter` examples
    - _Requirements: 28.2, 23.3_

- [x] 3. Phase 0 Setup: logging, keys, encrypted store and settings
  - [x] 3.1 Implement SafeLog without content
    - `security/log`: `DiagnosticEvent` sealed classes whose fields are only enums, counts, durations, Bank_Format IDs and job names; `SafeLog` with no `String` logging method
    - Exceptions recorded as class name plus stack frames with the message removed
    - A sink interface (Room-backed sink added in task 3.3) and `RecordingLogSink` for tests
    - _Requirements: 3.5, 7.5, 9.2_

  - [x] 3.2 Implement the Key_Manager
    - `security/keys/KeyManager` as in the design: Keystore AES-256-GCM wrapping key (not auth-bound, StrongBox when available); 32-byte random database key and Source_Ref HMAC key wrapped into `noBackupFilesDir/db.key.enc` and `ref.key.enc`; a separate Keystore key for `encryptSecret` / `decryptSecret`; `KeyError` mapping; `deleteAll`
    - _Requirements: 1.2, 1.5, 45.1, 51.2_

  - [x] 3.3 Implement the Encrypted_Store and the startup unlock path
    - `data/db/AppDatabase` version 1 with the `audit_entry`, `secret` and `diagnostic_event` entities in `data/db/entity`, schema export on, `DatabaseFactory.open` with `SupportOpenHelperFactory(key)`, an `ALL_MIGRATIONS` list and no destructive fallback
    - A `MigrationTestHelper` test harness that migrates from every exported schema version (later phases add cases)
    - Startup sequence: get the key, open the database; on `KeyError` show the "stored data cannot be unlocked" screen with "Delete everything" (restore is added in task 37.4), leaving the database file untouched
    - Room-backed diagnostic ring buffer (last 1,000 events) behind the SafeLog sink
    - _Requirements: 1.1, 1.3, 1.4, 1.5, 65.6_

  - [x] 3.4 Write instrumented tests for the encrypted database
    - `androidTest`: opening the database without the key or with a wrong key fails and returns no records; a raw byte search of the database file finds no stored test text
    - Runs on an emulator only; if no emulator is available, the executor reports it instead of skipping silently
    - _Requirements: 1.3, 1.4, 64.2_

  - [x] 3.5 Implement the settings store
    - Proto DataStore `Settings` message with the Phase 0 and Phase 1 fields from the design (Lock_Timeout, notification times, Working_Hours, Quiet_Hours, Daily_Cap, Per_Type_Limits, Cap_Exempt_Types, enabled types, last day of week, Streak_Rule) and the proposed defaults table; later phases add their fields
    - `SettingsValidator` with each field's range; an invalid value keeps the previous value
    - _Requirements: 6.2, 18.1, 18.4, 18.8, 45.6_

  - [x] 3.6 Implement the network egress allow-list
    - `security/network`: OkHttp `EgressAllowListInterceptor` that rejects any host not in the configured set, and a single `OkHttpClient` factory used by every network adapter
    - _Requirements: 1.7, 4.2_

  - [x] 3.7 Write unit tests for the egress allow-list
    - Allowed host passes; unknown host, IP literal and host taken from message text are rejected
    - _Requirements: 1.7, 4.2_

- [x] 4. Phase 0 Setup: app lock, permissions, audit log and wipe
  - [x] 4.1 Implement the App_Lock
    - Pure `LockPolicy.requiresAuth(backgroundedAtElapsed, nowElapsed, lockTimeout)` using elapsed realtime
    - `security/lock/AppLock` with `StateFlow<LockState>`, `BiometricPrompt` authenticators by API level as in the design (verify the androidx.biometric matrix), `KeyguardManager` on API 26 and 27, and the "set a screen lock" message when no credential exists
    - Lock screen with retry; the navigation host shows only the lock screen while locked, and data ViewModels are created only after unlock
    - _Requirements: 6.1, 6.2, 6.3, 6.4_

  - [x] 4.2 Write property test for the lock timeout policy
    - **Property 7: Lock timeout policy**
    - **Validates: Requirements 6.2**

  - [x] 4.3 Implement the Permission_Manager
    - `PermissionManager` port and Android implementation in `ui/permission`; `Feature` enum mapped to one permission group and one rationale string each
    - Permission_Rationale screen with "Continue" and "Not now"; one group per system dialog; app-settings link when the dialog no longer shows; notification-access settings flow with a check on return; `POST_NOTIFICATIONS` on Android 13+ when the first Notification_Type is enabled
    - `revocations` flow checked at start, on foreground and before background reads; the dependent Data_Source is shown disconnected
    - _Requirements: 5.1, 5.2, 5.3, 5.4, 5.5, 5.6, 5.7, 5.8_

  - [x] 4.4 Write unit tests for the Permission_Manager
    - First launch requests nothing; rationale shown before the system dialog; "Not now" keeps the feature off; revoked permission turns the feature off
    - _Requirements: 5.1, 5.2, 5.5, 5.7_

  - [x] 4.5 Implement the Audit_Log
    - `data/audit`: insert-and-query-only DAO; `recordRead`, `recordAiSend` returning `Err` when the write fails, `markAiResult` inserting a linked result row (no updates)
    - Audit_Log screen reached from Settings, newest first with paging, showing every field including full payloads
    - _Requirements: 7.1, 7.2, 7.4, 7.5_

  - [ ] 4.6 Write property test for audit recording
    - **Property 8: Every read and every AI send is audited**
    - Covers reads now; task 29.3 adds the AI-attempt cases
    - **Validates: Requirements 7.1, 7.2**

  - [x] 4.7 Implement the Data_Wiper and source disconnect
    - `DataWiper.wipeAll` writes `wipe.pending` first, then deletes database files, keys, DataStore, caches, alarms, WorkManager work and widget state; `resumeIfPending` runs at launch before any screen
    - `SourceDisconnector.disconnect(source, deleteFacts)` stops work, deletes credentials and, when asked, deletes facts by Source_Ref prefix in one transaction
    - _Requirements: 8.2, 8.3, 8.5, 8.6, 8.7_

  - [ ] 4.8 Write property test for source disconnect
    - **Property 9: Disconnect deletes exactly that source's facts**
    - **Validates: Requirements 8.3**

  - [ ] 4.9 Write unit tests for the wipe
    - Cancelling the dialog deletes nothing; an interrupted wipe finishes at next launch before any screen other than the First_Run_Screen
    - _Requirements: 8.6, 8.7_

- [x] 5. Phase 0 Setup: theme, shell screens, architecture rules and CI
  - [x] 5.1 Implement the theme and the app shell
    - `ui/designsystem`: one Material 3 theme with light and dark colour schemes following the system setting
    - Navigation graph, First_Run_Screen, and Settings_Screen skeleton with the Data_Source list and status, installed version name and code, the Audit_Log entry and the "Delete everything" dialog wired to task 4.7 (the text states that Export_Files outside the app are kept)
    - _Requirements: 10.9, 8.1, 8.4, 8.8, 65.3_

  - [ ] 5.2 Write property test for theme contrast
    - **Property 84: Theme contrast**
    - **Validates: Requirements 63.4, 63.5**

  - [x] 5.3 Add architecture tests and static checks
    - `ArchitectureTest.kt` with Konsist: the layer table from the design, no `android.util.Log`/Timber outside `security.log`, no clock or default-zone calls outside `data.time`, no `Double`/`Float` for amount, paise or balance names in `domain`/`parse`, every `@Entity` in `data.db.entity`, and banned APIs (`SmsManager.send*`, calendar provider writes, `cancelNotification`, `PendingIntent.send` in readers)
    - detekt config with `ForbiddenImport` and ktlint wired into `check`
    - _Requirements: 2.1, 2.2, 3.5, 9.1, 9.2_

  - [x] 5.4 Write CLAUDE.md
    - Project rules from Requirements 1 to 4 and 9 and the fixed technical constraints from the requirements' introduction, plus the conventions in this plan's Overview
    - _Requirements: 10.8_

  - [x] 5.5 Implement release verification Gradle tasks
    - `verifyReadOnlyPermissions`: parses the merged manifest of every release variant and fails on `SEND_SMS`, `CALL_PHONE`, `WRITE_CALENDAR`, or any SMS permission in `smsFree`
    - `verifyVersionCode`: fails unless the version code is greater than the value in `release/last-version-code.txt` (updated only by the release job)
    - _Requirements: 2.1, 65.1, 65.4, 67.2_

  - [x] 5.6 Write unit tests for the verification tasks
    - Manifest checker rejects each forbidden permission; version check rejects equal and lower codes
    - _Requirements: 2.1, 65.4_

  - [x] 5.7 Create the CI workflows
    - `.github/workflows/ci.yml` on every push: gitleaks, ktlint, detekt, Android Lint, unit tests for `personalWithSmsDebug` and `sharedSmsFreeDebug`, `assembleDebug` for all variants, dependency vulnerability scan, and a check that `CLAUDE.md` exists; any failure fails the run
    - `.github/workflows/release.yml` on release tags: `verifyVersionCode`, signing secrets present check, `verifyReadOnlyPermissions`, `assembleRelease`, `apksigner verify`, artifact upload; signing files written to a temp path, masked and deleted at the end
    - _Requirements: 10.2, 10.3, 10.5, 10.6, 10.7, 64.5, 64.6, 65.4_

- [x] 6. Checkpoint: Phase 0 Setup complete
  - Ensure all tests pass, ask the user if questions arise.

- [x] 7. Phase 1 Core: goals and progress engine
  - [x] 7.1 Implement the Goal model, validator and periods
    - `domain/engine/goals`: `GoalInput`, `GoalValidator.validate`, `validateProgress`, `validateLink`; `Periods.instanceContaining` with the User-set last day of the week
    - _Requirements: 11.1, 11.2, 11.3, 11.7, 11.8_

  - [x] 7.2 Write property test for the goal link rule
    - **Property 11: Goal link rule**
    - **Validates: Requirements 11.3, 11.7**

  - [x] 7.3 Implement progress arithmetic
    - `ProgressMath.percent`, `daysBehind` (exact hundredths arithmetic with `Math.floorDiv`), `streak` with `StreakRule`
    - _Requirements: 17.1, 17.2, 17.3, 17.4_

  - [x] 7.4 Write property test for percentage and Days_Behind
    - **Property 12: Progress percentage and Days_Behind arithmetic**
    - **Validates: Requirements 17.1, 17.2**

  - [x] 7.5 Write property test for streaks
    - **Property 13: Streak matches a reference count**
    - **Validates: Requirements 17.3, 17.4**

  - [x] 7.6 Implement Goal persistence
    - Entities `goal`, `goal_progress`, `streak_day` with database version bump and migration case; `GoalRepository` with `activeGoals`, `save`, `link`, `logProgress`, `archive` (archived Goals accept no progress)
    - _Requirements: 11.1, 11.3, 11.4, 11.6, 11.9_

- [x] 8. Phase 1 Core: tasks and Quick_Add
  - [x] 8.1 Implement the Task model and validator
    - `TaskInput`, `TaskValidator.validate`, `normaliseQuickAdd` (line breaks to spaces, trim, 1 to 200)
    - _Requirements: 12.1, 12.2, 12.6, 13.2, 13.4, 13.5_

  - [x] 8.2 Write property test for validators
    - **Property 6: Validators accept exactly the valid region**
    - Starts with the Goal, progress, Task and Quick_Add validators; each later validator task adds its generator cases to this test
    - **Validates: Requirements 11.1, 11.2, 11.8, 12.1, 12.2, 12.6, 13.2, 13.4, 13.5**

  - [x] 8.3 Implement Task persistence
    - Entities `task`, `task_move` with migration case; `TaskRepository` with `openTasksFor`, `save`, `quickAdd`, `markDone` (adds Goal "tasks" progress in the same transaction), `move` (rejects past dates, records one Task_Move, removes the Task from old Priorities and Time_Blocks), `drop`, `delete`
    - _Requirements: 11.5, 12.1, 12.3, 12.4, 12.5, 12.7, 13.2_

  - [x] 8.4 Write property test for task completion, move and delete
    - **Property 14: Task completion, move and delete**
    - **Validates: Requirements 12.3, 12.4, 12.5, 12.7**

  - [x] 8.5 Write property test for goal progress per Period_Instance
    - **Property 10: Goal progress is kept per Period_Instance**
    - **Validates: Requirements 11.1, 11.4, 11.5, 11.6, 11.9**

- [x] 9. Phase 1 Core: plan builder, evening review and progress report
  - [x] 9.1 Implement the Plan_Builder engine
    - `domain/engine/plan`: `PlanInputs`, `DailyPlan`, `PlanBuilder.rank`, `busyIntervals`, `build` with the first-fit Time_Block algorithm from the design; inputs are sorted canonically so list order cannot change the result
    - _Requirements: 14.2, 14.3, 14.4, 14.5, 14.6, 14.10, 23.2, 23.3_

  - [x] 9.2 Write property test for Priority selection
    - **Property 15: Priority selection follows the ranking**
    - Compares with a reference comparator in the test source set
    - **Validates: Requirements 14.2, 12.3**

  - [x] 9.3 Write property test for Time_Block placement
    - **Property 16: Time_Block placement invariant**
    - **Validates: Requirements 14.3, 14.4, 14.10, 23.2, 23.3**

  - [x] 9.4 Write property test for plan determinism
    - **Property 17: Plan building is deterministic**
    - **Validates: Requirements 14.5**

  - [x] 9.5 Implement plan persistence and overrides
    - Entities `daily_plan`, `plan_priority`, `time_block`, `plan_override` with migration case; `PlanRepository.ensurePlanFor(date)` used by the morning brief job and the Today_Screen; overrides applied last on rebuild; removing a Priority deletes its Time_Block
    - _Requirements: 14.1, 14.6, 14.7, 14.9_

  - [x] 9.6 Write property test for plan contents and overrides
    - **Property 18: Plan contents and override persistence**
    - **Validates: Requirements 14.6, 14.7**

  - [x] 9.7 Implement the Evening_Review engine and repository
    - `ReviewDraft`, `ReviewChoice`, `ReviewEngine.summarise`; `ReviewRepository.finish` applies all choices in one transaction and writes nothing before finish
    - _Requirements: 16.1, 16.3, 16.4, 16.6, 16.7_

  - [x] 9.8 Write property test for evening review conservation
    - **Property 19: Evening review conservation and atomicity**
    - **Validates: Requirements 16.1, 16.3, 16.4, 16.6, 16.7**

  - [x] 9.9 Implement the weekly progress report
    - `ReportCalculator.weeklyProgress`; `report` entity with migration case; `ReportRepository.ensureReportsUpTo` creating missed reports on next run; content recalculated on display
    - _Requirements: 17.5, 17.6_

  - [x] 9.10 Write property test for reports
    - **Property 20: Reports are a function of data and period**
    - Covers the weekly progress report now; task 22.7 adds spending reports
    - **Validates: Requirements 17.5, 17.6, 35.4, 35.5**

- [x] 10. Phase 1 Core: notification governor and scheduler
  - [x] 10.1 Implement the Notification_Governor policy
    - `notify/governor`: `Candidate`, `GovernorState`, `GovernorSettings`, `Decision`, `NotificationPolicy.decide` following the decision flow in the design (per-type limit before exemption), `quietHoursEnd` for ranges over midnight
    - _Requirements: 18.2, 18.3, 18.4, 18.5, 18.8, 18.9, 18.10, 19.5, 24.6_

  - [x] 10.2 Write decision-table tests for the governor policy
    - Table-driven JUnit tests: one row per branch of the decision flow, including Quiet_Hours over midnight, cap reached, per-type limit with an exempt type, snooze repeat and stale items
    - _Requirements: 18.2, 18.3, 18.4, 18.5, 18.8, 18.9, 18.10_

  - [x] 10.3 Write property test for the governor policy
    - **Property 21: Notification_Governor matches the reference policy**
    - **Validates: Requirements 18.2, 18.3, 18.5, 18.9, 18.10, 19.5, 24.6**

  - [x] 10.4 Write property test for the daily cap
    - **Property 22: Daily cap and per-type limit invariant**
    - **Validates: Requirements 18.4, 18.8, 18.10**

  - [x] 10.5 Implement schedule time resolution
    - Pure functions: resolve `AtLocalTime(date, minute)` in a zone, keep `AtInstant` fixed, next occurrence of a changed brief or review time (today if not passed, else tomorrow), and staleness classification for items missed while the device was off
    - _Requirements: 19.4, 19.5, 20.7_

  - [x] 10.6 Write property test for rescheduling after time changes
    - **Property 23: Rescheduling after time and zone changes**
    - **Validates: Requirements 19.4, 20.7, 24.2**

  - [x] 10.7 Implement the Notification_Scheduler on Android
    - Entities `alarm_queue` and `notification_log` with migration case; `NotificationScheduler` with one alarm for the earliest row (`setExactAndAllowWhileIdle` with Exact_Alarm_Access, `setAndAllowWhileIdle` otherwise), `onAlarmFired` processing rows due within 30 s through the governor, snooze
    - Receivers for `BOOT_COMPLETED`, `TIME_SET`, `TIMEZONE_CHANGED`, `MY_PACKAGE_REPLACED` and the exact-alarm permission change, all calling `rescheduleAll`
    - One channel per Notification_Type; `VISIBILITY_PRIVATE` with a type-only public version; "Why am I seeing this?" action opening `WhyActivity` with the log row ID only; Inbox_List read from `notification_log`; immutable PendingIntents
    - _Requirements: 18.1, 18.6, 18.7, 18.9, 19.1, 19.2, 19.3, 19.4, 19.5, 65.5_

  - [x] 10.8 Write Robolectric tests for the scheduler and receivers
    - Boot and package-replaced reschedule, inexact fallback when exact alarms are denied, cancel-all on type switched off, alarm re-armed for the next row
    - _Requirements: 18.9, 19.1, 19.3, 65.5_

  - [x] 10.9 Implement morning brief, evening review and goal nudge
    - Pure content builders for the morning brief and goal nudge; morning brief job creates the Daily_Plan first; at most one goal nudge per day; `maintenance-daily` periodic worker (report catch-up, Audit_Log size check)
    - _Requirements: 14.1, 20.1, 20.3, 20.5, 20.6, 20.7_

  - [x] 10.10 Write property test for brief and nudge content
    - **Property 24: Morning brief and goal nudge content**
    - **Validates: Requirements 20.1, 20.5, 20.6**

- [x] 11. Phase 1 Core: screens
  - [x] 11.1 Build the Today_Screen and Quick_Add
    - ViewModel combining plan, tasks and goals into one `UiState`; Priorities in rank order, Time_Blocks with times, other open Tasks for today, Behind_Goals with Days_Behind, "no Priorities" message, Quick_Add field (200-character limit), mark done, date change refresh, template wording only
    - Opening from the morning brief notification lands here after App_Lock
    - _Requirements: 13.1, 13.3, 13.4, 13.5, 14.8, 14.9, 15.1, 15.2, 15.3, 15.4, 20.2_

  - [x] 11.2 Build the Goals and progress screens
    - Create, edit, link, archive and log progress with field errors; progress, target, percentage, Days_Behind and Streak, with status shown as text or icon as well as colour
    - _Requirements: 11.1, 11.2, 11.3, 11.7, 11.8, 17.1, 17.2, 17.3, 63.6_

  - [x] 11.3 Build the Evening_Review screen
    - One screen listing Tasks for the Review_Date with one-tap done, move to tomorrow and drop; draft in `SavedStateHandle`; summary after finish; empty-day message; opened from the notification with that date as Review_Date
    - _Requirements: 16.1, 16.2, 16.5, 16.7, 16.8, 20.4_

  - [x] 11.4 Build the notification settings and Inbox_List
    - Settings section for per-type switches, Quiet_Hours, Daily_Cap, Per_Type_Limits, Cap_Exempt_Types, brief and review times, Working_Hours, Lock_Timeout, last day of week, Streak_Rule, with the exact-alarm notice and link
    - Inbox_List screen and Why_Explanation screen with a control that turns the type off
    - _Requirements: 18.1, 18.7, 19.3, 20.7_

  - [x] 11.5 Write Compose UI tests for Phase 1 screens
    - Today states (empty, data), Quick_Add rejection of blank text, Evening_Review leave-without-finishing, Why screen turning a type off
    - _Requirements: 13.4, 15.4, 16.7, 18.7_

- [x] 12. Phase 1 Core: home-screen widget
  - [x] 12.1 Implement the Glance widget
    - `WidgetModel` projection with no money, Bill, Goal-progress or email fields; Glance widget with Priority titles and status, tick action calling `TaskRepository.markDone` without App_Lock, Quick_Add button opening the app (behind App_Lock, the proposed default), "no Priorities" message, empty state after wipe; `WidgetUpdater` observing repository flows plus `updatePeriodMillis` fallback
    - _Requirements: 8.8, 13.1, 21.1, 21.2, 21.3, 21.4, 21.6, 21.7_

  - [x] 12.2 Write tests for the widget
    - Konsist allow-list on `WidgetModel` fields; tick marks the Task done without unlock; wipe produces the empty state
    - _Requirements: 21.2, 21.4, 21.7_

- [x] 13. Checkpoint: Phase 1 Core complete
  - Ensure all tests pass, ask the user if questions arise.

- [x] 14. Phase 2 Calendar: calendar reading
  - [x] 14.1 Implement the calendar cache policy
    - `CalendarEventFact` model; `CalendarCachePolicy.window` (00:00 today to 24:00 on today + 7) and `keep` (overlap, not cancelled, not declined)
    - _Requirements: 22.3, 22.6_

  - [x] 14.2 Implement the Calendar_Reader adapter and repository
    - `CalendarSource` adapter over `CalendarContract.Instances` (timed as instants, all-day as dates, cancelled and declined filtered); entities `calendar_source`, `calendar_event` with migration case; `CalendarRepository.refresh` replacing the cache in one transaction and keeping it on failure; `lastSuccessfulRefresh`; Audit_Log read entry per refresh
    - `calendar-refresh` worker triggered by content-URI change, date change, zone change, selection change and foreground
    - Settings calendar list with display and account names, all selected at first, behind the calendar Permission_Rationale
    - _Requirements: 7.1, 22.1, 22.2, 22.3, 22.4, 22.5, 22.6, 22.8_

  - [x] 14.3 Write property test for the calendar cache
    - **Property 25: Calendar cache equals the filtered provider set**
    - **Validates: Requirements 22.2, 22.3, 22.4, 22.6**

  - [x] 14.4 Write property test for time-zone stability of events
    - **Property 26: Stored events are stable across time zones**
    - **Validates: Requirements 22.5, 23.4**

- [x] 15. Phase 2 Calendar: events in Today, free gaps and conflicts
  - [x] 15.1 Implement the Today timeline ordering and formatting
    - Pure `TodayTimeline` ordering by start on the current date (00:00 for earlier starts), then end, then title; date added to times on other dates; empty-title placeholder; next-event selection for the Widget
    - _Requirements: 15.1, 21.5, 23.1, 23.5_

  - [x] 15.2 Write property test for timeline ordering
    - **Property 27: Today timeline ordering and formatting**
    - **Validates: Requirements 15.1, 21.5, 23.1, 23.5**

  - [x] 15.3 Wire calendar events into the plan, Today and the Widget
    - Timed events feed `PlanInputs`; all-day list above timed items; "calendar not connected" state and last refresh time; Widget next event
    - _Requirements: 21.5, 22.7, 22.8, 23.1, 23.2, 23.4, 23.5_

  - [x] 15.4 Implement the Gap_Finder
    - `GapFinder.freeGaps`, `proposal` (greedy first fit in ranking order, no Task repeated), `place` (back to back from the later of gap start and now)
    - _Requirements: 25.1, 25.2, 25.3, 25.4_

  - [x] 15.5 Write property test for free gaps
    - **Property 29: Free_Gap finder matches a minute-grid model**
    - **Validates: Requirements 25.1**

  - [x] 15.6 Write property test for focus-block proposals
    - **Property 30: Focus_Block_Proposals are greedy, fit and do not repeat Tasks**
    - **Validates: Requirements 25.2, 25.3, 25.4, 25.5**

  - [x] 15.7 Implement the ConflictDetector
    - `ConflictDetector.conflicts` and `suggestMove` (earliest later gap on the date that can hold the block, else removal)
    - _Requirements: 23.6_

  - [x] 15.8 Write property test for conflict detection
    - **Property 31: Conflict detection and move suggestion**
    - **Validates: Requirements 23.6**

  - [x] 15.9 Build the focus-block and conflict UI
    - Focus_Block_Proposal cards with accept and dismiss, "gap is no longer free" re-check on accept, no repeat after dismiss unless the gap changes; conflicting Time_Block marker with move or remove, changing nothing until accepted; Min_Focus_Length and Event_Lead_Time settings
    - _Requirements: 23.6, 25.2, 25.4, 25.5, 25.6_

- [x] 16. Phase 2 Calendar: event reminders
  - [x] 16.1 Implement event reminder scheduling
    - Pure `EventReminderPlanner` diffing cached timed events against pending reminder rows (add, move, retitle, cancel; within 1 minute when start is closer than the lead time; none for all-day); called after each refresh, on lead-time change and when calendar reading is turned off
    - _Requirements: 24.1, 24.2, 24.3, 24.4, 24.5_

  - [x] 16.2 Write property test for event reminders
    - **Property 28: Event reminder set matches timed events**
    - **Validates: Requirements 24.1, 24.2, 24.3, 24.4, 24.5**

- [x] 17. Checkpoint: Phase 2 Calendar complete
  - Ensure all tests pass, ask the user if questions arise.

- [x] 18. Phase 3 Money: amount, date and template parsing
  - [x] 18.1 Implement the AmountParser
    - `parse/money/AmountParser.parse` to `Paise`: Currency_Markers before or after, "/-", Indian or international grouping, 0 to 2 decimals, Valid_Amount bounds
    - _Requirements: 28.2, 28.5_

  - [x] 18.2 Write property test for amount parsing
    - **Property 34: Amount parsing round trip**
    - **Validates: Requirements 28.2, 28.5**

  - [x] 18.3 Implement the DateTimeParser
    - Day-month-year order and separators per format, numeric and 3-letter months in any case, YY as 20YY, 24-hour and 12-hour times, missing parts from Received_Time, future-date check
    - _Requirements: 28.1, 28.5, 28.9_

  - [x] 18.4 Write property test for date parsing
    - **Property 35: Date and time parsing round trip**
    - **Validates: Requirements 28.1, 28.5, 28.9**

  - [x] 18.5 Implement the Parser_Registry
    - `BankFormat`, `TemplateToken`, JSON loading from `assets/parser_registry/*.json`, compilation to anchored regexes with named groups and possessive field patterns, OTP formats first, OTP keyword list
    - Ship synthetic sample formats for two placeholder banks and one placeholder UPI app covering debit, credit, bill, OTP, failed and reversal kinds; real bank formats are added once Open decision 4 is made
    - _Requirements: 26.4, 28.1, 28.3, 28.10, 28.11_

  - [x] 18.6 Implement the Message_Parser and Template_Formatter
    - `MessageParser.parse` returning `Parsed`, `Otp`, `FailedPayment` or `Unknown(reason)`; inputs over 2,000 characters go to `Unknown` without matching; `ParseError` carries field names and format IDs only
    - `TemplateFormatter.canHold` and `render` with `RenderStyle`
    - _Requirements: 28.1, 28.3, 28.4, 28.5, 28.6, 28.10, 28.11_

  - [x] 18.7 Write property test for the template round trip
    - **Property 36: Bank_Format template round trip**
    - **Validates: Requirements 28.1, 28.6, 28.7, 28.10**

  - [x] 18.8 Add parser fixtures and the fixture inventory test
    - `src/test/resources/fixtures/money/<bank>/<format-id>/NNN.json` with synthetic values: at least one per Bank_Format plus the coverage list of Requirement 28.8; parameterised fixture test; Prompt_Injection_Fixtures for SMS and app notifications
    - `FixtureInventoryTest` failing on missing fixtures per format, missing injection fixtures per source, or non-synthetic values
    - _Requirements: 4.5, 28.8, 64.1_

- [x] 19. Phase 3 Money: pure ingest rules
  - [x] 19.1 Implement sender matching and Source_Refs
    - `SenderMatcher.matches` with core header and prefix and suffix patterns; `assets/bank_senders.json` for the placeholder banks
    - `SourceRefFactory` producing `<prefix>:v1:<base32 of first 128 bits of HMAC-SHA256>` from the stable inputs in the design table, using the Key_Manager ref key
    - _Requirements: 26.1, 26.3, 26.4, 29.3_

  - [x] 19.2 Implement multipart join and history start date
    - Pure join of SMS parts by index; pure SMS_History_Import start date (same day 3 months earlier, clamped to month end)
    - _Requirements: 26.2, 26.7_

  - [x] 19.3 Write property test for multipart SMS join
    - **Property 33: Multipart SMS join**
    - **Validates: Requirements 26.7**

  - [x] 19.4 Write property test for the history import start date
    - **Property 32: SMS history import start date**
    - **Validates: Requirements 26.2**

  - [x] 19.5 Implement the Deduplicator
    - `ParsedFact`, `Deduplicator.cluster` (canonical order, matching rule, nearest then earlier, forced merges) and `resolve` (User edits, SMS over app, earlier Received_Time, lower Source_Ref, Reversal if any)
    - _Requirements: 29.1, 29.2, 29.4, 29.5, 29.6, 29.7, 32.3_

  - [x] 19.6 Write property test for dedupe matching
    - **Property 39: Dedupe matching follows the rule**
    - Compares with a reference matcher in the test source set
    - **Validates: Requirements 29.1, 29.2, 29.5, 29.7, 32.3**

  - [x] 19.7 Write property test for field resolution
    - **Property 42: Field resolution preserves User edits**
    - **Validates: Requirements 29.6, 32.1, 32.2**

  - [x] 19.8 Implement the Categoriser and the default Merchant list
    - `Categoriser.matchMerchant` (exact, trimmed, case-folded name or alias) and `categoryFor` (latest correction, default, "Uncategorised"); `assets/merchants.json` with common Indian merchants and VPAs, including Swiggy mapped to Food
    - _Requirements: 31.1, 31.2, 31.5, 31.7_

  - [x] 19.9 Implement the Redactor
    - Pure `Redactor.redact` replacing person names (context patterns and test-set names), phone numbers, account and card numbers including masked forms, UPI IDs, email addresses, links and Instruction_Pattern_List matches with typed placeholders, keeping amounts, dates and times; `assets/instruction_patterns.json`; Redactor_Test_Set fixtures in `fixtures/redactor/`
    - Needed now because Review_Queue items hold redacted text
    - _Requirements: 4.3, 30.1, 46.2, 46.3, 46.4_

  - [x] 19.10 Write property test for redaction coverage
    - **Property 61: Redactor removes every protected pattern**
    - **Validates: Requirements 4.3, 46.2, 46.3**

  - [x] 19.11 Write property test for redaction idempotence
    - **Property 62: Redactor is idempotent**
    - **Validates: Requirements 46.4**

- [x] 20. Phase 3 Money: persistence and the ingest pipeline
  - [x] 20.1 Add the money entities
    - Entities `parsed_fact`, `txn`, `forced_merge`, `tombstone`, `processed_source_ref`, `review_item`, `merchant`, `merchant_alias`, `category`, `category_correction`, `budget`, `bill`, `bill_proposal`, `bill_paid_proposal`, `sender_entry`, `money_app`, `quality_counter` with indexes from the design, migration case and seeding of built-in categories, merchants and senders
    - _Requirements: 1.1, 3.1, 65.6_

  - [x] 20.2 Implement the TransactionRepository
    - Dedupe persistence: load the candidate neighbourhood, recompute clusters, reconcile with stored Transactions (keep IDs, lower ID wins on collapse, newest edit wins per field); edits with the `edited_fields` bitmask; manual add with source "manual"; merge of same-type Transactions as forced merges; delete with tombstones; category change with correction record and the offer to apply to earlier Transactions except individually set ones
    - _Requirements: 29.1, 29.2, 29.6, 31.3, 31.4, 31.6, 32.1, 32.2, 32.3, 32.4, 32.5, 32.7, 32.8_

  - [x] 20.3 Write property test for merge
    - **Property 44: Merge keeps all Source_Refs and only same-type pairs merge**
    - **Validates: Requirements 32.4, 32.8**

  - [x] 20.4 Write property test for categorisation
    - **Property 45: Categoriser precedence and matching**
    - **Validates: Requirements 31.1, 31.2, 31.3, 31.6, 31.7**

  - [x] 20.5 Implement the ReviewQueueRepository
    - `add` (one item per Source_Ref, redacted text only), `resolve` through the Deduplicator with entered fields marked as User-edited, `dismiss` (deletes text, tombstones the ref), newest-first listing
    - _Requirements: 30.1, 30.2, 30.3, 30.4, 30.6, 30.8_

  - [x] 20.6 Implement the ingest pipeline
    - `PipelineStage`, `StageResult`, `IngestPipeline`, and the stages `SenderFilterStage`, `SourceRefStage`, `ProcessedRefStage` (processed and tombstone tables), `OtpStage`, `ParseStage`, `ReviewQueueStage`, `BillProposalStage`, `DedupeStage`, `CategoriseStage`, `PersistStage` with post-commit hooks (Bill paid matcher, Quality_Monitor; Subscription_Detector added in task 25.6)
    - Exceptions mapped to `IngestOutcome.ERROR` with a content-free DiagnosticEvent; processing continues with the next message; one Audit_Log read entry per batch
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 4.1, 7.1, 26.1, 26.3, 27.3, 27.6, 28.3, 28.4, 28.5, 28.11, 29.3_

  - [x] 20.7 Write property test for no Message_Text at rest
    - **Property 1: No Message_Text at rest or in logs**
    - **Validates: Requirements 3.1, 3.2, 3.5, 7.5**

  - [x] 20.8 Write property test for unlisted origins
    - **Property 2: Unlisted origins leave no trace**
    - **Validates: Requirements 3.3, 26.1, 26.3, 27.3, 27.6**

  - [x] 20.9 Write property test for OTP handling
    - **Property 3: OTP precedence and discard**
    - **Validates: Requirements 3.4, 28.3, 52.3**

  - [x] 20.10 Write property test for injected instructions
    - **Property 4: Injected instructions do not change outcomes**
    - **Validates: Requirements 4.1, 4.2, 4.4, 49.5, 54.7**

  - [x] 20.11 Write property test for unreadable messages
    - **Property 37: Unreadable messages go to the Review_Queue and processing continues**
    - **Validates: Requirements 28.4, 28.5, 30.1**

  - [x] 20.12 Write property test for failed payments
    - **Property 38: Failed payments change nothing**
    - **Validates: Requirements 28.11**

  - [x] 20.13 Write property test for ingest idempotence
    - **Property 40: Ingest is idempotent**
    - **Validates: Requirements 29.3**

  - [x] 20.14 Write property test for ingest order independence
    - **Property 41: Ingest is order-independent**
    - **Validates: Requirements 29.4**

  - [x] 20.15 Write property test for review items and tombstones
    - **Property 43: One review item per Source_Ref; tombstones are final**
    - **Validates: Requirements 30.1, 30.2, 30.4, 32.7**

- [ ] 21. Phase 3 Money: SMS and notification readers
  - [ ] 21.1 Implement the Sms_Reader (withSms source set only)
    - `SmsSource` adapter in `app/src/withSms/`: `SMS_RECEIVED` receiver using `goAsync` and `ApplicationScope`, multipart join, inbox query since a time; `sms-catchup` worker on process start and boot; `sms-history-import` foreground worker (`dataSync`) with the import summary; WorkManager data carries IDs and enum names only
    - _Requirements: 26.1, 26.2, 26.5, 26.6, 26.7_

  - [ ] 21.2 Implement the Notification_Reader
    - `NotificationListenerService` reading only title and text extras of Money_App_List apps, never cancelling or firing actions; `activeNow` on listener reconnect; removal from the list stops reading and keeps Transactions
    - _Requirements: 2.3, 27.1, 27.3, 27.5, 27.6, 27.7_

  - [ ] 21.3 Write Robolectric tests for the readers
    - Unlisted sender and app are ignored; no Message_Text in WorkManager data or Intents; listener leaves notifications untouched
    - _Requirements: 2.3, 3.2, 26.3, 27.3_

- [x] 22. Phase 3 Money: budgets, bills, reports and quality
  - [x] 22.1 Implement the TransactionValidator and the Budget_Engine
    - `TransactionValidator.validate`; `BudgetEngine.spent` and `status`; Budget save with Valid_Amount check; add both validators to the Property 6 test
    - _Requirements: 32.6, 33.1, 33.2, 33.3, 33.4, 33.5, 33.6, 33.7_

  - [x] 22.2 Write property test for budget arithmetic
    - **Property 46: Budget arithmetic**
    - **Validates: Requirements 32.5, 33.2, 33.3, 33.4, 33.5, 33.7**

  - [x] 22.3 Implement the Due_Date_Rule and the Bill_Tracker
    - Shared pure `DueDateRule.next` (used again by subscriptions); `BillRules.nextDue` and `paidCandidate`; Bill validator added to the Property 6 test
    - Bill repository: save, proposals from parsed bills (no duplicates), paid proposals once per pair, next occurrence on paid, bill-due notifications at the morning brief time with cancel on paid or delete; unpaid Bills due in the next 2 days fed into `PlanInputs`
    - _Requirements: 14.6, 34.1, 34.2, 34.3, 34.4, 34.5, 34.6, 34.7, 34.8_

  - [x] 22.4 Write property test for the Due_Date_Rule
    - **Property 48: Due_Date_Rule**
    - **Validates: Requirements 34.4, 37.1, 38.4, 40.6**

  - [x] 22.5 Write property test for bill matching and notifications
    - **Property 47: Bill matching, proposals and due notifications**
    - **Validates: Requirements 34.3, 34.5, 34.8**

  - [x] 22.6 Write property test for proposals
    - **Property 5: Proposals change nothing until accepted**
    - Covers Bill, Bill paid, Review_Queue and conflict-move proposals; tasks 25.6 and 30.2 add Subscription and plan proposals
    - **Validates: Requirements 2.4, 2.5, 23.6, 38.5, 48.7, 50.5**

  - [x] 22.7 Implement spending reports
    - `ReportCalculator.monthlySpending` and `weeklySpending` (top 5, "Other", month-on-month change, separate Reversal line); catch-up in `ensureReportsUpTo`; add spending cases to the Property 20 test
    - _Requirements: 35.1, 35.2, 35.3, 35.4, 35.5, 35.6_

  - [x] 22.8 Write property test for spending report arithmetic
    - **Property 49: Spending report arithmetic**
    - **Validates: Requirements 35.1, 35.2, 35.3, 35.6**

  - [x] 22.9 Implement the money Quality_Monitor
    - `QualityMonitor` with counters per origin and day (counts only), corrections once per message attributed to the original Received_Time day, `QualityMath.accuracy` returning null for "no data"
    - _Requirements: 36.1, 36.2, 36.3, 36.4_

  - [x] 22.10 Write property test for parse accuracy
    - **Property 50: Parse accuracy metric**
    - **Validates: Requirements 36.1, 36.2**

- [x] 23. Phase 3 Money: screens
  - [x] 23.1 Build the Money_Screen and editors
    - Transactions list, edit, manual add, merge and delete with confirmation; Budgets with spent, remaining, percentage and over-budget text; Bills with proposals; Review_Queue count, list and save form; category change with the apply-to-earlier offer; spending report screens
    - _Requirements: 30.3, 30.6, 30.7, 30.8, 31.4, 32.1, 32.3, 32.4, 32.5, 32.6, 32.8, 33.5, 33.7, 34.1, 34.2, 34.6, 35.1, 35.2_

  - [x] 23.2 Build the money settings
    - SMS reading enable with rationale and one-time history import offer and summary; Bank_Sender_List additions; installed-app list for the Money_App_List; notification access flow; parse accuracy per origin
    - _Requirements: 5.4, 26.2, 26.5, 27.2, 27.5, 36.2_

  - [x] 23.3 Write Compose UI tests for money screens
    - Review_Queue save with an invalid field keeps the item; merge of debit and credit is rejected; over-budget text shown
    - _Requirements: 30.6, 32.8, 33.7_

- [ ] 24. Checkpoint: Phase 3 Money complete
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 25. Phase 4 Subscriptions: detection and suggestions
  - [ ] 25.1 Implement the Subscription_Detector engine
    - `SubscriptionDetector.detect` (longest chain per Merchant and interval using `DueDateRule`, Amount_Tolerance, Min_Occurrences, skip when any Subscription exists), `yearlyCost` with `Math.multiplyExact`, `matchCharge`, `missedCharges`; `DetectionConfig` settings with defaults
    - _Requirements: 37.1, 37.3, 37.5, 38.2, 38.4, 38.5, 38.7, 40.5_

  - [ ] 25.2 Write property test for detection
    - **Property 51: Subscription detection is sound, idempotent and remembers rejections**
    - **Validates: Requirements 37.1, 37.3, 37.5**

  - [ ] 25.3 Write property test for yearly cost
    - **Property 52: Yearly cost and total**
    - **Validates: Requirements 38.2, 38.3**

  - [ ] 25.4 Implement the Suggestion_Manager for rule-based Suggestions
    - `suggestion` entity with migration case; `ProposedChange` sealed interface; `SuggestionManager.visible`, `accept` (applies exactly the stored change), `dismiss`, `expire` (ignored after 7 days, run by `maintenance-daily`); accept-rate math per group with "no data"
    - _Requirements: 2.4, 2.5, 50.1, 50.2, 50.3, 50.4, 50.5, 50.6_

  - [ ] 25.5 Write property test for Suggestion expiry and accept rate
    - **Property 68: Suggestion accept rate and expiry**
    - **Validates: Requirements 50.3, 50.4, 50.6**

  - [ ] 25.6 Implement Subscription persistence and lifecycle
    - Entities `subscription`, `subscription_charge`, `usage_check` with migration case; detector run as a pipeline post-commit hook and on Transaction edit, merge and delete; confirm, reject, manual save with validator (added to the Property 6 test); charge matching, price-change Suggestion, missed-charge check in `maintenance-daily`, mark cancelled (no external request), charge-after-cancellation Suggestion and reactivation; Quality_Monitor proposal counts; add Subscription proposals to the Property 5 test
    - _Requirements: 37.1, 37.2, 37.3, 37.4, 37.6, 37.7, 38.4, 38.5, 38.6, 38.7, 40.3, 40.5, 40.6_

  - [ ] 25.7 Write property test for the charge lifecycle
    - **Property 53: Charge matching lifecycle**
    - **Validates: Requirements 38.4, 38.5, 38.6, 38.7, 40.5**

- [ ] 26. Phase 4 Subscriptions: usage checks, renewal alerts and screen
  - [ ] 26.1 Implement usage checks and keep-or-cancel suggestions
    - `UsageCheckRules.isDue` and `afterAnswer`; due marking in `maintenance-daily`; one usage-check notification at the evening review time; answer storage; review Suggestion at 2 consecutive "No" with yearly saving; resets on "Yes" and after the Suggestion; removal when ended
    - _Requirements: 39.1, 39.2, 39.3, 39.5, 39.6, 40.1, 40.2, 40.4_

  - [ ] 26.2 Write property test for usage checks
    - **Property 54: Usage_Check due dates and "No" counting**
    - **Validates: Requirements 39.1, 39.3, 39.5, 39.6, 40.1, 40.2, 40.4**

  - [ ] 26.3 Implement renewal alerts
    - `AtLocalTime(nextDue − Renewal_Lead_Time, morning brief time)` rows keyed by Subscription and due date; reschedule on lead time, due date, amount or status change; late scheduling rule; Renewal_Lead_Time setting (1 or 2, default 2)
    - _Requirements: 41.1, 41.2, 41.3, 41.4, 41.5_

  - [ ] 26.4 Write property test for renewal alerts
    - **Property 55: Renewal alerts**
    - **Validates: Requirements 41.1, 41.3, 41.4, 41.5**

  - [ ] 26.5 Build the Subscriptions_Screen
    - Proposed and active lists ordered by next due date, yearly cost and total, confirm and reject, manual add form, usage-check screen with one-tap answers, Suggestion cards with text, reason, accept and dismiss
    - _Requirements: 37.1, 37.2, 37.4, 37.7, 38.1, 38.3, 39.4, 50.1_

  - [ ] 26.6 Write Compose UI tests for the Subscriptions_Screen
    - Total shows ₹0 with no active Subscription; usage checks answered with one tap; manual form rejects a past due date
    - _Requirements: 37.7, 38.3, 39.4_

- [ ] 27. Checkpoint: Phase 4 Subscriptions complete
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 28. Phase 5 AI advice: contracts, validation and payloads
  - [ ] 28.1 Define the AI ports and the None mode
    - `AiMode`, `PromptType`, `AiProvider`, `ProviderRequest`, `AiResult`, `RedactedPayload` (constructible only through the Redactor path), `NoneProvider`; `FakeAiProvider` with scripted responses and two test vendor implementations in the test source set; AI_Mode None on first run
    - _Requirements: 42.1, 42.4, 42.5_

  - [ ] 28.2 Implement Number_Tokens and Derivation_Rules
    - `NumberTokens.extract` (groupings, decimals, ₹/Rs/%, times, dates, English number words); `DerivationRules` per Prompt_Type from the design
    - _Requirements: 47.3_

  - [ ] 28.3 Write property test for number provenance
    - **Property 64: Number provenance**
    - **Validates: Requirements 47.3, 64.3**

  - [ ] 28.4 Implement output schemas, the Output_Validator and the Output_Serializer
    - `kotlinx.serialization` classes per Prompt_Type decoded strictly (single object, no unknown keys, max lengths, at most 3 Suggestions with reasons, mail score 0 to 10 and category set); handle membership and name checks; daily plan placement checks against payload Free_Gaps; Restricted_Advice_List asset and contact-pattern rejection reusing Redactor patterns; `OutputSerializer.toJson`
    - _Requirements: 47.1, 47.2, 47.4, 47.5, 47.6, 47.7, 47.8, 47.9, 47.10, 48.6, 54.6_

  - [ ] 28.5 Write property test for serialisation round trip and strict parsing
    - **Property 63: Output serialisation round trip and strict parsing**
    - **Validates: Requirements 47.1, 47.2, 47.5, 47.6, 47.7, 54.6**

  - [ ] 28.6 Write property test for restricted advice and contacts
    - **Property 65: Restricted advice and contact details are rejected**
    - **Validates: Requirements 47.4, 47.9**

  - [ ] 28.7 Write property test for payload item references
    - **Property 66: Outputs refer only to payload items and fit the Free_Gaps**
    - **Validates: Requirements 47.10, 48.6**

  - [ ] 28.8 Implement the Summary_Builder
    - One fixed schema per Prompt_Type with opaque handles (`t1`, `g2`, `gap3`); every value passed through the Redactor; Message_Text only in the data field of the SMS fallback and mail classifier types; payloads over 4,000 characters or with a Redactor error are skipped
    - _Requirements: 36.3, 46.1, 46.5, 46.6, 46.7_

  - [ ] 28.9 Write property test for payload schemas
    - **Property 60: Payloads follow their schema and size limit**
    - **Validates: Requirements 46.1, 46.5, 46.7, 49.1**

- [ ] 29. Phase 5 AI advice: gateway and providers
  - [ ] 29.1 Implement the cap governor
    - `ai_usage` entity with migration case; `CapGovernor.tryReserve`, `record` (every attempt, including failures), `monthRollover`; switch to None and back; AI_Monthly_Cap validator added to the Property 6 test
    - _Requirements: 45.3, 45.4, 45.6, 45.7_

  - [ ] 29.2 Write property test for the monthly cap
    - **Property 58: Cloud usage never exceeds the cap**
    - **Validates: Requirements 45.3, 45.4, 45.7**

  - [ ] 29.3 Implement the AiGateway
    - Mode epoch; None, offline and breaker short-circuits (zero attempts, no usage); per attempt: cap reservation, Audit_Log `recordAiSend` before send (no send on failure), 30-second timeout, validation; up to 3 attempts with the same payload; epoch check on result; circuit breaker (3 failures, 30 minutes); add AI-attempt cases to the Property 8 test
    - _Requirements: 7.2, 7.3, 42.2, 42.7, 43.1, 43.2, 43.3, 43.4, 43.5, 46.6_

  - [ ] 29.4 Write property test for AI_Mode changes
    - **Property 56: AI_Mode changes and the None mode**
    - **Validates: Requirements 42.2, 42.7, 43.1**

  - [ ] 29.5 Write property test for retry and fallback
    - **Property 57: Retry and fallback**
    - **Validates: Requirements 43.2, 43.3, 43.4, 47.8**

  - [ ] 29.6 Implement the Cloud adapter scaffold
    - Vendor-neutral Cloud adapter base on Retrofit, OkHttp (egress allow-list) and kotlinx.serialization; per-Prompt_Type model setting with one default model; `personal` flavour reads the Cloud AI key from the `secret` table via the Key_Manager; `shared` flavour sends through a `ProxyClient` interface and contains no key; Cloud mode refused until a cap (and, in `personal`, a key) is set
    - _Requirements: 42.3, 42.6, 45.1, 45.2, 45.5_

  - [ ] 29.7 Write property test for secrets
    - **Property 59: Secrets never reach a sink**
    - Covers the Cloud AI key now; task 34.1 adds mailbox credentials
    - **Validates: Requirements 45.5, 51.2**

  - [ ] 29.8 Implement the real Cloud vendor adapter (BLOCKED on Open decision 2)
    - Ask the user which vendor and cap to use before starting; implement the vendor request and response mapping behind `AiProvider`, add the vendor host to the egress allow-list, and run the existing contract tests against it with recorded responses
    - _Requirements: 42.3, 42.4_

  - [ ] 29.9 Implement the OnDevice adapter
    - Availability check (proposed: Android 14+, 8 GB RAM, model present; verify the on-device runtime API before coding); unavailable reasons named in Settings; failure never falls back to Cloud
    - _Requirements: 44.1, 44.2, 44.3, 44.4_

  - [ ] 29.10 Write unit tests for the OnDevice adapter
    - A recording OkHttp client sees zero requests in OnDevice mode; model failure gives a failed request with no Cloud attempt
    - _Requirements: 44.3, 44.4_

- [ ] 30. Phase 5 AI advice: AI features
  - [ ] 30.1 Add versioned prompt files and offline prompt tests
    - `assets/prompts/<type>/v1.md` per Prompt_Type with the schema version in the header; tests feed fixed payloads to `FakeAiProvider` recorded outputs and validate them, with no network or key
    - _Requirements: 48.5_

  - [ ] 30.2 Wire AI wording into plans and reviews
    - Daily plan, evening review, weekly review and monthly money review requests after the Rule_Based_Result is shown; accepted wording replaces template text; plan order or placement changes become Suggestions; "AI wording unavailable" indication; AI settings section (mode, cap, key in `personal`, models, on-device status, cap-reached message with resume date); add plan proposals to the Property 5 test
    - _Requirements: 42.6, 43.3, 43.4, 44.1, 44.2, 45.4, 45.6, 48.1, 48.2, 48.3, 48.4, 48.7_

  - [ ] 30.3 Implement the SMS fallback parser
    - Off by default; one request per new Review_Queue item tracked by `fallback_attempted`; accepted proposal shown for confirmation; failure or an amount not in the redacted text leaves no proposal and is never retried; accept rate per Prompt_Type shown in Quality settings
    - _Requirements: 30.5, 49.1, 49.2, 49.3, 49.4, 49.5, 50.3_

  - [ ] 30.4 Write property test for SMS fallback limits
    - **Property 67: SMS fallback parser limits**
    - **Validates: Requirements 49.1, 49.3, 49.4**

  - [ ] 30.5 Write integration tests for AI fallback in features
    - With `FakeAiProvider`: offline Cloud shows the Rule_Based_Result with the indication; cap reached shows the resume date; injection fixtures through the fallback parser produce at most one proposal
    - _Requirements: 43.5, 45.4, 49.5_

- [ ] 31. Checkpoint: Phase 5 AI advice complete
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 32. Phase 6 Email: mail triage rules
  - [ ] 32.1 Implement the mail domain and triage rules
    - `MailHeader`, `MailConnector` port, `MailError`, `MailBatch`, `MailCursor`; `MailTriage.isOtp`, `shortlisted`, `ruleScore`, `applyPenalty`; Shortlist_Rules validator added to the Property 6 test; `assets/otp_mail_patterns.json`
    - _Requirements: 52.3, 53.1, 53.3, 53.4, 53.5, 54.2, 55.5_

  - [ ] 32.2 Write property test for shortlist matching
    - **Property 71: Shortlist matching and AI exposure**
    - **Validates: Requirements 53.1, 53.3, 53.5, 54.4**

  - [ ] 32.3 Implement the SnippetExtractor
    - Plain-text part first, else visible HTML text without markup, scripts or styles; declared charset decoding with replacement marker fallback; Snippet_Length limit
    - _Requirements: 52.2, 52.5, 52.6_

  - [ ] 32.4 Write property test for snippet extraction
    - **Property 70: Snippet extraction**
    - **Validates: Requirements 52.2, 52.5, 52.6**

- [ ] 33. Phase 6 Email: mail sync, alerts and digest
  - [ ] 33.1 Implement the mail sync loop
    - Entities `inbox_item`, `shortlist_rule`, `sender_penalty`, `mail_cursor` with migration case; sync loop over the `MailConnector` port (tested with a fake connector): first sync covers 7 days, at most 200 emails oldest first, OTP discard, shortlist, mail classifier through `AiGateway` with Rule_Score fallback, Sender_Penalty, one Inbox_Item per Source_Ref with no snippet or links, item and cursor committed together, Audit_Log read entry
    - Important-mail notification at or above the Importance_Threshold with "Not important" action; Wrong_Mail_Alert counted once; penalty +2
    - Add email cases to the Property 1, 3 and 4 tests
    - _Requirements: 3.1, 7.1, 51.7, 52.1, 52.3, 52.4, 54.1, 54.3, 54.4, 54.5, 54.7, 55.1, 55.2, 55.3, 55.5, 55.6_

  - [ ] 33.2 Write property test for sync batching and resume
    - **Property 69: Mail sync batching and resume**
    - **Validates: Requirements 51.7, 52.4**

  - [ ] 33.3 Write property test for rule scores and unique Inbox_Items
    - **Property 72: Rule scores are deterministic and Inbox_Items are unique**
    - **Validates: Requirements 54.2, 54.3**

  - [ ] 33.4 Write property test for importance scoring
    - **Property 73: Importance score, penalty and threshold**
    - **Validates: Requirements 55.1, 55.5, 55.6**

  - [ ] 33.5 Implement mail quality metrics
    - Weekly Wrong_Mail_Alert and notification totals for 8 weeks, daily counts for 7 days with the average, 7-day window of fully connected days
    - _Requirements: 55.4, 58.1, 58.2, 58.3_

  - [ ] 33.6 Write property test for wrong-alert counting
    - **Property 74: Wrong-alert counting**
    - **Validates: Requirements 55.3, 55.4, 58.2, 58.3**

  - [ ] 33.7 Implement the Mail_Digest
    - `DigestBuilder.build` (category order, newest first); digest notification at the digest time, skipped when empty and connected, with the sync-stopped line when reconnection is needed
    - _Requirements: 56.1, 56.2, 56.3, 56.4_

  - [ ] 33.8 Write property test for the digest
    - **Property 75: Mail digest**
    - **Validates: Requirements 56.1, 56.2, 56.3, 56.4**

  - [ ] 33.9 Implement form prefill from Inbox_Items
    - Pure mapping from an Inbox_Item to Task form and Bill form initial values (valid dates and positive amounts only); Task and Bill form ViewModels accept prefill and create nothing on cancel
    - _Requirements: 57.1, 57.2, 57.3_

  - [ ] 33.10 Write property test for prefilled forms
    - **Property 76: Forms prefilled from mail**
    - **Validates: Requirements 57.1, 57.2**

- [ ] 34. Phase 6 Email: mail connector and screens
  - [ ] 34.1 Implement the Mail_Connector adapter (BLOCKED on Open decision 3)
    - Ask the user whether to use IMAP with an app password or Google sign-in before starting. IMAP: `EXAMINE`, `ENVELOPE`, `FLAGS`, `BODY.PEEK[TEXT]<0.N>`, cursor of folder, UIDVALIDITY and last UID (check library size first). Google: Gmail API read-only scope with metadata and snippet
    - Connection_Test with a 30-second timeout; credentials stored through the Key_Manager only after success; rejected credentials set needs reconnection and stop retries; mail host added to the egress allow-list; add mailbox credentials to the Property 59 test
    - _Requirements: 51.1, 51.2, 51.3, 51.4, 51.5, 51.6_

  - [ ] 34.2 Write contract tests for the Mail_Connector
    - Same assertions against a fake server for the chosen adapter: read-only commands only, same `MailHeader` fields, timeout and rejection mapping
    - _Requirements: 51.1, 51.3, 51.5, 51.6_

  - [ ] 34.3 Build the mail screens and the sync worker
    - `mail-sync` periodic worker with network constraint at Mail_Sync_Interval; mail settings (connect, folders, interval, Snippet_Length, Importance_Threshold, digest time, Shortlist_Rules editor with limits and the empty-rules notice, Mailbox_Status); Inbox and digest screens with "Create task" and "Create bill" (bill category only) actions
    - _Requirements: 51.4, 52.1, 52.2, 53.2, 53.4, 53.5, 56.3, 57.1, 57.2, 57.4_

- [ ] 35. Checkpoint: Phase 6 Email complete
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 36. Phase 7 Career import and Polish: Huntfolio import
  - [ ] 36.1 Implement the Huntfolio parser and formatter
    - Proposed JSON format with Huntfolio_Format_Version and Huntfolio_Record_Keys (to be agreed with Huntfolio); 5 MB check before reading; streaming parse; file-level errors and first-invalid-record error; `HuntfolioFormatter.format`
    - _Requirements: 59.2, 59.6, 59.7, 59.8_

  - [ ] 36.2 Write property test for the Huntfolio round trip
    - **Property 77: Huntfolio round trip**
    - **Validates: Requirements 59.6, 59.7**

  - [ ] 36.3 Write property test for invalid records
    - **Property 79: Huntfolio validation names the first invalid record**
    - **Validates: Requirements 59.2**

  - [ ] 36.4 Implement the Career_Importer
    - `import_record` entity with migration case; `preview` with counts and already-imported counts; `commit` in one Room transaction creating Tasks for new follow-ups and Goals for selected skills, skipping known keys and reporting skipped counts; Audit_Log read entry
    - _Requirements: 7.1, 59.1, 59.3, 59.4, 59.5, 59.9, 59.10_

  - [ ] 36.5 Write property test for import idempotence
    - **Property 78: Huntfolio import is idempotent**
    - **Validates: Requirements 59.3, 59.5, 59.9**

  - [ ] 36.6 Build the import UI
    - File picker, Import_Preview with counts and skill selection with Goal_Period, target and unit, result summary and error messages
    - _Requirements: 59.1, 59.2, 59.4, 59.8, 59.9_

- [ ] 37. Phase 7 Career import and Polish: encrypted export and restore
  - [ ] 37.1 Implement the Export_File format and the Backup_Manager
    - Header with magic, KDF and AEAD fields as associated data; Argon2id via Bouncy Castle (64 MiB, 3 passes; measure on the Low_RAM_Profile) feeding Tink `AesGcmHkdfStreaming` (verify the API on min SDK 26); ZIP of `data.jsonl`, settings and the ref key; `secret` table excluded; passphrase as `CharArray` cleared after use
    - `export` via temp file with partial-file deletion on failure or cancel; `inspect`; `restore` through a staging database, `restore.marker` and atomic rename, then reschedule and mark sources without credentials disconnected; `recoverIfInterrupted`; Export_Passphrase validator added to the Property 6 test
    - _Requirements: 60.1, 60.2, 60.3, 60.4, 60.5, 60.6, 60.7, 60.8, 67.5_

  - [ ] 37.2 Write property test for export round trip
    - **Property 80: Export and restore round trip with exclusions**
    - **Validates: Requirements 60.4, 60.5, 67.5**

  - [ ] 37.3 Write property test for tamper rejection
    - **Property 81: Tampered or wrongly unlocked exports are rejected**
    - **Validates: Requirements 60.3**

  - [ ] 37.4 Build the export and restore UI
    - Export flow with two passphrase entries and the unrecoverable warning; restore from Settings, First_Run_Screen and the "cannot be unlocked" screen with created date and replace warning; re-entry of the passphrase without re-selecting the file; "restore did not complete" notice
    - _Requirements: 1.5, 60.1, 60.3, 60.6, 60.7, 60.8_

- [ ] 38. Phase 7 Career import and Polish: battery guide, health check, accessibility and security suite
  - [ ] 38.1 Implement the Battery_Guide
    - `BatteryGuide.stepsFor` (case-insensitive manufacturer match, Standard_Android_Steps otherwise; Manufacturer_Steps only for brands checked on a device), `isOptimised`, open-settings action with written-steps fallback, re-check on return, automatic display at most once
    - _Requirements: 61.1, 61.2, 61.3, 61.4, 61.5, 61.6_

  - [ ] 38.2 Write property test for manufacturer matching
    - **Property 82: Battery guide manufacturer match**
    - **Validates: Requirements 61.3**

  - [ ] 38.3 Implement the Health_Check
    - `job_run` entity with migration case; `HealthRules.jobMissed` (30 minutes); `health-check` periodic worker and checks on app open; permission, notification and channel checks; one combined warning with fix actions and Battery_Guide link; dismiss until a new problem; Health_Status per job and feature in Settings; notification access and mailbox warnings
    - _Requirements: 27.4, 51.4, 51.7, 62.1, 62.2, 62.3, 62.4, 62.5, 62.6_

  - [ ] 38.4 Write property test for health status
    - **Property 83: Health status state machine**
    - **Validates: Requirements 62.1, 62.4, 62.5, 62.6**

  - [ ] 38.5 Complete the accessibility pass
    - Content descriptions for interactive and informative elements, decorative images hidden, 48 dp touch targets, layouts that scroll at 200 percent font scale on 360 dp width, full text for ellipsised items, colour statuses with text or icon, focus order
    - _Requirements: 63.1, 63.2, 63.3, 63.6, 63.7_

  - [ ] 38.6 Write automated accessibility tests
    - Semantics checks for content descriptions and touch targets; Roborazzi screenshots at 360 dp, font scale 1.0 and 2.0, light and dark
    - _Requirements: 63.1, 63.2, 63.3_

  - [ ] 38.7 Expand fixtures and complete the security test suite
    - Fixtures to at least 200 Money_Messages with at least 5 per Bank_Format, 10 OTP_Messages and 10 Prompt_Injection_Fixtures; mail fixtures (`.eml`) for HTML-only, odd charsets, OTP and injection; raise the `FixtureInventoryTest` thresholds; per-Prompt_Type number-validator tests; add the instrumented SQLCipher test to the release job on an emulator
    - _Requirements: 4.5, 64.1, 64.2, 64.3, 64.7_

  - [ ] 38.8 Write the Device_Test_Plan and helper scripts
    - `docs/device-test-plan.md` as a checklist of the Device_Test_Plan table in the design (API 26 to 35, Low_RAM_Profile, reboot, Doze, battery saver, two reminders 5 minutes apart in Doze, zone change, SMS timing, listener reconnect, update, wipe, backup exclusion, TalkBack, memory)
    - PowerShell helper scripts in `scripts/device/` wrapping the `adb` commands (`dumpsys deviceidle force-idle`, `bmgr`, reboot). The executor writes these files and does not claim to have run them on devices
    - _Requirements: 1.8, 19.1, 19.2, 63.7, 64.4_

- [ ] 39. Checkpoint: Phase 7 Career import and Polish complete
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 40. Phase 8 Share and distribution
  - [ ] 40.1 Implement consent for the shared build
    - `consent` entity with migration case; `ConsentGate.mustShow`; in the `shared` flavour the start sequence shows the Consent_Screen before creating the database, key or settings; decline stores nothing and shows it again next launch; acceptance stores date, time and Consent_Text_Version shown in Settings; Privacy_Policy as a bundled string resource
    - _Requirements: 66.1, 66.2, 66.5, 66.6, 66.7_

  - [ ] 40.2 Write property test for the consent gate
    - **Property 85: Consent gate**
    - **Validates: Requirements 66.1, 66.5, 66.7**

  - [ ] 40.3 Implement the Proxy_Server client and server (server hosting BLOCKED on ADR 0023)
    - Client: `ProxyClient` over the egress-allowed host with invite token stored in the `secret` table and optional pinning with a backup pin
    - Server: ask the user for the hosting choice before writing it; a stateless function that forwards one request, holds the payload and response in memory only and logs no bodies
    - _Requirements: 45.2, 66.3_

  - [ ] 40.4 Verify the Sms_Free_Build
    - `smsFree` hides SMS in the Data_Source list and never requests SMS permissions; CI runs the full test suite for `smsFree` except the `sms` test package
    - _Requirements: 67.2, 67.3, 67.4_

  - [ ] 40.5 Harden app updates
    - Migration failure screen that leaves the database untouched; pending notifications rescheduled on `MY_PACKAGE_REPLACED`; data and source status kept across updates
    - _Requirements: 65.2, 65.5, 65.6_

  - [ ] 40.6 Write tests for update handling
    - Migration from every exported schema to the latest keeps rows; a failing migration leaves the file unchanged; package-replaced reschedules every pending row
    - _Requirements: 65.2, 65.5, 65.6_

  - [ ] 40.7 Add Google Play permission declarations (BLOCKED on Open decision 5)
    - Ask the user whether Play distribution is chosen before starting; then add the declarations the policy in force requires (verify current policy)
    - _Requirements: 67.1_

- [ ] 41. Checkpoint: Phase 8 Share and distribution complete
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 42. Later (optional): important dates
  - [ ] 42.1 Implement Important_Dates
    - `important_date` entity with migration case, validator (added to the Property 6 test), `ImportantDateRules.reminderDate`, reminder scheduling with late-save rule, edit and delete replacing the reminder, screen and enable toggle
    - _Requirements: 68.1, 68.2, 68.3, 68.4, 68.5_

  - [ ] 42.2 Write property test for important date reminders
    - **Property 86: Important date reminder timing**
    - **Validates: Requirements 68.2, 68.4**

- [ ] 43. Later (optional): habits
  - [ ] 43.1 Implement Habits
    - `habit` and `habit_mark` entities with migration case, validator (added to the Property 6 test), `HabitMath.streak`, mark and unmark, screen and enable toggle
    - _Requirements: 69.1, 69.2, 69.3, 69.4_

  - [ ] 43.2 Write property test for habit streaks
    - **Property 87: Habit streak**
    - **Validates: Requirements 69.2, 69.4**

- [ ] 44. Later (optional): learning plan
  - [ ] 44.1 Implement Learning_Topics
    - `learning_topic` entity with migration case, validator (added to the Property 6 test), stored as a week Goal with unit "sessions"; `LearningPlanner.proposals`; accept adds a Task and Time_Block; completion adds progress
    - _Requirements: 70.1, 70.2, 70.3, 70.4, 70.5_

  - [ ] 44.2 Write property test for learning proposals
    - **Property 88: Learning session proposals**
    - **Validates: Requirements 70.2**

- [ ] 45. Later (optional): voice capture
  - [ ] 45.1 Implement voice capture for Quick_Add
    - `VoiceCapture` with `SpeechRecognizer.createOnDeviceSpeechRecognizer` (API 31+), microphone rationale on first tap only, 10-second silence timeout, text placed in Quick_Add for confirmation, unavailable state with reason
    - _Requirements: 71.1, 71.2, 71.3, 71.4, 71.5_

  - [ ] 45.2 Write unit tests for voice capture
    - Unavailable recogniser, denied microphone, silence timeout and cancel each create no Task and keep text entry working
    - _Requirements: 71.4, 71.5_

- [ ] 46. Later (optional): Hindi and Tamil (BLOCKED on Open decision 6)
  - [ ] 46.1 Add the chosen App_Language
    - Ask the user which language before starting; add translated string resources, in-app language selection applied from the next screen, English fallback for missing strings, same digits for amounts and dates
    - _Requirements: 72.1, 72.3, 72.4, 72.5_

  - [ ] 46.2 Add the script check to the Output_Validator
    - `ScriptCheck.mostlyInScript` applied to Suggestion text and reason after removing numbers, Merchant names, Task titles and Goal names
    - _Requirements: 72.2_

  - [ ] 46.3 Write property test for the script check
    - **Property 89: Script check for Hindi and Tamil**
    - **Validates: Requirements 72.2**

  - [ ] 46.4 Write property test for language independence
    - **Property 90: Results do not depend on the App_Language**
    - **Validates: Requirements 72.5, 72.6**

- [ ] 47. Final checkpoint
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for a faster MVP. The Later section (tasks 42 to 46) is optional as a whole.
- Property tests use kotest-property inside JUnit 5 tests with at least 100 iterations (1,000 for parser, dedupe and redactor properties). Each test carries the comment `// Feature: personal-assistant-android, Property N: <title>` and its name starts with `propertyN_`.
- Each property test lives in its own file, in the package of the design's test-class group (for example `ingest/IngestProperty41Test.kt`), so property tasks never edit the same file. New generators stay private to the test file; shared generators in `gen/` are added only by tasks in different waves.
- Property 6 (validators), Property 5 (proposals), Property 8 (audit), Property 20 (reports), Properties 1, 3 and 4 (ingest privacy) and Property 59 (secrets) are created once and extended by later tasks, as noted in those tasks.
- The decision-table tests for the Notification_Governor (task 10.2) and the parser fixture tests (task 18.8) are required, because they pin down behaviour that the property tests check only against reference models.
- Instrumented tests (task 3.4 and the release-job SQLCipher test) need an emulator. The Device_Test_Plan (task 38.8) is run by the developer on real phones; the executor writes it and its scripts but does not run them.
- Tasks marked BLOCKED (29.8, 34.1, 40.3 server part, 40.7, 46) wait for the open decisions listed in `requirements.md`. Everything before them works with the None AI mode, a fake mail connector and synthetic bank formats.
- The Consent_Screen and Privacy_Policy DPDP review record (Requirement 66.4) and the ADR files are documentation and are not coding tasks; they should be written by the developer before the first shared build.

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1"] },
    { "id": 1, "tasks": ["1.2"] },
    { "id": 2, "tasks": ["1.3", "1.4"] },
    { "id": 3, "tasks": ["2.1"] },
    { "id": 4, "tasks": ["2.2", "3.1"] },
    { "id": 5, "tasks": ["2.3", "3.2"] },
    { "id": 6, "tasks": ["3.3"] },
    { "id": 7, "tasks": ["3.4", "3.5"] },
    { "id": 8, "tasks": ["3.6"] },
    { "id": 9, "tasks": ["3.7", "4.1"] },
    { "id": 10, "tasks": ["4.2", "4.3"] },
    { "id": 11, "tasks": ["4.4", "4.5"] },
    { "id": 12, "tasks": ["4.6", "4.7"] },
    { "id": 13, "tasks": ["4.8", "4.9", "5.1"] },
    { "id": 14, "tasks": ["5.2", "5.3"] },
    { "id": 15, "tasks": ["5.4", "5.5"] },
    { "id": 16, "tasks": ["5.6", "5.7"] },
    { "id": 17, "tasks": ["7.1", "8.1", "10.1"] },
    { "id": 18, "tasks": ["7.2", "7.3", "8.2", "10.2", "10.5"] },
    { "id": 19, "tasks": ["7.4", "7.5", "9.1", "10.3", "10.4", "10.6"] },
    { "id": 20, "tasks": ["9.2", "9.3", "9.4", "7.6"] },
    { "id": 21, "tasks": ["8.3"] },
    { "id": 22, "tasks": ["8.4", "8.5", "9.5"] },
    { "id": 23, "tasks": ["9.6", "9.7"] },
    { "id": 24, "tasks": ["9.8", "9.9"] },
    { "id": 25, "tasks": ["9.10", "10.7"] },
    { "id": 26, "tasks": ["10.8", "10.9"] },
    { "id": 27, "tasks": ["10.10", "11.1"] },
    { "id": 28, "tasks": ["11.2"] },
    { "id": 29, "tasks": ["11.3"] },
    { "id": 30, "tasks": ["11.4"] },
    { "id": 31, "tasks": ["11.5", "12.1"] },
    { "id": 32, "tasks": ["12.2"] },
    { "id": 33, "tasks": ["14.1", "15.1", "15.4", "15.7"] },
    { "id": 34, "tasks": ["14.2", "15.2", "15.5", "15.6", "15.8"] },
    { "id": 35, "tasks": ["14.3", "14.4", "15.3"] },
    { "id": 36, "tasks": ["15.9"] },
    { "id": 37, "tasks": ["16.1"] },
    { "id": 38, "tasks": ["16.2"] },
    { "id": 39, "tasks": ["18.1", "18.3", "19.1", "19.2", "19.5", "19.8", "19.9"] },
    { "id": 40, "tasks": ["18.2", "18.4", "18.5", "19.3", "19.4", "19.6", "19.7", "19.10", "19.11"] },
    { "id": 41, "tasks": ["18.6"] },
    { "id": 42, "tasks": ["18.7", "18.8", "20.1"] },
    { "id": 43, "tasks": ["20.2"] },
    { "id": 44, "tasks": ["20.3", "20.4", "20.5"] },
    { "id": 45, "tasks": ["20.6"] },
    { "id": 46, "tasks": ["20.7", "20.8", "20.9", "20.10", "20.11", "20.12", "20.13", "20.14", "20.15", "21.1"] },
    { "id": 47, "tasks": ["21.2", "22.1"] },
    { "id": 48, "tasks": ["21.3", "22.2", "22.3"] },
    { "id": 49, "tasks": ["22.4", "22.5", "22.6", "22.7"] },
    { "id": 50, "tasks": ["22.8", "22.9"] },
    { "id": 51, "tasks": ["22.10", "23.1"] },
    { "id": 52, "tasks": ["23.2"] },
    { "id": 53, "tasks": ["23.3"] },
    { "id": 54, "tasks": ["25.1"] },
    { "id": 55, "tasks": ["25.2", "25.3", "25.4"] },
    { "id": 56, "tasks": ["25.5", "25.6"] },
    { "id": 57, "tasks": ["25.7", "26.1"] },
    { "id": 58, "tasks": ["26.2", "26.3"] },
    { "id": 59, "tasks": ["26.4", "26.5"] },
    { "id": 60, "tasks": ["26.6"] },
    { "id": 61, "tasks": ["28.1", "28.2"] },
    { "id": 62, "tasks": ["28.3", "28.4"] },
    { "id": 63, "tasks": ["28.5", "28.6", "28.7", "28.8"] },
    { "id": 64, "tasks": ["28.9", "29.1"] },
    { "id": 65, "tasks": ["29.2", "29.3"] },
    { "id": 66, "tasks": ["29.4", "29.5", "29.6"] },
    { "id": 67, "tasks": ["29.7", "29.8"] },
    { "id": 68, "tasks": ["29.9", "30.1"] },
    { "id": 69, "tasks": ["29.10", "30.2"] },
    { "id": 70, "tasks": ["30.3"] },
    { "id": 71, "tasks": ["30.4", "30.5"] },
    { "id": 72, "tasks": ["32.1", "32.3"] },
    { "id": 73, "tasks": ["32.2", "32.4", "33.1"] },
    { "id": 74, "tasks": ["33.2", "33.3", "33.4", "33.5"] },
    { "id": 75, "tasks": ["33.6", "33.7"] },
    { "id": 76, "tasks": ["33.8", "33.9"] },
    { "id": 77, "tasks": ["33.10", "34.1"] },
    { "id": 78, "tasks": ["34.2", "34.3"] },
    { "id": 79, "tasks": ["36.1", "37.1", "38.1"] },
    { "id": 80, "tasks": ["36.2", "36.3", "36.4", "37.2", "37.3", "38.2"] },
    { "id": 81, "tasks": ["36.5", "36.6"] },
    { "id": 82, "tasks": ["37.4"] },
    { "id": 83, "tasks": ["38.3"] },
    { "id": 84, "tasks": ["38.4", "38.5"] },
    { "id": 85, "tasks": ["38.6", "38.7"] },
    { "id": 86, "tasks": ["38.8"] },
    { "id": 87, "tasks": ["40.1"] },
    { "id": 88, "tasks": ["40.2", "40.3"] },
    { "id": 89, "tasks": ["40.4"] },
    { "id": 90, "tasks": ["40.5"] },
    { "id": 91, "tasks": ["40.6", "40.7"] },
    { "id": 92, "tasks": ["42.1"] },
    { "id": 93, "tasks": ["42.2", "43.1"] },
    { "id": 94, "tasks": ["43.2", "44.1"] },
    { "id": 95, "tasks": ["44.2", "45.1"] },
    { "id": 96, "tasks": ["45.2", "46.1"] },
    { "id": 97, "tasks": ["46.2"] },
    { "id": 98, "tasks": ["46.3", "46.4"] }
  ]
}
```
