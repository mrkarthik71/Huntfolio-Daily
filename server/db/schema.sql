-- Apex Sync service schema (PostgreSQL 16). The phone stays the source of truth; this is the
-- opt-in sync mirror. Every row is owned by one user and carries a sync version.
CREATE EXTENSION IF NOT EXISTS pgcrypto;   -- gen_random_uuid()
CREATE EXTENSION IF NOT EXISTS btree_gist; -- exclusion constraints on time ranges

CREATE TYPE energy_tag      AS ENUM ('deep_work', 'light_admin', 'physical_routine', 'rest');
CREATE TYPE goal_status     AS ENUM ('active', 'paused', 'done', 'archived');
CREATE TYPE task_status     AS ENUM ('open', 'scheduled', 'done', 'moved', 'dropped');
CREATE TYPE verify_kind     AS ENUM ('none', 'photo', 'link', 'timer');
CREATE TYPE cal_provider    AS ENUM ('device', 'google', 'outlook');
CREATE TYPE reminder_kind   AS ENUM ('time', 'location', 'device_state');
CREATE TYPE sub_status      AS ENUM ('proposed', 'active', 'cancelled', 'rejected', 'ended');
CREATE TYPE sub_interval    AS ENUM ('weekly', 'monthly', 'quarterly', 'yearly');

-- ---------------------------------------------------------------- users & devices
CREATE TABLE users (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    email           citext UNIQUE NOT NULL,
    display_name    text,
    time_zone       text NOT NULL DEFAULT 'Asia/Kolkata',     -- IANA zone
    locale          text NOT NULL DEFAULT 'en-IN',
    currency        char(3) NOT NULL DEFAULT 'INR',
    work_hours      jsonb NOT NULL DEFAULT '{"start":"09:30","end":"18:30","days":[1,2,3,4,5]}',
    energy_profile  jsonb NOT NULL DEFAULT '{}',               -- preferred hours per energy_tag
    created_at      timestamptz NOT NULL DEFAULT now(),
    deleted_at      timestamptz                                -- soft delete, hard purge after 30 days
);

CREATE TABLE devices (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    platform        text NOT NULL CHECK (platform IN ('android', 'ios', 'web')),
    push_token      text,                                      -- FCM / APNs
    public_key      bytea NOT NULL,                            -- device key for end-to-end encrypted fields
    last_seen_at    timestamptz,
    sync_cursor     bigint NOT NULL DEFAULT 0
);

CREATE TABLE oauth_connections (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    provider        text NOT NULL CHECK (provider IN ('google', 'microsoft')),
    scopes          text[] NOT NULL,
    refresh_token   bytea NOT NULL,                            -- envelope-encrypted with a KMS key
    expires_at      timestamptz,
    revoked_at      timestamptz,
    UNIQUE (user_id, provider)
);

-- ---------------------------------------------------------------- Module A: goals
CREATE TABLE goals (
    id              uuid PRIMARY KEY,                          -- client-generated (UUIDv7) so offline creates sync
    user_id         uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    parent_id       uuid REFERENCES goals(id) ON DELETE SET NULL,
    title           text NOT NULL CHECK (length(title) BETWEEN 1 AND 200),
    why             text,
    status          goal_status NOT NULL DEFAULT 'active',
    priority        smallint NOT NULL DEFAULT 3 CHECK (priority BETWEEN 1 AND 5),
    deadline        date,
    target_qty      numeric(12,2),                             -- e.g. 12 books
    target_unit     text,
    estimate_hours  numeric(7,2),                              -- total effort, set by the estimator or the user
    period          text CHECK (period IN ('week', 'month', 'quarter', 'year', 'none')),
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    version         bigint NOT NULL DEFAULT 1,
    deleted         boolean NOT NULL DEFAULT false
);
CREATE INDEX goals_user_active ON goals (user_id) WHERE status = 'active' AND NOT deleted;

CREATE TABLE milestones (
    id              uuid PRIMARY KEY,
    goal_id         uuid NOT NULL REFERENCES goals(id) ON DELETE CASCADE,
    user_id         uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    title           text NOT NULL,
    position        smallint NOT NULL,
    due_date        date,
    estimate_hours  numeric(7,2),
    completed_at    timestamptz,
    version         bigint NOT NULL DEFAULT 1,
    deleted         boolean NOT NULL DEFAULT false,
    UNIQUE (goal_id, position)
);

