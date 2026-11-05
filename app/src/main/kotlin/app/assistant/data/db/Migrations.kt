// The CREATE statements are copied verbatim from the Room-exported schema (2.json) and must match
// byte for byte, so they are single long lines on purpose; wrapping them would risk the migration.
@file:Suppress("MaxLineLength")

package app.assistant.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Version 1 to 2 (Phase 1 Core): goals, tasks, plans, reports, the alarm queue and the notification log.
 * The SQL is copied from the exported schema 2.json, so it matches what Room validates.
 */
val MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `goal` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `period` TEXT NOT NULL, `start_date` TEXT NOT NULL, `target_hundredths` INTEGER NOT NULL, `unit` TEXT NOT NULL, `parent_goal_id` INTEGER, `created_at` INTEGER NOT NULL, `archived_at` INTEGER)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_goal_parent_goal_id` ON `goal` (`parent_goal_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_goal_archived_at` ON `goal` (`archived_at`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `goal_progress` (`goal_id` INTEGER NOT NULL, `period_first_day` TEXT NOT NULL, `progress_hundredths` INTEGER NOT NULL, PRIMARY KEY(`goal_id`, `period_first_day`), FOREIGN KEY(`goal_id`) REFERENCES `goal`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `task` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, `status` TEXT NOT NULL, `due_date` TEXT, `planned_date` TEXT, `estimate_min` INTEGER, `goal_id` INTEGER, `created_at` INTEGER NOT NULL, `completed_at` INTEGER, `completed_date` TEXT, `source_ref` TEXT)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_task_planned_date_status` ON `task` (`planned_date`, `status`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_task_goal_id` ON `task` (`goal_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_task_due_date` ON `task` (`due_date`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_task_completed_date` ON `task` (`completed_date`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `task_move` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `task_id` INTEGER NOT NULL, `from_date` TEXT, `to_date` TEXT NOT NULL, `moved_on` TEXT NOT NULL)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_task_move_task_id` ON `task_move` (`task_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_task_move_moved_on` ON `task_move` (`moved_on`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `daily_plan` (`date` TEXT NOT NULL, `created_at` INTEGER NOT NULL, PRIMARY KEY(`date`))")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `plan_priority` (`date` TEXT NOT NULL, `rank` INTEGER NOT NULL, `task_id` INTEGER NOT NULL, `user_set` INTEGER NOT NULL, PRIMARY KEY(`date`, `rank`))",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_plan_priority_task_id` ON `plan_priority` (`task_id`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `time_block` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `date` TEXT NOT NULL, `start_at` INTEGER NOT NULL, `end_at` INTEGER NOT NULL, `task_id` INTEGER, `kind` TEXT NOT NULL, `conflicting` INTEGER NOT NULL)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_time_block_date` ON `time_block` (`date`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_time_block_task_id` ON `time_block` (`task_id`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `report` (`kind` TEXT NOT NULL, `period_first_day` TEXT NOT NULL, `created_at` INTEGER NOT NULL, PRIMARY KEY(`kind`, `period_first_day`))",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `alarm_queue` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `type` TEXT NOT NULL, `ref_key` TEXT NOT NULL, `rule` TEXT NOT NULL, `local_date` TEXT, `local_minute` INTEGER, `due_at` INTEGER NOT NULL, `is_snooze` INTEGER NOT NULL)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_alarm_queue_due_at` ON `alarm_queue` (`due_at`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_alarm_queue_type_ref_key` ON `alarm_queue` (`type`, `ref_key`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `notification_log` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `day` TEXT NOT NULL, `type` TEXT NOT NULL, `ref_key` TEXT NOT NULL, `decision` TEXT NOT NULL, `rule_id` TEXT NOT NULL, `at` INTEGER NOT NULL, `counted` INTEGER NOT NULL, `type_counted` INTEGER NOT NULL, `summary` TEXT NOT NULL)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_notification_log_day_type` ON `notification_log` (`day`, `type`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_notification_log_decision` ON `notification_log` (`decision`)")
    }
}

