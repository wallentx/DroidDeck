package com.droiddeck.launcher.runtime

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipFile

/** Installs one content-addressed graphics profile without changing the selected profile. */
object GraphicsProfilePack {
    data class FileSpec(
        /** Archive member name. For [Spec.wrapper], this is the same as [path]. */
        val name: String,
        /** Path relative to the installed pack directory. */
        val path: String,
        val size: Long,
        val sha256: String,
    )

    data class Archive(
        val id: String,
        val url: String,
        val size: Long,
        val sha256: String,
        val files: List<FileSpec>,
        val ignored: Set<String>,
    )

    data class Spec(
        val id: String,
        /** SHA-256 of [manifestBytes], including its original whitespace. */
        val version: String,
        val manifestBytes: ByteArray,
        val wrapper: FileSpec,
        val archives: List<Archive>,
    )

    private val SHA256 = Regex("^[0-9a-f]{64}$")
    private val ID = Regex("^[a-z0-9][a-z0-9._-]*$")

    @Throws(IOException::class)
    fun parse(bytes: ByteArray): Spec {
        try {
            val root = JSONObject(bytes.toString(Charsets.UTF_8))
            if (requiredLong(root, "format") != 1L) throw IOException("Unsupported graphics profile manifest format")
            val id = requiredId(root, "id", "profile")
            val wrapperObject = root.getJSONObject("wrapper")
            val wrapperPath = requiredPath(wrapperObject, "path", "wrapper path")
            val wrapper = FileSpec(
                name = wrapperPath,
                path = wrapperPath,
                size = requiredSize(wrapperObject, "size", "wrapper"),
                sha256 = requiredSha(wrapperObject, "sha256", "wrapper"),
            )
            if (wrapper.path == "pack.json" || wrapper.path == ".complete") {
                throw IOException("Graphics wrapper path conflicts with pack metadata: ${wrapper.path}")
            }

            val archiveArray = root.getJSONArray("archives")
            if (archiveArray.length() == 0) throw IOException("Graphics profile manifest has no archives")
            val archiveIds = mutableSetOf<String>()
            val destinations = mutableSetOf(wrapper.path, "pack.json", ".complete")
            val archives = ArrayList<Archive>(archiveArray.length())
            for (index in 0 until archiveArray.length()) {
                val archiveObject = archiveArray.getJSONObject(index)
                val archiveId = requiredId(archiveObject, "id", "archive")
                if (archiveId !in setOf("dxvk", "wsi")) {
                    throw IOException("Unexpected graphics archive id: $archiveId")
                }
                if (!archiveIds.add(archiveId)) throw IOException("Duplicate graphics archive id: $archiveId")
                val url = archiveObject.getString("url")
                if (!url.startsWith("https://")) throw IOException("Graphics archive $archiveId has an invalid URL")
                val members = mutableSetOf<String>()
                val filesArray = archiveObject.getJSONArray("files")
                val files = ArrayList<FileSpec>(filesArray.length())
                for (fileIndex in 0 until filesArray.length()) {
                    val fileObject = filesArray.getJSONObject(fileIndex)
                    val name = requiredArchiveName(fileObject.getString("name"), "archive member")
                    if (name.endsWith('/')) throw IOException("Mapped archive member is a directory: $name")
                    if (!members.add(name)) throw IOException("Duplicate archive member in manifest: $name")
                    val path = requiredPath(fileObject, "path", "installed path")
                    if (!destinations.add(path)) throw IOException("Duplicate installed graphics path: $path")
                    files += FileSpec(
                        name = name,
                        path = path,
                        size = requiredSize(fileObject, "size", "archive member $name"),
                        sha256 = requiredSha(fileObject, "sha256", "archive member $name"),
                    )
                }
                val ignoredArray = archiveObject.getJSONArray("ignored")
                val ignored = LinkedHashSet<String>(ignoredArray.length())
                for (ignoredIndex in 0 until ignoredArray.length()) {
                    val name = requiredArchiveName(ignoredArray.getString(ignoredIndex), "ignored archive member")
                    if (!members.add(name)) throw IOException("Duplicate archive member in manifest: $name")
                    ignored += name
                }
                archives += Archive(
                    id = archiveId,
                    url = url,
                    size = requiredSize(archiveObject, "size", "archive $archiveId"),
                    sha256 = requiredSha(archiveObject, "sha256", "archive $archiveId"),
                    files = files.toList(),
                    ignored = ignored.toSet(),
                )
            }
            return Spec(
                id = id,
                version = sha256(bytes),
                manifestBytes = bytes.copyOf(),
                wrapper = wrapper,
                archives = archives.toList(),
            )
        } catch (error: IOException) {
            throw error
        } catch (error: Exception) {
            throw IOException("Invalid graphics profile manifest: ${error.message}", error)
        }
    }