CREATE TABLE tasks (
    id              uuid PRIMARY KEY,
    user_id         uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    goal_id         uuid REFERENCES goals(id) ON DELETE SET NULL,
    milestone_id    uuid REFERENCES milestones(id) ON DELETE SET NULL,
    title           text NOT NULL CHECK (length(title) BETWEEN 1 AND 200),
    status          task_status NOT NULL DEFAULT 'open',
    priority        smallint NOT NULL DEFAULT 3 CHECK (priority BETWEEN 1 AND 5),
    energy          energy_tag NOT NULL DEFAULT 'light_admin',
    estimate_min    integer CHECK (estimate_min BETWEEN 5 AND 600),
    due_date        date,
    planned_date    date,
    verify          verify_kind NOT NULL DEFAULT 'none',
    source          text NOT NULL DEFAULT 'manual',            -- manual | decomposer | mail | huntfolio
    created_at      timestamptz NOT NULL DEFAULT now(),
    completed_at    timestamptz,
    version         bigint NOT NULL DEFAULT 1,
    deleted         boolean NOT NULL DEFAULT false
);
CREATE INDEX tasks_user_open ON tasks (user_id, due_date) WHERE status IN ('open', 'scheduled') AND NOT deleted;

CREATE TABLE task_verifications (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    task_id         uuid NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    kind            verify_kind NOT NULL CHECK (kind <> 'none'),
    blob_key        text,                                      -- photo in object storage, client-encrypted
    link_url        text CHECK (link_url ~ '^https://'),
    tracked_seconds integer CHECK (tracked_seconds > 0),
    created_at      timestamptz NOT NULL DEFAULT now(),
    CHECK ((kind = 'photo' AND blob_key IS NOT NULL) OR (kind = 'link' AND link_url IS NOT NULL)
        OR (kind = 'timer' AND tracked_seconds IS NOT NULL))
);

-- ---------------------------------------------------------------- Module B: calendar & routines
CREATE TABLE calendar_accounts (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    provider        cal_provider NOT NULL,
    connection_id   uuid REFERENCES oauth_connections(id) ON DELETE CASCADE,
    external_id     text NOT NULL,                             -- provider calendar id
    name            text NOT NULL,
    write_enabled   boolean NOT NULL DEFAULT false,            -- writes only after the user opts in per calendar
    sync_token      text,                                      -- Google syncToken / Graph deltaLink
    UNIQUE (user_id, provider, external_id)
);

CREATE TABLE calendar_events (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    account_id      uuid REFERENCES calendar_accounts(id) ON DELETE CASCADE,  -- NULL = Apex-owned block
    external_id     text,
    etag            text,
    title           text NOT NULL,
    during          tstzrange NOT NULL,
    all_day         boolean NOT NULL DEFAULT false,
    busy            boolean NOT NULL DEFAULT true,
    energy          energy_tag,
    task_id         uuid REFERENCES tasks(id) ON DELETE SET NULL,     -- a scheduled task block
    origin          text NOT NULL CHECK (origin IN ('external', 'scheduler', 'routine', 'user')),
    locked          boolean NOT NULL DEFAULT false,            -- the auto-rescheduler never moves a locked block
    version         bigint NOT NULL DEFAULT 1,
    deleted         boolean NOT NULL DEFAULT false,
    UNIQUE (account_id, external_id)
);
CREATE INDEX calendar_events_user_time ON calendar_events USING gist (user_id, during) WHERE NOT deleted;
-- Apex-owned blocks may never overlap each other.
ALTER TABLE calendar_events ADD CONSTRAINT apex_blocks_no_overlap
    EXCLUDE USING gist (user_id WITH =, during WITH &&) WHERE (origin = 'scheduler' AND NOT deleted);

CREATE TABLE routines (
    id              uuid PRIMARY KEY,
    user_id         uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    title           text NOT NULL,
    energy          energy_tag NOT NULL,
    rrule           text NOT NULL,                             -- RFC 5545, e.g. FREQ=DAILY;BYHOUR=7;BYMINUTE=0
    duration_min    integer NOT NULL CHECK (duration_min BETWEEN 5 AND 480),
    flexible_min    integer NOT NULL DEFAULT 0,                -- how far the rescheduler may slide it
    active          boolean NOT NULL DEFAULT true,
    version         bigint NOT NULL DEFAULT 1,
    deleted         boolean NOT NULL DEFAULT false
);

-- ---------------------------------------------------------------- Module C: reminders & mail facts
CREATE TABLE reminders (
    id              uuid PRIMARY KEY,
    user_id         uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    task_id         uuid REFERENCES tasks(id) ON DELETE CASCADE,
    kind            reminder_kind NOT NULL,
    fire_at         timestamptz,                               -- kind = time
    geo_lat         double precision,                          -- kind = location (stored only if the user syncs it)
    geo_lng         double precision,
    geo_radius_m    integer CHECK (geo_radius_m BETWEEN 100 AND 5000),
    geo_transition  text CHECK (geo_transition IN ('enter', 'exit')),
    device_trigger  text CHECK (device_trigger IN ('charging', 'bluetooth_car', 'headphones', 'wifi_home', 'unlock')),
    message         text NOT NULL,
    active          boolean NOT NULL DEFAULT true,
    version         bigint NOT NULL DEFAULT 1,
    deleted         boolean NOT NULL DEFAULT false,
    CHECK ((kind = 'time' AND fire_at IS NOT NULL)
        OR (kind = 'location' AND geo_lat IS NOT NULL AND geo_lng IS NOT NULL AND geo_radius_m IS NOT NULL)
        OR (kind = 'device_state' AND device_trigger IS NOT NULL))
);

