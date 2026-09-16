package com.vayunmathur.screentime.data

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Version 1 held the focus profile (paused set plus its own schedule fields). Version 2
 * replaces it with the manual paused set: `id` + `pausedPackages` move to the `PausedApps`
 * table, the schedule columns are dropped with the old table. Existing installs keep exactly
 * the apps they had paused.
 *
 * Table-recreate rather than `DROP COLUMN`: minSdk 31's SQLite is too old for it.
 */
val MIGRATION_1_2: Migration =
    object : Migration(1, 2) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS PausedApps (" +
                    "id INTEGER NOT NULL PRIMARY KEY, " +
                    "pausedPackages TEXT NOT NULL)",
            )
            connection.execSQL(
                "INSERT INTO PausedApps (id, pausedPackages) " +
                    "SELECT id, pausedPackages FROM FocusProfile",
            )
            connection.execSQL("DROP TABLE FocusProfile")
        }
    }
