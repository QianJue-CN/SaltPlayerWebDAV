package com.saltplayermod.webdav

import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.util.HexFormat
import java.util.Locale
import java.util.Properties

internal data class SyncResult(val uploaded: Int, val downloaded: Int, val unchanged: Int, val conflicts: List<String>, val failures: List<String>) {
    fun summary() = "上传 $uploaded 首，下载 $downloaded 首，未变更 $unchanged 首，冲突 ${conflicts.size} 首，失败 ${failures.size} 首"
}

internal class SyncEngine(private val client: WebDavClient, private val dataDirectory: Path) {
    private data class Baseline(val remote: String, val local: String, val hash: String)
    private data class Download(val file: Path, val hash: String)

    fun sync(target: Path, progress: (String) -> Unit = {}): SyncResult {
        val remote = client.scan(progress).associateBy { it.path }
        Files.createDirectories(target)
        val root = target.toRealPath()
        val stateRoot = dataDirectory.resolve("sync").resolve(digest("${client.sourceIdentity}|$root"))
        Files.createDirectories(stateRoot)
        val local = Files.walk(root).use { paths ->
            paths.filter { Files.isRegularFile(it, NOFOLLOW_LINKS) }
                .map { root.relativize(it).joinToString("/") }
                .filter { WebDavClient.isAudioPath(it) && WebDavClient.BACKUP_DIRECTORY !in it.split('/') }
                .toList().toSet()
        }
        val paths = (remote.keys + local).sorted()
        val destinations = HashSet<String>()
        var uploaded = 0
        var downloaded = 0
        var unchanged = 0
        val conflicts = mutableListOf<String>()
        val failures = mutableListOf<String>()
        for ((index, path) in paths.withIndex()) {
            checkCancelled()
            progress("双向同步 ${index + 1}/${paths.size}：$path")
            var fetched: Download? = null
            try {
                val destination = safeDestination(root, path)
                val canonical = destination.toString().let {
                    if (System.getProperty("os.name").startsWith("Windows", true)) it.lowercase(Locale.ROOT) else it
                }
                if (!destinations.add(canonical)) throw SyncConflict("多个文件映射到同一个本地路径")
                val stateFile = stateRoot.resolve(digest(path) + ".properties")
                val baseline = readState(stateFile)
                val track = remote[path]
                val exists = Files.exists(destination, NOFOLLOW_LINKS)
                val stamp = if (exists) localStamp(destination) else null
                fun remoteCopy(): Download {
                    return fetched ?: download(track ?: error("Missing remote track"), destination.parent).also { fetched = it }
                }
                fun pull() {
                    val copy = remoteCopy()
                    checkCancelled()
                    safeDestination(root, path)
                    if (Files.exists(destination, NOFOLLOW_LINKS) != exists || (exists &&
                        (localStamp(destination) != stamp || (baseline != null && hashFile(destination) != baseline.hash)))) {
                        throw SyncConflict("下载期间本地文件已修改")
                    }
                    val nextStamp = localStamp(copy.file)
                    if (exists) atomicReplace(copy.file, destination)
                    else try { Files.move(copy.file, destination) }
                    catch (_: FileAlreadyExistsException) { throw SyncConflict("本地已出现同名文件") }
                    saveState(stateFile, Baseline(track?.fingerprint ?: "", nextStamp, copy.hash))
                    downloaded++
                }
                fun push() {
                    val snapshot = Files.createTempFile(stateRoot, "upload-", ".part")
                    try {
                        if (localStamp(destination) != stamp) throw SyncConflict("本地文件正在修改")
                        Files.copy(destination, snapshot, REPLACE_EXISTING)
                        val hash = hashFile(snapshot)
                        if (localStamp(destination) != stamp || Files.size(snapshot) != Files.size(destination) || stableHash(destination, stamp) != hash) {
                            throw SyncConflict("本地文件正在修改")
                        }
                        val updated = client.upload(snapshot, path, track, baseline?.hash)
                        saveState(stateFile, Baseline(updated.fingerprint ?: "", stamp, hash))
                        uploaded++
                    } finally { Files.deleteIfExists(snapshot) }
                }
                when {
                    !exists && track != null -> pull()
                    exists && track == null -> push()
                    exists && track != null && baseline == null -> {
                        val hash = stableHash(destination, stamp!!)
                        if (hash != remoteCopy().hash) throw SyncConflict("首次同步发现两端同名文件内容不同")
                        saveState(stateFile, Baseline(track.fingerprint ?: "", stamp, hash))
                        unchanged++
                    }
                    exists && track != null && baseline != null -> {
                        val localHash = stableHash(destination, stamp!!)
                        val localChanged = localHash != baseline.hash
                        val remoteChanged = if (track.fingerprint != null && baseline.remote.isNotEmpty()) track.fingerprint != baseline.remote
                            else remoteCopy().hash != baseline.hash
                        when {
                            localChanged && remoteChanged -> {
                                if (localHash != remoteCopy().hash) throw SyncConflict("两端都已修改，请手动选择保留版本")
                                saveState(stateFile, Baseline(track.fingerprint ?: "", stamp, localHash))
                                unchanged++
                            }
                            localChanged -> push()
                            remoteChanged -> pull()
                            else -> {
                                val next = Baseline(track.fingerprint ?: "", stamp, baseline.hash)
                                if (next != baseline) saveState(stateFile, next)
                                unchanged++
                            }
                        }
                    }
                }
            } catch (failure: InterruptedException) {
                throw failure
            } catch (failure: SyncConflict) {
                conflicts += "$path：${failure.message}"
            } catch (failure: Exception) {
                checkCancelled()
                failures += "$path：${failure.message ?: "同步失败"}"
            } finally { fetched?.file?.let { Files.deleteIfExists(it) } }
        }
        return SyncResult(uploaded, downloaded, unchanged, conflicts, failures)
    }

