package com.add.pepers.cloud

/**
 * Compatibility helper for legacy sync code that calls getOrThrow() on
 * upload functions returning an already-successful String value.
 * It intentionally leaves the String unchanged.
 */
internal fun String.getOrThrow(): String = this
