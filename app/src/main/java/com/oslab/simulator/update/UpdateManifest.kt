package com.oslab.simulator.update

import org.json.JSONException
import org.json.JSONObject

/**
 * Mirrors manifest.json inside an update package:
 * { "name", "version", "targetVersion", "formatVersion" }
 */
data class UpdateManifest(
    val name: String,
    val version: String,
    val targetVersion: String,
    val formatVersion: Int
) {
    companion object {
        /** Parses manifest.json bytes. Never throws — malformed input is a normal, expected case. */
        fun parse(bytes: ByteArray): ValidationResult {
            return try {
                val json = JSONObject(bytes.decodeToString())
                val manifest = UpdateManifest(
                    name = json.optString("name", ""),
                    version = json.optString("version", ""),
                    targetVersion = json.optString("targetVersion", ""),
                    formatVersion = json.optInt("formatVersion", -1)
                )
                if (manifest.name.isBlank() || manifest.version.isBlank() || manifest.targetVersion.isBlank()) {
                    return ValidationResult.Invalid("manifest.json missing name/version/targetVersion")
                }
                ValidationResult.Valid(manifest)
            } catch (e: JSONException) {
                ValidationResult.Invalid("manifest.json is not valid JSON: ${e.message}")
            }
        }
    }
}

sealed class ValidationResult {
    data class Valid(val manifest: UpdateManifest) : ValidationResult()
    data class Invalid(val reason: String) : ValidationResult()
}

/**
 * Validates manifest.json content and version compatibility against the
 * currently running virtual OS (Stage 4).
 */
object ManifestValidator {
    fun validateCompatibility(manifest: UpdateManifest, currentOsVersion: String): ValidationResult {
        if (manifest.formatVersion != 1) {
            return ValidationResult.Invalid("Unsupported formatVersion: ${manifest.formatVersion}")
        }
        if (manifest.targetVersion != currentOsVersion) {
            return ValidationResult.Invalid(
                "targetVersion ${manifest.targetVersion} does not match running OS $currentOsVersion"
            )
        }
        return ValidationResult.Valid(manifest)
    }
}
