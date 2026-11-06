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

## Status (6 Nov, after the overnight build)

Built and tested on device code paths: Today dashboard (focus hero, proactive cards, energy
timeline), Goals with milestones/forecast/plan proposals, Subscription health hub, smart
reminders (time, place, charging, car Bluetooth), hourly proactive agent, CRED widget restyle,
Manrope + motion system, Room v10. Blueprint: https://claude.ai/artifact/DF7u9WibhruMegAJtHrGUH
(copy in docs/apex-blueprint.html; server/ has the PostgreSQL schema and route list).

Deliberately not done (needs a decision or is out of reach here):
- AI goal decomposer: needs the Cloud AI vendor (open decision); rule-based planner covers it.
- Goals progress on the home-screen widget: Requirement 21.2 forbids goal progress on the widget.
- Google/Outlook calendar writes and Gmail OAuth: need the Apex Sync service (hosting decision).
- Kotlin Multiplatform split and the iOS app: next phase per the blueprint.
- No emulator here (no KVM): screens verified with Robolectric screenshots (design-preview/).

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
- [x] 11. Module C: smart reminders (time, place via Geofencing, phone state: charging, car Bluetooth).
- [x] 12. Widgets v2: focus, quick capture, next up, goals.
- [x] 13. Polish pass: every screen on the new components, screenshots, lint/detekt/ktlint.
- [x] 14. Blueprint doc published (schema, API, prompt) + server/ scaffold.
