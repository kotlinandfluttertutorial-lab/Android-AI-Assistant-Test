# Skill: Room Entity & DAO

## Purpose
Add a new Room entity, DAO, and optional FTS4 virtual table to `AppDatabase` in the
`:core-database` module, following the exact conventions already used by the project's
13 existing entities.

## When to Use
- Persisting a new domain concept locally (e.g. adding `ApiKeyEntity`, `FeedbackEntity`)
- Adding a new DAO query to an existing entity
- Adding FTS full-text search to an entity that doesn't have it yet
- Writing a database migration after changing an existing schema

---

## Module: `:core-database`

Package root: `com.aiassistant.core.database`

Key files:
- `AppDatabase.kt` — `@Database` class, version currently **2**, exports schema
- `converter/DatabaseConverters.kt` — `@TypeConverters` (handles `Instant`, `List<String>`, etc.)
- `entity/` — one `*Entity.kt` per table
- `dao/` — one `*Dao.kt` per entity (or logical grouping)

---

## Existing Entities (for reference)

| Entity class | Table name | Notes |
|---|---|---|
| `UserEntity` | `users` | |
| `ConversationEntity` | `conversations` | |
| `MessageEntity` | `messages` | |
| `DocumentEntity` | `documents` | RAG uploads |
| `MemoryEntity` | `memories` | AI memory vectors |
| `NoteEntity` | `notes` | |
| `TodoItemEntity` | `todo_items` | |
| `CalendarEventEntity` | `calendar_events` | |
| `ReminderEntity` | `reminders` | |
| `HabitDefinitionEntity` | `habit_definitions` | |
| `HabitEntryEntity` | `habit_entries` | |
| `ConversationFtsEntity` | `conversations_fts` | FTS4 virtual table |
| `MessageFtsEntity` | `messages_fts` | FTS4 virtual table |

---

## Step 1 — Write the Entity

```kotlin
// entity/<Name>Entity.kt
package com.aiassistant.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant

/**
 * Room entity representing the `<table_name>` table.
 *
 * Design rules:
 * - PrimaryKey is always a UUID String, never an auto-increment Int.
 * - Timestamps use [Instant]; [DatabaseConverters] handles the Long ↔ Instant mapping.
 * - Nullable fields must have explicit defaults (null or empty string).
 * - Add @Index for any column used in WHERE or JOIN predicates.
 */
@Entity(
    tableName = "<table_name>",
    indices = [
        Index(value = ["user_id"]),             // example — adjust to your access pattern
    ],
    // Uncomment and fill in if this entity references another:
    // foreignKeys = [
    //     ForeignKey(
    //         entity = UserEntity::class,
    //         parentColumns = ["id"],
    //         childColumns = ["user_id"],
    //         onDelete = ForeignKey.CASCADE,
    //     )
    // ]
)
data class <Name>Entity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "user_id")
    val userId: String,

    @ColumnInfo(name = "title")
    val title: String,

    @ColumnInfo(name = "content")
    val content: String = "",

    @ColumnInfo(name = "created_at")
    val createdAt: Instant = Instant.now(),

    @ColumnInfo(name = "updated_at")
    val updatedAt: Instant = Instant.now(),
)
```

**Never store `List<*>` or complex objects** directly. Either:
- Flatten into scalar columns, or
- Use `@TypeConverter` (add to `DatabaseConverters.kt`), or
- Create a join table.

---

## Step 2 — Write the DAO

```kotlin
// dao/<Name>Dao.kt
package com.aiassistant.core.database.dao

import androidx.room.*
import com.aiassistant.core.database.entity.<Name>Entity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for [<Name>Entity].
 *
 * Query conventions:
 * - READ queries that the UI observes return Flow<*>.
 * - READ queries used once (e.g. in a WorkManager task) return suspend fun.
 * - ALL write operations (insert/update/delete) are suspend fun.
 * - Never call DAO methods from the main thread — Room enforces this at runtime
 *   only in debug builds; enforce it yourself by running on Dispatchers.IO.
 */
@Dao
interface <Name>Dao {

    // ── Observe ───────────────────────────────────────────────────────────────

    @Query("SELECT * FROM <table_name> WHERE user_id = :userId ORDER BY created_at DESC")
    fun observeByUser(userId: String): Flow<List<<Name>Entity>>

    @Query("SELECT * FROM <table_name> WHERE id = :id LIMIT 1")
    fun observeById(id: String): Flow<<Name>Entity?>

    // ── One-shot reads ────────────────────────────────────────────────────────

    @Query("SELECT * FROM <table_name> WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): <Name>Entity?

    // ── Writes ────────────────────────────────────────────────────────────────

    /**
     * Insert or replace the full entity.
     * Use for initial creation and full sync overwrites.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: <Name>Entity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<<Name>Entity>)

    /**
     * Partial update — only the mutable fields, never the primary key or created_at.
     */
    @Query("""
        UPDATE <table_name>
        SET title = :title, content = :content, updated_at = :updatedAt
        WHERE id = :id
    """)
    suspend fun updateContent(id: String, title: String, content: String, updatedAt: Long)

    @Query("DELETE FROM <table_name> WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM <table_name> WHERE user_id = :userId")
    suspend fun deleteAllForUser(userId: String)
}
```

