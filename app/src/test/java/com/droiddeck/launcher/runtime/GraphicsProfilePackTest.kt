package com.droiddeck.launcher.runtime

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class GraphicsProfilePackTest {
    @get:Rule val temporary = TemporaryFolder()

    private val wrapper = "#!/usr/bin/env python3\n".toByteArray()
    private val payload = "graphics payload".toByteArray()
    private val license = "license text".toByteArray()

    private data class Fixture(
        val spec: GraphicsProfilePack.Spec,
        val archive: ByteArray,
    )

    private fun fixture(
        archive: ByteArray = zip("payload.bin" to payload, "LICENSE" to license),
        declaredPayload: ByteArray = payload,
        member: String = "payload.bin",
        installedPath: String = "dxvk/x64/payload.bin",
        ignored: List<String> = listOf("LICENSE"),
    ): Fixture {
        val manifest = JSONObject()
            .put("format", 1)
            .put("id", "powervr-bc-dxvk-v1")
            .put(
                "wrapper",
                JSONObject()
                    .put("path", "run-proton.py")
                    .put("size", wrapper.size)
                    .put("sha256", sha(wrapper)),
            )
            .put(
                "archives",
                JSONArray().put(
                    JSONObject()
                        .put("id", "dxvk")
                        .put("url", "https://github.com/example/releases/download/v1/dxvk.zip")
                        .put("size", archive.size)
                        .put("sha256", sha(archive))
                        .put(
                            "files",
                            JSONArray().put(
                                JSONObject()
                                    .put("name", member)
                                    .put("path", installedPath)
                                    .put("size", declaredPayload.size)
                                    .put("sha256", sha(declaredPayload)),
                            ),
                        )
                        .put("ignored", JSONArray(ignored)),
                ),
            )
            .toString()
            .toByteArray()
        return Fixture(GraphicsProfilePack.parse(manifest), archive)
    }

    private fun install(fixture: Fixture, base: File = temporary.newFolder(), cache: File = temporary.newFolder()): File =
        GraphicsProfilePack.install(base, cache, fixture.spec, wrapper, fetch = { _, part, progress ->
            progress(50)
            part.writeBytes(fixture.archive)
        })

    @Test fun versionIsTheChecksumOfExactManifestBytesAndDirectoryIsStrict() {
        val compact = fixture().spec
        val prettyBytes = JSONObject(compact.manifestBytes.toString(Charsets.UTF_8)).toString(2).toByteArray()
        val pretty = GraphicsProfilePack.parse(prettyBytes)
        assertEquals(sha(compact.manifestBytes), compact.version)
        assertEquals(sha(prettyBytes), pretty.version)
        assertFalse(compact.version == pretty.version)
        val base = temporary.newFolder()
        assertEquals(File(base, compact.version), GraphicsProfilePack.directory(base, compact.version))
        fails<IllegalArgumentException> { GraphicsProfilePack.directory(base, compact.version.uppercase()) }
        fails<IllegalArgumentException> { GraphicsProfilePack.directory(base, "../escape") }
    }

    @Test fun installsExactPayloadManifestMarkerAndExecutableWrapper() {
        val fixture = fixture()
        val base = temporary.newFolder()
        val progress = mutableListOf<Int>()
        val pack = GraphicsProfilePack.install(base, temporary.newFolder(), fixture.spec, wrapper,
            fetch = { _, part, report -> report(25); part.writeBytes(fixture.archive) },
            onProgress = progress::add,
        )
        assertTrue(GraphicsProfilePack.verify(base, fixture.spec.version))
        assertTrue(File(pack, "run-proton.py").canExecute())
        assertEquals(payload.toList(), File(pack, "dxvk/x64/payload.bin").readBytes().toList())
        assertEquals(fixture.spec.manifestBytes.toList(), File(pack, "pack.json").readBytes().toList())
        assertEquals(fixture.spec.version, File(pack, ".complete").readText().trim())
        assertEquals(100, progress.last())
    }

    @Test fun validExistingPackIsReusedWithoutFetchOrWrapperRestaging() {
        val fixture = fixture()
        val base = temporary.newFolder()
        val first = install(fixture, base)
        var fetched = false
        val second = GraphicsProfilePack.install(base, temporary.newFolder(), fixture.spec, byteArrayOf(),
            fetch = { _, _, _ -> fetched = true })
        assertEquals(first, second)
        assertFalse(fetched)
        assertTrue(GraphicsProfilePack.verify(base, fixture.spec.version))
    }

    @Test fun verificationUsesEachInstalledPacksOwnManifest() {
        val base = temporary.newFolder()
        val old = fixture(declaredPayload = payload)
        install(old, base)
        val newerPayload = "new payload".toByteArray()
        val newerArchive = zip("payload.bin" to newerPayload, "LICENSE" to license)
        val newer = fixture(archive = newerArchive, declaredPayload = newerPayload)
        install(newer, base)
        assertTrue(GraphicsProfilePack.verify(base, old.spec.version))
        assertTrue(GraphicsProfilePack.verify(base, newer.spec.version))
        assertFalse(old.spec.version == newer.spec.version)
    }

    @Test fun verificationDetectsPayloadMarkerAndPermissionTampering() {
        val fixture = fixture()
        val base = temporary.newFolder()
        val pack = install(fixture, base)
        File(pack, "dxvk/x64/payload.bin").appendText("tampered")
        assertFalse(GraphicsProfilePack.verify(base, fixture.spec.version))
        File(pack, "dxvk/x64/payload.bin").writeBytes(payload)
        File(pack, ".complete").writeText("wrong")
        assertFalse(GraphicsProfilePack.verify(base, fixture.spec.version))
        File(pack, ".complete").writeText(fixture.spec.version)
        assertTrue(File(pack, "run-proton.py").setExecutable(false, false))
        assertFalse(GraphicsProfilePack.verify(base, fixture.spec.version))
    }

    @Test fun wrapperIsVerifiedBeforeFetchOrExistingTargetChanges() {
        val fixture = fixture()
        val base = temporary.newFolder()
        val target = GraphicsProfilePack.directory(base, fixture.spec.version).apply { mkdirs() }
        File(target, "old-data").writeText("keep")
        var fetched = false
        fails<IOException> {
            GraphicsProfilePack.install(base, temporary.newFolder(), fixture.spec, "wrong".toByteArray(),
                fetch = { _, _, _ -> fetched = true })
        }
        assertFalse(fetched)
        assertEquals("keep", File(target, "old-data").readText())
        assertTrue(base.listFiles().orEmpty().none { it.name.contains("quarantine") })
    }

    @Test fun downloadTamperingAndTruncatedZipLeaveNoPublishedOrStagedPack() {
        val good = fixture()
        val base = temporary.newFolder()
        fails<IOException> {
            GraphicsProfilePack.install(base, temporary.newFolder(), good.spec, wrapper,
                fetch = { _, part, _ -> part.writeBytes(good.archive + 0) })
        }
        assertCleanFailure(base, good.spec.version)

        val truncatedBytes = good.archive.copyOf(good.archive.size / 2)
        val truncated = fixture(archive = truncatedBytes)
        fails<IOException> { install(truncated, base) }
        assertCleanFailure(base, truncated.spec.version)
    }

    @Test fun duplicateTraversalOversizeAndMissingMembersAreRejected() {
        val duplicateArchive = renameZipMember(
            zip("a.bin" to payload, "b.bin" to license),
            from = "b.bin",
            to = "a.bin",
        )
        val duplicate = fixture(
            archive = duplicateArchive,
            member = "a.bin",
            ignored = listOf("b.bin"),
        )
        fails<IOException> { install(duplicate) }

        val traversalArchive = zip("payload.bin" to payload, "LICENSE" to license, "../escape" to byteArrayOf(1))
        fails<IOException> { install(fixture(archive = traversalArchive)) }

        val oversize = fixture(
            archive = zip("payload.bin" to (payload + 0), "LICENSE" to license),
            declaredPayload = payload,
        )
        fails<IOException> { install(oversize) }

        val missing = fixture(archive = zip("LICENSE" to license))
        fails<IOException> { install(missing) }
    }

    @Test fun extractionFailureDoesNotModifyAnInvalidExistingTarget() {
        val bad = fixture(archive = zip("LICENSE" to license))
        val base = temporary.newFolder()
        val target = GraphicsProfilePack.directory(base, bad.spec.version).apply { mkdirs() }
        File(target, "old-data").writeText("keep")
        fails<IOException> { install(bad, base) }
        assertEquals("keep", File(target, "old-data").readText())
        assertTrue(base.listFiles().orEmpty().none { it.name.contains("quarantine") })
        assertTrue(base.listFiles().orEmpty().none { it.name.startsWith(".stage-") })
    }

    @Test fun successfulRepairPreservesInvalidPackInUniqueQuarantine() {
        val fixture = fixture()
        val base = temporary.newFolder()
        val target = GraphicsProfilePack.directory(base, fixture.spec.version).apply { mkdirs() }
        File(target, "old-data").writeText("keep")
        val repaired = install(fixture, base)
        assertTrue(GraphicsProfilePack.verify(base, fixture.spec.version))
        assertEquals(repaired, target)
        val quarantines = base.listFiles().orEmpty().filter { it.name.startsWith("${fixture.spec.version}.quarantine-") }
        assertEquals(1, quarantines.size)
        assertEquals("keep", File(quarantines.single(), "old-data").readText())
    }

    @Test fun shadowModuleMakesPackInvalidAndRepairQuarantinesIt() {
        val fixture = fixture()
        val base = temporary.newFolder()
        val pack = install(fixture, base)
        File(pack, "argparse.py").writeText("raise RuntimeError('shadowed')")
        assertFalse(GraphicsProfilePack.verify(base, fixture.spec.version))

        val repaired = install(fixture, base)
        assertTrue(GraphicsProfilePack.verify(base, fixture.spec.version))
        assertFalse(File(repaired, "argparse.py").exists())
        val quarantine = quarantines(base, fixture.spec.version).single()
        assertTrue(File(quarantine, "argparse.py").isFile)
    }

    @Test fun expectedFileSymlinkIsRejectedAndRepairDoesNotTouchItsTarget() {
        val fixture = fixture()
        val base = temporary.newFolder()
        val pack = install(fixture, base)
        val outside = temporary.newFile().apply { writeBytes(payload) }
        val installed = File(pack, "dxvk/x64/payload.bin")
        assertTrue(installed.delete())
        Files.createSymbolicLink(installed.toPath(), outside.toPath())
        assertFalse(GraphicsProfilePack.verify(base, fixture.spec.version))

        install(fixture, base)
        assertEquals(payload.toList(), outside.readBytes().toList())
        assertTrue(outside.isFile)
        val quarantinedLink = File(quarantines(base, fixture.spec.version).single(), "dxvk/x64/payload.bin")
        assertTrue(Files.isSymbolicLink(quarantinedLink.toPath()))
        assertTrue(GraphicsProfilePack.verify(base, fixture.spec.version))
    }

    @Test fun packDirectorySymlinkIsRejectedAndRepairMovesOnlyTheLink() {
        val fixture = fixture()
        val base = temporary.newFolder()
        val outside = temporary.newFolder()
        val marker = File(outside, "keep").apply { writeText("outside") }
        val target = GraphicsProfilePack.directory(base, fixture.spec.version)
        Files.createSymbolicLink(target.toPath(), outside.toPath())
        assertFalse(GraphicsProfilePack.verify(base, fixture.spec.version))

        install(fixture, base)
        assertEquals("outside", marker.readText())
        assertTrue(marker.isFile)
        val quarantine = quarantines(base, fixture.spec.version).single()
        assertTrue(Files.isSymbolicLink(quarantine.toPath()))
        assertTrue(GraphicsProfilePack.verify(base, fixture.spec.version))
    }

    @Test fun manifestRejectsUnsafeOrConflictingDestinations() {
        val normal = fixture()
        val root = JSONObject(normal.spec.manifestBytes.toString(Charsets.UTF_8))
        root.getJSONArray("archives").getJSONObject(0).getJSONArray("files").getJSONObject(0)
            .put("path", "../run-proton.py")
        fails<IOException> { GraphicsProfilePack.parse(root.toString().toByteArray()) }

        val collision = JSONObject(normal.spec.manifestBytes.toString(Charsets.UTF_8))
        collision.getJSONArray("archives").getJSONObject(0).getJSONArray("files").getJSONObject(0)
            .put("path", "run-proton.py")
        fails<IOException> { GraphicsProfilePack.parse(collision.toString().toByteArray()) }
    }

    private fun assertCleanFailure(base: File, version: String) {
        assertFalse(GraphicsProfilePack.directory(base, version).exists())
        assertTrue(base.listFiles().orEmpty().none { it.name.startsWith(".stage-") })
    }

    private fun quarantines(base: File, version: String): List<File> =
        base.listFiles().orEmpty().filter { it.name.startsWith("$version.quarantine-") }

    private inline fun <reified T : Throwable> fails(block: () -> Unit): T {
        try {
            block()
            fail("Expected ${T::class.java.simpleName}")
        } catch (error: Throwable) {
            if (error !is T) throw error
            return error
        }
        throw AssertionError("unreachable")
    }

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun renameZipMember(bytes: ByteArray, from: String, to: String): ByteArray {
        require(from.length == to.length)
        val result = bytes.copyOf()
        val needle = from.toByteArray()
        val replacement = to.toByteArray()
        for (index in 0..result.size - needle.size) {
            if (needle.indices.all { offset -> result[index + offset] == needle[offset] }) {
                replacement.copyInto(result, index)
            }
        }
        return result
    }

    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
