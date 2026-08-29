/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-database
 * File       : QueryRoutingLogEntity.kt
 * Purpose    : Room entity that records every routing decision made by
 *              QueryRouter.  Provides an audit trail for debugging why a
 *              query was sent on-device vs to the cloud and whether any
 *              automatic fallback occurred.
 *
 * Architecture Layer : Core-Database — persistence layer.
 *                      Written by QueryRoutingLogRepository (data module) via
 *                      QueryRoutingLogDao.  Read by BenchmarkScreen and
 *                      ManageModelsScreen through the same repository; never
 *                      accessed directly from feature modules.
 *
 * Dependencies       : Room
 *
 * Design Decision    : selectedPath is stored as a String ("ON_DEVICE" |
 *                      "CLOUD") rather than an enum so the schema is stable
 *                      across future InferencePath additions.
 *                      capabilityBitmask persists the raw 4-bit integer so
 *                      Property 40 tests can reconstruct the exact signal
 *                      state that drove the decision.
 *                      Rows older than 30 days are pruned by
 *                      QueryRoutingLogDao.deleteOlderThan() — called from a
 *                      periodic WorkManager job in the data module.
 * ============================================================
 */
package com.aiassistant.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One routing decision log entry.
 *
 * @param id                UUID, primary key.
 * @param userId            Owner of the query session.
 * @param timestamp         Epoch millis when the routing decision was made.
 * @param selectedPath      Which inference path was chosen: "ON_DEVICE" | "CLOUD".
 * @param capabilityBitmask 4-bit integer encoding the four routing signals at
 *                          decision time:
 *                          bit 0 = Gemma model files present + checksum valid,
 *                          bit 1 = EmbeddingModel.isReady,
 *                          bit 2 = LocalVectorIndex has ≥1 chunk for userId,
 *                          bit 3 = network reachable to Backend.
 * @param userOverride      The explicit PathPreference the user set, or null
 *                          if no override was active ("PREFER_ON_DEVICE" |
 *                          "PREFER_CLOUD" | null).
 * @param fallbackOccurred  True when the router initially selected one path but
 *                          had to fall back to the other at runtime (e.g. RAM
 *                          exceeded during on-device inference).
 * @param reason            Human-readable explanation of the routing decision,
 *                          shown in BenchmarkScreen for debugging.
 */
@Entity(
    tableName = "query_routing_log",
    indices = [
        Index(value = ["userId"]),
        Index(value = ["timestamp"])
    ]
)
data class QueryRoutingLogEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val timestamp: Long,
    val selectedPath: String,        // "ON_DEVICE" | "CLOUD"
    val capabilityBitmask: Int,
    val userOverride: String? = null, // "PREFER_ON_DEVICE" | "PREFER_CLOUD" | null
    val fallbackOccurred: Boolean = false,
    val reason: String
)
