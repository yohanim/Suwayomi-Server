package suwayomi.tachidesk.server.database

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import de.neonew.exposed.migrations.MigrationsTable
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.exists
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.vendors.currentDialectMetadata
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.ChapterUserTable

private val logger = KotlinLogging.logger {}

// Custom build only. Older custom builds shipped the chapter indexes as M0067_AddChapterIndexes
// plus M0068_AddChapterFetchedAtIndex; upstream merged both into a single M0067. A database that
// ran the custom M0068 sits at version 68 and would silently skip upstream's next M0068.
// Dropping the rows of 67 and 68 lets the runner re-run upstream's M0067 (every statement is
// CREATE INDEX IF NOT EXISTS), which creates whatever index is missing. The row named
// AddChapterFetchedAtIndex only exists once, so this runs a single time.
private const val CUSTOM_M0068_NAME = "AddChapterFetchedAtIndex"

private val M0067_INDEXES =
    listOf(
        "chapter_manga_source_order",
        "chapter_manga_fetched_at",
        "chapter_manga_date_upload",
        "chapter_fetched_at",
        "chapteruser_user_read_chapter",
        "chapteruser_user_last_read_at_chapter",
        "chapteruser_user_downloaded_chapter",
        "chapteruser_user_bookmarked_chapter",
    )

/** @return whether the custom migration rows were dropped */
fun dropCustomBuildMigrationRows(): Boolean =
    transaction {
        if (!MigrationsTable.exists()) return@transaction false

        val hasCustomM0068 =
            MigrationsTable
                .selectAll()
                .where { (MigrationsTable.id eq 68) and (MigrationsTable.name eq CUSTOM_M0068_NAME) }
                .any()
        if (!hasCustomM0068) return@transaction false

        logger.info { "Custom build: missing chapter indexes before re-running M0067: ${missingM0067Indexes()}" }
        MigrationsTable.deleteWhere { MigrationsTable.id inList listOf(67, 68) }
        logger.info { "Custom build: dropped migration rows 67 and 68, M0067_AddChapterIndexes will re-run" }
        true
    }

fun logMissingM0067Indexes() {
    val missing = transaction { missingM0067Indexes() }
    if (missing.isEmpty()) {
        logger.info { "Custom build: all ${M0067_INDEXES.size} M0067 chapter indexes are present" }
    } else {
        logger.error { "Custom build: M0067 chapter indexes still missing: $missing" }
    }
}

internal fun missingM0067Indexes(): List<String> {
    currentDialectMetadata.resetCaches()
    val existing =
        currentDialectMetadata
            .existingIndices(ChapterTable, ChapterUserTable)
            .values
            .flatten()
            .map { it.indexName.lowercase() }
            .toSet()
    return M0067_INDEXES.filterNot { it in existing }
}
