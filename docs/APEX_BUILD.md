# Apex Assistant: overnight build log

Read this first when resuming. Approved direction: hybrid (phone is the source of truth, optional
sync service later) + Kotlin Multiplatform later. Design: the approved CRED-style canvas
(https://claude.ai/artifact/DVpEg7yLFi2XzZhMBVL8eR) — ink canvas, raised dark cards, violet accent,
floating pill nav, Manrope headings, energy colours (deep work violet, light admin blue, physical
green, rest amber), motion via Compose APIs (stagger 300–450 ms, springs, reduced-motion respected).

Build environment (cloud): Android SDK at /opt/android-sdk; Maven Central via the Google mirror
init script in ~/.gradle/init.d; tests need
`JAVA_TOOL_OPTIONS="$JAVA_TOOL_OPTIONS -Drobolectric.dependency.repo.url=https://maven-central.storage-download.googleapis.com/maven2/"`.
Commands: `./gradlew :app:assemblePersonalWithSmsDebug`, `./gradlew :app:testPersonalWithSmsDebugUnitTest`.
Branch: claude/add-ui-ux-pro-max-skill (push blocked until GitHub access is granted).

## Steps (tick when done, commit after each)

- [x] 1. Green tests: SMS fixtures to 200, SubscriptionsScreenTest re-run.
- [x] 2. Rename to Apex Assistant (app_name, launcher label).
- [x] 3. Design system v2: Manrope via downloadable Google Fonts, energy colour tokens, motion
        primitives (press-scale, staggered entrance, animated counters/rings, shared nav transitions),
        reduced-motion switch.
- [x] 4. Module A domain: milestones, task priority, energy tag, verification (Room v10 migration),
        SlotAssigner + estimator engine with tests.
- [x] 5. Module B: routines (daily/weekday rules), Rescheduler engine with tests.
- [x] 6. Today dashboard: focus hero, load meter, proactive card, energy timeline, free-slot fill.
- [x] 7. Goals screen v2: progress ring, hours, forecast, milestones; goal detail.
- [x] 8. Plan-with-AI proposal sheet (rule-based fallback when AI is off; GatewayAdvisor when on).
- [x] 9. Module E: hourly ProactiveWorker + cards through the Notification_Governor.
- [x] 10. Money: Subscription health hub (monthly burn, renewing soon, second look, mail-found).
- [ ] 11. Module C: smart reminders (time, place via Geofencing, phone state: charging, car Bluetooth).
- [ ] 12. Widgets v2: focus, quick capture, next up, goals.
- [ ] 13. Polish pass: every screen on the new components, screenshots, lint/detekt/ktlint.
- [ ] 14. Blueprint doc published (schema, API, prompt) + server/ scaffold.
