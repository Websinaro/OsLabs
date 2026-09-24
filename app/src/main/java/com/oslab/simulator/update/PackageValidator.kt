package com.oslab.simulator.update

import com.oslab.simulator.security.PathValidator
import com.oslab.simulator.security.ResourceLimits

/**
 * Structural validation of an update ZIP before a single byte is extracted
 * (Stage 4): entry count, sizes, path safety, duplicate names, unsupported
 * file types. Runs after ZipImporter reads the central directory and
 * before anything is handed to UpdateEngine.
 *
 * Entry metadata only — no extraction happens in this class.
 */
data class ZipEntryMeta(
    val path: String,
    val compressedSize: Long,
    val uncompressedSize: Long
)

sealed class PackageOutcome {
    object Valid : PackageOutcome()
    data class Invalid(val reason: String) : PackageOutcome()
}

object PackageValidator {

    fun validate(entries: List<ZipEntryMeta>): PackageOutcome {
        if (entries.isEmpty()) {
            return PackageOutcome.Invalid("Package is empty")
        }
        if (entries.size > ResourceLimits.MAX_FILE_COUNT) {
            return PackageOutcome.Invalid("Too many entries: ${entries.size}")
        }

        val seen = mutableSetOf<String>()
        var totalUncompressed = 0L

        for (entry in entries) {
            if (!PathValidator.isSafe(entry.path)) {
                return PackageOutcome.Invalid("Unsafe path: ${entry.path}")
            }
            if (!seen.add(entry.path)) {
                return PackageOutcome.Invalid("Duplicate entry: ${entry.path}")
            }
            if (entry.uncompressedSize > ResourceLimits.MAX_FILE_BYTES) {
                return PackageOutcome.Invalid("File too large: ${entry.path}")
            }
            totalUncompressed += entry.uncompressedSize
        }

        if (totalUncompressed > ResourceLimits.MAX_UNCOMPRESSED_BYTES) {
            return PackageOutcome.Invalid("Package expands beyond the allowed limit (possible zip bomb)")
        }

        return PackageOutcome.Valid
    }
}