/**
 * Version 2 to 3 (Phase 2 Calendar): the selected-calendar list and the event cache.
 * The SQL is copied from the exported schema 3.json, so it matches what Room validates.
 */
val MIGRATION_2_3: Migration = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `calendar_source` (`calendar_id` INTEGER NOT NULL, `display_name` TEXT NOT NULL, `account_name` TEXT NOT NULL, `selected` INTEGER NOT NULL, PRIMARY KEY(`calendar_id`))",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `calendar_event` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `calendar_id` INTEGER NOT NULL, `event_id` INTEGER NOT NULL, `title` TEXT NOT NULL, `start_at` INTEGER, `end_at` INTEGER, `all_day_start` TEXT, `all_day_end` TEXT)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_calendar_event_start_at` ON `calendar_event` (`start_at`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_calendar_event_all_day_start` ON `calendar_event` (`all_day_start`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_calendar_event_calendar_id` ON `calendar_event` (`calendar_id`)")
    }
}

/**
 * Version 3 to 4 (Phase 3 Money: persistence and the ingest pipeline). The SQL is copied from the
 * exported schema 4.json, so it matches what Room validates.
 */
val MIGRATION_3_4: Migration = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `parsed_fact` (`source_ref` TEXT NOT NULL, `origin` TEXT NOT NULL, `source_kind` TEXT NOT NULL, `type` TEXT NOT NULL, `amount_paise` INTEGER NOT NULL, `at` INTEGER NOT NULL, `received_at` INTEGER NOT NULL, `reference` TEXT, `account4` TEXT, `merchant_or_vpa` TEXT, `balance_paise` INTEGER, `is_reversal` INTEGER NOT NULL, `format_id` TEXT NOT NULL, `transaction_id` INTEGER, PRIMARY KEY(`source_ref`))",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_parsed_fact_type_amount_paise_at` ON `parsed_fact` (`type`, `amount_paise`, `at`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_parsed_fact_transaction_id` ON `parsed_fact` (`transaction_id`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `txn` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `type` TEXT NOT NULL, `amount_paise` INTEGER NOT NULL, `at` INTEGER NOT NULL, `merchant_id` INTEGER, `category_id` INTEGER NOT NULL, `source` TEXT NOT NULL, `reference` TEXT, `account4` TEXT, `balance_paise` INTEGER, `is_reversal` INTEGER NOT NULL, `edited_fields` INTEGER NOT NULL, `category_user_set` INTEGER NOT NULL, `edited_at` INTEGER)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_txn_at` ON `txn` (`at`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_txn_category_id` ON `txn` (`category_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_txn_merchant_id` ON `txn` (`merchant_id`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `forced_merge` (`source_ref` TEXT NOT NULL, `group_id` INTEGER NOT NULL, PRIMARY KEY(`source_ref`))")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `tombstone` (`source_ref` TEXT NOT NULL, `reason` TEXT NOT NULL, `at` INTEGER NOT NULL, PRIMARY KEY(`source_ref`))",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `processed_source_ref` (`source_ref` TEXT NOT NULL, `outcome` TEXT NOT NULL, `at` INTEGER NOT NULL, PRIMARY KEY(`source_ref`))",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `review_item` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `source_ref` TEXT NOT NULL, `redacted_text` TEXT NOT NULL, `origin` TEXT NOT NULL, `received_at` INTEGER NOT NULL, `fallback_attempted` INTEGER NOT NULL, `proposal_json` TEXT)",
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_review_item_source_ref` ON `review_item` (`source_ref`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_review_item_received_at` ON `review_item` (`received_at`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `merchant` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `name_norm` TEXT NOT NULL, `default_category_id` INTEGER, `builtin` INTEGER NOT NULL)",
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_merchant_name_norm` ON `merchant` (`name_norm`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `merchant_alias` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `merchant_id` INTEGER NOT NULL, `alias_norm` TEXT NOT NULL)",
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_merchant_alias_alias_norm` ON `merchant_alias` (`alias_norm`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `category` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `builtin` INTEGER NOT NULL)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `category_correction` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `merchant_id` INTEGER NOT NULL, `category_id` INTEGER NOT NULL, `at` INTEGER NOT NULL)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_category_correction_merchant_id_at` ON `category_correction` (`merchant_id`, `at`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `budget` (`category_id` INTEGER NOT NULL, `amount_paise` INTEGER NOT NULL, PRIMARY KEY(`category_id`))")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `bill` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `amount_paise` INTEGER NOT NULL, `due_date` TEXT NOT NULL, `recurrence` TEXT NOT NULL, `anchor_day` INTEGER NOT NULL, `merchant_id` INTEGER, `paid` INTEGER NOT NULL, `series_id` INTEGER NOT NULL, `notified` INTEGER NOT NULL)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_bill_due_date_paid` ON `bill` (`due_date`, `paid`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `bill_proposal` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `merchant_id` INTEGER, `amount_paise` INTEGER NOT NULL, `due_date` TEXT, `status` TEXT NOT NULL)",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_bill_proposal_merchant_id_amount_paise_due_date` ON `bill_proposal` (`merchant_id`, `amount_paise`, `due_date`)",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `bill_paid_proposal` (`transaction_id` INTEGER NOT NULL, `bill_id` INTEGER NOT NULL, `status` TEXT NOT NULL, PRIMARY KEY(`transaction_id`, `bill_id`))",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `sender_entry` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `core` TEXT NOT NULL, `prefix_pattern` TEXT, `suffix_pattern` TEXT, `builtin` INTEGER NOT NULL, `enabled` INTEGER NOT NULL)",
        )
        db.execSQL("CREATE TABLE IF NOT EXISTS `money_app` (`package_name` TEXT NOT NULL, PRIMARY KEY(`package_name`))")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `quality_counter` (`origin` TEXT NOT NULL, `day` TEXT NOT NULL, `metric` TEXT NOT NULL, `count` INTEGER NOT NULL, PRIMARY KEY(`origin`, `day`, `metric`))",
        )
    }
}

