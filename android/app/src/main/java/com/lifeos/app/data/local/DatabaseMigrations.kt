package com.lifeos.app.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Schema-only migrations that preserve the app's existing offline caches. */
val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `priority_items_cache` (
                `id` INTEGER NOT NULL,
                `subject` TEXT NOT NULL,
                `sender` TEXT NOT NULL,
                `company` TEXT,
                `role` TEXT,
                `importance` TEXT NOT NULL,
                `labels` TEXT NOT NULL,
                `state` TEXT NOT NULL,
                `confidence` REAL NOT NULL,
                `summary` TEXT NOT NULL,
                `whyImportant` TEXT NOT NULL,
                `evidence` TEXT NOT NULL,
                `actionText` TEXT,
                `actionUrl` TEXT,
                `dueAt` TEXT,
                `eventStart` TEXT,
                `eventEnd` TEXT,
                `timezone` TEXT,
                `location` TEXT,
                `meetingUrl` TEXT,
                `calendarUid` TEXT,
                `hasCalendarInvite` INTEGER NOT NULL,
                `gmailUrl` TEXT NOT NULL,
                `preview` TEXT NOT NULL,
                `receivedAt` TEXT NOT NULL,
                `snoozedUntil` TEXT,
                `accountLabel` TEXT NOT NULL,
                `accountAddress` TEXT NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
    }
}
