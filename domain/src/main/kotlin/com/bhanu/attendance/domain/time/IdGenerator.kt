package com.bhanu.attendance.domain.time

import java.util.UUID

/** Injected so tests get deterministic ids and can assert on generated rows. */
fun interface IdGenerator {
    fun newId(): String

    companion object {
        val RANDOM: IdGenerator = IdGenerator { UUID.randomUUID().toString() }
    }
}