/**
 * Version 4 to 5 (Phase 4 Subscriptions): subscriptions, their charges, usage checks and suggestions. The SQL is copied
 * from the exported schema 5.json so it matches what Room validates; verify against 5.json after the next build.
 */
val MIGRATION_4_5: Migration = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `subscription` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `merchant_id` INTEGER NOT NULL, `amount_paise` INTEGER NOT NULL, `interval` TEXT NOT NULL, `anchor_day` INTEGER NOT NULL, `next_due` TEXT NOT NULL, `activated_on` TEXT NOT NULL, `last_usage_check` TEXT, `cancelled_on` TEXT, `consecutive_no` INTEGER NOT NULL, `status` TEXT NOT NULL)",
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_subscription_merchant_id_interval` ON `subscription` (`merchant_id`, `interval`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_subscription_status` ON `subscription` (`status`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_subscription_next_due` ON `subscription` (`next_due`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `subscription_charge` (`transaction_id` INTEGER NOT NULL, `subscription_id` INTEGER NOT NULL, `charged_on` TEXT NOT NULL, `amount_paise` INTEGER NOT NULL, PRIMARY KEY(`transaction_id`))",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_subscription_charge_subscription_id` ON `subscription_charge` (`subscription_id`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `usage_check` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `subscription_id` INTEGER NOT NULL, `due_on` TEXT NOT NULL, `answered_at` INTEGER, `used_it` INTEGER)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_usage_check_subscription_id` ON `usage_check` (`subscription_id`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `suggestion` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `kind` TEXT NOT NULL, `prompt_type` TEXT, `text` TEXT NOT NULL, `reason` TEXT NOT NULL, `shown_at` INTEGER NOT NULL, `responded_at` INTEGER, `response` TEXT, `change_json` TEXT)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_suggestion_response` ON `suggestion` (`response`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_suggestion_shown_at` ON `suggestion` (`shown_at`)")
    }
}

/**
 * Version 5 to 6 (Phase 5 AI advice): the Cap_Governor's monthly usage table. No existing table
 * changes. The SQL is copied from the exported schema 6.json, so it matches what Room validates.
 */
val MIGRATION_5_6: Migration = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `ai_usage` (`month` TEXT NOT NULL, `spentPaise` INTEGER NOT NULL, `attempts` INTEGER NOT NULL, PRIMARY KEY(`month`))",
        )
    }
}

/**
 * Version 6 to 7 (Phase 6 Email): the mailbox tables. No existing table changes. The SQL is copied
 * from the exported schema 7.json, so it matches what Room validates.
 */
val MIGRATION_6_7: Migration = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `inbox_item` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `source_ref` TEXT NOT NULL, `sender` TEXT NOT NULL, `subject` TEXT NOT NULL, `category` TEXT NOT NULL, `rule_score` INTEGER NOT NULL, `final_score` INTEGER NOT NULL, `received_at` INTEGER NOT NULL, `notified` INTEGER NOT NULL, `wrong_alert` INTEGER NOT NULL)",
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_inbox_item_source_ref` ON `inbox_item` (`source_ref`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_inbox_item_received_at` ON `inbox_item` (`received_at`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `shortlist_rule` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sender_contains` TEXT NOT NULL, `subject_contains` TEXT NOT NULL, `score` INTEGER NOT NULL)",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `sender_penalty` (`sender` TEXT NOT NULL, `penalty` INTEGER NOT NULL, PRIMARY KEY(`sender`))",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `mail_cursor` (`folder` TEXT NOT NULL, `uid_validity` INTEGER NOT NULL, `last_uid` INTEGER NOT NULL, PRIMARY KEY(`folder`))",
        )
    }
}