---

## Step 3 — Add FTS (optional)

When full-text search is needed, add an FTS4 virtual table that mirrors the main table:

```kotlin
// entity/<Name>FtsEntity.kt
package com.aiassistant.core.database.entity

import androidx.room.Entity
import androidx.room.Fts4

/**
 * FTS4 virtual table for full-text search over [<Name>Entity].
 *
 * The [contentEntity] points at the real table; Room keeps the FTS index
 * in sync automatically on insert/update/delete via triggers.
 */
@Fts4(contentEntity = <Name>Entity::class)
@Entity(tableName = "<table_name>_fts")
data class <Name>FtsEntity(
    val title: String,
    val content: String,
)
```

Add a search query to `<Name>Dao`:

```kotlin
@Query("""
    SELECT main.* FROM <table_name> AS main
    INNER JOIN <table_name>_fts ON main.rowid = <table_name>_fts.rowid
    WHERE <table_name>_fts MATCH :query
    ORDER BY main.created_at DESC
""")
fun searchByText(query: String): Flow<List<<Name>Entity>>
```

---

## Step 4 — Register in `AppDatabase`

Open `core-database/src/main/kotlin/com/aiassistant/core/database/AppDatabase.kt` and:

1. Add the entity class(es) to the `entities` array:
```kotlin
@Database(
    entities = [
        // ... existing entities ...
        <Name>Entity::class,
        <Name>FtsEntity::class,   // only if FTS was added
    ],
    version = 3,                  // INCREMENT the version
    exportSchema = true
)
```

2. Add the abstract DAO accessor:
```kotlin
abstract fun <name>Dao(): <Name>Dao
```

---

## Step 5 — Write a Migration

**Never** change `version` without providing a migration. Auto-migration can handle
simple column additions; for everything else write a manual `Migration`.

```kotlin
// di/DatabaseModule.kt  (or a dedicated Migrations.kt)

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `<table_name>` (
                `id` TEXT NOT NULL,
                `user_id` TEXT NOT NULL,
                `title` TEXT NOT NULL,
                `content` TEXT NOT NULL DEFAULT '',
                `created_at` INTEGER NOT NULL,
                `updated_at` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
        """)
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_<table_name>_user_id` ON `<table_name>` (`user_id`)")
    }
}
```

Register it in the `Room.databaseBuilder(...)` call in `DatabaseModule.kt`:
```kotlin
Room.databaseBuilder(context, AppDatabase::class.java, "ai_assistant.db")
    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
    .build()
```

---

## Step 6 — Domain Mapper

Entities live in `:core-database`. Domain models live in `:domain`. The `:data` layer
owns the mapping. Add a mapper in `data/src/main/kotlin/com/aiassistant/data/mapper/`:

```kotlin
// mapper/<Name>Mapper.kt
fun <Name>Entity.toDomain(): <Name> = <Name>(
    id = id,
    userId = userId,
    title = title,
    content = content,
    createdAt = createdAt,
)

fun <Name>.toEntity(): <Name>Entity = <Name>Entity(
    id = id,
    userId = userId,
    title = title,
    content = content,
    createdAt = createdAt,
    updatedAt = Instant.now(),
)
```

---

## TypeConverter Reference

`DatabaseConverters.kt` already handles:
- `Instant` ↔ `Long` (stored as epoch millis)
- `List<String>` ↔ `String` (comma-separated or JSON — check the existing impl)

If you need a new type, add a `@TypeConverter` pair there. Do **not** create a
separate converter class.

---

## Checklist

- [ ] Entity uses `String` UUID primary key (never `autoGenerate = true`)
- [ ] Timestamps are `Instant` (converted via `DatabaseConverters`)
- [ ] Indices added for all WHERE/JOIN columns
- [ ] `OnConflictStrategy.REPLACE` used for upsert
- [ ] Observe queries return `Flow`, one-shot queries are `suspend fun`
- [ ] `AppDatabase` version incremented
- [ ] Migration written and registered — no destructive migrations in release builds
- [ ] Schema exported (`exportSchema = true`) — commit the JSON file under `schemas/`
- [ ] Domain mapper added in `:data`
- [ ] No direct DAO calls from ViewModel — always goes through Repository → UseCase
