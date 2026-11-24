package com.hongguotv.core

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AppUpdatesTest {
    private val bytes = "signed-apk-test-bytes".toByteArray()

    private fun json() =
        JSONObject()
            .put("schema", 1)
            .put("packageName", "io.github.tsukiyo_zero.guoyingtv")
            .put("versionCode", 10001)
            .put("versionName", "0.9.0+1")
            .put("minSdk", 26)
            .put("tag", "kotlin-v0.9.0-build.1")
            .put(
                "apk",
                JSONObject()
                    .put("name", "hongguotv-kotlin-0.9.0-build.1-android8.apk")
                    .put("size", bytes.size)
                    .put(
                        "sha256",
                        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
                            "%02x".format(it)
                        },
                    ),
            )

    private fun update() = AppUpdate.parse(json().toString())

    private fun withFile(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("update-test").toFile()
        try {
            block(File(dir, "update.apk"))
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun checkRepository(responses: Map<String, String>, requested: MutableList<String>) =
        AppUpdateRepository(
            OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                val url = request.url.toString()
                requested += url
                val body = responses[url]
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                    .code(if (body == null) 503 else 200)
                    .message(if (body == null) "Unavailable" else "OK")
                    .body(body.orEmpty().toResponseBody())
                    .build()
            }.build(),
        )

    private fun primaryManifest(version: Int): Map<String, String> {
        val manifest = json().put("versionCode", version).put("minSdk", 23)
        val tag = manifest.getString("tag")
        val release = JSONObject().put("tag_name", tag)
            .put("assets", JSONArray().put(JSONObject().put("name", "update.json")))
        return mapOf(
            AppUpdate.LATEST_API to release.toString(),
            "${AppUpdate.RELEASES}/download/$tag/update.json" to manifest.toString(),
        )
    }

    @Test
    fun manualCheckContinuesPastStalePrimaryAndFailedDirectSource() {
        val requested = mutableListOf<String>()
        val mirror = AppUpdate.MANIFEST_MIRRORS[1]
        val responses = primaryManifest(78) +
            (AppUpdate.latestManifestUrl(mirror) to json().put("versionCode", 80).put("minSdk", 23).toString())
        checkRepository(responses, requested).use { repository ->
            assertEquals(78L, repository.latest().versionCode)
            assertEquals(2, requested.size)
            requested.clear()

            val received = repository.latest(installedVersion = 79, sdk = 23)
            assertEquals(80L, received.versionCode)
            assertEquals(AppUpdate.releasesBaseOf(mirror), received.releasesBase)
            assertEquals(AppUpdate.MANIFEST_MIRRORS.take(2).map { AppUpdate.latestManifestUrl(it) }, requested.drop(2))
        }
    }

    @Test
    fun manualCheckFindsCompatibleUpdateWhenFirstNewerManifestNeedsAndroid8() {
        val mirror = AppUpdate.MANIFEST_MIRRORS[1]
        val responses = mapOf(
            AppUpdate.latestManifestUrl(AppUpdate.MANIFEST_MIRRORS[0]) to
                json().put("versionCode", 81).put("minSdk", 26).toString(),
            AppUpdate.latestManifestUrl(mirror) to
                json().put("versionCode", 80).put("minSdk", 23).toString(),
        )
        checkRepository(responses, mutableListOf()).use { repository ->
            assertEquals(80L, repository.latest(installedVersion = 79, sdk = 23).versionCode)
            assertEquals(81L, repository.latest(installedVersion = 79, sdk = 26).versionCode)
        }
    }

    @Test
    fun manualCheckDistinguishesNoNewerManifestFromAllSourcesFailing() {
        val requested = mutableListOf<String>()
        checkRepository(primaryManifest(79), requested).use {
            assertEquals(79L, it.latest(installedVersion = 79, sdk = 23).versionCode)
            assertEquals(2 + AppUpdate.MANIFEST_MIRRORS.size, requested.size)
        }
        checkRepository(emptyMap(), mutableListOf()).use {
            assertThrows(IOException::class.java) { it.latest(installedVersion = 79, sdk = 23) }
        }
    }

    @Test
    fun latestReleaseRequiresStableTagAndManifest() {
        val release =
            JSONObject()
                .put("tag_name", "kotlin-v0.1.1")
                .put("prerelease", false)
                .put("assets", org.json.JSONArray().put(JSONObject().put("name", "update.json")))
        assertEquals("kotlin-v0.1.1", AppUpdate.latestTag(release.toString()))
        assertThrows(IllegalArgumentException::class.java) {
            AppUpdate.latestTag(release.put("prerelease", true).toString())
        }
        release.put("prerelease", false).put("tag_name", "../bad")
        assertThrows(IllegalArgumentException::class.java) {
            AppUpdate.latestTag(release.toString())
        }
        release.put("tag_name", "kotlin-v0.1.1").put("assets", org.json.JSONArray())
        assertThrows(IllegalArgumentException::class.java) {
            AppUpdate.latestTag(release.toString())
        }
    }

    @Test
    fun android6AndLegacyAndroid8UpdatesRespectTheirMinimumSdk() {
        val metadata =
            json()
                .put("versionCode", 17)
                .put("versionName", "0.0.2-android6")
                .put("minSdk", 23)
                .put("tag", "kotlin-v0.0.2-android6")
        metadata.getJSONObject("apk").put("name", "hongguotv-kotlin-0.0.2-android6.apk")
        val compatible = AppUpdate.parse(metadata.toString())
        assertTrue(compatible.newerThan(16, 23))
        assertFalse(compatible.newerThan(16, 22))
        assertFalse(update().newerThan(16, 23))
        assertThrows(IllegalArgumentException::class.java) {
            AppUpdate.parse(metadata.put("minSdk", 22).toString())
        }
    }

    @Test
    fun onlyHigherCompatibleVersionsAreUpdates() {
        val update = update()
        assertTrue(update.newerThan(9, 26))
        assertFalse(update.newerThan(10001, 36))
        assertFalse(update.newerThan(10002, 36))
        assertFalse(update.newerThan(9, 25))
    }

    @Test
    fun downloadUrlUsesOnlyFixedRepositoryAndValidatedNames() {
        assertEquals(
            "https://gitcode.com/tsukiyo-zero/hongguoTV/releases/download/kotlin-v0.9.0-build.1/hongguotv-kotlin-0.9.0-build.1-android8.apk",
            update().downloadUrl,
        )
        for (value in
            listOf(
                "../../bad.apk",
                "evil.apk",
                "https://example.com/evil.apk",
                "hongguotv-kotlin-1/2-android8.apk",
            )) {
            val o = json()
            o.getJSONObject("apk").put("name", value)
            assertThrows(IllegalArgumentException::class.java) { AppUpdate.parse(o.toString()) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            AppUpdate.parse(json().put("tag", "../../other").toString())
        }
    }

    @Test
    fun rejectOtherPackageAndInvalidSchema() {
        assertThrows(IllegalArgumentException::class.java) {
            AppUpdate.parse(json().put("packageName", "com.hongguotv.nativeapp").toString())
        }
        assertThrows(IllegalArgumentException::class.java) {
            AppUpdate.parse(json().put("packageName", "other.app").toString())
        }
        assertThrows(IllegalArgumentException::class.java) {
            AppUpdate.parse(json().put("schema", 2).toString())
        }
    }

    @Test
    fun enforceVersionSizeAndChecksumBounds() {
        for (code in listOf(0L, -1L, Int.MAX_VALUE.toLong() + 1)) assertThrows(
            IllegalArgumentException::class.java
        ) {
            AppUpdate.parse(json().put("versionCode", code).toString())
        }
        for (size in listOf(0L, -1L, AppUpdate.MAX_APK_BYTES + 1)) {
            val o = json()
            o.getJSONObject("apk").put("size", size)
            assertThrows(IllegalArgumentException::class.java) { AppUpdate.parse(o.toString()) }
        }
        val o = json()
        o.getJSONObject("apk").put("sha256", "invalid")
        assertThrows(IllegalArgumentException::class.java) { AppUpdate.parse(o.toString()) }
    }

    @Test
    fun verifiedDownloadAtomicallyReplacesOldFile() = withFile { file ->
        file.writeText("previous")
        val progress = mutableListOf<Int>()
        UpdateFiles.receive(ByteArrayInputStream(bytes), file, update(), progress::add)
        assertTrue(UpdateFiles.verify(file, update()))
        assertEquals(100, progress.last())
        assertFalse(File(file.path + ".part").exists())
    }

    @Test
    fun truncatedDownloadPreservesPreviousFile() = withFile { file ->
        file.writeText("previous")
        assertThrows(IOException::class.java) {
            UpdateFiles.receive(ByteArrayInputStream(bytes.copyOf(4)), file, update())
        }
        assertEquals("previous", file.readText())
        assertFalse(File(file.path + ".part").exists())
    }

    @Test
    fun oversizedDownloadIsRejectedAndRemoved() = withFile { file ->
        assertThrows(IOException::class.java) {
            UpdateFiles.receive(ByteArrayInputStream(bytes + byteArrayOf(1)), file, update())
        }
        assertFalse(file.exists())
        assertFalse(File(file.path + ".part").exists())
    }

    @Test
    fun checksumMismatchCannotBecomeInstallableFile() = withFile { file ->
        val corrupt = bytes.copyOf()
        corrupt[0] = 0
        assertThrows(IOException::class.java) {
            UpdateFiles.receive(ByteArrayInputStream(corrupt), file, update())
        }
        assertFalse(file.exists())
        assertFalse(File(file.path + ".part").exists())
    }

    @Test
    fun interruptedDownloadCleansUpPartialFile() = withFile { file ->
        Thread.currentThread().interrupt()
        try {
            assertThrows(IOException::class.java) {
                UpdateFiles.receive(ByteArrayInputStream(bytes), file, update())
            }
        } finally {
            Thread.interrupted()
        }
        assertFalse(file.exists())
        assertFalse(File(file.path + ".part").exists())
    }

    @Test
    fun cacheMustStillMatchExpectedContent() = withFile { file ->
        file.writeBytes(bytes)
        assertTrue(UpdateFiles.verify(file, update()))
        file.writeBytes(ByteArray(bytes.size))
        assertFalse(UpdateFiles.verify(file, update()))
    }
}
