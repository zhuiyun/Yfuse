package com.yfuse.update

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AutomaticUpdateCheckGateTest {

    @Test
    fun a_successful_check_holds_the_gate_for_a_day() {
        val settings = MapSettings()
        var now = 1_000_000_000_000L
        val gate = AutomaticUpdateCheckGate(settings) { now }

        assertTrue(gate.tryAcquire())
        assertFalse(gate.tryAcquire())

        now += AUTOMATIC_UPDATE_CHECK_INTERVAL_MS - 1
        assertFalse(gate.tryAcquire())

        now += 1
        assertTrue(gate.tryAcquire())
    }

    @Test
    fun a_failed_check_retries_within_the_hour_instead_of_the_next_day() {
        val settings = MapSettings()
        var now = 1_000_000_000_000L
        val gate = AutomaticUpdateCheckGate(settings) { now }

        assertTrue(gate.tryAcquire())
        gate.releaseForRetry()

        now += FAILED_CHECK_RETRY_INTERVAL_MS - 1
        assertFalse(gate.tryAcquire())

        now += 1
        assertTrue(gate.tryAcquire())
    }
}

class UpdateManifestPolicyTest {

    @Test
    fun same_origin_production_https_download_is_accepted() {
        val manifest = manifest("https://47.112.219.60/yfuse/Yfuse-latest.apk")

        assertSame(
            manifest,
            manifest.validateForUpdateSource("https://47.112.219.60/yfuse/update-v2.json"),
        )
    }

    @Test
    fun an_http_source_may_upgrade_the_download_to_https() {
        manifest("https://updates.example.com/yfuse/Yfuse-latest.apk")
            .validateForUpdateSource("http://updates.example.com/yfuse/update.json")
    }

    @Test
    fun an_https_source_cannot_downgrade_the_download() {
        assertFailsWith<IllegalArgumentException> {
            manifest("http://updates.example.com/yfuse/Yfuse-latest.apk")
                .validateForUpdateSource("https://updates.example.com/yfuse/update.json")
        }
    }

    @Test
    fun a_manifest_cannot_redirect_to_another_origin_or_directory() {
        assertFailsWith<IllegalArgumentException> {
            manifest("https://attacker.example/yfuse/Yfuse-latest.apk")
                .validateForUpdateSource("http://updates.example.com/yfuse/update.json")
        }
        assertFailsWith<IllegalArgumentException> {
            manifest("http://updates.example.com/other/Yfuse-latest.apk")
                .validateForUpdateSource("http://updates.example.com/yfuse/update.json")
        }
        assertFailsWith<IllegalArgumentException> {
            manifest("http://updates.example.com/yfuse/../other/Yfuse-latest.apk")
                .validateForUpdateSource("http://updates.example.com/yfuse/update.json")
        }
    }

    @Test
    fun implicit_and_explicit_default_ports_are_the_same_origin() {
        manifest("http://updates.example.com:80/yfuse/Yfuse-latest.apk")
            .validateForUpdateSource("http://updates.example.com/yfuse/update.json")
    }

    @Test
    fun malformed_digest_is_rejected_before_download() {
        assertFailsWith<IllegalArgumentException> {
            manifest("http://updates.example.com/yfuse/Yfuse-latest.apk", sha256 = "bad")
                .validateForUpdateSource("http://updates.example.com/yfuse/update.json")
        }
    }

    private fun manifest(
        apkUrl: String,
        sha256: String = "a".repeat(64),
    ) = UpdateManifest(
        versionCode = 2,
        versionName = "1.2.3",
        apkUrl = apkUrl,
        sha256 = sha256,
        size = 123L,
    )
}
