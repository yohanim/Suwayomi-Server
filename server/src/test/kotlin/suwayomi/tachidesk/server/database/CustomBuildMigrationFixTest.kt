package suwayomi.tachidesk.server.database

import de.neonew.exposed.migrations.MigrationsTable
import de.neonew.exposed.migrations.loadMigrationsFrom
import de.neonew.exposed.migrations.runMigrations
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import suwayomi.tachidesk.server.ServerConfig
import suwayomi.tachidesk.test.ApplicationTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock

class CustomBuildMigrationFixTest : ApplicationTest() {
    private fun latestMigrations(): Map<Int, String> =
        transaction {
            MigrationsTable
                .selectAll()
                .where { MigrationsTable.id greaterEq 66 }
                .associate { it[MigrationsTable.id].value to it[MigrationsTable.name] }
        }

    @Test
    fun `re-runs upstream M0067 once on a database that ran the custom M0068`() {
        assertEquals(mapOf(66 to "AddUsers", 67 to "AddChapterIndexes"), latestMigrations())

        // State left by an older custom build: the custom M0068 row, and here two indexes missing
        transaction {
            MigrationsTable.insert {
                it[id] = 68
                it[name] = "AddChapterFetchedAtIndex"
                it[executedAt] = Clock.System.now()
            }
            exec("DROP INDEX IF EXISTS chapter_fetched_at")
            exec("DROP INDEX IF EXISTS chapteruser_user_read_chapter")
        }
        assertEquals(
            listOf("chapter_fetched_at", "chapteruser_user_read_chapter"),
            transaction { missingM0067Indexes() },
        )

        assertTrue(dropCustomBuildMigrationRows())
        runMigrations(loadMigrationsFrom("suwayomi.tachidesk.server.database.migration", ServerConfig::class.java))

        // Upstream's M0067 is recorded again, the custom M0068 is gone, and every M0067 index exists
        assertEquals(mapOf(66 to "AddUsers", 67 to "AddChapterIndexes"), latestMigrations())
        assertEquals(emptyList(), transaction { missingM0067Indexes() })

        // One time only
        assertFalse(dropCustomBuildMigrationRows())
    }
}
