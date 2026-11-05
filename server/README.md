# Apex Sync service (planned)

Opt-in sync for Apex Assistant. The phone stays the source of truth (see docs/apex-blueprint.html).
Not built yet: hosting, region and the backend language are open decisions.

- `db/schema.sql`: PostgreSQL 16 schema.
- `db/goal-decomposer.prompt.txt`: system prompt for the goal-decomposition agent.

## REST routes (/v1)

### Auth & devices

- `POST /v1/auth/session` Exchange a Google / Apple / email OTP sign-in for access + refresh tokens
- `POST /v1/devices` Register a device: platform, push token, public key
- `DELETE /v1/devices/{id}` Sign a device out and revoke its tokens
- `DELETE /v1/me` Delete the account and every synced row (hard purge within 30 days)
- `GET /v1/me/export` Download everything the service holds about you (JSON)

### Sync

- `POST /v1/sync/push` Upload local changes: [{entity, id, version, op, fields}]; returns conflicts
- `GET /v1/sync/pull?since={seq}` Changes after a cursor, paginated; long-poll or SSE

### Module A · Goals

- `POST /v1/goals` Create a goal
- `PATCH /v1/goals/{id}` Edit title, deadline, priority, status
- `POST /v1/goals/{id}/decompose` Ask the decomposer agent for milestones and tasks; returns a proposal, writes nothing
- `POST /v1/goals/{id}/decompose/{proposalId}/accept` Apply an accepted (and optionally edited) proposal
- `GET /v1/goals/{id}/progress` Milestone and task completion, hours done vs. planned, forecast finish date
- `POST /v1/tasks/{id}/verifications` Attach proof: photo (pre-signed upload), link, or tracked time

### Module B · Calendar & routines

- `POST /v1/calendar/connections/{provider}` Start OAuth for google | outlook (PKCE)
- `GET /v1/calendar/accounts` List calendars with read / write flags
- `PATCH /v1/calendar/accounts/{id}` Turn writing on or off for one calendar
- `POST /v1/calendar/resync` Incremental resync of all accounts (sync tokens / delta links)
- `POST /v1/calendar/webhooks/google` Google push notification channel (verified)
- `POST /v1/calendar/webhooks/microsoft` Microsoft Graph change notification (validated)
- `POST /v1/schedule/plan?date=YYYY-MM-DD` Run the slot assigner for a day or week; returns proposed blocks
- `POST /v1/schedule/reschedule` Auto-reschedule after a spill-over or overrun; returns a diff to accept
- `POST /v1/schedule/apply` Apply an accepted diff (writes to provider calendars only where enabled)
- `CRUD /v1/routines` Routines with RRULE, energy tag and flexibility

### Module C · Reminders & mail

- `CRUD /v1/reminders` Time, location and device-state reminders (location stays on device unless synced)
- `POST /v1/mail/connections/google` Start Gmail OAuth (gmail.readonly)
- `POST /v1/mail/scan` Scan new messages since the last history id; extracts facts, stores no bodies
- `GET /v1/mail/facts?status=proposed` Invoices, meetings, flights and action items waiting for review
- `POST /v1/mail/facts/{id}/accept` Turn a fact into a task, bill, event or subscription
- `POST /v1/mail/webhooks/gmail` Gmail Pub/Sub push (verified JWT)

### Module D · Subscriptions

- `GET /v1/subscriptions/ledger` Active list with monthly burn, yearly total and days to renewal
- `POST /v1/subscriptions/parse` Detect recurring charges from a batch of extracted charge facts
- `POST /v1/subscriptions/{id}/confirm` Confirm a proposed subscription
- `POST /v1/subscriptions/{id}/usage` Answer “did you use it?”
- `POST /v1/subscriptions/{id}/cancelled` Mark as cancelled after the user cancels with the merchant

### Module E · Proactive agent

- `GET /v1/cards?date=YYYY-MM-DD` Proactive cards for a day
- `POST /v1/cards/{id}/accept` Apply the card's proposal
- `POST /v1/cards/{id}/dismiss` Dismiss, with an optional “not useful” signal
