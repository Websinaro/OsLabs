package com.oslab.simulator.update

import android.content.ContentResolver
import android.net.Uri
import com.oslab.simulator.security.ResourceLimits
import java.io.File
import java.util.zip.ZipFile

/**
 * Reads an update ZIP selected via Android's Storage Access Framework
 * (Stage 4). SAF hands back a content Uri scoped to the one file the user
 * picked, so this never needs (and never requests) broad storage
 * permissions.
 *
 * The ZIP is copied to the app's own cache directory first (with a hard
 * byte cap enforced *while copying*, not trusted from any header) so it
 * can be opened as a random-access java.util.zip.ZipFile — that gives
 * accurate per-entry sizes from the central directory instead of trusting
 * a possibly-forged local file header. Nothing here extracts a single byte
 * without PackageValidator having first approved every entry's metadata.
 */
object ZipImporter {

    data class ImportedPackage(
        val files: Map<String, ByteArray>,
        val manifestBytes: ByteArray?
    )

    sealed class ImportOutcome {
        data class Ok(val pkg: ImportedPackage, val log: List<String>) : ImportOutcome()
        data class Error(val reason: String, val log: List<String>) : ImportOutcome()
    }

    /** Runs the whole read → validate → extract pipeline for one picked ZIP. Safe to call off the main thread. */
    fun import(resolver: ContentResolver, uri: Uri, cacheDir: File): ImportOutcome {
        val log = mutableListOf<String>()
        val cacheFile = File(cacheDir, "import-${System.currentTimeMillis()}.zip")

        try {
            val copied = copyWithCap(resolver, uri, cacheFile, ResourceLimits.MAX_PACKAGE_BYTES)
            if (!copied) {
                return ImportOutcome.Error("Package exceeds the ${ResourceLimits.MAX_PACKAGE_BYTES / (1024 * 1024)}MB import limit", log)
            }
            log.add("[OK] Package copied into sandbox (${cacheFile.length()} bytes)")

            ZipFile(cacheFile).use { zip ->
                val metas = zip.entries().asSequence()
                    .filter { !it.isDirectory }
                    .map { ZipEntryMeta(it.name, it.compressedSize, it.size) }
                    .toList()

                when (val outcome = PackageValidator.validate(metas)) {
                    is PackageOutcome.Invalid -> {
                        log.add("[FAIL] Package validation: ${outcome.reason}")
                        return ImportOutcome.Error(outcome.reason, log)
                    }
                    PackageOutcome.Valid -> log.add("[OK] Package structure validated (${metas.size} files)")
                }

                val extracted = mutableMapOf<String, ByteArray>()
                var runningTotal = 0L
                for (meta in metas) {
                    val entry = zip.getEntry(meta.path) ?: continue
                    val bytes = zip.getInputStream(entry).use { readBounded(it, ResourceLimits.MAX_FILE_BYTES) }
                        ?: return ImportOutcome.Error("File exceeded the per-file limit during extraction: ${meta.path}", log)
                    runningTotal += bytes.size
                    if (runningTotal > ResourceLimits.MAX_UNCOMPRESSED_BYTES) {
                        return ImportOutcome.Error("Package exceeded the total expansion limit during extraction", log)
                    }
                    extracted[meta.path] = bytes
                }
                log.add("[OK] Extracted ${extracted.size} files into the import sandbox (not yet applied)")

                val manifestBytes = extracted["manifest.json"]
                if (manifestBytes == null) {
                    log.add("[FAIL] manifest.json missing from package")
                    return ImportOutcome.Error("manifest.json missing from package", log)
                }

                return ImportOutcome.Ok(ImportedPackage(extracted, manifestBytes), log)
            }
        } catch (e: Exception) {
            log.add("[FAIL] Import error: ${e.message}")
            return ImportOutcome.Error(e.message ?: "unknown import error", log)
        } finally {
            cacheFile.delete()
        }
    }

    /** Copies [uri]'s content to [dest], aborting (and returning false) if it would exceed [maxBytes]. */
    private fun copyWithCap(resolver: ContentResolver, uri: Uri, dest: File, maxBytes: Long): Boolean {
        val input = resolver.openInputStream(uri) ?: return false
        input.use { source ->
            dest.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > maxBytes) {
                        dest.delete()
                        return false
                    }
                    out.write(buffer, 0, read)
                }
            }
        }
        return true
    }

    /** Reads [stream] fully, returning null instead of a byte array if it would exceed [maxBytes]. */
    private fun readBounded(stream: java.io.InputStream, maxBytes: Long): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) return null
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }
}
