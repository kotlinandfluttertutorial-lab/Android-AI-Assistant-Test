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

    /**
     * v2 → v3: Create the three On-Device RAG tables.
     *
     * Three new tables are required by the on-device RAG pipeline (Task 44):
     *
     * on_device_documents  — tracks each user document submitted for local
     *                        ingestion and its lifecycle status.
     *
     * on_device_chunks     — stores text chunks with their pre-computed
     *                        float32 embedding vectors as BLOB columns.
     *                        Foreign key on document_id cascades deletes so
     *                        removing a document also removes all its chunks.
     *
     * query_routing_log    — records every QueryRouter decision (ON_DEVICE vs
     *                        CLOUD) with the 4-bit capability bitmask, user
     *                        override preference, and whether a fallback occurred.
     *                        Rows are pruned after 30 days by the data module.
     *
     * No existing data is touched — all three tables are brand new.
     */
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(database: SupportSQLiteDatabase) {
            createOnDeviceDocumentsTable(database)
            createOnDeviceChunksTable(database)
            createQueryRoutingLogTable(database)
        }
    }

    private fun createOnDeviceDocumentsTable(database: SupportSQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS on_device_documents (
                id               TEXT    NOT NULL PRIMARY KEY,
                userId           TEXT    NOT NULL,
                fileName         TEXT    NOT NULL,
                mimeType         TEXT    NOT NULL,
                sizeBytes        INTEGER NOT NULL,
                totalChunks      INTEGER NOT NULL DEFAULT 0,
                ingestionStatus  TEXT    NOT NULL,
                failureStage     TEXT,
                createdAt        INTEGER NOT NULL
            )
            """.trimIndent()
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS index_on_device_documents_userId ON on_device_documents(userId)"
        )
    }

    private fun createOnDeviceChunksTable(database: SupportSQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS on_device_chunks (
                id               TEXT    NOT NULL PRIMARY KEY,
                userId           TEXT    NOT NULL,
                documentId       TEXT    NOT NULL,
                documentName     TEXT    NOT NULL,
                chunkIndex       INTEGER NOT NULL,
                pageNumber       INTEGER,
                startCharOffset  INTEGER NOT NULL,
                endCharOffset    INTEGER NOT NULL,
                content          TEXT    NOT NULL,
                embeddingBlob    BLOB    NOT NULL,
                createdAt        INTEGER NOT NULL,
                FOREIGN KEY (documentId) REFERENCES on_device_documents(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS index_on_device_chunks_userId ON on_device_chunks(userId)"
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS index_on_device_chunks_documentId ON on_device_chunks(documentId)"
        )
    }

    private fun createQueryRoutingLogTable(database: SupportSQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS query_routing_log (
                id                 TEXT    NOT NULL PRIMARY KEY,
                userId             TEXT    NOT NULL,
                timestamp          INTEGER NOT NULL,
                selectedPath       TEXT    NOT NULL,
                capabilityBitmask  INTEGER NOT NULL,
                userOverride       TEXT,
                fallbackOccurred   INTEGER NOT NULL DEFAULT 0,
                reason             TEXT    NOT NULL
            )
            """.trimIndent()
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS index_query_routing_log_userId ON query_routing_log(userId)"
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS index_query_routing_log_timestamp ON query_routing_log(timestamp)"
        )
    }
}
