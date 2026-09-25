package com.oslab.simulator.update

import org.junit.Assert.assertTrue
import org.junit.Test

class ManifestValidatorTest {

    private fun manifest(
        name: String = "MyOS",
        version: String = "1.0.1",
        targetVersion: String = "1.0",
        formatVersion: Int = 1
    ) = UpdateManifest(name, version, targetVersion, formatVersion)

    @Test
    fun matchingNameAndTargetVersionIsCompatible() {
        val result = ManifestValidator.validateCompatibility(manifest(), "MyOS", "1.0")
        assertTrue(result is ValidationResult.Valid)
    }

    @Test
    fun unsupportedFormatVersionIsRejectedFirst() {
        val result = ManifestValidator.validateCompatibility(manifest(formatVersion = 2), "MyOS", "1.0")
        assertTrue(result is ValidationResult.Invalid)
        assertTrue((result as ValidationResult.Invalid).reason.contains("formatVersion"))
    }

    @Test
    fun mismatchedOsNameIsRejected() {
        // WebOS update must never apply to a running MyOS device, even if the
        // version numbers would otherwise line up.
        val result = ManifestValidator.validateCompatibility(manifest(name = "WebOS"), "MyOS", "1.0")
        assertTrue(result is ValidationResult.Invalid)
        assertTrue((result as ValidationResult.Invalid).reason.contains("WebOS"))
    }

    @Test
    fun mismatchedTargetVersionIsRejectedAfterNameMatches() {
        val result = ManifestValidator.validateCompatibility(manifest(targetVersion = "0.9"), "MyOS", "1.0")
        assertTrue(result is ValidationResult.Invalid)
        assertTrue((result as ValidationResult.Invalid).reason.contains("targetVersion"))
    }
}
