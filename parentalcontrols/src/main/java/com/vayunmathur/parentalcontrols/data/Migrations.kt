package com.vayunmathur.parentalcontrols.data

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Version 1 held bedtime + per-app caps. Version 2 adds the Family Link set: downtime and
 * school-time windows, the device-wide daily limit, one-shot bonus grants, and the
 * downtime allow-list column on [AppRule].
 *
 * All new tables start empty (every new feature defaults to off) and the new column defaults
 * to blocked-everywhere, so existing installs keep exactly the behavior they had.
 */
val MIGRATION_1_2: Migration =
    object : Migration(1, 2) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS DowntimeSchedule (" +
                    "id INTEGER NOT NULL PRIMARY KEY, " +
                    "enabled INTEGER NOT NULL DEFAULT 0, " +
                    "startMinute INTEGER NOT NULL DEFAULT 720, " +
                    "endMinute INTEGER NOT NULL DEFAULT 840, " +
                    "daysMask INTEGER NOT NULL DEFAULT 127)",
            )
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS SchoolTimeSchedule (" +
                    "id INTEGER NOT NULL PRIMARY KEY, " +
                    "enabled INTEGER NOT NULL DEFAULT 0, " +
                    "startMinute INTEGER NOT NULL DEFAULT 480, " +
                    "endMinute INTEGER NOT NULL DEFAULT 900, " +
                    "daysMask INTEGER NOT NULL DEFAULT 31)",
            )
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS DailyLimit (" +
                    "id INTEGER NOT NULL PRIMARY KEY, " +
                    "dailyLimitMinutes INTEGER)",
            )
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS BonusGrant (" +
                    "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                    "packageName TEXT, " +
                    "bonusMinutes INTEGER NOT NULL DEFAULT 0, " +
                    "day TEXT NOT NULL DEFAULT '')",
            )
            connection.execSQL(
                "ALTER TABLE AppRule ADD COLUMN allowedInDowntime INTEGER NOT NULL DEFAULT 0",
            )
        }
    }