-- Facts extracted from mail. Never the body: a Source_Ref hash points back to the message.
CREATE TABLE mail_facts (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    source_ref      bytea NOT NULL,                            -- HMAC(message-id), not reversible
    kind            text NOT NULL CHECK (kind IN ('invoice', 'meeting', 'flight', 'receipt', 'action_item')),
    title           text NOT NULL,
    amount_minor    bigint,                                    -- paise / cents; never float
    currency        char(3),
    due_at          timestamptz,
    payload         jsonb NOT NULL DEFAULT '{}',               -- e.g. flight number, PNR, gate (schema per kind)
    status          text NOT NULL DEFAULT 'proposed' CHECK (status IN ('proposed', 'accepted', 'dismissed')),
    created_at      timestamptz NOT NULL DEFAULT now(),
    UNIQUE (user_id, source_ref, kind)
);

-- ---------------------------------------------------------------- Module D: subscriptions
CREATE TABLE merchants (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name            text NOT NULL,
    manage_url      text CHECK (manage_url ~ '^https://'),     -- curated list only, never a URL from a message
    UNIQUE (user_id, name)
);

CREATE TABLE subscriptions (
    id              uuid PRIMARY KEY,
    user_id         uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    merchant_id     uuid NOT NULL REFERENCES merchants(id),
    amount_minor    bigint NOT NULL CHECK (amount_minor > 0),
    currency        char(3) NOT NULL DEFAULT 'INR',
    interval        sub_interval NOT NULL,
    next_due        date NOT NULL,
    status          sub_status NOT NULL DEFAULT 'proposed',
    detected_from   text NOT NULL CHECK (detected_from IN ('sms', 'notification', 'mail', 'manual')),
    last_used_answer boolean,
    cancelled_on    date,
    version         bigint NOT NULL DEFAULT 1,
    deleted         boolean NOT NULL DEFAULT false
);
CREATE INDEX subscriptions_renewals ON subscriptions (user_id, next_due) WHERE status = 'active' AND NOT deleted;

CREATE TABLE subscription_charges (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    subscription_id uuid NOT NULL REFERENCES subscriptions(id) ON DELETE CASCADE,
    charged_on      date NOT NULL,
    amount_minor    bigint NOT NULL,
    source_ref      bytea NOT NULL,
    UNIQUE (subscription_id, source_ref)
);

-- Monthly burn and renewal countdown, read by the Subscription Health hub.
CREATE VIEW subscription_ledger AS
SELECT s.user_id, s.id, m.name AS merchant, s.amount_minor, s.currency, s.interval, s.next_due,
       (s.next_due - current_date) AS days_to_renewal,
       CASE s.interval WHEN 'weekly' THEN s.amount_minor * 52 / 12
                       WHEN 'monthly' THEN s.amount_minor
                       WHEN 'quarterly' THEN s.amount_minor / 3
                       WHEN 'yearly' THEN s.amount_minor / 12 END AS monthly_burn_minor
FROM subscriptions s JOIN merchants m ON m.id = s.merchant_id
WHERE s.status = 'active' AND NOT s.deleted;

-- ---------------------------------------------------------------- Module E: proactive cards
CREATE TABLE proactive_cards (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    kind            text NOT NULL CHECK (kind IN ('overload', 'goal_at_risk', 'renewal', 'free_slot', 'routine_missed')),
    for_date        date NOT NULL,
    title           text NOT NULL,
    reason          text NOT NULL,                             -- "Why am I seeing this?"
    proposal        jsonb,                                     -- a change the user can accept; never applied silently
    status          text NOT NULL DEFAULT 'new' CHECK (status IN ('new', 'accepted', 'dismissed', 'expired')),
    created_at      timestamptz NOT NULL DEFAULT now(),
    UNIQUE (user_id, kind, for_date)
);

-- ---------------------------------------------------------------- sync & audit
CREATE TABLE sync_log (                                        -- append-only change feed per user
    seq             bigserial PRIMARY KEY,
    user_id         uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    entity          text NOT NULL,
    entity_id       uuid NOT NULL,
    version         bigint NOT NULL,
    op              text NOT NULL CHECK (op IN ('upsert', 'delete')),
    device_id       uuid REFERENCES devices(id) ON DELETE SET NULL,
    at              timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX sync_log_user_seq ON sync_log (user_id, seq);

CREATE TABLE audit_log (                                       -- what the service read or sent, shown in the app
    id              bigserial PRIMARY KEY,
    user_id         uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    action          text NOT NULL,                             -- e.g. 'gmail.read_headers', 'gcal.write_event', 'llm.call'
    target          text,
    detail          jsonb NOT NULL DEFAULT '{}',               -- counts and ids, never content
    at              timestamptz NOT NULL DEFAULT now()
);
