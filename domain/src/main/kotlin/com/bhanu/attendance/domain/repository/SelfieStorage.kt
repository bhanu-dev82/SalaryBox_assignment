package com.bhanu.attendance.domain.repository

import java.time.LocalDate

/**
 * Filesystem storage for the JPEG attached to each attendance record.
 *
 * Kept behind an interface so the domain never sees a `File`, and so tests can substitute an
 * in-memory double without touching disk.
 */
interface SelfieStorage {
    /** Writes [jpegBytes] and returns the absolute path to record on the attendance row. */
    suspend fun save(jpegBytes: ByteArray, recordId: String, localDate: LocalDate): String
    suspend fun load(path: String): ByteArray?

    /** Never throws: a missing file is a successful no-op. */
    suspend fun delete(path: String)

    /** Used when a staff member is deleted or the app is reset. */
    suspend fun deleteAllUnder(directoryName: String)
}