    private fun download(track: RemoteTrack, parent: Path): Download {
        val temporary = Files.createTempFile(parent, ".webdav-", ".part")
        try {
            val hash = MessageDigest.getInstance("SHA-256")
            client.request(track.url).use { response ->
                WebDavClient.requireSuccess(response)
                val body = response.body ?: throw IOException("服务器返回空文件")
                var copied = 0L
                body.byteStream().use { input ->
                    Files.newOutputStream(temporary).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            checkCancelled()
                            val length = input.read(buffer)
                            if (length < 0) break
                            output.write(buffer, 0, length)
                            hash.update(buffer, 0, length)
                            copied += length
                        }
                    }
                }
                if ((track.size >= 0 && copied != track.size) || (body.contentLength() >= 0 && copied != body.contentLength())) {
                    throw IOException("下载文件大小不符，请重新同步")
                }
                val etag = response.header("ETag")
                if (!etag.isNullOrBlank() && track.etag.isNotBlank() && etag != track.etag) throw SyncConflict("下载期间远端文件已修改")
            }
            track.modified?.let { Files.setLastModifiedTime(temporary, FileTime.from(it)) }
            return Download(temporary, HexFormat.of().formatHex(hash.digest()))
        } catch (failure: Exception) {
            Files.deleteIfExists(temporary)
            throw failure
        }
    }

    private fun stableHash(file: Path, expected: String): String {
        if (localStamp(file) != expected) throw SyncConflict("本地文件正在修改")
        val hash = hashFile(file)
        if (localStamp(file) != expected) throw SyncConflict("本地文件正在修改")
        return hash
    }

    companion object {
        private fun localStamp(file: Path): String {
            if (!Files.isRegularFile(file, NOFOLLOW_LINKS)) throw IOException("本地目标不是普通文件")
            return "${Files.size(file)}:${Files.getLastModifiedTime(file).toMillis()}"
        }

        private fun hashFile(file: Path): String {
            val hash = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(file).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    checkCancelled()
                    val count = input.read(buffer)
                    if (count < 0) break
                    hash.update(buffer, 0, count)
                }
            }
            return HexFormat.of().formatHex(hash.digest())
        }

        internal fun safeDestination(root: Path, relative: String): Path {
            val parts = relative.split('/')
            if (parts.isEmpty() || parts.any { part ->
                part.isEmpty() || part == "." || part == ".." || part.endsWith('.') || part.endsWith(' ') ||
                    part.any { it.code < 32 || it in "\\:*?\"<>|" } ||
                    part.substringBefore('.').uppercase(Locale.ROOT).matches(Regex("CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]"))
            }) throw IOException("文件名无法安全同步")
            var parent = root
            for (part in parts.dropLast(1)) {
                val next = parent.resolve(part)
                if (Files.exists(next, NOFOLLOW_LINKS)) {
                    if (Files.isSymbolicLink(next) || !Files.isDirectory(next) || !next.toRealPath().startsWith(root)) {
                        throw IOException("同步路径经过符号链接或无效目录")
                    }
                } else Files.createDirectory(next)
                parent = next
            }
            val destination = parent.resolve(parts.last()).normalize()
            if (!destination.startsWith(root) || Files.isSymbolicLink(destination) ||
                (Files.exists(destination) && !destination.toRealPath().startsWith(root))) {
                throw IOException("同步路径超出本地音乐目录")
            }
            return destination
        }

        private fun atomicReplace(source: Path, target: Path) {
            try { Files.move(source, target, ATOMIC_MOVE, REPLACE_EXISTING) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(source, target, REPLACE_EXISTING) }
        }

        private fun readState(file: Path): Baseline? {
            if (!Files.exists(file)) return null
            val values = Properties()
            Files.newInputStream(file).use(values::load)
            val remote = values.getProperty("remote") ?: return null
            val local = values.getProperty("local") ?: return null
            val hash = values.getProperty("hash") ?: return null
            return Baseline(remote, local, hash)
        }

        private fun saveState(path: Path, baseline: Baseline) {
            val temporary = Files.createTempFile(path.parent, "state-", ".tmp")
            try {
                val values = Properties().apply {
                    setProperty("remote", baseline.remote)
                    setProperty("local", baseline.local)
                    setProperty("hash", baseline.hash)
                }
                Files.newOutputStream(temporary).use { values.store(it, "SaltPlayerWebDAV") }
                atomicReplace(temporary, path)
            } finally { Files.deleteIfExists(temporary) }
        }
    }
}
