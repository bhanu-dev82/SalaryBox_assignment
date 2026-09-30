package com.bhanu.attendance.core.common.dispatchers

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Injected dispatchers rather than referencing [Dispatchers] directly.
 *
 * Two reasons, both practical: unit tests can substitute a deterministic scheduler, and it
 * makes every IO touchpoint greppable. `StrictMode` will flag any disk or network access that
 * accidentally runs on [Dispatchers.Main] in debug builds, and a reviewer can audit that
 * invariant by finding the interface.
 */
interface AppDispatchers {
    /** Disk, database, file IO. */
    val io: CoroutineDispatcher

    /** CPU-bound work: face landmarks, descriptor maths, image decoding. */
    val default: CoroutineDispatcher

    /** The Android main thread. */
    val main: CoroutineDispatcher
}

class DefaultAppDispatchers : AppDispatchers {
    override val io: CoroutineDispatcher get() = Dispatchers.IO
    override val default: CoroutineDispatcher get() = Dispatchers.Default
    override val main: CoroutineDispatcher get() = Dispatchers.Main
}