/**
 * Version 7 to 8 (Phase 7 Career import): the Huntfolio import-record table for idempotent imports.
 * No existing table changes. SQL copied from the exported schema 8.json.
 */
val MIGRATION_7_8: Migration = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `import_record` (`key` TEXT NOT NULL, `imported_at` INTEGER NOT NULL, PRIMARY KEY(`key`))",
        )
    }
}

/**
 * Version 8 to 9 (Phase 7 polish): the Health_Check job-run table. No existing table changes. SQL
 * copied from the exported schema 9.json.
 */
val MIGRATION_8_9: Migration = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `job_run` (`job` TEXT NOT NULL, `last_run_at` INTEGER NOT NULL, PRIMARY KEY(`job`))",
        )
    }
}

/** Apex planner (v10): task and goal planning columns, and the milestone, routine, reminder, card and proof tables. */
val MIGRATION_9_10: Migration = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `task` ADD COLUMN `priority` INTEGER NOT NULL DEFAULT 3")
        db.execSQL("ALTER TABLE `task` ADD COLUMN `energy` TEXT NOT NULL DEFAULT 'LIGHT_ADMIN'")
        db.execSQL("ALTER TABLE `task` ADD COLUMN `milestone_id` INTEGER")
        db.execSQL("ALTER TABLE `task` ADD COLUMN `verify` TEXT NOT NULL DEFAULT 'NONE'")
        db.execSQL("ALTER TABLE `goal` ADD COLUMN `deadline` TEXT")
        db.execSQL("ALTER TABLE `goal` ADD COLUMN `priority` INTEGER NOT NULL DEFAULT 3")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `milestone` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `goal_id` INTEGER NOT NULL, `title` TEXT NOT NULL, `position` INTEGER NOT NULL, `due_date` TEXT, `estimate_min` INTEGER, `completed_at` INTEGER, FOREIGN KEY(`goal_id`) REFERENCES `goal`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_milestone_goal_id` ON `milestone` (`goal_id`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `routine` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, `energy` TEXT NOT NULL, `days_mask` INTEGER NOT NULL, `start_minute` INTEGER NOT NULL, `duration_min` INTEGER NOT NULL, `flexible_min` INTEGER NOT NULL, `active` INTEGER NOT NULL)",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `smart_reminder` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `message` TEXT NOT NULL, `kind` TEXT NOT NULL, `fire_at` INTEGER, `place_name` TEXT, `latitude` REAL, `longitude` REAL, `radius_m` INTEGER, `on_enter` INTEGER NOT NULL, `device` TEXT, `not_before_minute` INTEGER, `active` INTEGER NOT NULL, `task_id` INTEGER, `last_fired_at` INTEGER)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_smart_reminder_kind_active` ON `smart_reminder` (`kind`, `active`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `proactive_card` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `kind` TEXT NOT NULL, `for_date` TEXT NOT NULL, `title` TEXT NOT NULL, `reason` TEXT NOT NULL, `status` TEXT NOT NULL, `created_at` INTEGER NOT NULL)",
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_proactive_card_kind_for_date` ON `proactive_card` (`kind`, `for_date`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_proactive_card_for_date` ON `proactive_card` (`for_date`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `task_verification` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `task_id` INTEGER NOT NULL, `kind` TEXT NOT NULL, `link_url` TEXT, `tracked_seconds` INTEGER, `photo_file` TEXT, `created_at` INTEGER NOT NULL, FOREIGN KEY(`task_id`) REFERENCES `task`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_task_verification_task_id` ON `task_verification` (`task_id`)")
    }
}

/** v10 -> v11: the memory_note table for "Ask Apex: remember ...". */
val MIGRATION_10_11: Migration = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `memory_note` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `text` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `pinned` INTEGER NOT NULL)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_note_created_at` ON `memory_note` (`created_at`)")
    }
}

