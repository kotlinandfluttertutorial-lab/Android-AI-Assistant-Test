package com.aiassistant.core.database.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * All Room schema migrations for AppDatabase.
 *
 * Add new migrations here as `MIGRATION_X_Y` objects and register them in
 * [com.aiassistant.core.database.di.DatabaseModule.provideAppDatabase].
 */
object DatabaseMigrations {

    /**
     * v1 → v2: Add `errorMessage` column to the `documents` table.
     *
     * Stores the human-readable failure reason returned by the backend job API
     * (`GET /jobs/{job_id}`) so the UI can display it without an extra network call.
     * NULL for documents that have not failed.
     */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                "ALTER TABLE documents ADD COLUMN errorMessage TEXT DEFAULT NULL"
            )
        }
    }
}