    fun directory(base: File, version: String): File {
        require(SHA256.matches(version)) { "Invalid graphics profile version" }
        return File(base, version)
    }

    /** Verifies a pack against the exact manifest stored inside that pack. */
    fun verify(base: File, version: String): Boolean = runCatching {
        verifyDirectory(directory(base, version), version)
    }.getOrDefault(false)

    /**
     * Stages and verifies a pack before publishing it with a same-filesystem rename. A corrupt
     * target is retained beside the repaired pack as `<version>.quarantine-<uuid>`.
     */
    @Synchronized
    @Throws(IOException::class)
    fun install(
        base: File,
        cache: File,
        spec: Spec,
        wrapper: ByteArray,
        fetch: (Archive, File, (Int) -> Unit) -> Unit,
        onProgress: (Int) -> Unit = {},
    ): File {
        val parsed = parse(spec.manifestBytes)
        requireSameSpec(spec, parsed)
        val target = directory(base, spec.version)
        if (verify(base, spec.version)) {
            onProgress(100)
            return target
        }
        verifyBytes("wrapper", wrapper, spec.wrapper.size, spec.wrapper.sha256)
        ensureDirectory(base, "graphics profile directory")
        ensureDirectory(cache, "graphics profile cache")

        val stage = File(base, ".stage-${spec.version}-${UUID.randomUUID()}")
        if (!stage.mkdir()) throw IOException("Could not create graphics profile staging directory: $stage")
        var quarantine: File? = null
        var published = false
        try {
            onProgress(0)
            writeFile(stage, spec.wrapper.path, wrapper)
            for ((archiveIndex, archive) in spec.archives.withIndex()) {
                val part = File(cache, ".graphics-${archive.id}-${UUID.randomUUID()}.part")
                try {
                    fetch(archive, part) { archiveProgress ->
                        val bounded = archiveProgress.coerceIn(0, 100)
                        onProgress((archiveIndex * 100 + bounded) / spec.archives.size)
                    }
                    verifyFile("downloaded archive ${archive.id}", part, archive.size, archive.sha256)
                    extract(archive, part, stage)
                } finally {
                    if (part.exists() && !part.delete()) part.deleteOnExit()
                }
                onProgress(((archiveIndex + 1) * 100) / spec.archives.size)
            }
            File(stage, "pack.json").writeBytes(spec.manifestBytes)
            val wrapperFile = destination(stage, spec.wrapper.path)
            if (!wrapperFile.setExecutable(true, false) || !wrapperFile.canExecute()) {
                throw IOException("Could not make graphics wrapper executable: ${spec.wrapper.path}")
            }
            File(stage, ".complete").writeText(spec.version + "\n", Charsets.UTF_8)
            if (!verifyDirectory(stage, spec.version)) throw IOException("Staged graphics profile failed verification")

            // Another process may have completed the same version while this process staged it.
            if (verify(base, spec.version)) {
                onProgress(100)
                return target
            }
            if (existsWithoutFollowingLinks(target)) {
                quarantine = uniqueSibling(target, "quarantine")
                if (!target.renameTo(quarantine)) {
                    throw IOException("Could not preserve invalid graphics profile at $target")
                }
            }
            if (!stage.renameTo(target)) {
                val error = IOException("Could not publish graphics profile at $target")
                val preserved = quarantine
                if (preserved != null && preserved.exists() && !preserved.renameTo(target)) {
                    error.addSuppressed(IOException("Could not restore invalid graphics profile from $preserved"))
                }
                quarantine = null
                throw error
            }
            published = true
            onProgress(100)
            return target
        } finally {
            if (!published && stage.exists()) stage.deleteRecursively()
        }
    }

