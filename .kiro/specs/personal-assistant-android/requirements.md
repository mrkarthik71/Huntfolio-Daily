# Requirements Document

## Introduction

This document specifies a local-first personal assistant for Android phones used in India. The app builds a short Today plan each morning (3 priorities, time blocks around calendar events, bills due, goals behind), sends a small number of reminders during the day, runs a 30-second evening review, and produces weekly and monthly progress, spending and subscription reports.

Data comes from the user's own entries, the phone's calendar, bank and UPI SMS and app notifications, and (later) the user's mailbox. All user data stays on the phone in an encrypted database. There is no account and no server for user data. Rule-based code reads and calculates; an optional AI provider only writes wording and judgment from a small redacted summary. The app reads data and suggests; the app never sends, pays, cancels or replies on the user's behalf.

The first user is the developer. Friends follow through a signed APK. Google Play distribution happens only if store rules allow. Huntfolio (the user's job-search app) stays a separate app; this app only imports a Huntfolio export file.

### Phase map

Requirements are grouped by delivery phase so that each phase can be implemented, used and shipped before the next one starts. Cross-cutting requirements (1 to 10) apply from Phase 0 and every later phase must comply with them.

| Phase | Name | Requirements | Done when (from the plan) |
|---|---|---|---|
| 0 | Setup | 1–10 | App runs with an encrypted database; CI builds; release signing works |
| 1 | Core | 11–21 | The developer has used goals, tasks, Today, notifications and widget daily for 2 weeks |
| 2 | Calendar | 22–25 | Today shows calendar events and fits tasks into free gaps |
| 3 | Money | 26–36 | The last 3 months of transactions look right and manual fixes work |
| 4 | Subscriptions | 37–41 | Real subscriptions are found with few false detections |
| 5 | AI advice | 42–50 | Suggestions are useful and never contain invented numbers |
| 6 | Email | 51–58 | Fewer than 1 wrong important-mail alert per day |
| 7 | Career import and Polish | 59–64 | Huntfolio import, battery guide, accessibility, security review and encrypted backup/export are complete |
| 8 | Share and distribution | 65–67 | Friends run the app from a signed APK; Play decision taken |
| Later | Optional / future | 68–72 | Not scheduled |

Dependencies across phases: the SMS fallback parser (Requirement 49) and AI mail classification (Requirement 54) depend on Phase 5 being in place. Before Phase 5, unreadable SMS go to the Review_Queue only, and mail triage would use rule scores only. Phase 6 (Email) is scheduled after Phase 5 in the roadmap.

### Open decisions (not decided in this document)

These items from section 13 of the plan are open. The requirements are written to be configurable or provider-agnostic for each of them.

1. **App name.** The document uses the placeholder system name "Assistant_App". (Still open — not blocking.)
2. **Cloud AI provider and monthly cap, or on-device only.** Requirements 42 to 45 define provider modes (Cloud, OnDevice, None) behind one interface and a user-set monthly cap. Gemini and Claude are named in the plan as candidates only. **DECIDED (2026-11-03):** build the Cloud adapter against the **OpenAI-compatible chat-completions wire format** (the de-facto standard most vendors accept: OpenAI, OpenRouter, Together, local servers), with **base URL + API key as runtime settings** and a **default monthly cap of ₹200**. No vendor account is needed to build or test; the key/base-URL are dropped in later. This keeps task 29.8 a one-line config rather than a code commitment.
3. **Email access method: IMAP with an app password, or Google sign-in in testing mode.** Requirement 51 treats the access method as a configurable connector. **DECIDED (2026-11-03): IMAP with an app password.** Works with any provider, needs no OAuth client registration or Google verification, and matches the read-only IMAP commands already in the design (`EXAMINE`, `ENVELOPE`, `BODY.PEEK[TEXT]`).
4. **Which banks and UPI apps are supported first.** Requirements 26 to 28 define the parser registry and sender/app lists as data, so the first set can be chosen later. **DECIDED (2026-11-03):** ship the **synthetic placeholder bank/UPI formats** the plan already specifies (two placeholder banks + one UPI app, covering debit/credit/bill/OTP/failed/reversal); real Indian bank formats are added as data later with no code change.
5. **APK only, or Google Play as well.** Requirements 65 to 67 cover APK distribution and make Play-specific requirements conditional. **DECIDED (2026-11-03): sideloaded APK first** (the `withSms` build), not Play. A `READ_SMS` app on Play needs a Permissions Declaration review that is slow and often rejected; the `smsFree` flavour stays the future Play candidate. Task 40.7 is a no-op until Play is chosen.
6. **Hindi or Tamil in the app and in suggestions.** Requirement 72 is an optional feature that applies only if a language is chosen. **DECIDED (2026-11-03): English only.** The whole Later/i18n section (task 46) is deferred.

**Proxy server hosting (design ADR 0023, task 40.3 server part): DECIDED (2026-11-03) — defer the server, build the `ProxyClient` interface only.** The proxy is only for the `shared` flavour's AI calls; the `personal` build uses a direct key and never needs it. Hosting stays a documented TODO, not a defaulted infra/cost commitment.

### Items that depend on external rules (verify before relying on them)

The plan marks these as "(verify)". This document does not state them as facts.

- Gmail IMAP access needing 2-step verification and an app password; Google sign-in in testing mode needing re-login about every 7 days.
- Google Play restrictions on the SMS permission, the required permissions declaration, and review of the notification-listener permission; approval is not guaranteed.
- Cloud AI cost of "a few rupees a month".
- DPDP Act 2023 obligations when people other than the developer use the app (consent, privacy policy, deletion on request, reasonable security).
- Account Aggregator as a later replacement for SMS reading.
- Manufacturer battery-optimisation behaviour (Xiaomi, Oppo, Vivo, Samsung) and the steps needed to exempt the app.

### Fixed technical constraints from the plan

These were decided in the plan and are recorded here as constraints for the design, not as acceptance criteria: native Android in Kotlin with Jetpack Compose and Material 3; Hilt; Room with SQLCipher and the key in Android Keystore; WorkManager for scheduled jobs and AlarmManager for exact reminders; DataStore for settings; Retrofit or Ktor for AI APIs; JUnit and Robolectric for tests; a single Gradle module to start, with packages ui, domain, data, ingest, parse, ai, notify and security.

### Assumptions and points to confirm

- **Review_Queue text.** The plan says to keep extracted facts only, and also that unreadable messages go to a "needs review" list. This document assumes the Review_Queue keeps the redacted text of an unreadable message, encrypted, only until the user resolves or dismisses the item (Requirement 30). Please confirm or choose a different approach.
- **Widget and app lock.** The home-screen widget shows data without unlocking the app. This document limits the widget to task titles, priorities and the next event, with no money amounts (Requirement 21). Please confirm.
- **Streak rule.** The plan does not define what counts for a streak. The default used here is "at least one Priority completed that day", configurable by the user.
- **Subscription renewal alert timing.** The plan says "renews tomorrow / 2 days before". This document uses a user-chosen lead time of 1 or 2 days, default 2 days.
- **Low-RAM target.** The plan says many target phones have little RAM and the app must stay light, but sets no number. The low-RAM device profile (RAM size and Android version) for the test matrix needs to be chosen.
- **Default values** not given in the plan (sync intervals, duplicate time window, thresholds, snippet length) are configurable settings whose defaults are set in the design.
- **Proposed numbers not in the plan.** These values were added to make criteria testable and should be confirmed: widget refresh within 1 minute (Req 21), calendar cache of 7 days and refresh within 5 minutes (Req 22), bill-to-payment match within 5 days of the due date (Req 34), usage check every 30 days and cancel suggestion after 2 "No" answers (Req 39, 40), AI timeout of 30 seconds and 2 retries (Req 43, 47), health warning when a scheduled job is 30 minutes late (Req 62), at least 200 parser fixtures (Req 64, the plan says "hundreds"), and accessibility targets of 48 dp, 200 percent font scale and 4.5:1 contrast (Req 63).
- **Values added during refinement (please confirm).**
  - App lock and privacy: Lock_Timeout from 0 seconds to 30 minutes (Req 6); on a device with no screen lock, data stays hidden and the User is asked to set one (Req 6); the "Delete everything" confirmation states that Export_Files saved outside the app are kept (Req 8); a Redacted_Payload that cannot be recorded in the Audit_Log is not sent (Req 7).
  - Theme: follows the Android system dark setting, with no in-app override (Req 10).
  - Goals and Tasks: Goal name 1 to 100 characters, unit 1 to 30 characters, target and progress amounts 0.01 to 999,999,999.99 (Req 11); Task title and Quick_Add text 1 to 200 characters (Req 12, 13); estimated duration 1 to 1,440 minutes (Req 12); Tasks cannot be moved to past dates (Req 12).
  - Daily plan: Priority ranking order is overdue, then due date, then Behind_Goal link, then creation time (Req 14).
  - Notifications: Daily_Cap and Per_Type_Limit 1 to 20 (Req 18); snooze 5 to 1,440 minutes (Req 18); rescheduling within 2 minutes after reboot (Req 19); the goal nudge is checked at the morning brief time (Req 20).
  - Widget: ticking a Priority on the Widget needs no App_Lock authentication (Req 21).
  - Still open: whether Quick_Add from the Widget needs App_Lock authentication, and what the morning brief notification shows on the lock screen.
  - Calendar: all calendars start selected; declined events are treated like cancelled; Event_Lead_Time 0 to 120 minutes; Min_Focus_Length 15 to 240 minutes; conflicting Time_Blocks are flagged and never moved automatically; Tasks are never split across focus blocks (Req 22 to 25).
  - Money: new SMS and notifications parsed within 60 seconds; SMS_History_Import covers 3 calendar months; Valid_Amount ₹0.01 to ₹99,99,99,999.99; a 2-digit year YY means 20YY; Reversals are stored as credits and shown as a separate report line, not subtracted from spend (gross spend; please choose gross or net); failed payments create nothing; duplicate merge prefers SMS values and never auto-merges manual Transactions; exact Merchant matching only; bill-paid match needs the exact amount within ±5 days; parse accuracy is measured over a rolling 30 days (Req 26 to 36).
  - Subscriptions: month-end dates follow the Due_Date_Rule; Min_Occurrences of 2 or more means yearly subscriptions are not found from the 3-month import and must be added manually; a missed charge advances the next due date and asks keep or cancel; price changes need User acceptance; the first Usage_Check is 30 days after activation, asked at the evening review time; the renewal alert comes at the morning brief time, at most 1 per due date (Req 37 to 41).
  - AI: AI_Mode is None on first run; Cloud needs a cap set (and a key in the personal build); at most 3 AI_Attempts per AI_Request (30 s each), all counted against the cap; Rule_Based_Result shows first and is replaced only by validated AI wording; OnDevice never falls back to Cloud; AI_Monthly_Cap 1–10,000 requests or ₹1–₹10,000; payload limit 4,000 characters; Number_Token covers English number words only; SMS fallback parser off by default; a Suggestion counts as ignored after 7 days (Req 42 to 50).
  - Email: Connection_Test timeout 30 s; Mail_Sync_Interval 15 min–24 h; only selected folders (default inbox); Snippet_Length 0–500, no attachments or remote content; first sync covers 7 days, max 200 emails per sync; health warning after 24 h without a sync; Shortlist_Rules entries 1–100 characters, max 200; whether default rules ship is undecided; Sender_Penalty 2 points per "Not important"; quality target restated as fewer than 7 Wrong_Mail_Alerts in 7 fully connected days (Req 51 to 58).
  - Import, backup and polish: Huntfolio_Export max 5 MB and needs a format version and stable record keys (to agree with Huntfolio); re-imported records are skipped; skills become Goals only after the User sets period, target and unit; Export_Passphrase 12–128 characters and unrecoverable; restore accepts same or older Export_Data_Version; Battery_Guide opens by itself at most once; accessibility tested at 360 dp width with 3:1 non-text contrast; at least 5 fixtures per Bank_Format, 10 OTP and 10 prompt-injection fixtures; CI secret scan on every push (Req 59 to 64).
  - Sharing and later items: each distributed build needs a higher version code; declining consent means nothing is read or stored; a changed Consent_Text_Version re-shows consent; Sms_Free_Build hides SMS from Settings; moving from APK to Play may need an export/restore because the signing key may differ (verify); Important_Date lead time 0–365 days (default 30); names for Important_Date, Habit and Learning_Topic 1–80 characters; Learning_Topic 1–14 sessions/week, 15–180 min; voice recognition on device only with a 10 s silence timeout; Hindi/Tamil script check rejects Suggestions with more than half their letters in another script (Req 65 to 72).

## Glossary

- **Assistant_App**: The Android application specified here (placeholder name; see Open decision 1).
- **User**: The person who owns the phone and uses the Assistant_App.
- **Encrypted_Store**: The on-device encrypted database holding all user data.
- **Key_Manager**: The component that creates and holds encryption keys in the Android Keystore.
- **App_Lock**: The component that requires biometric or device PIN authentication before showing user data.
- **Device_Credential**: The PIN, pattern or password set as the Android screen lock.
- **Lock_Timeout**: The User-set time, from 0 seconds to 30 minutes, that the Assistant_App may stay in the background before the App_Lock requires authentication again.
- **Permission_Manager**: The component that requests Android permissions and explains them to the User.
- **Permission_Rationale**: The plain-language screen the Permission_Manager shows before an Android permission request or special-access settings page.
- **Audit_Log**: The on-device record of what the Assistant_App read, when, and what was sent to an AI_Provider.
- **Data_Wiper**: The component that deletes all user data on request.
- **First_Run_Screen**: The screen the Assistant_App shows when no user data exists, as after a fresh install.
- **Consent_Screen**: The screen, shown as part of the First_Run_Screen in builds for users other than the developer, that states what data the Assistant_App reads, what is sent to the AI_Provider, and where user data is kept, with accept and decline controls.
- **Consent_Text_Version**: A positive integer that identifies the wording of the Consent_Screen and the Privacy_Policy. The number increases whenever that wording changes.
- **Privacy_Policy**: The in-app text describing which data the Assistant_App reads, what leaves the phone, and how the User deletes all data.
- **Settings_Screen**: The screen listing connected sources, provider settings, notification controls, export and delete options.
- **Data_Source**: One of SMS, app notifications, calendar, mailbox, AI_Provider and Huntfolio import, as listed on the Settings_Screen.
- **Project_Repository**: The source code repository of the Assistant_App.
- **CI_Pipeline**: The automated build and test run on the Project_Repository.
- **Release_Build**: The build of the Assistant_App made for distribution, shrunk and obfuscated with R8 and signed with the release signing key.
- **Sms_Free_Build**: A Release_Build produced with SMS reading excluded. It declares no SMS permission and contains no Sms_Reader.
- **Secret_Store**: The store outside the Project_Repository from which the CI_Pipeline reads signing keys and API keys.
- **Goal**: A target the User wants to reach within a Goal_Period, with a numeric target and a unit.
- **Goal_Period**: One of year, month or week.
- **Period_Instance**: One occurrence of a Goal_Period. A year Period_Instance is a calendar year, a month Period_Instance is a calendar month, and a week Period_Instance is the 7 days ending on the User-set last day of the week. Expected_Progress and Days_Behind are calculated for the current Period_Instance.
- **Goal_Manager**: The component that creates, updates and calculates progress for Goals.
- **Learning_Topic**: A User-entered subject with a name, a weekly session target and a session length, stored as a week Goal (Requirement 70).
- **Expected_Progress**: Target × (elapsed days of the Goal_Period ÷ total days of the Goal_Period).
- **Days_Behind**: (Expected_Progress − actual progress) ÷ (target ÷ total days of the Goal_Period), rounded down; zero or negative means on track.
- **Behind_Goal**: A Goal whose Days_Behind is 1 or more.
- **Task**: A to-do item with a title, optional due date, optional estimated duration and optional link to a Goal.
- **Planned_Date**: The optional date on which a Task is scheduled to be done. It is set by the User, by Quick_Add or by a Task_Move.
- **Task_Status**: The state of a Task, one of open, done or dropped. A dropped Task is kept on record but is excluded from open Task lists and from Priority selection.
- **Task_Move**: One change of a Task's Planned_Date to a different date, recorded together with the date of the change.
- **Task_Manager**: The component that creates, updates, completes and moves Tasks.
- **Quick_Add**: The input that creates a Task from a single line of text.
- **Daily_Plan**: The plan for one date, holding up to 3 Priorities, Time_Blocks, bills due and Behind_Goals.
- **Priority**: One of up to 3 Tasks selected as most important for a date.
- **Time_Block**: A start and end time on a date assigned to a Task or to focus work.
- **Working_Hours**: The User-set daily time window in which Time_Blocks may be placed.
- **Plan_Builder**: The rule-based component that creates the Daily_Plan.
- **Today_Screen**: The screen that shows the Daily_Plan for the current date.
- **Evening_Review**: The end-of-day flow in which the User marks Tasks done or moves them to the next day.
- **Review_Date**: The date an Evening_Review covers. If the User opens the Evening_Review from the evening review notification, the Review_Date is the date the notification was shown. Otherwise it is the current date.
- **Review_Flow**: The component that runs the Evening_Review and the weekly and monthly reviews.
- **Streak**: The count of consecutive days meeting the Streak_Rule.
- **Streak_Rule**: The User-configurable condition for a day to count toward a Streak (default: at least one Priority completed).
- **Habit**: A User-defined routine with a name and a frequency of daily or weekly (Requirement 69).
- **Habit_Streak**: For a daily Habit, the count of consecutive days, ending today or yesterday, on which the Habit was marked done. For a weekly Habit, the count of consecutive weeks, ending with the current or the previous week, in which the Habit was marked done at least once. Weeks end on the User-set last day of the week.
- **Progress_Tracker**: The component that calculates Goal progress, Days_Behind and Streaks.
- **Notification_Type**: One of morning brief, evening review, event reminder, bill due, goal nudge, important mail, mail digest, subscription renewal, subscription usage check, health warning, and important date (important date applies only where important dates are enabled; Requirement 68).
- **Important_Date**: A User-entered item with a name, a date and a lead time in days, used for renewal reminders (Requirement 68).
- **Notification_Scheduler**: The component that schedules notifications at set times, including exact-time reminders.
- **Exact_Alarm_Access**: The device's permission for the Assistant_App to schedule exact alarms. On Android 8 to 11 it is always available. On Android 12 and later it is available only while the exact-alarm permission is granted.
- **Time_Specific_Reminder**: A notification that the Notification_Scheduler schedules for a specific local time: the morning brief, the evening review, an event reminder, or a snoozed notification shown again.
- **Notification_Governor**: The component that applies quiet hours, the daily cap, snooze and per-type switches before any notification is shown.
- **Quiet_Hours**: The User-set daily time window in which the Notification_Governor holds notifications.
- **Daily_Cap**: The User-set maximum number of notifications shown per calendar day.
- **Cap_Exempt_Type**: A Notification_Type the User has marked as exempt from the Daily_Cap.
- **Per_Type_Limit**: The User-set maximum number of notifications of one Notification_Type shown per calendar day, from 1 to 20.
- **Why_Explanation**: The text shown by the "Why am I seeing this?" action, naming the rule, the data and the setting behind a notification.
- **Inbox_List**: The in-app list of notifications that were held or not shown because of the Daily_Cap.
- **Widget**: The Assistant_App home-screen widget.
- **Calendar_Reader**: The component that reads events from the phone's calendar provider.
- **Calendar_Event**: One occurrence of an event read from the phone's calendar provider. A Calendar_Event is either timed (all-day flag false, with a start time and an end time) or all-day (all-day flag true, with a start date and an end date).
- **Selected_Calendar**: A calendar in the phone's calendar provider that the User has marked in the Settings_Screen as one the Calendar_Reader may read.
- **Calendar_Cache_Window**: The period from 00:00 on the current date to 24:00 on the 7th date after the current date, in the device's current time zone (8 dates in total).
- **Overlap**: Two time ranges overlap when they share a period longer than zero minutes. Ranges that only touch at an end point do not overlap.
- **Event_Lead_Time**: The User-set number of minutes before the start of a timed Calendar_Event at which the event reminder is shown (0 to 120, default 15).
- **Free_Gap**: A period within Working_Hours on a date with no timed Calendar_Event and no existing Time_Block.
- **Gap_Finder**: The component that finds Free_Gaps and fits Tasks into them.
- **Min_Focus_Length**: The User-set minimum duration of a Free_Gap for a focus-block suggestion.
- **Focus_Block_Proposal**: A set of open Tasks that the Gap_Finder proposes for one Free_Gap, in proposal order.
- **Sms_Reader**: The component that reads SMS from the phone.
- **Notification_Reader**: The component that reads notifications posted by selected bank and UPI apps.
- **Bank_Sender_List**: The configurable list of SMS sender IDs treated as bank or UPI senders.
- **Money_App_List**: The configurable list of Android apps whose notifications the Notification_Reader reads.
- **Money_Message**: An SMS or app notification from a sender in the Bank_Sender_List or an app in the Money_App_List.
- **Money_Screen**: The screen that shows Transactions, Budgets, Bills and the entry point to the Review_Queue.
- **Message_Text**: The body text, in full or in part, of an SMS, an app notification or an email.
- **Received_Time**: The time the device received an SMS, or the time an app posted a notification, in the device's local time zone.
- **Message_Origin**: The SMS sender ID or the app from which a Money_Message came.
- **SMS_History_Import**: The one-time read of Bank_Sender_List SMS already on the phone, offered when the User first enables SMS reading.
- **Currency_Marker**: One of "Rs", "Rs.", "INR" or "₹", in any letter case, written before or after an amount, with or without a space.
- **Valid_Amount**: An amount from ₹0.01 (1 paisa) to ₹99,99,99,999.99 (99,999,999,999 paise), inclusive.
- **Message_Parser**: The rule-based component that turns a Money_Message into a Parsed_Message.
- **Bank_Format**: One parsing rule for one sender and one message template.
- **Parser_Registry**: The data-driven set of Bank_Formats used by the Message_Parser.
- **Parsed_Message**: The fields extracted from a Money_Message: type (debit, credit, bill, OTP, unknown), a Reversal marker, amount, merchant or VPA, account last 4 digits, date and time (for type bill, the due date), reference number if present, and balance if present. The Message_Parser may instead classify a Money_Message as an OTP_Message or a Failed_Payment_Message.
- **Template_Formatter**: The component that renders a Parsed_Message back into the text of a given Bank_Format (used for round-trip tests and fixtures).
- **OTP_Message**: A message the Message_Parser classifies as a one-time password or verification code.
- **Reversal**: A credit Parsed_Message or Transaction that its Bank_Format marks as a refund or as a reversal of an earlier debit.
- **Failed_Payment_Message**: A Money_Message that its Bank_Format marks as a failed, declined or cancelled payment.
- **Transaction**: A stored money movement with amount, type, Merchant, category, source, date, and Source_Ref.
- **Source_Ref**: A pointer to the origin of a stored fact (for example an SMS id, notification key, email Message-ID or file name) that contains no message text.
- **Deduplicator**: The component that detects that two Parsed_Messages describe the same payment.
- **Duplicate_Window**: The configurable maximum time difference for two Parsed_Messages to be treated as the same payment.
- **Review_Queue**: The "needs review" list of Money_Messages the Message_Parser could not read.
- **Merchant**: A payee with a name, aliases (including VPAs) and a default category.
- **Categoriser**: The component that assigns a category to each Transaction.
- **Transaction_Editor**: The screens and logic for editing, adding, merging and deleting Transactions.
- **Budget**: A monthly spending limit for one category.
- **Budget_Engine**: The component that calculates spent and remaining amounts for Budgets.
- **Bill**: A payment due with a name, amount, due date, optional recurrence and optional Merchant.
- **Bill_Tracker**: The component that stores Bills and tracks their due and paid status.
- **Report_Generator**: The component that builds weekly and monthly progress, spending and subscription reports from stored data.
- **Subscriptions_Screen**: The screen listing proposed and active Subscriptions, due Usage_Checks and the total yearly cost.
- **Subscription_Interval**: One of weekly, monthly, quarterly or yearly.
- **Anchor_Day**: The day of the month used for a Subscription's due dates. For a detected Subscription it is the day of the first charge in the Recurring_Pattern. For a manual Subscription it is the day of the next due date the User entered. When a next due date is set from a Transaction date (Requirements 38 and 40), it becomes the day of that Transaction date.
- **Due_Date_Rule**: The rule that gives the date one Subscription_Interval after a given date. Weekly adds 7 days. Monthly adds 1 calendar month and quarterly adds 3 calendar months, both landing on the Anchor_Day, or on the last day of the month when that month has fewer days. Yearly adds 1 year on the same month and Anchor_Day, using 28 February in non-leap years when the Anchor_Day is 29 February. Example: Anchor_Day 31 gives 31 Jan 2024, 29 Feb 2024, 31 Mar 2024, 30 Apr 2024.
- **Amount_Tolerance**: The configurable maximum difference, as a percentage of a reference amount, for a charge to count as the same price (default set in the design).
- **Charge_Window**: The configurable number of days before or after an expected charge date within which a debit Transaction counts as that charge (default set in the design).
- **Min_Occurrences**: The configurable minimum number of debit Transactions, 2 or more, needed for a Recurring_Pattern (default set in the design).
- **Recurring_Pattern**: At least Min_Occurrences debit Transactions to the same Merchant where each charge after the first is dated within Charge_Window days of the date the Due_Date_Rule gives one Subscription_Interval after the previous charge, and each amount is within Amount_Tolerance of the latest charge's amount.
- **Subscription**: A repeating charge with Merchant, amount, Subscription_Interval, Anchor_Day, next due date, date it became active, last usage-check date, cancellation date (when ended) and status (proposed, active, rejected, ended).
- **Subscription_Detector**: The rule-based component that finds repeating charges in Transactions.
- **Usage_Check**: The monthly "Did you use this?" question for one active Subscription.
- **Renewal_Lead_Time**: The User-chosen number of days (1 or 2, default 2) before a Subscription renewal at which the renewal alert is sent.
- **AI_Provider**: The interface through which the Assistant_App requests AI output, with modes Cloud, OnDevice and None.
- **AI_Mode**: The selected AI_Provider mode: Cloud, OnDevice or None.
- **AI_Request**: One request for one Prompt_Type with one Redacted_Payload, made of 1 to 3 AI_Attempts.
- **AI_Attempt**: One send of a Redacted_Payload through the selected AI_Mode, plus the wait for its response.
- **Rule_Based_Result**: The output a feature produces with rule-based logic and template wording, without AI.
- **Summary_Builder**: The component that builds the small structured summary sent to the AI_Provider.
- **Redactor**: The component that removes personal identifiers, links and instruction text from any data before the data reaches the AI_Provider.
- **Instruction_Pattern_List**: The configurable list of text patterns that the Redactor treats as instruction text.
- **Redacted_Payload**: Any data sent to the AI_Provider after the Redactor has processed the data.
- **Redactor_Test_Set**: The part of the Test_Fixture_Set that holds Redactor inputs and expected outputs. The inputs contain person names, phone numbers, account and card numbers (including masked forms), UPI IDs, email addresses, links, and matches for the Instruction_Pattern_List.
- **Prompt_Type**: One of daily plan, evening review, weekly review, monthly money review, mail classifier, and SMS fallback parser.
- **Output_Validator**: The component that checks AI_Provider output against the schema and content rules for its Prompt_Type.
- **Output_Serializer**: The component that formats validated AI output objects as JSON matching the Prompt_Type schema.
- **Number_Token**: Any number in AI_Provider output. This covers digit sequences (including decimals, Indian or international digit grouping, and digits attached to ₹, Rs or %), times, dates, and the English number words zero to ninety-nine, hundred, thousand, lakh and crore.
- **Derivation_Rule**: A rule defined per Prompt_Type that states which numbers may be calculated from values in the Redacted_Payload, and how they are rounded. Examples are the difference or percentage change of two payload values, or a time inside a Free_Gap in the payload.
- **Restricted_Advice_List**: The configurable list of terms and phrases that mark advice on loans, investments, insurance products or other financial products.
- **Suggestion**: A piece of advice with text, type, reason, time shown and the User's response (accepted, ignored, dismissed).
- **Suggestion_Manager**: The component that shows Suggestions and records the User's response.
- **AI_Monthly_Cap**: The User-set maximum AI usage per calendar month for the Cloud mode, expressed as an estimated cost or a request count.
- **Proxy_Server**: A small server that holds the Cloud AI key and forwards Redacted_Payloads for users other than the developer; the Proxy_Server stores no user data.
- **Mail_Connector**: The component that connects to the User's mailbox using the configured access method (see Open decision 3).
- **Mailbox_Status**: The state of the mailbox Data_Source shown in the Settings_Screen: one of connected, needs reconnection or disconnected.
- **Connection_Test**: A sign-in and folder-list check that uses read access only. The Mail_Connector runs it before the Mailbox_Status becomes connected.
- **Mail_Sync**: One run of the Mail_Connector that fetches Mail_Headers from the mailbox.
- **Mail_Sync_Interval**: The User-set time between scheduled Mail_Syncs, from 15 minutes to 24 hours. The default is set in the design.
- **Mail_Header**: The sender, subject, date, labels/folders and a snippet of at most Snippet_Length characters of one email.
- **Snippet_Length**: The configurable maximum number of body characters fetched per email.
- **OTP_Pattern_List**: The configurable list of words and patterns (for example "OTP", "verification code", "one-time password") that identify one-time password or verification-code email.
- **OTP_Mail**: An email whose subject or snippet matches an entry in the OTP_Pattern_List.
- **Mail_Triage**: The component that shortlists and scores email.
- **Shortlist_Rules**: The configurable known senders, keywords and labels used to shortlist likely-important email.
- **Inbox_Item**: A stored fact record for one email: sender, subject, category, extracted fields, importance score and Source_Ref.
- **Importance_Threshold**: The configurable importance score (0 to 10) at or above which an Inbox_Item triggers a notification.
- **Rule_Score**: The importance score (whole number, 0 to 10) and category that the Mail_Triage assigns from the Shortlist_Rules alone, without the AI_Provider.
- **Sender_Penalty**: The number of points subtracted from the importance score of later email from one sender email address. It goes up by 2 each time the User taps "Not important" on an alert from that address.
- **Wrong_Mail_Alert**: An important-mail notification on which the User tapped "Not important".
- **Mail_Digest**: The once-a-day summary of new Inbox_Items.
- **Career_Importer**: The component that imports jobs, follow-ups and skills from a Huntfolio export file.
- **Huntfolio_Export**: A file exported from Huntfolio containing jobs, follow-ups and skills (format to be agreed with Huntfolio).
- **Huntfolio_Formatter**: The component that writes import records back into Huntfolio_Export format (used for round-trip tests).
- **Huntfolio_Record_Key**: The value that identifies one job, follow-up or skill across Huntfolio_Export files. It is defined together with the Huntfolio_Export format.
- **Huntfolio_Format_Version**: The format version identifier carried by a Huntfolio_Export. It is defined together with the Huntfolio_Export format.
- **Import_Preview**: The screen the Career_Importer shows after parsing a Huntfolio_Export and before writing any record. It lists the counts found and the skills that can become Goals.
- **Backup_Manager**: The component that writes and restores encrypted export files.
- **Export_File**: A file holding all user data, encrypted with a User passphrase.
- **Export_Passphrase**: The passphrase the User enters to encrypt or decrypt an Export_File. The Assistant_App does not store it.
- **Export_Data_Version**: The version number of the data layout inside an Export_File, written by the Backup_Manager.
- **Battery_Guide**: The one-time guide showing the User how to exempt the Assistant_App from battery optimisation on the User's phone brand.
- **Manufacturer_Steps**: The exemption steps in the Battery_Guide for one phone manufacturer. They are included only after they have been checked on a device of that manufacturer (verify).
- **Standard_Android_Steps**: The Battery_Guide steps that use Android's own battery-optimisation settings and are not specific to any manufacturer.
- **Health_Check**: The component that detects missed scheduled jobs, revoked permissions and disabled notifications.
- **Health_Status**: The state the Health_Check shows for one scheduled job or permission-dependent feature: Working, Needs attention or Off.
- **Quality_Monitor**: The on-device component that tracks parse accuracy, wrong alerts, subscription false detections and Suggestion accept rate.
- **Test_Fixture_Set**: The anonymised sample messages and expected results stored in the Project_Repository for tests.
- **Prompt_Injection_Fixture**: A message in the Test_Fixture_Set whose text contains an instruction aimed at the Assistant_App or the AI_Provider.
- **Device_Test_Plan**: The documented set of tests run on physical or emulated devices, outside the CI_Pipeline unit tests.
- **Low_RAM_Profile**: The device RAM size and Android version chosen for low-RAM testing (not yet chosen; see Assumptions).
- **App_Language**: The language of user-facing text. English is the default. Hindi or Tamil is available only if chosen under Open decision 6.

## Requirements

### Requirement 1: Encrypted local storage (Phase 0: Setup, cross-cutting)

**User Story:** As a User, I want all my data kept only on my phone and encrypted, so that nobody else can read my goals, money or messages.

#### Acceptance Criteria

1. THE Encrypted_Store SHALL hold every user record, including Goals, Tasks, Daily_Plans, Transactions, Merchants, Budgets, Bills, Subscriptions, Suggestions, Inbox_Items, Review_Queue items and Audit_Log entries, on the device in a database encrypted with the database key supplied by the Key_Manager.
2. WHEN the Assistant_App launches for the first time, THE Key_Manager SHALL generate the database key, SHALL protect the database key with a key held in the Android Keystore, and SHALL never write the database key in unencrypted form to storage, logs or an Export_File.
3. IF a process opens the database file without the database key from the Key_Manager, THEN THE Encrypted_Store SHALL fail to open and SHALL return no user records.
4. THE Encrypted_Store SHALL keep the database file in a form in which a raw byte search finds no stored text value (for example a Task title created during a test) in readable form.
5. IF the Key_Manager cannot obtain the database key at launch, THEN THE Assistant_App SHALL show an error stating that the stored data cannot be unlocked, SHALL leave the existing database file unchanged, and SHALL offer "Delete everything" (Requirement 8) and, once Requirement 60 is in place, restore from an Export_File.
6. THE Assistant_App SHALL provide every feature without the User creating or signing in to an account for the Assistant_App, and SHALL send no user data to any server for storage.
7. THE Assistant_App SHALL send user data off the device only as (a) a Redacted_Payload to the AI_Provider of the selected AI_Mode, directly or through the Proxy_Server, (b) mailbox credentials and mailbox requests sent by the Mail_Connector to the User's configured mail server, or (c) an Export_File written to a location the User chooses.
8. THE Assistant_App SHALL exclude all app data from Android cloud backup and from Android device-to-device transfer.

### Requirement 2: Read-only behaviour (Phase 0: Setup, cross-cutting)

**User Story:** As a User, I want the app to only read and suggest, so that the app can never act on my money, messages or calendar without me.

#### Acceptance Criteria

1. THE Assistant_App SHALL hold no Android permission that allows sending SMS, placing calls or writing to the calendar provider, and SHALL access SMS, notifications, the calendar provider and the mailbox with read operations only.
2. THE Assistant_App SHALL contain no function that sends SMS or email, makes or starts a payment, cancels a Subscription with a Merchant, replies to, moves, flags or deletes email, or creates, changes or deletes Calendar_Events.
3. THE Notification_Reader SHALL leave notifications posted by other apps unchanged and SHALL not dismiss them, reply to them or trigger their actions.
4. THE Assistant_App SHALL apply a proposed change inside the Assistant_App (new Task, Bill, Transaction, Subscription status or plan change) only after the User accepts that proposal.
5. IF the User dismisses a proposal, ignores it or leaves it unanswered, THEN THE Assistant_App SHALL leave all stored data unchanged by that proposal.

### Requirement 3: Keep facts, not messages (Phase 0: Setup, cross-cutting)

**User Story:** As a User, I want the app to keep only the facts it needs, so that my full messages and email are never copied into the app.

#### Acceptance Criteria

1. WHEN the Assistant_App processes a Money_Message or an email, THE Assistant_App SHALL store only the extracted fields (the Parsed_Message fields for a Money_Message, the Inbox_Item fields for an email) and a Source_Ref.
2. WHEN extraction from a Money_Message or an email finishes, whether extraction succeeded or failed, THE Assistant_App SHALL keep no Message_Text in the Encrypted_Store, files, caches, scheduled-job data or logs, except the redacted text of Review_Queue items as defined in Requirement 30.
3. IF an SMS comes from a sender not in the Bank_Sender_List, or a notification comes from an app not in the Money_App_List, THEN THE Assistant_App SHALL store no part of that SMS or notification.
4. WHEN the Message_Parser classifies a message as an OTP_Message, THE Message_Parser SHALL discard the OTP_Message without storing, logging or sending any part of the OTP_Message, and SHALL not add the OTP_Message to the Review_Queue or include any part of the OTP_Message in a Redacted_Payload.
5. THE Assistant_App SHALL exclude Message_Text and OTP values from application logs and crash reports in every build type.

### Requirement 4: Message text is untrusted (Phase 0: Setup, cross-cutting)

**User Story:** As a User, I want text from SMS and email treated only as data, so that a malicious message cannot steer the app or the AI.

#### Acceptance Criteria

1. THE Assistant_App SHALL treat Message_Text as data only and SHALL derive setting changes, notifications and stored records only from the User's settings, the User's accepted proposals and the app's own rules.
2. THE Assistant_App SHALL make no network request to any address found in Message_Text.
3. WHEN text derived from Message_Text is included in a Redacted_Payload, THE Redactor SHALL remove every link and every text span matching the Instruction_Pattern_List before the Redacted_Payload reaches the AI_Provider.
4. WHEN a Prompt_Injection_Fixture is processed, THE Assistant_App SHALL produce no Task, Bill, Transaction, Suggestion, notification, setting change or network request that follows the injected instruction.
5. THE Test_Fixture_Set SHALL contain at least one Prompt_Injection_Fixture for each message source (SMS, app notification, email) that the current phase reads.

### Requirement 5: Permissions only when needed (Phase 0: Setup, cross-cutting)

**User Story:** As a User, I want permissions asked one at a time with a plain reason, so that I understand and control what the app can read.

#### Acceptance Criteria

1. WHEN the User enables a feature that needs an Android permission not yet granted, THE Permission_Manager SHALL request only the permissions of that feature at that moment, and SHALL request no permission at first launch before the User enables a feature.
2. WHEN the Permission_Manager is about to request an Android permission, THE Permission_Manager SHALL first show a Permission_Rationale stating what data the permission lets the Assistant_App read and which feature uses that data, with a "Continue" action and a "Not now" action.
3. THE Permission_Manager SHALL request one Android permission group per system dialog.
4. WHEN a feature needs notification-listener access, THE Permission_Manager SHALL open the Android notification-access settings page after the User chooses "Continue" on the Permission_Rationale, and SHALL check whether access was granted when the User returns to the Assistant_App.
5. IF the User denies a permission or chooses "Not now" on the Permission_Rationale, THEN THE Assistant_App SHALL keep the dependent feature off and SHALL keep every feature that does not need that permission working.
6. IF Android no longer shows the system dialog for a permission the User denied earlier, THEN THE Permission_Manager SHALL show the Permission_Rationale with an action that opens the Assistant_App's page in Android settings.
7. IF a previously granted permission is found revoked when the Assistant_App starts, returns to the foreground or begins a background read, THEN THE Assistant_App SHALL turn the dependent feature off, SHALL stop reading from the dependent Data_Source, and SHALL show that Data_Source as disconnected in the Settings_Screen.
8. WHILE the device runs Android 13 or later, WHEN the User enables the first Notification_Type and the notification permission is not granted, THE Permission_Manager SHALL request the notification permission.

### Requirement 6: App lock (Phase 0: Setup, cross-cutting)

**User Story:** As a User, I want a biometric or PIN lock on opening the app, so that someone holding my phone cannot see my data.

#### Acceptance Criteria

1. WHEN the Assistant_App starts, including when started from a notification or the Widget, THE App_Lock SHALL require biometric or Device_Credential authentication before showing any user data.
2. WHEN the Assistant_App returns to the foreground after spending longer than the Lock_Timeout in the background, THE App_Lock SHALL require biometric or Device_Credential authentication again before showing any user data.
3. IF authentication fails or is cancelled, THEN THE App_Lock SHALL keep all user data hidden and SHALL show the lock screen with an action to try authentication again.
4. IF the device has no biometric enrolled and no Device_Credential set, THEN THE App_Lock SHALL keep all user data hidden and SHALL show a message asking the User to set a device screen lock.
5. WHEN the Assistant_App moves to the background, THE Assistant_App SHALL show no user data in the Android recent-apps preview.

### Requirement 7: Audit log (Phase 0: Setup, cross-cutting)

**User Story:** As a User, I want a log of what the app read and what it sent to AI, so that I can check the app keeps its promises.

#### Acceptance Criteria

1. WHEN a read from SMS, app notifications, the calendar, the mailbox or an import file finishes, including a read that returns zero items or fails, THE Audit_Log SHALL record the Data_Source, the time of the read, the number of items read and whether the read succeeded.
2. WHEN the Assistant_App sends a Redacted_Payload to an AI_Provider, THE Audit_Log SHALL record the time, the AI_Mode, the Prompt_Type, the exact Redacted_Payload sent and whether the request succeeded.
3. IF the Audit_Log cannot record a Redacted_Payload, THEN THE AI_Provider SHALL not send that Redacted_Payload.
4. THE Settings_Screen SHALL show the Audit_Log entries newest first, with each entry showing every recorded field, including the full text of any Redacted_Payload.
5. THE Audit_Log SHALL contain no Message_Text, OTP value, credential or key other than the content of Redacted_Payloads.

### Requirement 8: Connected sources, delete everything (Phase 0: Setup, cross-cutting)

**User Story:** As a User, I want to see and disconnect each data source and wipe everything in one step, so that I stay in control of my data.

#### Acceptance Criteria

1. THE Settings_Screen SHALL list each Data_Source with a status of connected or disconnected.
2. WHEN the User disconnects a Data_Source, THE Assistant_App SHALL stop all reads from that Data_Source, including scheduled background reads, and SHALL delete any stored credentials and keys for that Data_Source.
3. WHEN the User disconnects a Data_Source, THE Assistant_App SHALL ask whether to delete the stored facts that came from that Data_Source, and SHALL delete every stored fact whose Source_Ref points to that Data_Source only if the User chooses to delete them.
4. WHEN the User taps "Delete everything", THE Data_Wiper SHALL show one confirmation dialog stating that all data in the Assistant_App will be deleted and that Export_Files saved outside the Assistant_App are kept.
5. WHEN the User confirms the "Delete everything" dialog, THE Data_Wiper SHALL delete the Encrypted_Store, the database key, settings, stored credentials and keys, scheduled notifications and background jobs, and cached files.
6. IF the User cancels the "Delete everything" dialog, THEN THE Data_Wiper SHALL delete nothing.
7. IF the wipe stops before it finishes, THEN THE Data_Wiper SHALL finish the wipe at the next launch before the Assistant_App shows any screen other than the First_Run_Screen.
8. WHEN the Data_Wiper finishes, THE Assistant_App SHALL show the First_Run_Screen and THE Widget SHALL show no user data.

### Requirement 9: No analytics or ads (Phase 0: Setup, cross-cutting)

**User Story:** As a User, I want no ads and no content-collecting analytics, so that my data is not used for anything else.

#### Acceptance Criteria

1. THE Assistant_App SHALL include no advertising library or advertising code and SHALL show no advertisements.
2. THE Assistant_App SHALL include no analytics or crash-reporting function that sends user data, Message_Text or Source_Refs off the device.

### Requirement 10: Project setup, build and release (Phase 0: Setup)

**User Story:** As the developer, I want a working project skeleton with CI and signed release builds, so that every later phase can be built and shipped safely.

#### Acceptance Criteria

1. THE Assistant_App SHALL install and complete first launch to the First_Run_Screen on every Android version from 8.0 (API level 26) through 15 (API level 35).
2. WHEN a commit is pushed to any branch of the Project_Repository, THE CI_Pipeline SHALL build the Assistant_App and run all unit tests.
3. IF the build fails or any unit test fails, THEN THE CI_Pipeline SHALL mark the run as failed and SHALL report the failing step or test.
4. THE Release_Build SHALL be shrunk and obfuscated with R8 and signed with the release signing key.
5. IF the release signing key is not available in the Secret_Store, THEN THE CI_Pipeline SHALL fail the release build and SHALL produce no unsigned or debug-signed Release_Build.
6. THE Project_Repository SHALL contain no signing keystore file, API key or password in any tracked file.
7. THE CI_Pipeline SHALL read signing keys and API keys from the Secret_Store and SHALL not print them in build logs.
8. THE Project_Repository SHALL contain a CLAUDE.md file stating the project rules listed in the plan, including at least the rules of Requirements 1 to 4 and 9 and the fixed technical constraints listed in the Introduction.
9. THE Assistant_App SHALL apply one Material 3 theme with light and dark variants on every screen, showing the dark variant while the Android system dark theme is on and the light variant otherwise.

### Requirement 11: Goals (Phase 1: Core)

**User Story:** As a User, I want year, month and week goals with targets, so that I can see whether I am on track.

#### Acceptance Criteria

1. WHEN the User saves a Goal with a name of 1 to 100 characters after removing leading and trailing spaces, a Goal_Period, a start date, a numeric target from 0.01 to 999,999,999.99 and a unit of 1 to 30 characters, THE Goal_Manager SHALL store the Goal as active and SHALL count the Goal's progress separately for each Period_Instance, starting with the Period_Instance that contains the start date.
2. IF the User saves a Goal with a name or unit that is empty after removing leading and trailing spaces, a name longer than 100 characters, a unit longer than 30 characters, no Goal_Period, no start date, or a target outside 0.01 to 999,999,999.99, THEN THE Goal_Manager SHALL reject the Goal, leave any stored version of the Goal unchanged, keep the entered values in the form and mark each invalid field.
3. WHEN the User links an active month Goal to an active year Goal, or an active week Goal to an active month Goal, THE Goal_Manager SHALL store the link, replacing any earlier parent link of that Goal, and SHALL show the linked Goal under the parent Goal.
4. WHEN the User logs a progress amount from 0.01 to 999,999,999.99 for an active Goal, THE Goal_Manager SHALL add the amount to the Goal's progress for the Period_Instance that contains the current date.
5. WHEN the User marks done a Task linked to an active Goal whose unit is "tasks" (ignoring letter case), THE Goal_Manager SHALL add 1 to that Goal's progress for the Period_Instance that contains the Task's completion date.
6. WHEN the User edits or archives a Goal, THE Goal_Manager SHALL keep the Goal's progress records for every Period_Instance unchanged.
7. IF the User links a Goal to a parent Goal whose Goal_Period is not the next larger Goal_Period (week to month, month to year), or to an archived Goal, THEN THE Goal_Manager SHALL reject the link, show that the link is not allowed and keep the existing links unchanged.
8. IF the User logs a progress amount that is not a number or is outside 0.01 to 999,999,999.99, THEN THE Goal_Manager SHALL reject the amount, show that the amount is invalid and leave the Goal's progress unchanged.
9. WHEN the User archives a Goal, THE Goal_Manager SHALL remove the Goal from the list of active Goals, exclude the Goal from Behind_Goals and goal nudges, and stop accepting progress for the Goal.

### Requirement 12: Tasks (Phase 1: Core)

**User Story:** As a User, I want to add, complete and move tasks, so that my daily plan reflects what I need to do.

#### Acceptance Criteria

1. WHEN the User saves a Task with a title of 1 to 200 characters after removing leading and trailing spaces, THE Task_Manager SHALL store the Task with Task_Status open and with the optional due date, Planned_Date, estimated duration and link to an active Goal that the User entered.
2. IF the User saves a Task with a title that is empty after removing leading and trailing spaces or longer than 200 characters, THEN THE Task_Manager SHALL reject the Task, leave any stored version of the Task unchanged, keep the entered values in the form and mark the title field as invalid.
3. WHEN the User marks a Task done, THE Task_Manager SHALL set the Task_Status to done, record the completion date and time, and exclude the Task from open Task lists and from Priority selection.
4. WHEN the User moves a Task to a date that differs from the Task's Planned_Date and is not earlier than the current date, THE Task_Manager SHALL set the Planned_Date to that date, record one Task_Move, and remove the Task from the Priorities and Time_Blocks of the previous Planned_Date.
5. WHEN the User deletes a Task, THE Task_Manager SHALL remove the Task and any Time_Block assigned to the Task, and SHALL leave unchanged any Goal progress already recorded from the Task.
6. IF the User saves a Task with an estimated duration outside 1 to 1,440 minutes, THEN THE Task_Manager SHALL reject the Task, leave any stored version of the Task unchanged and mark the duration field as invalid.
7. IF the User moves a Task to a date earlier than the current date, THEN THE Task_Manager SHALL reject the move and leave the Task's Planned_Date unchanged.

### Requirement 13: Quick add (Phase 1: Core)

**User Story:** As a User, I want to capture a task in one line from anywhere in the app or the widget, so that capture takes seconds.

#### Acceptance Criteria

1. THE Assistant_App SHALL show a Quick_Add entry on the Today_Screen and on the Widget.
2. WHEN the User confirms Quick_Add text that has 1 to 200 characters after line breaks are replaced with spaces and leading and trailing spaces are removed, THE Task_Manager SHALL create a Task with Task_Status open, that text as the title, the current date as the Planned_Date, and no due date, estimated duration or Goal link.
3. WHEN a Quick_Add entry is saved, THE Today_Screen SHALL show the new Task among the Tasks planned for the current date within 1 second, without further input from the User.
4. IF the User confirms Quick_Add with text that is empty after removing leading and trailing spaces, THEN THE Task_Manager SHALL create no Task and THE Assistant_App SHALL keep the Quick_Add entry open.
5. THE Assistant_App SHALL accept at most 200 characters in the Quick_Add entry.

### Requirement 14: Daily plan builder (Phase 1: Core)

**User Story:** As a User, I want a short plan every morning with 3 priorities and time blocks, so that I know what to do first.

#### Acceptance Criteria

1. WHEN the morning brief time is reached and no Daily_Plan exists for the current date, THE Plan_Builder SHALL create a Daily_Plan for the current date.
2. WHEN the Plan_Builder creates a Daily_Plan, THE Plan_Builder SHALL select as Priorities the 3 highest-ranked open Tasks whose Planned_Date is empty or not later than the current date, or all such Tasks if there are fewer than 3. The ranking keys apply in this order: overdue Tasks (due date before the current date) before other Tasks; earlier due date before later due date, with Tasks without a due date last; Tasks linked to a Behind_Goal before unlinked Tasks; and earlier creation time before later creation time.
3. WHEN the Plan_Builder creates a Daily_Plan, THE Plan_Builder SHALL place one Time_Block for each Priority that has an estimated duration, with a length equal to the estimated duration, entirely inside the Working_Hours of the current date and starting no earlier than the time the Daily_Plan is created.
4. THE Plan_Builder SHALL produce Time_Blocks that do not overlap each other and whose start time is earlier than their end time.
5. WHEN the Plan_Builder is given the same Tasks, Goals, Calendar_Events, Bills, settings, current date and creation time twice, THE Plan_Builder SHALL produce identical Daily_Plans (determinism property).
6. WHEN the Plan_Builder creates a Daily_Plan, THE Plan_Builder SHALL include in the Daily_Plan every unpaid Bill whose due date is from the current date to 2 days after the current date, and every Behind_Goal.
7. WHEN the User reorders a Priority, replaces a Priority with another open Task, or removes a Priority, THE Plan_Builder SHALL store the change, SHALL remove the Time_Block of a removed Priority, and SHALL not overwrite the change for the rest of that date.
8. WHILE the AI_Mode is None, THE Today_Screen SHALL show the Daily_Plan with fixed template wording and no AI-written text.
9. WHEN the User opens the Today_Screen and no Daily_Plan exists for the current date, THE Plan_Builder SHALL create a Daily_Plan for the current date before the Today_Screen shows the plan.
10. IF the estimated duration of a Priority does not fit in the Working_Hours left on the current date without overlapping another Time_Block, THEN THE Plan_Builder SHALL keep the Task as a Priority without a Time_Block.

### Requirement 15: Today screen (Phase 1: Core)

**User Story:** As a User, I want one screen showing today's plan, so that I can see priorities, schedule, bills and goals at a glance.

#### Acceptance Criteria

1. THE Today_Screen SHALL show, for the current date: the Priorities in rank order; the Time_Blocks in start-time order with their start and end times; the other open Tasks whose Planned_Date is the current date; the Bills in the Daily_Plan; and the Behind_Goals with their Days_Behind.
2. WHEN the User marks a Priority done on the Today_Screen, THE Task_Manager SHALL mark the Task done, and THE Today_Screen SHALL show the Priority as done within 1 second.
3. WHEN the device's local date changes while the Today_Screen is open, THE Today_Screen SHALL show the Daily_Plan of the new date within 1 minute, and the Plan_Builder creates that Daily_Plan first if none exists.
4. IF the Daily_Plan for the current date has no Priorities, THEN THE Today_Screen SHALL show a message that no Priorities are set, together with the Quick_Add entry.

### Requirement 16: Evening review (Phase 1: Core)

**User Story:** As a User, I want a 30-second evening review, so that I can close the day and carry unfinished work to tomorrow.

#### Acceptance Criteria

1. WHEN the User opens the Evening_Review, THE Review_Flow SHALL list on one screen every Task whose Planned_Date is the Review_Date, with each Task's Task_Status.
2. THE Review_Flow SHALL offer three choices for each open Task (done, move to tomorrow and drop), each selected with a single tap, and SHALL let the User change a choice before finishing.
3. WHEN the User finishes the Evening_Review, THE Review_Flow SHALL have the Task_Manager move each Task marked "move to tomorrow" to the date after the Review_Date, recording one Task_Move per Task.
4. WHEN the User finishes the Evening_Review, THE Review_Flow SHALL count each listed Task as exactly one of done, moved, dropped or left open, so that the four counts add up to the number of listed Tasks (no Task is lost).
5. WHEN the User finishes the Evening_Review, THE Review_Flow SHALL show a summary with the number of Tasks done, moved, dropped and left open.
6. WHEN the User finishes the Evening_Review, THE Review_Flow SHALL have the Task_Manager mark each Task marked "done" as done and set each Task marked "drop" to Task_Status dropped.
7. IF the User leaves the Evening_Review without finishing, THEN THE Review_Flow SHALL apply none of the choices made in that Evening_Review and SHALL leave every Task unchanged.
8. IF no Task has the Review_Date as its Planned_Date, THEN THE Review_Flow SHALL show a message that no Tasks were planned, together with a control to finish the Evening_Review.

### Requirement 17: Progress, streaks and weekly progress report (Phase 1: Core)

**User Story:** As a User, I want to see progress and streaks and get a weekly report, so that I stay motivated and notice slips early.

#### Acceptance Criteria

1. THE Progress_Tracker SHALL show, for each active Goal, the progress and target for the current Period_Instance and the percentage reached. The percentage is progress ÷ target × 100, rounded down to a whole number, and is shown above 100 when progress exceeds the target.
2. THE Progress_Tracker SHALL calculate Days_Behind for each active Goal using the Expected_Progress definition in the Glossary. Elapsed days are the whole days before the current date, and total days are all days of the current Period_Instance. Both are counted from the later of the Period_Instance's first day and the Goal's start date.
3. THE Progress_Tracker SHALL calculate the Streak as the count of consecutive dates, in device local time, that meet the Streak_Rule. The count ends with the current date if the current date meets the Streak_Rule, and otherwise ends with the previous date.
4. WHEN the User changes the Streak_Rule, THE Progress_Tracker SHALL recalculate the Streak from stored history and show the recalculated Streak the next time the Streak is shown.
5. WHEN the User-set last day of the week ends at local midnight, THE Report_Generator SHALL create a weekly progress report that lists the progress against target of each week Goal that was active during that week, and each Task with 2 or more Task_Moves recorded during that week.
6. IF the Assistant_App cannot run when a week ends (for example because the device is off), THEN THE Report_Generator SHALL create that week's report the next time the Assistant_App runs, with the same content as a report created on time.

### Requirement 18: Notification controls (Phase 1: Core)

**User Story:** As a User, I want few notifications with quiet hours, a daily cap, snooze and per-type switches, so that the app helps without nagging.

#### Acceptance Criteria

1. THE Notification_Governor SHALL provide an on/off switch for each Notification_Type in the Settings_Screen.
2. WHILE the current time is within Quiet_Hours, including Quiet_Hours that span midnight, THE Notification_Governor SHALL hold notifications instead of showing them.
3. IF, at the time Quiet_Hours end, a held notification refers to a Calendar_Event whose start time has passed, or to a Bill due date or Subscription renewal date that is before the current date, THEN THE Notification_Governor SHALL move the notification to the Inbox_List instead of showing it.
4. WHEN the number of notifications shown on a calendar day (local midnight to local midnight) reaches the Daily_Cap (User-set, 1 to 20), THE Notification_Governor SHALL place further notifications that day in the Inbox_List instead of showing them. Notifications of a Cap_Exempt_Type are still shown and do not count toward the Daily_Cap.
5. WHEN the User snoozes a notification for a chosen duration from 5 to 1,440 minutes, THE Notification_Governor SHALL show the notification again within 1 minute after the duration ends, applying Quiet_Hours, and SHALL not count the repeat toward the Daily_Cap.
6. THE Notification_Governor SHALL attach a "Why am I seeing this?" action to every notification.
7. WHEN the User taps "Why am I seeing this?", THE Assistant_App SHALL show the Why_Explanation, naming the rule, the data and the setting that caused the notification, and a control that turns off that Notification_Type at once.
8. IF the number of notifications of one Notification_Type shown on a calendar day has reached that type's Per_Type_Limit, THEN THE Notification_Governor SHALL place further notifications of that type that day in the Inbox_List, even when the Daily_Cap has not been reached.
9. IF a Notification_Type is switched off, THEN THE Notification_Governor SHALL neither show notifications of that type nor add them to the Inbox_List, and SHALL cancel pending notifications of that type.
10. WHEN Quiet_Hours end, THE Notification_Governor SHALL show, within 1 minute and oldest first, the held notifications not moved to the Inbox_List under criterion 3, counting each one toward the Daily_Cap.

### Requirement 19: Scheduled delivery and reliability (Phase 1: Core)

**User Story:** As a User, I want reminders to arrive on time even after a reboot or in battery saver, so that I can rely on them.

#### Acceptance Criteria

1. WHEN the device finishes rebooting, THE Notification_Scheduler SHALL reschedule all pending notifications and scheduled jobs within 2 minutes.
2. WHILE Exact_Alarm_Access is available, THE Notification_Scheduler SHALL deliver each Time_Specific_Reminder within 1 minute of its scheduled time, including while the device is in Doze mode.
3. IF Exact_Alarm_Access is not available or is revoked, THEN THE Notification_Scheduler SHALL schedule Time_Specific_Reminders as inexact alarms, and THE Settings_Screen SHALL show a notice that reminders may arrive late, with a control that opens the Android exact-alarm setting.
4. WHEN the User changes the time zone or the device time, THE Notification_Scheduler SHALL reschedule pending notifications within 1 minute, so that notifications set to a User-set clock time (morning brief, evening review) keep that local clock time, and event reminders stay tied to their Calendar_Event's start as a point in time (Requirement 24).
5. IF a notification's scheduled time passed while the device was off, THEN THE Notification_Scheduler SHALL, after the reboot, place the notification in the Inbox_List when it refers to a Calendar_Event whose start time has passed or to a Bill due date or Subscription renewal date before the current date, and SHALL otherwise pass the notification to the Notification_Governor to be shown.

### Requirement 20: Morning brief, evening review and goal nudge notifications (Phase 1: Core)

**User Story:** As a User, I want a morning brief, an evening review prompt and gentle goal nudges, so that I start and end the day with a plan.

#### Acceptance Criteria

1. WHEN the User-set morning brief time is reached (default 08:00), THE Notification_Scheduler SHALL show, subject to the Notification_Governor, a morning brief notification with the count of Priorities in the current date's Daily_Plan and the title of the first Priority, or a statement that no Priorities are set when the count is 0.
2. WHEN the User taps the morning brief notification, THE Assistant_App SHALL open the Today_Screen after any App_Lock authentication that Requirement 6 requires.
3. WHEN the User-set evening review time is reached (default 21:00), THE Notification_Scheduler SHALL show, subject to the Notification_Governor, an evening review notification.
4. WHEN the User taps the evening review notification, THE Assistant_App SHALL open the Evening_Review, with the date the notification was shown as the Review_Date, after any App_Lock authentication that Requirement 6 requires.
5. WHEN the morning brief time is reached and one or more active week Goals have Days_Behind of 2 or more, THE Notification_Scheduler SHALL show, subject to the Notification_Governor, one goal nudge naming the Goal with the highest Days_Behind (the earliest-created Goal on a tie) and that Goal's Days_Behind.
6. THE Notification_Scheduler SHALL show at most 1 goal nudge per calendar day across all Goals.
7. WHEN the User changes the morning brief time or the evening review time to any time from 00:00 to 23:59, THE Notification_Scheduler SHALL cancel the pending notification of that type and schedule the next one at the new time: on the current date if the new time has not yet passed, and otherwise on the next date.

### Requirement 21: Home-screen widget (Phase 1: Core)

**User Story:** As a User, I want a home-screen widget with today's priorities, so that I can see and tick them without opening the app.

#### Acceptance Criteria

1. THE Widget SHALL show the title and done or open status of each Priority in the current date's Daily_Plan, and a Quick_Add button.
2. WHEN the User ticks an open Priority on the Widget, THE Task_Manager SHALL mark the Task done without requiring App_Lock authentication.
3. WHEN a Task, the Daily_Plan or a Calendar_Event changes, or the device's local date changes, THE Widget SHALL show the change within 1 minute.
4. THE Widget SHALL show no money amounts, account digits, Bill details, Goal progress or email content.
5. WHERE calendar reading is enabled (Phase 2), THE Widget SHALL show the start time and title of the next timed Calendar_Event that starts after the current time on the current date.
6. IF no Daily_Plan exists for the current date or the Daily_Plan has no Priorities, THEN THE Widget SHALL show a message that no Priorities are set, together with the Quick_Add button.
7. WHEN the Data_Wiper finishes, THE Widget SHALL show no user data within 1 minute.

### Requirement 22: Read the phone calendar (Phase 2: Calendar)

**User Story:** As a User, I want the app to read my phone calendar, so that my plan fits around meetings.

#### Acceptance Criteria

1. WHEN the User enables calendar reading and grants the calendar read permission after the Permission_Rationale, THE Settings_Screen SHALL list every calendar in the phone's calendar provider, including calendars synced from Google accounts, with each calendar's display name and account name, and with every calendar initially marked as a Selected_Calendar.
2. THE Calendar_Reader SHALL read Calendar_Events only from Selected_Calendars.
3. THE Calendar_Reader SHALL keep a local cache of every Calendar_Event from the Selected_Calendars that overlaps the Calendar_Cache_Window, including Calendar_Events that start before the window or end after the window.
4. WHEN the phone's calendar provider reports a change, the date changes, the device time zone changes, the User changes the Selected_Calendars, or the Assistant_App comes to the foreground, THE Calendar_Reader SHALL refresh the cache within 5 minutes so that the cache holds exactly the provider's current Calendar_Events from the Selected_Calendars that overlap the Calendar_Cache_Window.
5. THE Calendar_Reader SHALL store for each Calendar_Event only the start, end, title, all-day flag and a Source_Ref identifying the calendar and the event occurrence, with the start and end of a timed Calendar_Event stored as points in time that do not change when the device time zone changes, and the start and end of an all-day Calendar_Event stored as calendar dates.
6. THE Calendar_Reader SHALL store each occurrence of a recurring event that overlaps the Calendar_Cache_Window as a separate Calendar_Event, with a change made to a single occurrence applied to that occurrence only, and SHALL exclude every event or occurrence that is cancelled in the phone's calendar provider or that the User has declined.
7. IF the User denies or revokes the calendar read permission, or no Selected_Calendar remains, THEN THE Plan_Builder SHALL build Daily_Plans without Calendar_Events and THE Today_Screen SHALL show that the calendar is not connected while showing all other Daily_Plan content.
8. IF a read from the phone's calendar provider fails, THEN THE Calendar_Reader SHALL keep the previous cache unchanged and retry at the next refresh trigger, and THE Today_Screen SHALL show the date and time of the last successful refresh.

### Requirement 23: Events in Today (Phase 2: Calendar)

**User Story:** As a User, I want today's events shown in my plan, so that I see my whole day in one place.

#### Acceptance Criteria

1. THE Today_Screen SHALL show every timed Calendar_Event that overlaps the current date (00:00 to 24:00 in the device's current time zone) among the Time_Blocks, ordered by each item's start on the current date (00:00 for a Calendar_Event that started on an earlier date), with items of equal start ordered by earlier end and then by title from A to Z.
2. THE Plan_Builder SHALL place Time_Blocks only within Working_Hours and outside every timed Calendar_Event of that date, treating overlapping Calendar_Events from different Selected_Calendars as one busy period, using only the part of a Calendar_Event crossing midnight that falls on that date, and treating a timed Calendar_Event whose start equals its end as blocking no time.
3. THE Plan_Builder SHALL create no Time_Block that overlaps a timed Calendar_Event, where a Time_Block that starts exactly when a Calendar_Event ends or ends exactly when a Calendar_Event starts does not overlap (invariant).
4. THE Today_Screen SHALL show every all-day Calendar_Event whose date range includes the current date, based on the event's calendar dates regardless of the device time zone, in a list above the timed items on each date that the all-day Calendar_Event covers, and THE Plan_Builder SHALL treat all-day Calendar_Events as blocking no time.
5. THE Today_Screen SHALL show for each timed Calendar_Event the title and the start and end times in the device's current time zone and 12-hour or 24-hour setting, SHALL add the date to a start or end time that falls on a date other than the current date, and SHALL show a placeholder indicating no title when the title is empty.
6. IF a Calendar_Reader refresh adds or moves a timed Calendar_Event so that the Calendar_Event overlaps an existing Time_Block of a Daily_Plan, THEN THE Today_Screen SHALL mark that Time_Block as conflicting and offer to move the Time_Block to the earliest later Free_Gap on that date long enough to hold it, or to remove the Time_Block if no such Free_Gap exists, and THE Plan_Builder SHALL keep the Time_Block unchanged until the User accepts.

### Requirement 24: Event reminders (Phase 2: Calendar)

**User Story:** As a User, I want a reminder before each meeting, so that I am not late.

#### Acceptance Criteria

1. WHEN the current time reaches Event_Lead_Time before the start of a timed Calendar_Event, THE Notification_Scheduler SHALL show, subject to the Notification_Governor, exactly one event reminder for that Calendar_Event with the event title and the start time in the device's current time zone, including one event reminder for each occurrence of a recurring event.
2. WHEN a Calendar_Event's start time or title changes, the User changes the Event_Lead_Time, or the device time zone changes, THE Notification_Scheduler SHALL reschedule the pending event reminder to Event_Lead_Time before the Calendar_Event's start time as a point in time, with the current title.
3. WHEN a Calendar_Reader refresh removes a Calendar_Event from the cache (deleted, cancelled, declined, or its calendar no longer a Selected_Calendar), or calendar reading is turned off, THE Notification_Scheduler SHALL cancel the pending event reminder for that Calendar_Event.
4. THE Notification_Scheduler SHALL schedule no event reminders for all-day Calendar_Events, including all-day Calendar_Events that cover more than one date.
5. IF a Calendar_Reader refresh adds a timed Calendar_Event, or moves its start, so that the start is in the future and less than Event_Lead_Time away, THEN THE Notification_Scheduler SHALL show the event reminder, subject to the Notification_Governor, within 1 minute of that refresh.
6. IF an event reminder would be shown at or after the start of its Calendar_Event, THEN THE Notification_Governor SHALL place the event reminder in the Inbox_List instead of showing it.

### Requirement 25: Focus-block suggestions in free gaps (Phase 2: Calendar)

**User Story:** As a User, I want the app to suggest focus blocks in free gaps, so that I use open time for important tasks.

#### Acceptance Criteria

1. WHEN the Plan_Builder creates the Daily_Plan for the current date, or WHEN a Calendar_Reader refresh or a Time_Block change alters the free time of the current date, THE Gap_Finder SHALL find every Free_Gap of the current date whose part after the current time is at least Min_Focus_Length long (User-set, 15 to 240 minutes), using only that part as the Free_Gap.
2. WHEN a Free_Gap is found, THE Gap_Finder SHALL show on the Today_Screen one Focus_Block_Proposal for that Free_Gap, built by taking open Tasks that have an estimated duration and no Time_Block on the current date in Plan_Builder ranking order, adding each Task whose estimated duration fits in the remaining length of the Free_Gap and skipping each Task that does not fit, and SHALL show no Focus_Block_Proposal for a Free_Gap in which no such Task fits.
3. THE Gap_Finder SHALL produce only Focus_Block_Proposals in which the sum of the estimated durations of the proposed Tasks is less than or equal to the length of the Free_Gap and in which each Task appears in at most one Focus_Block_Proposal for the date (invariant).
4. WHEN the User accepts a Focus_Block_Proposal, THE Plan_Builder SHALL add one Time_Block per proposed Task to the Daily_Plan, placed back to back in proposal order from the later of the Free_Gap start and the current time, and SHALL leave the phone calendar unchanged.
5. IF, when the User accepts a Focus_Block_Proposal, a Calendar_Event or Time_Block overlaps the Free_Gap, or the part of the Free_Gap after the current time is shorter than the sum of the proposed Tasks' estimated durations, THEN THE Plan_Builder SHALL add no Time_Block, THE Today_Screen SHALL show a message that the gap is no longer free, and THE Gap_Finder SHALL find Free_Gaps again for the current date.
6. WHEN the User dismisses a Focus_Block_Proposal, THE Gap_Finder SHALL add no Time_Block and SHALL show no further Focus_Block_Proposal for that Free_Gap on that date unless the Free_Gap's start or end changes.

### Requirement 26: Read bank and UPI SMS (Phase 3: Money)

**User Story:** As a User, I want the app to read my bank and UPI SMS, so that my spending is tracked without manual entry.

#### Acceptance Criteria

1. WHILE SMS reading is enabled and the SMS permission is granted, WHEN an SMS arrives on any SIM from a sender ID that matches an entry in the Bank_Sender_List (ignoring letter case and any prefix or suffix pattern stored with that entry), THE Sms_Reader SHALL pass the Message_Text, the Message_Origin and the Received_Time to the Message_Parser within 60 seconds of arrival.
2. WHEN the User first enables SMS reading and the SMS permission is granted, THE Sms_Reader SHALL offer the SMS_History_Import once, covering SMS from Bank_Sender_List senders received on or after the same day of the month 3 calendar months earlier (or the last day of that month when that day does not exist), and SHALL import nothing unless the User accepts.
3. WHEN an SMS arrives from a sender ID that matches no entry in the Bank_Sender_List, THE Sms_Reader SHALL discard the SMS without storing, logging or passing any part of the SMS to the Message_Parser.
4. THE Sms_Reader SHALL read the Bank_Sender_List from data kept separate from the Message_Parser code, so that a sender ID can be added or removed without changing parser code.
5. WHEN the SMS_History_Import finishes, THE Assistant_App SHALL show the number of SMS read, the number of Transactions created and the number of items added to the Review_Queue.
6. WHEN the Sms_Reader starts after the Assistant_App process was stopped (for example by a reboot or by battery optimisation) while SMS reading was enabled, THE Sms_Reader SHALL read the Bank_Sender_List SMS in the phone's inbox received since the Received_Time of the last SMS the Sms_Reader processed.
7. WHEN an SMS from a Bank_Sender_List sender arrives in more than one part, THE Sms_Reader SHALL join the parts in order into one Message_Text before passing the Message_Text to the Message_Parser.

### Requirement 27: Read bank and UPI app notifications (Phase 3: Money)

**User Story:** As a User, I want the app to read payment notifications from my UPI and bank apps, so that payments are caught even without an SMS.

#### Acceptance Criteria

1. WHILE notification reading is enabled and notification access is granted, WHEN an app in the Money_App_List posts a notification, THE Notification_Reader SHALL pass the notification's title and text as the Message_Text, the Message_Origin and the Received_Time to the Message_Parser within 60 seconds of posting.
2. THE Settings_Screen SHALL list the apps installed on the phone and let the User add each app to, or remove each app from, the Money_App_List.
3. WHEN an app not in the Money_App_List posts a notification, THE Notification_Reader SHALL ignore the notification without storing, logging or passing any part of the notification to the Message_Parser.
4. IF the Android system disconnects notification access while notification reading is enabled, THEN THE Health_Check SHALL show the User, on the next app open, that notification reading has stopped and the steps to turn notification access back on.
5. IF the User returns from the Android notification access settings without granting access to the Assistant_App, THEN THE Assistant_App SHALL keep notification reading off and show notification reading as disconnected in the Settings_Screen.
6. IF a notification from an app in the Money_App_List has an empty title and empty text, THEN THE Notification_Reader SHALL discard the notification without passing it to the Message_Parser or the Review_Queue.
7. WHEN the User removes an app from the Money_App_List, THE Notification_Reader SHALL read no notification that app posts afterwards and SHALL keep the Transactions already created from that app.

### Requirement 28: Rule-based message parsing (Phase 3: Money)

**User Story:** As a User, I want each bank and UPI message format parsed exactly, so that amounts and merchants are correct.

#### Acceptance Criteria

1. WHEN a Money_Message matches a Bank_Format in the Parser_Registry, THE Message_Parser SHALL produce a Parsed_Message with the type and amount; for type debit or credit, the date and time, taking any date or time of day missing from the message from the Received_Time; for type bill, the due date when the message contains one; and the merchant or VPA, the account last 4 digits (the last 4 digits of the masked account or card number, for example 1234 from XX1234), the reference number and the balance when the message contains them.
2. THE Message_Parser SHALL convert every amount to exact integer paise, accepting a Currency_Marker before or after the number, an optional "/-" suffix, comma digit grouping in Indian or international style, and 0, 1 or 2 decimal places (for example "Rs 1,234" → 123400, "INR 1,234.5" → 123450, "₹1,23,456.78" → 12345678, "Rs.500/-" → 50000, "250.00 INR" → 25000).
3. WHEN a Money_Message matches a Bank_Format of type OTP or contains an OTP keyword listed in the Parser_Registry (for example "OTP", "one time password", "verification code"), THE Message_Parser SHALL classify the Money_Message as an OTP_Message before trying any other Bank_Format, including when the Money_Message also contains an amount or merchant, and SHALL pass no part of the Money_Message to the Review_Queue.
4. IF a Money_Message is not an OTP_Message and matches no Bank_Format, THEN THE Message_Parser SHALL return type unknown and pass the Money_Message to the Review_Queue.
5. IF a Money_Message matches a Bank_Format but its amount is not a Valid_Amount or has more than 2 decimal places, its date is not a valid calendar date, its debit or credit date and time is more than 1 day after the Received_Time, or extraction of any field raises an error, THEN THE Message_Parser SHALL return type unknown, pass the Money_Message to the Review_Queue and continue processing later Money_Messages.
6. WHEN the Template_Formatter receives a valid Parsed_Message and a Bank_Format that has a place for every non-empty field of that Parsed_Message, THE Template_Formatter SHALL render the Message_Text of that Bank_Format with those field values.
7. FOR ALL valid Parsed_Messages and Bank_Formats that can hold their fields, rendering with the Template_Formatter then parsing with the Message_Parser SHALL produce an equivalent Parsed_Message, where equivalent means equal type, Reversal marker, amount in paise, merchant or VPA, account last 4 digits, reference number, balance, and date and time at the precision the Bank_Format holds (round-trip property).
8. THE Test_Fixture_Set SHALL contain at least one anonymised sample Money_Message with its expected Parsed_Message for every Bank_Format in the Parser_Registry, and across the set at least one sample for each Currency_Marker, an amount with Indian comma grouping, amounts with 0, 1 and 2 decimal places, each date form in criterion 9, a Reversal, a Failed_Payment_Message, an OTP_Message that also contains an amount, and a Money_Message that matches no Bank_Format.
9. WHEN the Message_Parser reads a date or time from a Money_Message, THE Message_Parser SHALL apply the day-month-year order and separators defined by the matched Bank_Format, accept numeric months and 3-letter English month names in any letter case, read a 2-digit year YY as 20YY, accept 24-hour times and 12-hour times with AM or PM, and interpret the result in the device's local time zone (for example 05-01-25, 05/01/2025, 05-Jan-25, 05JAN2025, 14:05, 02:05 PM).
10. WHEN a Money_Message matches a Bank_Format marked as a refund or reversal, THE Message_Parser SHALL produce a Parsed_Message of type credit marked as a Reversal.
11. WHEN a Money_Message matches a Bank_Format marked as a failed, declined or cancelled payment, THE Message_Parser SHALL classify the Money_Message as a Failed_Payment_Message, from which no Transaction or Review_Queue item is created and no stored Transaction is changed.

### Requirement 29: Duplicate detection (Phase 3: Money)

**User Story:** As a User, I want one payment counted once even if both an SMS and an app notification arrive, so that my totals are right.

#### Acceptance Criteria

1. WHEN the Deduplicator receives a debit or credit Parsed_Message and a stored Transaction whose source is not manual has the same type, the same amount in paise, a time no more than the Duplicate_Window apart, the same reference number where both contain one (compared ignoring spaces and letter case), the same account last 4 digits where both contain them, and no Source_Ref from the same Message_Origin, THE Deduplicator SHALL add the Parsed_Message's Source_Ref to that Transaction instead of creating a new Transaction, whether the SMS or the app notification arrived first and regardless of any difference in merchant or VPA.
2. IF a debit or credit Parsed_Message matches no stored Transaction under criterion 1 or criterion 5 (for example because the reference numbers differ, the account last 4 digits differ, the type or amount differs, or the times are more than the Duplicate_Window apart), THEN THE Deduplicator SHALL store a new Transaction.
3. FOR ALL Money_Messages, ingesting the same Money_Message (same Source_Ref) twice, including once through the SMS_History_Import and once through live reading, SHALL produce the same set of Transactions as ingesting the Money_Message once (idempotence property).
4. FOR ALL sets of Money_Messages, the stored Transactions, including their linked Source_Refs and field values, SHALL be the same regardless of the order in which SMS and notifications arrive (confluence property).
5. IF a Parsed_Message has a different Source_Ref but the same Message_Origin as a Source_Ref already linked to a stored Transaction, THEN THE Deduplicator SHALL link the Parsed_Message to that Transaction only when both have the same type, amount in paise and reference number, and otherwise SHALL treat the Parsed_Message as a separate payment.
6. WHEN the Deduplicator links a Parsed_Message to a stored Transaction, THE Deduplicator SHALL keep every User-edited field unchanged, fill each other field the Transaction lacks from the Parsed_Message, resolve a field that both contain with different values by taking the SMS value over the app notification value and, between two sources of the same kind, the value with the earlier Received_Time, and mark the Transaction as a Reversal when any linked Parsed_Message is marked as a Reversal.
7. IF more than one stored Transaction meets the conditions of criterion 1 for a Parsed_Message, THEN THE Deduplicator SHALL link the Parsed_Message to the Transaction with the smallest time difference, and to the Transaction with the earlier time when the differences are equal.

### Requirement 30: Needs-review list (Phase 3: Money)

**User Story:** As a User, I want messages the app cannot read put in a review list, so that I can fix them instead of losing them.

#### Acceptance Criteria

1. WHEN the Message_Parser returns type unknown for a Money_Message, THE Review_Queue SHALL add one item holding the Redactor-processed Message_Text, the Message_Origin and the Received_Time, and SHALL add no second item for the same Source_Ref.
2. THE Review_Queue SHALL hold the redacted Message_Text of an item in the Encrypted_Store only until the User resolves or dismisses the item.
3. WHEN the User enters a type (debit or credit), an amount, a date and a Merchant for a Review_Queue item and saves with values that pass the checks of Requirement 32 criterion 6, THE Transaction_Editor SHALL create the Transaction with the item's Source_Ref through the Deduplicator, with the entered fields marked as edited by the User, and THE Review_Queue SHALL delete the item and its text.
4. WHEN the User dismisses a Review_Queue item, THE Review_Queue SHALL delete the item and its text and SHALL add no new item when a Money_Message with the same Source_Ref is processed again.
5. WHERE the SMS fallback parser is enabled (Phase 5, Requirement 49), THE Review_Queue SHALL show the AI_Provider's proposed fields for the User to confirm or edit, and SHALL create no Transaction until the User saves.
6. IF the User saves a Review_Queue item with a missing field or a value that fails the checks of Requirement 32 criterion 6, THEN THE Transaction_Editor SHALL reject the save, show which field is invalid, and keep the item and its text unchanged.
7. THE Money_Screen SHALL show the number of open Review_Queue items and a control that opens the Review_Queue.
8. THE Review_Queue SHALL list open items ordered by Received_Time, newest first.

### Requirement 31: Categories and learning from corrections (Phase 3: Money)

**User Story:** As a User, I want transactions categorised automatically and corrected once, so that reports are right without repeated fixing.

#### Acceptance Criteria

1. WHEN a Transaction is created, THE Categoriser SHALL assign the category from the User's most recent category correction for the Transaction's Merchant if one exists, otherwise the Merchant's default category if one is set, otherwise "Uncategorised".
2. THE Categoriser SHALL match a Transaction to a Merchant when the Transaction's merchant name or VPA equals the Merchant's name or any of the Merchant's aliases, ignoring letter case and leading and trailing spaces.
3. WHEN the User changes the category of a Transaction, THE Categoriser SHALL record the correction for that Merchant and assign the corrected category to Transactions of that Merchant created after the correction.
4. WHEN the User changes the category of a Transaction, THE Categoriser SHALL offer to apply the new category to earlier Transactions of the same Merchant.
5. THE Assistant_App SHALL ship a default Merchant list of common Indian merchants and VPAs, kept as data separate from the Categoriser code, in which each entry has a Merchant name, at least one alias or VPA and a default category, and which maps Swiggy to Food.
6. WHEN the User accepts the offer to apply a new category to earlier Transactions, THE Categoriser SHALL change the category of every earlier Transaction of that Merchant except Transactions whose category the User set individually.
7. IF a new Transaction has a non-empty merchant name or VPA that matches no Merchant, THEN THE Categoriser SHALL create a Merchant with that merchant name or VPA as name and alias and with no default category, and assign "Uncategorised" to the Transaction.

### Requirement 32: Manual edit and merge (Phase 3: Money)

**User Story:** As a User, I want to edit, add, merge and delete transactions, so that I can fix anything the parser got wrong.

#### Acceptance Criteria

1. WHEN the User edits a field of a Transaction and saves a value that passes the checks of criterion 6, THE Transaction_Editor SHALL store the new value and mark the field as edited by the User.
2. WHEN a Money_Message is processed again for a Transaction that has User-edited fields, THE Transaction_Editor SHALL keep every User-edited value unchanged.
3. WHEN the User adds a Transaction manually with an amount, a type (debit or credit), a date and a Merchant that pass the checks of criterion 6, THE Transaction_Editor SHALL store the Transaction with source "manual", with no Source_Ref, and exclude it from automatic duplicate detection by the Deduplicator.
4. WHEN the User merges two Transactions of the same type, THE Transaction_Editor SHALL keep one Transaction holding all Source_Refs of both Transactions and the field values the User selected, and SHALL remove the other Transaction from lists, Budgets and reports.
5. WHEN the User deletes a Transaction and confirms the deletion, THE Transaction_Editor SHALL remove the Transaction from lists, Budgets and reports.
6. IF the User saves an edited or manually added Transaction with an amount that is not a Valid_Amount, a type other than debit or credit, a missing or invalid date, a date later than the current date, a Merchant name longer than 100 characters, or (for a manually added Transaction) an empty Merchant, THEN THE Transaction_Editor SHALL reject the save, show which field is invalid, and keep the previously stored values.
7. IF a Money_Message whose Source_Ref belonged to a Transaction the User deleted is processed again, THEN THE Deduplicator SHALL create no Transaction and no Review_Queue item from that Money_Message.
8. IF the User tries to merge a debit Transaction with a credit Transaction, THEN THE Transaction_Editor SHALL reject the merge, show that only Transactions of the same type can be merged, and leave both Transactions unchanged.

### Requirement 33: Budgets (Phase 3: Money)

**User Story:** As a User, I want monthly budgets per category, so that I can see how much I have left.

#### Acceptance Criteria

1. WHEN the User sets a monthly amount that is a Valid_Amount for a category, THE Budget_Engine SHALL store the amount as that category's only Budget, replacing any earlier amount for that category.
2. THE Budget_Engine SHALL calculate spent for a Budget as the sum of the amounts of debit Transactions in that category dated in the current calendar month in the device's local time zone, excluding credit Transactions and Reversals.
3. THE Budget_Engine SHALL calculate remaining for a Budget as the Budget amount minus spent, giving a negative value when spent exceeds the Budget amount.
4. FOR ALL sets of Transactions, the sum of spent across all categories including "Uncategorised" SHALL equal the total of debit Transactions in the month (invariant).
5. THE Money_Screen SHALL show each Budget with spent, remaining and the percentage used, calculated as spent ÷ Budget amount × 100 rounded down to a whole number, including values above 100.
6. IF the User sets a Budget amount that is not a Valid_Amount, THEN THE Budget_Engine SHALL reject the amount, show an error indicating the allowed range, and keep any existing Budget for that category unchanged.
7. WHILE spent for a Budget exceeds the Budget amount, THE Money_Screen SHALL show the Budget as over budget with the amount by which spent exceeds the Budget amount.

### Requirement 34: Bills and due dates (Phase 3: Money)

**User Story:** As a User, I want my bills tracked with due dates and reminders, so that I never pay late.

#### Acceptance Criteria

1. WHEN the User saves a Bill with a name of 1 to 100 characters, an amount that is a Valid_Amount, a valid due date and a recurrence of none, monthly or yearly, THE Bill_Tracker SHALL store the Bill.
2. WHEN the Message_Parser produces a Parsed_Message of type bill, THE Bill_Tracker SHALL propose a Bill with the parsed Merchant as Merchant and name, the parsed amount and the parsed due date for the User to accept, leaving the due date empty for the User to enter when the message contains none.
3. WHEN a debit Transaction's Merchant matches an unpaid Bill's Merchant, the Transaction amount equals the Bill amount in paise, and the Transaction date is from 5 days before to 5 days after the Bill's due date inclusive, THE Bill_Tracker SHALL propose marking that Bill paid once per Transaction and Bill pair, choosing the Bill with the nearest due date when more than one unpaid Bill matches.
4. WHEN the User marks a recurring Bill paid, THE Bill_Tracker SHALL create the next occurrence with a due date 1 month later (monthly) or 1 year later (yearly) on the same day of the month as the first occurrence, or on the last day of the month when that month has fewer days (for example 31 January, then 28 or 29 February, then 31 March).
5. WHEN the morning brief time is reached and an unpaid Bill occurrence is due within the next 2 days (today, tomorrow or the day after) and has had no bill-due notification, THE Notification_Scheduler SHALL show, subject to the Notification_Governor, one bill-due notification with the Bill name, amount and due date.
6. IF the User saves or accepts a Bill with an empty name, a name longer than 100 characters, an amount that is not a Valid_Amount, or a missing or invalid due date, THEN THE Bill_Tracker SHALL reject the Bill, show which field is invalid, and keep the entered values for correction.
7. IF a Bill occurrence is marked paid or deleted before its bill-due notification is shown, THEN THE Notification_Scheduler SHALL cancel that notification.
8. IF a Parsed_Message of type bill has the same Merchant, amount and due date as an existing Bill or an open Bill proposal, THEN THE Bill_Tracker SHALL create no new proposal.

### Requirement 35: Spending reports (Phase 3: Money)

**User Story:** As a User, I want weekly and monthly spending reports, so that I know where my money goes.

#### Acceptance Criteria

1. WHEN a calendar month ends (00:00 device local time on the first day of the next month), THE Report_Generator SHALL create a monthly spending report for that month showing total spend (the sum of debit Transactions dated in the month, or 0 when there are none), spend per category including "Uncategorised", and for each category the change against the previous month in rupees and, when the previous month's amount is above 0, in percent rounded to the nearest whole number.
2. WHEN a week ends (at the end of the User-set last day of the week, as in Requirement 17), THE Report_Generator SHALL create a weekly spending report showing total spend, the 5 categories with the highest spend in descending order (ties ordered by category name A to Z), and the spend of all other categories combined as "Other".
3. FOR ALL spending reports, the sum of the per-category amounts shown, including "Other", SHALL equal the report's total spend (invariant).
4. WHEN the User edits, merges or deletes a Transaction dated in a past period, or the Categoriser changes the category of such a Transaction, THE Report_Generator SHALL recalculate the reports of that period so that the next display of each report shows the changed values.
5. IF the scheduled creation time of a weekly or monthly spending report passes without the report being created, THEN THE Report_Generator SHALL create the missing report the next time the Assistant_App opens.
6. THE Report_Generator SHALL show the total of Reversals dated in the report period as a separate line in each spending report, without subtracting the total from total spend.

### Requirement 36: Money quality tracking (Phase 3: Money)

**User Story:** As the developer, I want parse accuracy tracked per bank, so that I can see which formats break.

#### Acceptance Criteria

1. THE Quality_Monitor SHALL record, per Message_Origin in the Bank_Sender_List or the Money_App_List and excluding OTP_Messages, the number of Money_Messages parsed into a debit, credit or bill Parsed_Message or a Failed_Payment_Message, the number sent to the Review_Queue, and the number of parsed Money_Messages whose Transaction the User later corrected (edited the type, amount, Merchant, date and time, or account last 4 digits at least once, counted once per Money_Message).
2. THE Settings_Screen SHALL show, per Message_Origin, the parse accuracy for the last 30 days (today and the 29 days before, by Received_Time) as (parsed − corrected) ÷ (parsed + sent to the Review_Queue) × 100 rounded to 1 decimal place, and SHALL show "no data" for a Message_Origin with no Money_Messages in that period.
3. THE Quality_Monitor SHALL keep all metrics on the device and SHALL exclude the metrics from every Redacted_Payload.
4. THE Quality_Monitor SHALL store only counts, Message_Origins and dates, and SHALL store no Message_Text and no amounts.

### Requirement 37: Subscription detection (Phase 4: Subscriptions)

**User Story:** As a User, I want repeating charges found automatically, so that I know every subscription I pay for.

#### Acceptance Criteria

1. WHEN a debit Transaction is created, edited, merged or deleted, THE Subscription_Detector SHALL propose a Subscription for each Merchant and Subscription_Interval whose debit Transactions form a Recurring_Pattern and that has no Subscription in any status, and SHALL show the proposal on the Subscriptions_Screen with the Merchant, the latest charge amount, the Subscription_Interval, the number of matching Transactions and a next due date one Subscription_Interval after the latest charge calculated with the Due_Date_Rule.
2. WHEN the User confirms a proposed Subscription, THE Subscription_Detector SHALL set the Subscription status to active and record the confirmation date as the date the Subscription became active.
3. WHEN the User rejects a proposed Subscription, THE Subscription_Detector SHALL set the status to rejected and SHALL make no new proposal for the same Merchant and Subscription_Interval on any later run.
4. WHEN the User saves a manual Subscription with a non-empty Merchant, an amount greater than 0 paise, a Subscription_Interval and a next due date of today or later (for example the first paid charge date after a free trial ends), THE Subscription_Detector SHALL store the Subscription as active with the save date as the date the Subscription became active.
5. FOR ALL sets of Transactions, running the Subscription_Detector twice SHALL produce the same proposals as running the Subscription_Detector once, with no duplicate Subscription for any Merchant and Subscription_Interval (idempotence property).
6. THE Quality_Monitor SHALL record the number of confirmed and rejected Subscription proposals.
7. IF the User saves a manual Subscription with an empty Merchant, an amount of 0 paise or less, no Subscription_Interval, or a next due date before today, THEN THE Subscription_Detector SHALL reject the save, keep the entered values on screen and show which field is invalid.

### Requirement 38: Subscription list and yearly cost (Phase 4: Subscriptions)

**User Story:** As a User, I want each subscription's yearly cost shown, so that I see what small charges add up to.

#### Acceptance Criteria

1. THE Subscriptions_Screen SHALL list each active Subscription with Merchant, amount, Subscription_Interval, next due date and yearly cost, ordered by next due date with the earliest first.
2. THE Subscription_Detector SHALL calculate yearly cost in exact integer paise as the amount multiplied by the number of intervals per year (52 weekly, 12 monthly, 4 quarterly, 1 yearly), so that ₹149 monthly gives ₹1,788 and ₹1,499 yearly gives ₹1,499.
3. THE Subscriptions_Screen SHALL show the total yearly cost as the sum of the yearly costs of active Subscriptions only, excluding proposed, rejected and ended Subscriptions, and SHALL show ₹0 when no Subscription is active.
4. WHEN a debit Transaction has the Merchant of an active Subscription, an amount within Amount_Tolerance of the Subscription's amount and a date within Charge_Window days of the next due date, THE Subscription_Detector SHALL advance that Subscription's next due date by one Subscription_Interval using the Due_Date_Rule, matching each Transaction to at most one Subscription (the one with the nearest next due date) and at most once.
5. IF a debit Transaction has the Merchant of an active Subscription and a date within Charge_Window days of the next due date but an amount outside Amount_Tolerance, THEN THE Suggestion_Manager SHALL show a price-change Suggestion with the stored amount, the new amount and the Transaction date, and THE Subscription_Detector SHALL keep the amount, Subscription_Interval and next due date unchanged until the User accepts the Suggestion.
6. WHEN the User accepts a price-change Suggestion, THE Subscription_Detector SHALL store the new amount and the Subscription_Interval the User selects (default: the current Subscription_Interval), recalculate the yearly cost, and set the next due date with the Due_Date_Rule to one Subscription_Interval after the current next due date if the Subscription_Interval is unchanged, or one Subscription_Interval after the Transaction date if the User selected a different Subscription_Interval.
7. IF no debit Transaction matches an active Subscription by the end of the day that is Charge_Window days after the next due date and no price-change Suggestion is pending for that next due date, THEN THE Subscription_Detector SHALL keep the status active and advance the next due date by one Subscription_Interval, and THE Suggestion_Manager SHALL show a Suggestion stating that the expected charge was not seen, with options to keep the Subscription or mark it cancelled.

### Requirement 39: Monthly usage check (Phase 4: Subscriptions)

**User Story:** As a User, I want to be asked monthly whether I used each subscription, so that I notice the ones I am wasting money on.

#### Acceptance Criteria

1. WHEN 30 calendar days have passed since the date of an active Subscription's last Usage_Check answer, or since the date the Subscription became active if it has no Usage_Check answer, THE Review_Flow SHALL mark a Usage_Check due for that Subscription.
2. WHEN the User answers a Usage_Check with "Yes" or "No", THE Subscription_Detector SHALL store the answer, the date the Usage_Check became due and the answer time, and SHALL set the Subscription's last usage-check date to the answer date.
3. WHEN the User-set evening review time is reached on a calendar day with one or more due Usage_Checks, THE Notification_Scheduler SHALL show one subscription usage check notification stating the number of due Usage_Checks and SHALL show no other subscription usage check notification that calendar day.
4. WHEN the User opens the subscription usage check notification, or opens the Subscriptions_Screen while one or more Usage_Checks are due, THE Review_Flow SHALL show all due Usage_Checks on one screen, each with the Merchant, the amount and the question whether the User used the Subscription since the previous Usage_Check, answerable "Yes" or "No" with one tap.
5. IF a due Usage_Check has no answer at the end of the calendar day, THEN THE Review_Flow SHALL keep the Usage_Check due, record no answer, and include the Usage_Check in the next day's prompt.
6. WHEN a Subscription's status changes from active to ended, THE Review_Flow SHALL remove that Subscription's due Usage_Check and SHALL mark no further Usage_Checks due for that Subscription.

### Requirement 40: Keep or cancel suggestion (Phase 4: Subscriptions)

**User Story:** As a User, I want a keep or cancel suggestion based on my usage answers, so that I can decide quickly.

#### Acceptance Criteria

1. WHEN a "No" answer brings the count of consecutive "No" answers for an active Subscription to 2, THE Suggestion_Manager SHALL show a Suggestion to review the Subscription that gives the 2 "No" answers as the reason and the Subscription's yearly cost (Requirement 38) as the yearly amount cancelling would save, and THE Subscription_Detector SHALL reset that Subscription's count of consecutive "No" answers to 0.
2. WHEN the User answers "Yes" to a Usage_Check, THE Suggestion_Manager SHALL record a keep outcome for that Usage_Check and THE Subscription_Detector SHALL reset that Subscription's count of consecutive "No" answers to 0.
3. WHEN the User marks a Subscription as cancelled, THE Subscription_Detector SHALL set the Subscription status to ended, record the date marked cancelled as the cancellation date, and SHALL send no request to the Merchant, a bank, an app store or any other party (Requirement 2).
4. IF the User dismisses or ignores a review Suggestion, THEN THE Suggestion_Manager SHALL record the response, keep the Subscription status active, and show the next review Suggestion for that Subscription only after 2 further consecutive "No" answers.
5. IF a debit Transaction dated after an ended Subscription's cancellation date has that Subscription's Merchant and an amount within Amount_Tolerance of the Subscription's amount, THEN THE Suggestion_Manager SHALL show a Suggestion stating that a charge was seen after the Subscription was marked cancelled, with the amount and Transaction date, and THE Subscription_Detector SHALL keep the status ended until the User chooses to reactivate the Subscription.
6. WHEN the User chooses to reactivate an ended Subscription from a charge-after-cancellation Suggestion, THE Subscription_Detector SHALL set the status to active and set the next due date to one Subscription_Interval after that Transaction's date using the Due_Date_Rule.

### Requirement 41: Renewal alerts (Phase 4: Subscriptions)

**User Story:** As a User, I want an alert before a subscription renews, so that I can cancel in time if I want to.

#### Acceptance Criteria

1. WHEN the User-set morning brief time is reached on the local calendar date that is Renewal_Lead_Time days before an active Subscription's next due date, THE Notification_Scheduler SHALL show a subscription renewal alert with the Merchant, amount and renewal date.
2. THE Settings_Screen SHALL let the User set the Renewal_Lead_Time to 1 or 2 days, with 2 days as the default.
3. WHEN the Renewal_Lead_Time, a Subscription's next due date, a Subscription's amount or a Subscription's status changes, THE Notification_Scheduler SHALL cancel the pending renewal alert for each affected Subscription and SHALL schedule a new renewal alert only for Subscriptions whose status is active.
4. IF the renewal alert time for an active Subscription's next due date has already passed when the alert is scheduled or rescheduled (including after a device reboot), THEN THE Notification_Scheduler SHALL show the renewal alert within 1 minute if the next due date is today or later, and SHALL show no renewal alert for that next due date otherwise.
5. THE Notification_Scheduler SHALL show at most 1 renewal alert per Subscription per next due date.

### Requirement 42: AI provider modes (Phase 5: AI advice)

**User Story:** As a User, I want to choose cloud AI, on-device AI or no AI, so that I control cost and privacy.

#### Acceptance Criteria

1. THE AI_Provider SHALL offer the modes Cloud, OnDevice and None behind one interface used by every Prompt_Type.
2. WHEN the User changes the AI_Mode in the Settings_Screen, THE AI_Provider SHALL send every AI_Request started after the change through the new AI_Mode and THE Assistant_App SHALL keep all stored data and Suggestions unchanged.
3. WHERE the Cloud mode is selected, THE AI_Provider SHALL use the model configured for each Prompt_Type, and SHALL use the single default Cloud model for any Prompt_Type with no model configured (for example a lower-cost model for the mail classifier and a stronger model for reviews).
4. THE AI_Provider SHALL give calling features the same request and response contract for every Cloud vendor (see Open decision 2), verified by tests that run each Prompt_Type against at least 2 test vendor implementations with no change to calling feature code.
5. WHEN the Assistant_App runs for the first time, THE AI_Provider SHALL set the AI_Mode to None.
6. IF the User selects the Cloud mode while no AI_Monthly_Cap is set, or, WHERE the build is for the developer's personal use, while no Cloud AI key is stored, THEN THE Settings_Screen SHALL keep the previous AI_Mode and SHALL show which of these items is missing.
7. IF an AI_Request started under the previous AI_Mode returns output after the User changed the AI_Mode, THEN THE AI_Provider SHALL discard that output and THE Assistant_App SHALL show the Rule_Based_Result for that feature.

### Requirement 43: Works without AI (Phase 5: AI advice)

**User Story:** As a User, I want the app to work fully with AI turned off, so that I am never dependent on an AI service.

#### Acceptance Criteria

1. WHILE the AI_Mode is None, THE Assistant_App SHALL provide the Daily_Plan, the Evening_Review, the weekly progress report and the monthly spending and subscription reports as Rule_Based_Results, SHALL show Review_Queue items without AI proposals, and SHALL make zero AI_Attempts.
2. IF an AI_Attempt receives no complete response within 30 seconds, returns a provider or network error, or is rejected by the Output_Validator, THEN THE AI_Provider SHALL treat the AI_Attempt as failed and SHALL send the same Redacted_Payload again, up to a total of 3 AI_Attempts per AI_Request.
3. IF all 3 AI_Attempts of an AI_Request fail, THEN THE Assistant_App SHALL keep showing the Rule_Based_Result for that feature and SHALL show an indication that AI wording is unavailable for that result.
4. WHILE an AI_Request for a feature is pending, THE Assistant_App SHALL show the Rule_Based_Result for that feature and SHALL replace its wording only with AI output that the Output_Validator has accepted.
5. IF the AI_Mode is Cloud and the device has no network connection when an AI_Request is due, THEN THE AI_Provider SHALL make zero AI_Attempts for that AI_Request, SHALL record no usage against the AI_Monthly_Cap, and THE Assistant_App SHALL show the Rule_Based_Result with the indication that AI wording is unavailable.

### Requirement 44: On-device AI (Phase 5: AI advice)

**User Story:** As a User with a capable phone, I want an on-device AI option, so that no summary leaves my phone.

#### Acceptance Criteria

1. WHEN the User opens the AI section of the Settings_Screen, THE Assistant_App SHALL check the device against the on-device requirements defined in the design for the on-device model: minimum RAM, minimum Android version, and presence of the on-device model on the device.
2. IF the device fails one or more on-device requirements, THEN THE Settings_Screen SHALL show the OnDevice mode as unavailable, SHALL prevent the User from selecting the OnDevice mode, and SHALL name each failed requirement.
3. WHILE the AI_Mode is OnDevice, THE AI_Provider SHALL process every AI_Request on the device and SHALL make zero network requests that carry a Redacted_Payload or AI output.
4. IF the AI_Mode is OnDevice and the on-device model is unavailable or fails to run when an AI_Request is due, THEN THE AI_Provider SHALL treat the AI_Request as failed under Requirement 43, SHALL send no part of the AI_Request through the Cloud mode, and SHALL show the reason in the AI section of the Settings_Screen.

### Requirement 45: Cloud key handling and monthly cap (Phase 5: AI advice)

**User Story:** As a User, I want the AI key kept safe and spending capped, so that AI never costs more than I set.

#### Acceptance Criteria

1. WHERE the build is for the developer's personal use, THE Key_Manager SHALL store the Cloud AI key entered in the Settings_Screen encrypted with a key held in the Android Keystore.
2. WHERE the build is for users other than the developer, THE AI_Provider SHALL send every Cloud AI_Attempt through the Proxy_Server, and the Assistant_App SHALL contain no Cloud AI key.
3. THE AI_Provider SHALL record against the AI_Monthly_Cap of the current calendar month the estimated cost or the count of every Cloud AI_Attempt, including retries and failed AI_Attempts.
4. IF sending the next Cloud AI_Attempt would make the recorded usage exceed the AI_Monthly_Cap, THEN THE AI_Provider SHALL send no further Cloud AI_Attempts, SHALL switch the AI_Mode to None until the next calendar month starts or the User raises the AI_Monthly_Cap above the recorded usage, and SHALL show the User a message stating that the cap was reached and the date the Cloud mode resumes.
5. THE Assistant_App SHALL exclude the Cloud AI key from application logs, crash reports, the Audit_Log and every Redacted_Payload.
6. IF the User enters an AI_Monthly_Cap that is not a whole number from 1 to 10,000 requests, or not a whole amount from ₹1 to ₹10,000 of estimated cost, THEN THE Settings_Screen SHALL reject the value, SHALL keep the previous AI_Monthly_Cap, and SHALL show the valid range.
7. WHEN a new calendar month starts at 00:00 device local time on the first day of the month, THE AI_Provider SHALL set the recorded usage for the new month to zero and SHALL restore the Cloud mode if the AI_Monthly_Cap had switched the AI_Mode to None.

### Requirement 46: Summary building and redaction (Phase 5: AI advice)

**User Story:** As a User, I want only a small redacted summary sent to AI, so that my names, numbers and messages stay private.

#### Acceptance Criteria

1. THE Summary_Builder SHALL build each AI_Request from a fixed schema per Prompt_Type containing only the fields listed in that schema (for example date, weekday, calendar slots, tasks, goals, bills due and yesterday's planned and done counts for the daily plan).
2. WHEN a Redacted_Payload is prepared, THE Redactor SHALL replace person names, phone numbers, account and card numbers (including masked forms showing only the last digits), UPI IDs, email addresses, links and text matching the Instruction_Pattern_List with a placeholder naming the removed item's type, and SHALL keep money amounts, dates and times.
3. FOR ALL inputs, the Redactor output SHALL contain no substring matching the person name, phone number, account number, card number, UPI ID, email address or link patterns in the Redactor_Test_Set, or any entry of the Instruction_Pattern_List (invariant).
4. FOR ALL inputs, applying the Redactor twice SHALL produce the same output as applying the Redactor once (idempotence property).
5. THE Summary_Builder SHALL exclude Message_Text from every Prompt_Type except the SMS fallback parser and the mail classifier, which SHALL receive only Redactor-processed Message_Text placed in a data field of the Prompt_Type schema and never in the prompt's instruction text.
6. IF the Redactor reports an error or does not finish processing an input, THEN THE AI_Provider SHALL send no AI_Attempt containing that input and THE Assistant_App SHALL show the Rule_Based_Result for that feature.
7. IF a Redacted_Payload is longer than 4,000 characters, THEN THE AI_Provider SHALL send no AI_Attempt for that AI_Request and THE Assistant_App SHALL show the Rule_Based_Result for that feature.

### Requirement 47: AI output validation (Phase 5: AI advice)

**User Story:** As a User, I want AI advice checked before I see it, so that the app never shows made-up numbers or risky advice.

#### Acceptance Criteria

1. WHEN the AI_Provider returns output, THE Output_Validator SHALL parse the output as a single JSON object and SHALL check the object against the Prompt_Type schema for required fields, field types, allowed values and maximum text lengths.
2. IF the output is not a single valid JSON object, contains text outside the JSON object, lacks a required field, contains a field not in the schema, has a field of the wrong type or value, or exceeds a maximum text length in the schema, THEN THE Output_Validator SHALL reject the output and THE AI_Provider SHALL retry as defined in Requirement 43.
3. IF the output contains a Number_Token whose value equals no number in the Redacted_Payload and cannot be calculated from the Redacted_Payload by a Derivation_Rule of that Prompt_Type, THEN THE Output_Validator SHALL reject the output.
4. IF the output contains a term or phrase from the Restricted_Advice_List giving advice on loans, investments, insurance products or other financial products, THEN THE Output_Validator SHALL reject the output.
5. IF the output contains more than 3 Suggestions, or contains a Suggestion with an empty or missing reason, THEN THE Output_Validator SHALL reject the whole output.
6. THE Output_Serializer SHALL format validated output objects as JSON matching the Prompt_Type schema.
7. FOR ALL valid output objects, serializing with the Output_Serializer and then parsing with the Output_Validator SHALL produce an equivalent object (round-trip property).
8. THE Assistant_App SHALL show only AI output that the Output_Validator has accepted and SHALL show no part of rejected output.
9. IF the output contains a link, phone number, email address or UPI ID matching the patterns in the Redactor_Test_Set, THEN THE Output_Validator SHALL reject the output.
10. IF the output names a Task, Goal, Bill, Subscription or category that is not present in the Redacted_Payload, THEN THE Output_Validator SHALL reject the output.

### Requirement 48: AI prompts for plans and reviews (Phase 5: AI advice)

**User Story:** As a User, I want AI to word my daily plan and reviews, so that the advice reads naturally and explains its reasons.

#### Acceptance Criteria

1. WHERE the AI_Mode is Cloud or OnDevice, WHEN the Plan_Builder creates a Daily_Plan, THE AI_Provider SHALL return daily plan output containing an order of the Priorities in the Redacted_Payload, a placement inside a Free_Gap of the Redacted_Payload for each Priority with an estimated duration, and a reason for the order.
2. WHERE the AI_Mode is Cloud or OnDevice, WHEN the Evening_Review finishes, THE AI_Provider SHALL return evening review output containing a day summary, the carried-over Tasks (exactly the Tasks marked "move to tomorrow" in the Redacted_Payload) and at most 1 tip.
3. WHERE the AI_Mode is Cloud or OnDevice, WHEN the weekly progress report is created, THE AI_Provider SHALL return weekly review output containing the progress against target of each week Goal in the Redacted_Payload, what slipped (the Tasks moved more than once and the Behind_Goals in the Redacted_Payload) and exactly 1 suggested change.
4. WHERE the AI_Mode is Cloud or OnDevice, WHEN the monthly spending report is created, THE AI_Provider SHALL return monthly money review output containing spend by category versus last month for each category in the Redacted_Payload, advice only on active Subscriptions in the Redacted_Payload, the Bills due in the next calendar month listed in the Redacted_Payload, and exactly 1 saving idea.
5. THE Project_Repository SHALL store each Prompt_Type's prompt as a versioned file with tests that the CI_Pipeline runs without network access or a Cloud AI key, feeding fixed inputs and checking the output against the Prompt_Type schema.
6. IF daily plan output names a Task not in the Redacted_Payload, names a Task more than once, or places a Task outside the Free_Gaps of the Redacted_Payload, overlapping another placement, or in a period shorter than the Task's estimated duration, THEN THE Output_Validator SHALL reject the output.
7. WHEN validated daily plan output proposes an order or placement that differs from the Daily_Plan, THE Suggestion_Manager SHALL show the proposal as a Suggestion and SHALL leave the Daily_Plan unchanged until the User accepts the Suggestion.

### Requirement 49: SMS fallback parser (Phase 5: AI advice)

**User Story:** As a User, I want AI to propose fields for messages the rules cannot read, so that review takes less effort.

#### Acceptance Criteria

1. WHERE the SMS fallback parser is enabled in the Settings_Screen and the AI_Mode is Cloud or OnDevice, WHEN an item enters the Review_Queue, THE Summary_Builder SHALL build at most one SMS fallback parser AI_Request for that item, whose Redacted_Payload contains only the Redactor-processed Message_Text of that item.
2. WHEN the Output_Validator accepts SMS fallback parser output, THE Review_Queue SHALL show the proposed type (debit, credit, bill or unknown), amount in integer paise, Merchant name, and date and time as a proposal that the User confirms or edits before the Transaction_Editor creates a Transaction.
3. THE Summary_Builder SHALL include OTP_Messages, and Money_Messages that the Message_Parser parsed to a type other than unknown, in SMS fallback parser AI_Requests zero times.
4. IF all AI_Attempts of an SMS fallback parser AI_Request fail, including rejection of a proposed amount that does not appear in the Redactor-processed Message_Text, THEN THE Review_Queue SHALL keep the item with no proposal, SHALL let the User fill in the fields manually, and SHALL send the item to the SMS fallback parser no further times.
5. WHEN a Prompt_Injection_Fixture is processed by the SMS fallback parser, THE Assistant_App SHALL produce no Task, Suggestion, notification, setting change or Transaction, and SHALL produce at most one Review_Queue proposal limited to the fields in criterion 2.

### Requirement 50: Suggestions and accept rate (Phase 5: AI advice)

**User Story:** As a User, I want to accept or dismiss each suggestion, so that I stay in charge and the app learns what is useful.

#### Acceptance Criteria

1. THE Suggestion_Manager SHALL show each Suggestion with the Suggestion's text, the Suggestion's reason, an accept action and a dismiss action.
2. WHEN the User accepts or dismisses a Suggestion, THE Suggestion_Manager SHALL record the response, the time shown and the time of the response, and SHALL stop showing the Suggestion.
3. THE Quality_Monitor SHALL show on the device, per Prompt_Type and for rule-based Suggestions as one separate group, the accept rate as accepted ÷ (accepted + dismissed + ignored) for Suggestions shown in the last 30 days, rounded to the nearest whole percent.
4. IF a shown Suggestion has no accept or dismiss response 7 days after the time shown, THEN THE Suggestion_Manager SHALL record the response as ignored and SHALL stop showing the Suggestion.
5. WHEN the User accepts a Suggestion that proposes a change to a Task, the Daily_Plan, a Bill or a Subscription status, THE Suggestion_Manager SHALL apply exactly that change and no other change.
6. IF a Prompt_Type or the rule-based group has no Suggestion with a recorded response in the last 30 days, THEN THE Quality_Monitor SHALL show "no data" for that Prompt_Type or group instead of a percentage.

### Requirement 51: Mailbox connection (Phase 6: Email)

**User Story:** As a User, I want to connect my mailbox read-only, so that the app can spot important mail.

#### Acceptance Criteria

1. THE Mail_Connector SHALL connect using the one access method chosen at configuration (IMAP with an app password, or Google sign-in; see Open decision 3) and SHALL give the Mail_Triage Mail_Headers with the same fields whichever access method is used.
2. WHEN the Connection_Test succeeds, THE Key_Manager SHALL store the mailbox credentials or tokens encrypted, and THE Assistant_App SHALL set the Mailbox_Status to connected and SHALL keep the credentials or tokens out of application logs, crash reports, the Audit_Log and every screen.
3. THE Mail_Connector SHALL use read operations only, so that after any number of Mail_Syncs the read or unread state, flags, labels, folder and count of every email in the mailbox are the same as before the first Mail_Sync, and the Mail_Connector SHALL never send, reply to, move, flag, label or delete any email.
4. IF the mailbox rejects the stored credentials (for example after the app password is revoked) or the sign-in expires or is revoked, THEN THE Mail_Connector SHALL stop Mail_Sync, set the Mailbox_Status to needs reconnection, make no further sign-in attempts with the rejected credentials until the User reconnects, and keep the stored Inbox_Items, and THE Health_Check SHALL show the User a warning with a reconnect action.
5. WHEN the User submits mailbox credentials or completes Google sign-in, THE Mail_Connector SHALL run the Connection_Test and SHALL end the Connection_Test as failed after 30 seconds without a result.
6. IF the Connection_Test fails or times out, THEN THE Mail_Connector SHALL store no credentials or tokens, SHALL keep the Mailbox_Status as disconnected, and SHALL show an error indicating whether the mailbox rejected the credentials or could not be reached.
7. IF the network is unavailable or the connection drops during a Mail_Sync, THEN THE Mail_Connector SHALL keep the Inbox_Items from emails fully processed before the drop, discard any partly fetched email from memory, keep the Mailbox_Status as connected, and resume at the next scheduled Mail_Sync from the first email not fully processed, and THE Health_Check SHALL show a warning when no Mail_Sync has completed for 24 hours while the Mailbox_Status is connected.

### Requirement 52: Header-first reading (Phase 6: Email)

**User Story:** As a User, I want the app to read only headers and a short snippet, so that the app sees as little of my mail as needed.

#### Acceptance Criteria

1. WHILE the Mailbox_Status is connected, WHEN the Mail_Sync_Interval elapses, THE Mail_Connector SHALL fetch the Mail_Header of each email received since the last completed Mail_Sync in the folders or labels the User selected in the Settings_Screen (default: the inbox).
2. THE Mail_Connector SHALL fetch at most Snippet_Length characters of body text per email, where Snippet_Length is a User setting from 0 to 500 characters, and SHALL fetch no attachments, images or remote content.
3. WHEN a fetched email is an OTP_Mail, THE Mail_Triage SHALL discard the email before shortlisting, even if the email matches a Shortlist_Rule, without storing, logging, notifying about or sending to the AI_Provider any part of the email.
4. WHEN the first Mail_Sync after a connection runs, THE Mail_Connector SHALL fetch only emails received in the 7 days before the connection, and in every Mail_Sync the Mail_Connector SHALL process at most 200 emails, oldest first, leaving the remaining emails to the next Mail_Sync.
5. IF an email has no plain-text body part, THEN THE Mail_Connector SHALL take the snippet from the visible text of the HTML part, with markup, scripts and style content removed, up to Snippet_Length characters.
6. IF a header or the snippet of an email is in a character set other than UTF-8, THEN THE Mail_Connector SHALL decode the text using the declared character set, SHALL replace characters that cannot be decoded (including when no character set is declared or the declared one is invalid) with a replacement marker, and SHALL continue the Mail_Sync with the next email.

### Requirement 53: Rule-based mail shortlist (Phase 6: Email)

**User Story:** As a User, I want rules to shortlist likely-important mail first, so that only a few emails need deeper checking.

#### Acceptance Criteria

1. WHEN a Mail_Header's sender email address (not the display name) equals a known-sender entry or has a domain equal to one, or the subject or snippet contains a keyword entry as a whole word ignoring case (for example invoice, interview, due), or the email carries a label or folder exactly matching a label entry in the Shortlist_Rules, THE Mail_Triage SHALL add the email to the shortlist.
2. THE Settings_Screen SHALL let the User add, edit and delete Shortlist_Rules entries, up to 200 entries, and THE Mail_Triage SHALL apply the changed Shortlist_Rules from the next Mail_Sync onward without fetching emails again from earlier Mail_Syncs.
3. WHEN a Mail_Header matches no Shortlist_Rule, THE Mail_Triage SHALL discard the Mail_Header from memory without creating an Inbox_Item and without sending any part of the email to the AI_Provider.
4. IF the User saves a Shortlist_Rules entry that is empty, longer than 100 characters, a duplicate of an existing entry, or beyond the 200-entry limit, THEN THE Settings_Screen SHALL reject the entry, show an error indicating the reason, and keep the existing Shortlist_Rules unchanged.
5. IF the Shortlist_Rules contain no entries, THEN THE Mail_Triage SHALL shortlist no email, and THE Settings_Screen SHALL show that no mail will be shortlisted until a rule is added.

### Requirement 54: Mail classification and extraction (Phase 6: Email)

**User Story:** As a User, I want shortlisted mail scored and key details pulled out, so that I see interviews, bills, deadlines and deliveries clearly.

#### Acceptance Criteria

1. WHERE the AI_Mode is Cloud or OnDevice, WHEN an email is shortlisted, THE AI_Provider SHALL return as JSON, under the mail classifier Prompt_Type, an importance score as a whole number from 0 to 10, one category from interview, bill, deadline, delivery and other, and only the extracted fields defined in the mail classifier schema.
2. WHILE the AI_Mode is None, THE Mail_Triage SHALL give each shortlisted email its Rule_Score, so that the same Mail_Header and the same Shortlist_Rules always produce the same score and category, with category other when no rule implies a category.
3. WHEN classification of an email finishes, THE Mail_Triage SHALL store one Inbox_Item with sender, subject, category, extracted fields, importance score and Source_Ref, with no snippet, no Message_Text and no links, and SHALL discard the snippet from memory; IF an Inbox_Item with the same Source_Ref already exists, THEN THE Mail_Triage SHALL keep the existing Inbox_Item and create no second Inbox_Item.
4. THE AI_Provider SHALL receive email data only for shortlisted emails that are not OTP_Mail, and only as a Redacted_Payload built from that email's Mail_Header, after the Redactor has removed links and text matching the Instruction_Pattern_List.
5. IF the AI request for a shortlisted email fails, times out after 30 seconds, or is rejected by the Output_Validator after all retries, THEN THE Mail_Triage SHALL store the Inbox_Item using the Rule_Score for that email.
6. IF the AI_Provider output has an importance score that is not a whole number from 0 to 10, a category outside interview, bill, deadline, delivery and other, or a field not in the mail classifier schema, THEN THE Output_Validator SHALL reject the output and THE AI_Provider SHALL retry as defined in Requirement 43.
7. WHEN an email from the Prompt_Injection_Fixture set is processed, THE Mail_Triage SHALL produce no Task, Bill, Suggestion, notification, Shortlist_Rules change or setting change that follows the injected instruction, and SHALL take the importance score and category only from schema fields accepted by the Output_Validator or from the Rule_Score.

### Requirement 55: Important mail alerts (Phase 6: Email)

**User Story:** As a User, I want alerts only for truly important mail, so that I am not disturbed by noise.

#### Acceptance Criteria

1. WHEN an Inbox_Item is created with an importance score at or above the Importance_Threshold (a whole number from 0 to 10 set by the User), THE Notification_Scheduler SHALL pass one important-mail notification to the Notification_Governor showing only the sender, subject and category.
2. THE important-mail notification SHALL include a "Not important" action and SHALL contain no snippet, no Message_Text and no links from the email.
3. WHEN the User taps "Not important" on an important-mail notification, THE Quality_Monitor SHALL count one Wrong_Mail_Alert for that notification, and SHALL not count further taps on the same notification.
4. THE Quality_Monitor SHALL show the number of Wrong_Mail_Alerts and the total number of important-mail notifications for each calendar week (Monday to Sunday, device local time) of the last 8 weeks.
5. WHEN the User taps "Not important" on an important-mail notification for the first time, THE Mail_Triage SHALL increase the Sender_Penalty for that sender email address by 2 and SHALL subtract the Sender_Penalty from the importance score of every later email from that address, with a minimum score of 0.
6. IF an Inbox_Item's importance score is below the Importance_Threshold, THEN THE Notification_Scheduler SHALL create no important-mail notification for that Inbox_Item, and the Inbox_Item SHALL appear only in the Mail_Digest.

### Requirement 56: Daily mail digest (Phase 6: Email)

**User Story:** As a User, I want one daily digest of important mail, so that I can check everything in one place.

#### Acceptance Criteria

1. WHEN the User-set digest time is reached, THE Mail_Digest SHALL pass one notification to the Notification_Governor showing the number of Inbox_Items per category created in the 24 hours before the digest time.
2. IF no Inbox_Items were created in the 24 hours before the digest time and the Mailbox_Status is connected, THEN THE Mail_Digest SHALL skip the notification for that day.
3. WHEN the User opens the Mail_Digest, THE Mail_Digest SHALL list the Inbox_Items created in the 24 hours before the digest time, grouped by category in the order interview, bill, deadline, delivery, other, newest first within each category.
4. IF the Mailbox_Status is needs reconnection at the digest time, THEN THE Mail_Digest SHALL pass the digest notification to the Notification_Governor with a line stating that mail sync has stopped, even when no Inbox_Items were created in the 24 hours before the digest time.

### Requirement 57: Create tasks and bills from mail (Phase 6: Email)

**User Story:** As a User, I want to turn an important email into a task or bill with one tap, so that nothing gets forgotten.

#### Acceptance Criteria

1. WHEN the User taps "Create task" on an Inbox_Item, THE Task_Manager SHALL open a new Task form for the User to save, with the title prefilled from the Inbox_Item subject, the due date prefilled from the extracted date field, and the due date left empty when no valid calendar date was extracted.
2. WHEN the User taps "Create bill" on an Inbox_Item of category bill, THE Bill_Tracker SHALL open a new Bill form for the User to save, with the name prefilled from the sender display name, the amount prefilled only if the extracted amount is a positive number, and the due date prefilled only if the extracted due date is a valid calendar date, and with all other fields left empty.
3. WHEN the User cancels a Task or Bill form opened from an Inbox_Item, THE Assistant_App SHALL create no Task or Bill and SHALL leave the Inbox_Item unchanged.
4. THE Assistant_App SHALL show the "Create bill" action only on Inbox_Items of category bill.

### Requirement 58: Email quality target (Phase 6: Email)

**User Story:** As the developer, I want a measurable target for wrong alerts, so that I know when Phase 6 is good enough.

#### Acceptance Criteria

1. THE Mail_Triage SHALL average fewer than 1 Wrong_Mail_Alert per day (fewer than 7 Wrong_Mail_Alerts in total), measured by the Quality_Monitor over 7 consecutive calendar days of the developer's use during which the Mailbox_Status was connected for the whole of each day.
2. THE Quality_Monitor SHALL show the Wrong_Mail_Alert count for each of the last 7 calendar days and the average per day over those 7 days.
3. IF the Mailbox_Status was not connected for the whole of a calendar day, THEN THE Quality_Monitor SHALL leave that day out of the measurement and SHALL start a new 7-day window on the next calendar day that is fully connected.

### Requirement 59: Huntfolio career import (Phase 7: Career import and Polish)

**User Story:** As a User, I want to import jobs, follow-ups and skills from Huntfolio, so that job-search actions appear in my daily plan.

#### Acceptance Criteria

1. WHEN the User selects a Huntfolio_Export file that passes the checks in criterion 8, THE Career_Importer SHALL parse the file into job, follow-up and skill records and SHALL show the Import_Preview with the number of jobs, follow-ups and skills found and the number of those already imported, writing no record to the Encrypted_Store until the User confirms.
2. IF a file that passes the checks in criterion 8 contains a record with a missing required field or a field value that does not match the Huntfolio_Export format, THEN THE Career_Importer SHALL show an error naming the 1-based position and type (job, follow-up or skill) of the first invalid record and the name of the invalid field, and SHALL import nothing.
3. WHEN the User confirms the import on the Import_Preview, THE Career_Importer SHALL create one Task for each follow-up not imported before, with the follow-up's due date, or with no due date if the follow-up has none.
4. WHEN the User selects one or more skills on the Import_Preview, sets a Goal_Period, target and unit for each selected skill, and confirms the import, THE Career_Importer SHALL create one Goal for each selected skill not imported before, with the Goal_Period, target and unit the User set.
5. FOR ALL Huntfolio_Export files, importing the same file twice SHALL create the same Tasks and Goals as importing the file once, with records matched by Huntfolio_Record_Key (idempotence property).
6. THE Huntfolio_Formatter SHALL write every job, follow-up and skill record that the Career_Importer accepts in Huntfolio_Export format, using a Huntfolio_Format_Version the Career_Importer supports.
7. FOR ALL valid job, follow-up and skill records, formatting with the Huntfolio_Formatter then parsing with the Career_Importer SHALL produce records with the same Huntfolio_Record_Key and the same value in every field defined by the Huntfolio_Export format (round-trip property).
8. IF the selected file is larger than 5 MB, cannot be read as a Huntfolio_Export, has a missing Huntfolio_Format_Version or one the Career_Importer does not support, or contains no job, follow-up or skill records, THEN THE Career_Importer SHALL show an error indicating which of these causes applies, SHALL import nothing, and SHALL leave the Encrypted_Store unchanged.
9. WHEN a confirmed import contains a follow-up or skill whose Huntfolio_Record_Key matches a record imported earlier, THE Career_Importer SHALL skip that record, SHALL leave the existing Task or Goal unchanged, and SHALL show the number of skipped records.
10. IF an import stops before all its Tasks and Goals are written (the app is closed, the device shuts down or storage runs out), THEN THE Career_Importer SHALL keep none of the records from that import, leaving the Encrypted_Store as it was before the import started.

### Requirement 60: Encrypted export and restore (Phase 7: Career import and Polish)

**User Story:** As a User, I want to export and restore all my data as an encrypted file, so that I do not lose everything if I lose my phone.

#### Acceptance Criteria

1. WHEN the User starts an export, enters the same Export_Passphrase of 12 to 128 characters twice, and picks a save location, THE Backup_Manager SHALL state that a lost Export_Passphrase cannot be recovered and SHALL write one Export_File at that location containing all records in the Encrypted_Store and all settings, encrypted with that Export_Passphrase.
2. WHEN the User confirms the replace warning described in criterion 6, THE Backup_Manager SHALL replace all current user data with the data in the Export_File, SHALL reschedule notifications from the restored settings, and SHALL show each Data_Source whose credentials are not in the Export_File as disconnected in the Settings_Screen.
3. IF the Export_Passphrase is wrong, the Export_File is damaged or altered, or the Export_File's Export_Data_Version is newer than the installed Assistant_App supports, THEN THE Backup_Manager SHALL show an error indicating either "wrong passphrase or damaged file" or "file made by a newer app version", SHALL leave the current data unchanged, and SHALL let the User enter the passphrase again without selecting the file again.
4. FOR ALL user data sets, exporting with an Export_Passphrase then restoring with the same Export_Passphrase SHALL produce records and settings equal to the original, apart from the items excluded by criterion 5 (round-trip property).
5. THE Export_File SHALL contain no stored credentials, no Cloud AI key, no database key, no Export_Passphrase, and no user data outside the passphrase-encrypted content.
6. WHEN the User selects an Export_File from the Settings_Screen or the First_Run_Screen and enters the correct Export_Passphrase, THE Backup_Manager SHALL show the date the Export_File was created and a warning that all current data will be replaced, and SHALL change no data until the User confirms.
7. IF the two Export_Passphrase entries differ, the Export_Passphrase is shorter than 12 or longer than 128 characters, or writing the Export_File fails or is cancelled, THEN THE Backup_Manager SHALL show an error indicating the cause and SHALL leave no complete or partial Export_File at the chosen location.
8. IF a restore stops before completion (the app is closed, the device shuts down or storage runs out), THEN THE Backup_Manager SHALL keep the data that existed before the restore started and SHALL show, the next time the Assistant_App opens, that the restore did not complete.

### Requirement 61: Battery optimisation guide (Phase 7: Career import and Polish)

**User Story:** As a User on a Xiaomi, Oppo, Vivo or Samsung phone, I want a guide to stop the system killing the app, so that reminders keep working.

#### Acceptance Criteria

1. WHILE battery optimisation is active for the Assistant_App and the Battery_Guide has not yet been shown automatically, WHEN the Assistant_App opens, THE Battery_Guide SHALL show the steps to exempt the Assistant_App for the manufacturer reported by the device.
2. WHEN the User opens the Battery_Guide from the Settings_Screen, THE Battery_Guide SHALL show the steps for the manufacturer reported by the device and whether battery optimisation is currently active for the Assistant_App, whether or not the Battery_Guide was shown before.
3. IF the device reports no manufacturer, or the reported manufacturer does not match (ignoring letter case) any manufacturer that has Manufacturer_Steps in the Battery_Guide, THEN THE Battery_Guide SHALL show the Standard_Android_Steps.
4. WHEN the User taps the open-settings action in the Battery_Guide, THE Battery_Guide SHALL open the Android battery-optimisation settings screen for the Assistant_App.
5. IF the Android battery-optimisation settings screen cannot be opened on the device, THEN THE Battery_Guide SHALL keep showing the written steps and SHALL show that the User must open the settings screen by hand.
6. WHEN the User returns to the Assistant_App from system settings opened by the Battery_Guide, THE Battery_Guide SHALL check again and show whether battery optimisation is still active for the Assistant_App.

### Requirement 62: Health check (Phase 7: Career import and Polish)

**User Story:** As a User, I want the app to tell me when background work has stopped, so that I can fix it before I miss reminders.

#### Acceptance Criteria

1. IF a scheduled morning brief or evening review job has not started within 30 minutes after its scheduled time, THEN THE Health_Check SHALL record the miss with the job name and scheduled time, and SHALL show a health warning naming the job and the scheduled time, with a link to the Battery_Guide, the next time the Assistant_App opens.
2. IF, when the Assistant_App opens or a scheduled job starts, a permission needed by an enabled feature is revoked, notifications for the Assistant_App are off in system settings, or the system notification channel of an enabled Notification_Type is off, THEN THE Health_Check SHALL show a health warning (in the app if the app is open, otherwise as a health warning notification through the Notification_Governor) naming each affected feature and offering an action that opens the system settings screen where the User can fix it.
3. THE Settings_Screen SHALL show, for each scheduled job and each permission-dependent feature, a Health_Status of Working, Needs attention or Off, and, for each scheduled job, the time of the last successful run.
4. WHEN the cause of a health warning is no longer present (the job starts within 30 minutes of its next scheduled time, or the permission or notification setting is restored), THE Health_Check SHALL set the feature's Health_Status to Working and remove that item from the health warning at the next check.
5. WHEN two or more misses or problems are open at the same time, THE Health_Check SHALL show all of them in one health warning, listing each affected job or feature.
6. WHEN the User dismisses a health warning, THE Health_Check SHALL keep the warning hidden until a new miss or problem is recorded, and SHALL keep the Health_Status of each affected feature at Needs attention until the cause is no longer present.

### Requirement 63: Accessibility (Phase 7: Career import and Polish)

**User Story:** As a User with visual or motor needs, I want the app to work with accessibility settings, so that I can use every feature.

#### Acceptance Criteria

1. THE Assistant_App SHALL give every interactive element, and every image, chart or progress bar that carries information, a content description read by TalkBack that names the element's purpose or states the values shown, and SHALL hide decorative images from TalkBack.
2. THE Assistant_App SHALL make the touch target of every interactive element on every screen and dialog at least 48 by 48 dp.
3. WHILE the system font scale is any value from 100 to 200 percent, THE Assistant_App SHALL show every screen on a 360 dp wide display without clipped or overlapping text and with every action reachable (by scrolling where needed), and any text shortened with an ellipsis SHALL be readable in full on the item's detail screen or through TalkBack.
4. THE Assistant_App SHALL show all text with a contrast ratio of at least 4.5 to 1 against its background in both the light and dark themes.
5. THE Assistant_App SHALL show icons, input field borders and focus indicators with a contrast ratio of at least 3 to 1 against adjacent colours in both the light and dark themes.
6. THE Assistant_App SHALL show every status that uses colour (for example on track or behind, Budget over or under) with text or an icon as well.
7. WHILE TalkBack is on, THE Assistant_App SHALL let the User complete every flow, including Quick_Add, the Evening_Review, Huntfolio import, export and restore, using TalkBack gestures only, with focus moving through each screen top to bottom and start to end.

### Requirement 64: Security and test suites (Phase 7: Career import and Polish)

**User Story:** As the developer, I want automated checks for the security and quality rules, so that later changes cannot quietly break them.

#### Acceptance Criteria

1. THE Test_Fixture_Set SHALL contain at least 200 anonymised Money_Messages, with at least 5 for each Bank_Format in the Parser_Registry and each with its expected Parsed_Message, plus at least 10 OTP_Messages and at least 10 Prompt_Injection_Fixtures. In every fixture, each name, account digit, VPA, phone number and reference number SHALL be replaced with a synthetic value.
2. THE CI_Pipeline SHALL run tests that confirm (a) the Encrypted_Store fails to open and returns no user records without the key from the Key_Manager, (b) no OTP_Message from the Test_Fixture_Set is stored, logged or sent, (c) no Message_Text from the Test_Fixture_Set appears in application logs or crash reports, and (d) each Prompt_Injection_Fixture produces no Task, Suggestion, notification or setting change that follows its instruction, and the Redactor removes text matching the Instruction_Pattern_List.
3. THE CI_Pipeline SHALL run tests, for every Prompt_Type, that confirm the Output_Validator rejects AI output containing a number that does not appear in the Redacted_Payload and cannot be derived from it by the rules of that Prompt_Type, and accepts otherwise valid output whose numbers all appear in or are derived from the Redacted_Payload.
4. THE Device_Test_Plan SHALL check, on each Android version from Android 8.0 (API level 26) through Android 15 (API level 35) and on the Low_RAM_Profile, that time-specific reminders with the exact-alarm permission granted arrive within 1 minute of the scheduled time after a reboot, in Doze mode, and in battery saver.
5. IF any test run by the CI_Pipeline fails, THEN THE CI_Pipeline SHALL mark the build as failed and SHALL produce no Release_Build from that commit.
6. WHEN code is pushed to the Project_Repository, THE CI_Pipeline SHALL scan the pushed files for signing keys, API keys and passwords and SHALL mark the build as failed if any is found.
7. THE CI_Pipeline SHALL run tests that confirm an Export_File contains no stored credentials, Cloud AI key, database key or Export_Passphrase, and that the round-trip and idempotence properties of Requirements 59 and 60 hold.

### Requirement 65: APK distribution to friends (Phase 8: Share and distribution)

**User Story:** As the developer, I want to share a signed APK with friends, so that they can use the app before any store release.

#### Acceptance Criteria

1. THE Release_Build SHALL be an APK signed with the release signing key held in the Secret_Store, with a version name and a positive integer version code.
2. WHEN a User installs a newer Release_Build signed with the same key over an installed Assistant_App, THE Assistant_App SHALL keep all Encrypted_Store data, all settings and the connected or disconnected status of each data source, and SHALL show the existing data after App_Lock authentication without asking the User to re-enter or re-import any data.
3. THE Settings_Screen SHALL show the installed version name and version code.
4. IF the version code of a Release_Build is less than or equal to the version code of the last distributed Release_Build, THEN THE CI_Pipeline SHALL fail the release and SHALL produce no distributable APK.
5. WHEN a newer Release_Build is installed over an installed Assistant_App, THE Notification_Scheduler SHALL show each notification that was scheduled before the update and is due after the update at its scheduled time, without the User opening the Assistant_App.
6. IF the stored data cannot be converted to the newer version's format on the first start after an update, THEN THE Assistant_App SHALL leave the Encrypted_Store unchanged, SHALL delete or overwrite no stored data, and SHALL show an error message stating that the update could not convert the data.

### Requirement 66: Use by people other than the developer (Phase 8: Share and distribution)

**User Story:** As a friend using the app, I want clear consent and a privacy policy, so that I know what the app does with my data.

#### Acceptance Criteria

1. WHERE the build is for users other than the developer, WHEN the Assistant_App opens for the first time or after the Data_Wiper finishes, THE Assistant_App SHALL show the Consent_Screen on the First_Run_Screen before any Permission_Rationale, permission request or data source read, and the Consent_Screen SHALL list each data source the Assistant_App can read (SMS, app notifications, calendar, mailbox, Huntfolio import), SHALL state that only a Redacted_Payload is sent through the Proxy_Server in the Cloud AI_Mode and that nothing is sent in the OnDevice or None AI_Mode, and SHALL state that all other user data stays on the phone except Export_Files the User creates.
2. WHERE the build is for users other than the developer, THE Settings_Screen SHALL show the Privacy_Policy without needing a network connection, and the Privacy_Policy SHALL state the data sources read, what is sent to the AI_Provider and the Proxy_Server, and how to delete all data with "Delete everything" (Requirement 8).
3. WHERE the build is for users other than the developer, THE Proxy_Server SHALL hold each Redacted_Payload and AI response in memory only until the request completes or fails, and SHALL write no Redacted_Payload content or AI response content to storage or logs.
4. WHERE the build is for users other than the developer, THE Project_Repository SHALL contain a dated record of a review of the Consent_Screen and Privacy_Policy text against the DPDP Act 2023 obligations (consent, privacy policy, deletion on request, reasonable security; verify, not stated here as fact) before the first such Release_Build is shared.
5. WHERE the build is for users other than the developer, IF the User declines on the Consent_Screen shown at first run, THEN THE Assistant_App SHALL request no permission, read no data source, send no data to any AI_Provider or the Proxy_Server, store no user data, and show the Consent_Screen again the next time the Assistant_App opens.
6. WHERE the build is for users other than the developer, WHEN the User accepts on the Consent_Screen, THE Assistant_App SHALL store the acceptance date and time and the accepted Consent_Text_Version in the Encrypted_Store and SHALL show both in the Settings_Screen.
7. WHERE the build is for users other than the developer, WHEN the Assistant_App first opens after an update whose Consent_Text_Version differs from the accepted Consent_Text_Version, THE Assistant_App SHALL show the Consent_Screen again and SHALL read no data source and send no data to any AI_Provider until the User accepts.

### Requirement 67: Google Play readiness (Phase 8: Share and distribution, conditional)

**User Story:** As the developer, I want the option of a Play Store release, so that I can publish if the store rules allow.

#### Acceptance Criteria

1. WHERE the Assistant_App is distributed on Google Play, THE Release_Build SHALL include the permission declarations that the Google Play policy in force at submission requires for SMS and notification-listener access (verify current policy; approval is not guaranteed).
2. WHEN the SMS exclusion build option is set, THE CI_Pipeline SHALL produce an Sms_Free_Build that declares no SMS permission and contains no Sms_Reader, so that a build without SMS reading can be released if the SMS declaration is refused.
3. WHERE the build is an Sms_Free_Build, THE Assistant_App SHALL keep the Notification_Reader, manual Transactions and every feature that does not read SMS working, as shown by all tests other than SMS-reading tests passing on the Sms_Free_Build.
4. WHERE the build is an Sms_Free_Build, THE Assistant_App SHALL omit SMS from the data source list in the Settings_Screen and SHALL never request an SMS permission.
5. WHERE the Assistant_App is distributed on Google Play, WHEN the User restores an Export_File created by an APK Release_Build, THE Backup_Manager SHALL restore all stored facts, including Transactions whose source is SMS, with their Source_Ref.

### Requirement 68: Important dates (Later, optional)

**User Story:** As a User, I want reminders for important dates like insurance, vehicle and passport renewals, so that I renew on time.

#### Acceptance Criteria

1. WHERE important dates are enabled, WHEN the User saves an Important_Date with a name of 1 to 80 characters, a date on or after the current date, and a lead time from 0 to 365 days (default 30), THE Assistant_App SHALL store the Important_Date in the Encrypted_Store.
2. WHERE important dates are enabled, WHEN the morning brief time is reached on the date that is the lead time in days before an Important_Date, THE Notification_Scheduler SHALL show a reminder of Notification_Type "important date" naming the item, the date and the number of days left, subject to the Notification_Governor rules.
3. WHERE important dates are enabled, IF the User saves an Important_Date with an empty name, a name longer than 80 characters, a date before the current date, or a lead time outside 0 to 365 days, THEN THE Assistant_App SHALL reject the save, show which field is invalid, and keep the values the User entered.
4. WHERE important dates are enabled, IF the reminder time of a newly saved Important_Date (the morning brief time on the date that is the lead time in days before the Important_Date) has already passed, THEN THE Notification_Scheduler SHALL show the reminder within 1 minute of the save.
5. WHERE important dates are enabled, WHEN the User edits or deletes an Important_Date, THE Notification_Scheduler SHALL replace or cancel the pending reminder so that only a reminder matching the saved values is shown.

### Requirement 69: Habits (Later, optional)

**User Story:** As a User, I want to track daily or weekly habits, so that I can build routines.

#### Acceptance Criteria

1. WHERE habits are enabled, WHEN the User saves a Habit with a name of 1 to 80 characters and a frequency of daily or weekly, THE Assistant_App SHALL store the Habit in the Encrypted_Store.
2. WHERE habits are enabled, THE Progress_Tracker SHALL show the Habit_Streak of each Habit as defined in the Glossary, and SHALL show 0 for a Habit with no done mark in the current or previous day (daily) or week (weekly).
3. WHERE habits are enabled, IF the User saves a Habit with an empty name, a name longer than 80 characters, or no frequency, THEN THE Assistant_App SHALL reject the save, show which field is invalid, and keep the values the User entered.
4. WHERE habits are enabled, WHEN the User marks a Habit done, THE Progress_Tracker SHALL record one done mark for the current date, SHALL count repeated marks on the same date as one, and SHALL remove the mark if the User unmarks the Habit on the same date.

### Requirement 70: Learning plan (Later, optional)

**User Story:** As a User, I want a learning plan with sessions placed in free time, so that I keep learning steadily.

#### Acceptance Criteria

1. WHERE the learning plan is enabled, WHEN the User saves a Learning_Topic with a name of 1 to 80 characters, a weekly session target from 1 to 14 sessions and a session length from 15 to 180 minutes (default 30), THE Goal_Manager SHALL store the Learning_Topic as a week Goal with the session target as the target and "sessions" as the unit.
2. WHERE the learning plan is enabled, WHEN the Gap_Finder finds a Free_Gap at least as long as a Learning_Topic's session length and the Learning_Topic's completed sessions in the current week are fewer than its weekly session target, THE Gap_Finder SHALL propose one learning session of that session length in that Free_Gap, with at most one proposal per Learning_Topic per date.
3. WHERE the learning plan is enabled, IF the User saves a Learning_Topic with an empty name, a name longer than 80 characters, a weekly session target outside 1 to 14, or a session length outside 15 to 180 minutes, THEN THE Goal_Manager SHALL reject the save, show which field is invalid, and keep the values the User entered.
4. WHERE the learning plan is enabled, WHEN the User accepts a learning session proposal, THE Plan_Builder SHALL add a Time_Block for a Task named after the Learning_Topic and linked to its week Goal, and SHALL leave the phone calendar unchanged.
5. WHERE the learning plan is enabled, WHEN the User completes a learning session Task, THE Goal_Manager SHALL add 1 to the progress of the Learning_Topic's week Goal.

### Requirement 71: Voice capture (Later, optional)

**User Story:** As a User, I want to add tasks by voice, so that capture works when my hands are busy.

#### Acceptance Criteria

1. WHERE voice capture is enabled, WHEN the User taps the voice control in Quick_Add and speaks, THE Assistant_App SHALL convert the speech to text, SHALL place the text in the Quick_Add field for the User to edit, and SHALL create the Task only after the User confirms.
2. WHERE voice capture is enabled, WHEN the User taps the voice control for the first time, THE Permission_Manager SHALL show the Permission_Rationale for the microphone and then request the microphone permission, and SHALL request the microphone permission at no earlier time.
3. WHERE voice capture is enabled, THE Assistant_App SHALL perform speech recognition on the device, SHALL send no audio off the device, and SHALL store no audio after the recognised text is shown.
4. WHERE voice capture is enabled, IF on-device speech recognition is not available on the phone, THEN THE Assistant_App SHALL show the voice control as unavailable with the reason and SHALL keep text entry in Quick_Add working.
5. WHERE voice capture is enabled, IF the microphone permission is denied, no speech is recognised within 10 seconds of tapping the voice control, or the User cancels voice capture, THEN THE Assistant_App SHALL create no Task, SHALL show a message indicating why no text was captured, and SHALL keep text entry in Quick_Add working.

### Requirement 72: Hindi and Tamil (Later, optional; Open decision 6)

**User Story:** As a User who prefers Hindi or Tamil, I want the app and its suggestions in my language, so that the app is easier to use.

#### Acceptance Criteria

1. WHERE Hindi or Tamil is chosen as a supported App_Language, WHEN the User selects that App_Language in the Settings_Screen, THE Assistant_App SHALL show all screens, notifications, Widget text and rule-based template wording in that App_Language, starting with the next screen shown, without a reinstall or a data reset.
2. WHERE a Hindi or Tamil App_Language is selected and the AI_Mode is Cloud or OnDevice, THE Output_Validator SHALL reject any Suggestion whose text and reason, after removing numbers, Merchant names, Task titles and Goal names, have more than half of their letters outside the script of the selected App_Language (Devanagari for Hindi, Tamil script for Tamil). The rejected output is then retried and falls back to the Rule_Based_Result under Requirement 43.
3. THE Assistant_App SHALL keep all user-facing text in Android string resource files, so that an App_Language can be added by adding resource files without code changes.
4. WHERE a Hindi or Tamil App_Language is selected, IF a user-facing text has no translation in the selected App_Language, THEN THE Assistant_App SHALL show the English text for that item.
5. WHERE a Hindi or Tamil App_Language is selected, THE Assistant_App SHALL show User-entered text, Merchant names and message-derived fields exactly as stored, and SHALL show amounts and dates with the same digits as the English App_Language.
6. WHERE a Hindi or Tamil App_Language is selected, THE Message_Parser SHALL produce the same Parsed_Message for a given Money_Message as it produces with the English App_Language.