/** v11 -> v12: habits, important dates, savings goals, follow-ups, mood log and focus sessions. */
val MIGRATION_11_12: Migration = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        V12_SQL.forEach(db::execSQL)
    }
}

private val V12_SQL = listOf(
    "CREATE TABLE IF NOT EXISTS `habit` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `kind` TEXT NOT NULL, `times` TEXT NOT NULL, `days` TEXT NOT NULL, `active` INTEGER NOT NULL, `created_on` TEXT NOT NULL)",
    "CREATE TABLE IF NOT EXISTS `habit_check` (`habit_id` INTEGER NOT NULL, `date` TEXT NOT NULL, `slot` TEXT NOT NULL, PRIMARY KEY(`habit_id`, `date`, `slot`), FOREIGN KEY(`habit_id`) REFERENCES `habit`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
    "CREATE INDEX IF NOT EXISTS `index_habit_check_date` ON `habit_check` (`date`)",
    "CREATE TABLE IF NOT EXISTS `important_date` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, `kind` TEXT NOT NULL, `date` TEXT NOT NULL, `yearly` INTEGER NOT NULL, `from_contacts` INTEGER NOT NULL)",
    "CREATE TABLE IF NOT EXISTS `savings_goal` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `target_paise` INTEGER NOT NULL, `deadline` TEXT, `created_on` TEXT NOT NULL)",
    "CREATE TABLE IF NOT EXISTS `savings_entry` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `goal_id` INTEGER NOT NULL, `amount_paise` INTEGER NOT NULL, `date` TEXT NOT NULL, FOREIGN KEY(`goal_id`) REFERENCES `savings_goal`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
    "CREATE INDEX IF NOT EXISTS `index_savings_entry_goal_id` ON `savings_entry` (`goal_id`)",
    "CREATE TABLE IF NOT EXISTS `follow_up` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `person` TEXT NOT NULL, `about` TEXT NOT NULL, `nudge_on` TEXT NOT NULL, `done` INTEGER NOT NULL, `created_on` TEXT NOT NULL)",
    "CREATE TABLE IF NOT EXISTS `mood_log` (`date` TEXT NOT NULL, `mood` INTEGER NOT NULL, `energy` INTEGER NOT NULL, PRIMARY KEY(`date`))",
    "CREATE TABLE IF NOT EXISTS `focus_session` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, `task_id` INTEGER, `started_at` INTEGER NOT NULL, `planned_min` INTEGER NOT NULL, `ended_at` INTEGER, `completed` INTEGER NOT NULL)",
    "CREATE INDEX IF NOT EXISTS `index_focus_session_started_at` ON `focus_session` (`started_at`)",
)