    private fun extract(archive: Archive, zip: File, stage: File) {
        ZipFile(zip).use { input ->
            val expected = archive.files.associateBy { it.name }
            val seen = mutableSetOf<String>()
            val entries = input.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val name = requiredArchiveName(entry.name, "ZIP member")
                if (!seen.add(name)) throw IOException("Archive ${archive.id} contains duplicate member: $name")
                if (name !in expected && name !in archive.ignored) {
                    throw IOException("Archive ${archive.id} contains unexpected member: $name")
                }
                val file = expected[name] ?: continue
                if (entry.isDirectory) throw IOException("Archive ${archive.id} member is not a regular file: $name")
                if (entry.size != file.size) {
                    throw IOException("Archive ${archive.id} member has wrong size: $name")
                }
                val target = destination(stage, file.path)
                ensureDirectory(target.parentFile!!, "directory for ${file.path}")
                val digest = MessageDigest.getInstance("SHA-256")
                input.getInputStream(entry).use { source ->
                    FileOutputStream(target).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val count = source.read(buffer)
                            if (count < 0) break
                            total += count
                            if (total > file.size) {
                                throw IOException("Archive ${archive.id} member exceeds declared size: $name")
                            }
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                        }
                        if (total != file.size) {
                            throw IOException("Archive ${archive.id} member is truncated: $name")
                        }
                    }
                }
                val actual = digest.digest().toHex()
                if (actual != file.sha256) throw IOException("Archive ${archive.id} member checksum mismatch: $name")
            }
            val missing = expected.keys - seen
            if (missing.isNotEmpty()) {
                throw IOException("Archive ${archive.id} is missing member: ${missing.sorted().first()}")
            }
            val missingIgnored = archive.ignored - seen
            if (missingIgnored.isNotEmpty()) {
                throw IOException("Archive ${archive.id} is missing ignored member: ${missingIgnored.sorted().first()}")
            }
        }
    }

    private fun verifyDirectory(pack: File, version: String): Boolean {
        if (Files.isSymbolicLink(pack.toPath()) || !Files.isDirectory(pack.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            return false
        }
        val manifest = File(pack, "pack.json")
        if (Files.isSymbolicLink(manifest.toPath()) || !Files.isRegularFile(manifest.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            return false
        }
        val bytes = manifest.readBytes()
        if (sha256(bytes) != version) return false
        val spec = parse(bytes)
        if (spec.version != version || spec.id.isEmpty()) return false
        if (!hasExactTree(pack, spec)) return false
        val complete = File(pack, ".complete")
        if (complete.readText(Charsets.UTF_8).trim() != version) return false
        if (!verifyPayload(pack, spec.wrapper, executable = true)) return false
        for (archive in spec.archives) {
            for (file in archive.files) if (!verifyPayload(pack, file, executable = false)) return false
        }
        return true
    }

    private fun verifyPayload(pack: File, file: FileSpec, executable: Boolean): Boolean {
        val target = runCatching { destination(pack, file.path) }.getOrNull() ?: return false
        if (Files.isSymbolicLink(target.toPath()) || !Files.isRegularFile(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            return false
        }
        if (target.length() != file.size) return false
        if (executable && !target.canExecute()) return false
        return sha256(target) == file.sha256
    }

    /** The wrapper imports from its own directory, so undeclared siblings are part of integrity. */
    private fun hasExactTree(pack: File, spec: Spec): Boolean {
        val expectedFiles = linkedSetOf("pack.json", ".complete", spec.wrapper.path)
        spec.archives.forEach { archive -> archive.files.forEach { expectedFiles += it.path } }
        val expectedDirectories = mutableSetOf<String>()
        for (path in expectedFiles) {
            var parent = path.substringBeforeLast('/', "")
            while (parent.isNotEmpty()) {
                expectedDirectories += parent
                parent = parent.substringBeforeLast('/', "")
            }
        }
        return exactDirectory(pack, "", expectedFiles, expectedDirectories)
    }

    private fun exactDirectory(
        directory: File,
        prefix: String,
        expectedFiles: Set<String>,
        expectedDirectories: Set<String>,
    ): Boolean {
        val children = directory.listFiles() ?: return false
        for (child in children) {
            val relative = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
            val path = child.toPath()
            if (Files.isSymbolicLink(path)) return false
            when {
                Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) -> {
                    if (relative !in expectedDirectories) return false
                    if (!exactDirectory(child, relative, expectedFiles, expectedDirectories)) return false
                }
                Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) -> if (relative !in expectedFiles) return false
                else -> return false
            }
        }
        return true
    }

    private fun requireSameSpec(given: Spec, parsed: Spec) {
        if (
            given.id != parsed.id || given.version != parsed.version ||
            !given.manifestBytes.contentEquals(parsed.manifestBytes) || given.wrapper != parsed.wrapper ||
            given.archives != parsed.archives
        ) {
            throw IOException("Graphics profile spec does not match its manifest bytes")
        }
    }

    private fun writeFile(root: File, relative: String, bytes: ByteArray) {
        val target = destination(root, relative)
        ensureDirectory(target.parentFile!!, "directory for $relative")
        target.writeBytes(bytes)
    }

    private fun destination(root: File, relative: String): File {
        validatePath(relative, "installed path")
        val rootPath = root.canonicalFile.toPath()
        val target = File(root, relative).canonicalFile
        if (!target.toPath().startsWith(rootPath)) throw IOException("Installed path escapes pack: $relative")
        return target
    }

    private fun requiredId(objectValue: JSONObject, key: String, label: String): String {
        val value = objectValue.getString(key)
        if (!ID.matches(value)) throw IOException("Invalid graphics $label id: $value")
        return value
    }

    private fun requiredPath(objectValue: JSONObject, key: String, label: String): String =
        objectValue.getString(key).also { validatePath(it, label) }

    private fun requiredArchiveName(value: String, label: String): String {
        val path = if (value.endsWith('/')) value.dropLast(1) else value
        validatePath(path, label)
        return value
    }

    private fun validatePath(value: String, label: String) {
        if (
            value.isEmpty() || value.startsWith('/') || value.contains('\\') || value.contains('\u0000') ||
            value.split('/').any { it.isEmpty() || it == "." || it == ".." }
        ) {
            throw IOException("Invalid $label: $value")
        }
    }

    private fun requiredLong(objectValue: JSONObject, key: String): Long {
        val value = objectValue.get(key)
        if (value !is Number) throw IOException("Graphics manifest field $key is not a number")
        return value.toLong()
    }

    private fun requiredSize(objectValue: JSONObject, key: String, label: String): Long {
        val size = requiredLong(objectValue, key)
        if (size < 0) throw IOException("Invalid size for $label")
        return size
    }

    private fun requiredSha(objectValue: JSONObject, key: String, label: String): String {
        val sha = objectValue.getString(key).lowercase(Locale.ROOT)
        if (!SHA256.matches(sha)) throw IOException("Invalid checksum for $label")
        return sha
    }

    private fun verifyBytes(label: String, bytes: ByteArray, size: Long, expectedSha: String) {
        if (bytes.size.toLong() != size) throw IOException("$label has wrong size")
        if (sha256(bytes) != expectedSha) throw IOException("$label checksum mismatch")
    }

    private fun verifyFile(label: String, file: File, size: Long, expectedSha: String) {
        if (!file.isFile) throw IOException("$label was not downloaded")
        if (file.length() != size) throw IOException("$label has wrong size")
        if (sha256(file) != expectedSha) throw IOException("$label checksum mismatch")
    }

    private fun ensureDirectory(directory: File, label: String) {
        if (directory.isDirectory) return
        if (!directory.mkdirs() || !directory.isDirectory) throw IOException("Could not create $label: $directory")
    }

    private fun uniqueSibling(file: File, label: String): File {
        while (true) {
            val candidate = File(file.parentFile, "${file.name}.$label-${UUID.randomUUID()}")
            if (!existsWithoutFollowingLinks(candidate)) return candidate
        }
    }

    private fun existsWithoutFollowingLinks(file: File): Boolean =
        Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
