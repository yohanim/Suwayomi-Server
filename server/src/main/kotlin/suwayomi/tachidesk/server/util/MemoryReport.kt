package suwayomi.tachidesk.server.util

import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File
import java.lang.management.ManagementFactory
import javax.management.ObjectName
import kotlin.concurrent.fixedRateTimer
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.hours

private val logger = KotlinLogging.logger {}

/**
 * Custom build only: logs the memory use of the container and the JVM, so it can be read from the application log
 * without running jcmd in the container. The native memory part needs -XX:NativeMemoryTracking=summary.
 */
object MemoryReport {
    private val interval = 1.hours

    fun start() {
        logProcesses()
        log("startup")
        fixedRateTimer("memory-report", daemon = true, initialDelay = interval.inWholeMilliseconds, period = interval.inWholeMilliseconds) {
            log("periodic")
        }
        Runtime.getRuntime().addShutdownHook(
            thread(start = false) {
                logger.info { "Shutdown hook started" }
                log("shutdown")
            },
        )
    }

    // shows where a container stop signal gets lost: process groups, ignored/caught signal masks and tini's settings
    private fun logProcesses() {
        try {
            val processes =
                File("/proc")
                    .listFiles { file -> file.name.all(Char::isDigit) }
                    .orEmpty()
                    .sortedBy { it.name.toInt() }
                    .mapNotNull { dir ->
                        runCatching {
                            // pid (comm) state ppid pgrp ...
                            val stat = File(dir, "stat").readText().substringAfterLast(") ").split(' ')
                            val status = File(dir, "status").readLines()

                            fun field(name: String) = status.firstOrNull { it.startsWith("$name:") }?.substringAfter(':')?.trim()
                            val cmd =
                                File(dir, "cmdline")
                                    .readText()
                                    .replace('\u0000', ' ')
                                    .trim()
                                    .take(120)
                            "pid=${dir.name} ppid=${stat[1]} pgrp=${stat[2]} sigIgn=${field("SigIgn")} " +
                                "sigCgt=${field("SigCgt")} sigBlk=${field("SigBlk")} cmd=$cmd"
                        }.getOrNull()
                    }
            val tiniEnv =
                runCatching {
                    File("/proc/1/environ").readText().split('\u0000').filter { it.startsWith("TINI_") }
                }.getOrElse { listOf("unreadable: ${it.message}") }
            logger.info {
                "Processes (self=${ProcessHandle.current().pid()}, PID 1 TINI_ env=$tiniEnv)\n${processes.joinToString("\n")}"
            }
        } catch (e: Exception) {
            logger.warn(e) { "Process report failed" }
        }
    }

    private fun log(reason: String) {
        try {
            val heap = ManagementFactory.getMemoryMXBean().heapMemoryUsage
            logger.info {
                "Memory ($reason): container=${containerMemory()}, process RSS=${processRss()}, " +
                    "heap used=${heap.used.mb()} committed=${heap.committed.mb()} max=${heap.max.mb()}\n${nativeMemorySummary()}"
            }
        } catch (e: Exception) {
            logger.warn(e) { "Memory report failed" }
        }
    }

    // cgroup v2, then v1
    private fun containerMemory(): String =
        listOf("/sys/fs/cgroup/memory.current", "/sys/fs/cgroup/memory/memory.usage_in_bytes")
            .firstNotNullOfOrNull { path ->
                runCatching {
                    File(path)
                        .readText()
                        .trim()
                        .toLong()
                        .mb()
                }.getOrNull()
            }
            ?: "unavailable"

    private fun processRss(): String =
        runCatching {
            File("/proc/self/status")
                .readLines()
                .first { it.startsWith("VmRSS:") }
                .substringAfter(':')
                .trim()
        }.getOrDefault("unavailable")

    private fun nativeMemorySummary(): String =
        runCatching {
            ManagementFactory.getPlatformMBeanServer().invoke(
                ObjectName("com.sun.management:type=DiagnosticCommand"),
                "vmNativeMemory",
                arrayOf<Any>(arrayOf("summary", "scale=MB")),
                arrayOf(Array<String>::class.java.name),
            ) as String
        }.getOrElse { "Native memory summary unavailable: ${it.message}" }

    private fun Long.mb() = "${this / (1024 * 1024)} MB"
}
