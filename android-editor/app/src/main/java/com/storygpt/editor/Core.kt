package com.storygpt.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.Base64
import kotlin.math.roundToInt

/** The Android tool writes content on a draft branch; the reader continues to use its public site. */
data class Settings(
    val token: String = "",
    val owner: String = "youngju-eca20c",
    val repo: String = "STORY_GPT",
    val branch: String = "editor-drafts",
    val darkMode: Boolean = false,
    val autosave: Boolean = true,
    val optimizeImages: Boolean = true,
) {
    val scope: String get() = "$owner/$repo/$branch"
    val siteUrl: String get() = "https://$owner.github.io/$repo/#/"
    override fun toString(): String = "Settings(owner=$owner, repo=$repo, branch=$branch, token=[redacted])"
}

data class Chapter(val id: String, val json: JSONObject) {
    val title: String get() = json.optString("title", id)
    val file: String get() = json.optString("file", "chapters/$id.txt")
    // Existing `published` is a publication date, never a visibility boolean.
    val visibility: String get() = json.optString("visibility", "public")
}

data class Novel(val id: String, val index: JSONObject, val meta: JSONObject, val head: String) {
    private fun value(key: String, default: String = "") =
        if (meta.has(key)) meta.optString(key, default) else index.optString(key, default)
    val title: String get() = value("title", id)
    val description: String get() = value("description")
    val author: String get() = value("author")
    val cover: String get() = value("cover")
    val hero: String get() = value("representative")
    val visibility: String get() = value("visibility", "public")
    val chapters: List<Chapter> get() = meta.optJSONArray("chapters").objects().map {
        Chapter(it.getString("id"), clone(it))
    }
}

data class PendingImage(val path: String, val localPath: String, val mime: String)

data class Draft(
    val title: String,
    val body: String,
    val visibility: String = "public",
    val baseHead: String = "",
    val localSavedAt: Long = 0,
    val githubSavedAt: Long = 0,
    val originalTitle: String = title,
    val originalBody: String = body,
    val originalVisibility: String = visibility,
    val originalRaw: String = body,
    val pendingImages: List<PendingImage> = emptyList(),
    val deletedImagePaths: List<String> = emptyList(),
    val selectionStart: Int = 0,
) {
    val changed: Boolean get() = title != originalTitle || body != originalBody ||
        visibility != originalVisibility || pendingImages.isNotEmpty() || deletedImagePaths.isNotEmpty()
}

data class Version(val sha: String, val date: String, val message: String)
data class PublishResult(val head: String, val runUrl: String, val siteUrl: String)
class EditorException(message: String) : IOException(message)

private fun clone(value: JSONObject) = JSONObject(value.toString())
private fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }
private fun encoded(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
private fun today(): String = DateTimeFormatter.ISO_LOCAL_DATE.format(Instant.now().atZone(ZoneId.of("Asia/Seoul")))

/** AtomicFile includes a backup journal. Drafts and images survive process death and device restart. */
class LocalStore(private val context: Context) {
    private val preferences = context.getSharedPreferences("story-editor-settings", Context.MODE_PRIVATE)
    private val root = File(context.noBackupFilesDir, "editor").apply { mkdirs() }
    fun settings() = Settings(
        token = preferences.getString("token", "") ?: "",
        owner = preferences.getString("owner", "youngju-eca20c") ?: "youngju-eca20c",
        repo = preferences.getString("repo", "STORY_GPT") ?: "STORY_GPT",
        branch = preferences.getString("branch", "editor-drafts") ?: "editor-drafts",
        darkMode = preferences.getBoolean("darkMode", false),
        autosave = preferences.getBoolean("autosave", true),
        optimizeImages = preferences.getBoolean("optimizeImages", true),
    )
    fun saveSettings(settings: Settings) {
        val ok = preferences.edit().putString("token", settings.token.trim())
            .putString("owner", settings.owner.trim()).putString("repo", settings.repo.trim())
            .putString("branch", settings.branch.trim()).putBoolean("darkMode", settings.darkMode)
            .putBoolean("autosave", settings.autosave).putBoolean("optimizeImages", settings.optimizeImages).commit()
        if (!ok) throw EditorException("기기 설정을 저장하지 못했습니다. 저장 공간을 확인해 주세요.")
    }
    private fun scoped(settings: Settings) = File(root, digest(settings.scope)).apply { mkdirs() }
    private fun draftFile(settings: Settings, novelId: String, chapterId: String) =
        File(scoped(settings), "draft-${digest("$novelId/$chapterId")}.json")
    private fun read(file: File): String? = try {
        AtomicFile(file).openRead().use { String(it.readBytes(), Charsets.UTF_8) }
    } catch (_: java.io.FileNotFoundException) { null }
    private fun write(file: File, bytes: ByteArray) {
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try { stream.write(bytes); atomic.finishWrite(stream) }
        catch (failure: Exception) { atomic.failWrite(stream); throw failure }
    }
    @Synchronized fun draft(settings: Settings, novelId: String, chapterId: String): Draft? {
        val raw = read(draftFile(settings, novelId, chapterId)) ?: return null
        try {
            val j = JSONObject(raw)
            return Draft(j.getString("title"), j.getString("body"), j.optString("visibility", "public"),
                j.optString("baseHead"), j.optLong("localSavedAt"), j.optLong("githubSavedAt"),
                j.optString("originalTitle", j.getString("title")), j.optString("originalBody", j.getString("body")),
                j.optString("originalVisibility", j.optString("visibility", "public")),
                j.optString("originalRaw", j.getString("body")),
                j.optJSONArray("pendingImages").objects().map {
                    PendingImage(it.getString("path"), it.getString("localPath"), it.getString("mime"))
                }, j.optJSONArray("deletedImagePaths")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
                j.optInt("selectionStart"))
        } catch (_: Exception) {
            throw EditorException("기기에 저장된 원고를 읽지 못했습니다. 원본 파일은 보존되어 있습니다.")
        }
    }
    @Synchronized fun saveDraft(settings: Settings, novelId: String, chapterId: String, draft: Draft): Draft {
        val saved = draft.copy(localSavedAt = System.currentTimeMillis())
        val j = JSONObject().put("title", saved.title).put("body", saved.body).put("visibility", saved.visibility)
            .put("baseHead", saved.baseHead).put("localSavedAt", saved.localSavedAt).put("githubSavedAt", saved.githubSavedAt)
            .put("originalTitle", saved.originalTitle).put("originalBody", saved.originalBody)
            .put("originalVisibility", saved.originalVisibility).put("originalRaw", saved.originalRaw)
            .put("selectionStart", saved.selectionStart).put("deletedImagePaths", JSONArray(saved.deletedImagePaths))
            .put("pendingImages", JSONArray().also { a -> saved.pendingImages.forEach {
                a.put(JSONObject().put("path", it.path).put("localPath", it.localPath).put("mime", it.mime))
            } })
        write(draftFile(settings, novelId, chapterId), j.toString().toByteArray(Charsets.UTF_8))
        return saved
    }
    @Synchronized fun deleteDraft(settings: Settings, novelId: String, chapterId: String) {
        AtomicFile(draftFile(settings, novelId, chapterId)).delete()
    }
    @Synchronized fun githubSavedAt(settings: Settings, novelId: String, chapterId: String): Long = try {
        JSONObject(read(File(scoped(settings), "github-times.json")) ?: "{}").optLong("$novelId/$chapterId")
    } catch (_: Exception) { 0 }
    @Synchronized fun rememberGithubSave(settings: Settings, novelId: String, chapterId: String, time: Long) {
        val file = File(scoped(settings), "github-times.json")
        val times = try { JSONObject(read(file) ?: "{}") } catch (_: Exception) { JSONObject() }
        times.put("$novelId/$chapterId", time)
        write(file, times.toString().toByteArray(Charsets.UTF_8))
    }
    fun cacheLibrary(settings: Settings, novels: List<Novel>) {
        val j = JSONArray().also { a -> novels.forEach { n -> a.put(JSONObject().put("id", n.id)
            .put("index", n.index).put("meta", n.meta).put("head", n.head)) } }
        write(File(scoped(settings), "library.json"), j.toString().toByteArray(Charsets.UTF_8))
    }
    fun cachedLibrary(settings: Settings): List<Novel> = try {
        JSONArray(read(File(scoped(settings), "library.json")) ?: "[]").objects().map {
            Novel(it.getString("id"), it.getJSONObject("index"), it.getJSONObject("meta"), it.getString("head"))
        }
    } catch (_: Exception) { emptyList() }
    fun hasUnsavedDrafts(settings: Settings): Boolean = scoped(settings).listFiles().orEmpty()
        .filter { it.name.startsWith("draft-") && (it.name.endsWith(".json") || it.name.endsWith(".json.bak")) }
        .map { if (it.name.endsWith(".bak")) File(it.parentFile, it.name.removeSuffix(".bak")) else it }.distinct()
        .any { file ->
            try {
                val j = JSONObject(read(file) ?: return@any false)
                j.optString("title") != j.optString("originalTitle") ||
                    j.optString("body") != j.optString("originalBody") ||
                    j.optString("visibility", "public") != j.optString("originalVisibility", "public") ||
                    (j.optJSONArray("pendingImages")?.length() ?: 0) > 0 ||
                    (j.optJSONArray("deletedImagePaths")?.length() ?: 0) > 0
            } catch (_: Exception) { true } // A damaged draft must not silently bypass the save check.
        }
    fun stageImage(settings: Settings, uri: Uri, novelId: String, kind: String = "illustration"): PendingImage {
        val mime = context.contentResolver.getType(uri) ?: throw EditorException("이미지 형식을 확인할 수 없습니다.")
        val extension = when (mime) {
            "image/jpeg" -> "jpg"; "image/png" -> "png"; "image/webp" -> "webp"
            else -> throw EditorException("JPEG, PNG, WebP 이미지를 선택해 주세요.")
        }
        val data = context.contentResolver.openInputStream(uri)?.use { stream ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = stream.read(buffer)
                if (count == -1) break
                if (output.size() + count > 25 * 1024 * 1024) throw EditorException("25MB 이하의 이미지를 선택해 주세요.")
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } ?: throw EditorException("선택한 이미지를 열 수 없습니다.")
        val bounds = BitmapFactory.Options().also { it.inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw EditorException("손상된 이미지이거나 지원하지 않는 이미지입니다.")
        var result = data
        if (settings.optimizeImages && maxOf(bounds.outWidth, bounds.outHeight) > 2400) {
            val options = BitmapFactory.Options().also { it.inSampleSize = 1 }
            while (maxOf(bounds.outWidth, bounds.outHeight) / options.inSampleSize > 3200) options.inSampleSize *= 2
            val decoded = BitmapFactory.decodeByteArray(data, 0, data.size, options)
                ?: throw EditorException("이미지를 처리할 수 없습니다.")
            val orientation = try {
                @Suppress("DEPRECATION")
                ExifInterface(java.io.ByteArrayInputStream(data)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } catch (_: IOException) { ExifInterface.ORIENTATION_NORMAL }
            val matrix = exifOrientationMatrix(orientation)
            val bitmap = if (matrix.isIdentity) decoded else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            if (bitmap !== decoded) decoded.recycle()
            val ratio = minOf(1.0, 2400.0 / maxOf(bitmap.width, bitmap.height))
            val scaled = Bitmap.createScaledBitmap(bitmap, maxOf(1, (bitmap.width * ratio).roundToInt()),
                maxOf(1, (bitmap.height * ratio).roundToInt()), true)
            val output = java.io.ByteArrayOutputStream()
            @Suppress("DEPRECATION") val format = when (extension) {
                "png" -> Bitmap.CompressFormat.PNG; "webp" -> Bitmap.CompressFormat.WEBP; else -> Bitmap.CompressFormat.JPEG
            }
            if (!scaled.compress(format, 92, output)) throw EditorException("이미지를 처리할 수 없습니다.")
            result = output.toByteArray()
            if (scaled !== bitmap) scaled.recycle()
            bitmap.recycle()
        }
        val name = "${kind}-${UUID.randomUUID()}.$extension"
        val path = if (kind == "illustration") "illust/$name" else "novels/${safeId(novelId)}/$name"
        val file = File(scoped(settings), name)
        write(file, result)
        return PendingImage(path, file.absolutePath, mime)
    }
    fun pendingBytes(image: PendingImage): ByteArray {
        val file = File(image.localPath).canonicalFile
        if (!file.path.startsWith(root.canonicalPath + File.separator)) throw EditorException("기기 이미지 경로가 잘못되었습니다.")
        return try { AtomicFile(file).openRead().use { stream -> stream.readBytes() } }
        catch (_: java.io.FileNotFoundException) { throw EditorException("기기에 저장된 이미지를 찾을 수 없습니다. 다시 선택해 주세요.") }
    }
}

private fun safeId(value: String): String {
    if (!value.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]*")) || value == "." || value == "..")
        throw EditorException("작품 또는 회차 식별자가 잘못되었습니다.")
    return value
}

/** Blocking API. Call from Dispatchers.IO. Never prints headers, tokens, or response bodies. */
class Repository(val settings: Settings, private val store: LocalStore? = null,
                 private val connectionFactory: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }) {
    private val prefix: String
    private var latestHead: String = ""
    val lastSavedHead: String get() = latestHead
    private val immutableContentCache = mutableMapOf<String, ByteArray?>()
    init {
        if (!settings.owner.matches(Regex("[A-Za-z0-9][A-Za-z0-9-]*")) ||
            !settings.repo.matches(Regex("[A-Za-z0-9_.-]+"))) throw EditorException("GitHub 사용자명 또는 저장소 이름을 확인해 주세요.")
        if (settings.branch.isBlank() || settings.branch.contains("..") || settings.branch.contains(Regex("[\\s~^:?*\\[\\\\]")))
            throw EditorException("브랜치 이름을 확인해 주세요.")
        prefix = "/repos/${encoded(settings.owner)}/${encoded(settings.repo)}"
    }
    private data class Response(val status: Int, val bytes: ByteArray)
    private fun request(method: String, path: String, payload: JSONObject? = null, raw: Boolean = false,
                        allowMissing: Boolean = false): Response {
        if (settings.token.isBlank()) throw EditorException("설정에서 GitHub Token을 입력해 주세요.")
        if (settings.token.trim().any { it.isWhitespace() || it.code < 32 || it.code == 127 })
            throw EditorException("GitHub Token에는 공백이나 줄바꿈을 넣을 수 없습니다. 설정에서 다시 입력해 주세요.")
        val url = URL("https://api.github.com$prefix$path")
        check(url.protocol == "https" && url.host == "api.github.com")
        val connection = connectionFactory(url)
        connection.instanceFollowRedirects = false // Never forward the credential to another host.
        connection.connectTimeout = 20_000
        connection.readTimeout = 35_000
        connection.requestMethod = method
        connection.setRequestProperty("Authorization", "Bearer ${settings.token.trim()}")
        connection.setRequestProperty("Accept", if (raw) "application/vnd.github.raw+json" else "application/vnd.github+json")
        connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        connection.setRequestProperty("User-Agent", "STORY-GPT-Editor")
        try {
            if (payload != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val bytes = (if (status in 200..299) connection.inputStream else connection.errorStream)?.use { it.readBytes() }
                ?: ByteArray(0)
            if (status !in 200..299 && !(allowMissing && status == 404)) {
                val message = when (status) {
                    401 -> "GitHub Token이 유효하지 않습니다. 설정에서 다시 입력해 주세요."
                    403 -> if (connection.getHeaderField("X-RateLimit-Remaining") == "0")
                        "GitHub API 사용 한도에 도달했습니다. 잠시 후 다시 시도해 주세요."
                        else "GitHub 권한이 부족합니다. Contents 및 Actions 쓰기 권한을 확인해 주세요."
                    404 -> "GitHub 저장소, 브랜치 또는 파일을 찾을 수 없습니다. 연결 설정과 Token 접근 권한을 확인해 주세요."
                    409, 422 -> "GitHub의 내용이 변경되었거나 요청이 거절되었습니다. 로컬 원고는 유지됩니다. 다시 불러온 뒤 변경사항을 확인해 주세요."
                    429 -> "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요."
                    in 500..599 -> "GitHub 서버 오류입니다. 로컬 원고는 유지됩니다. 잠시 후 다시 시도해 주세요."
                    else -> "GitHub 요청에 실패했습니다 (HTTP $status). 로컬 원고는 유지됩니다."
                }
                throw EditorException(message)
            }
            return Response(status, bytes)
        } catch (failure: EditorException) { throw failure }
        catch (_: java.net.SocketTimeoutException) { throw EditorException("GitHub 응답 시간이 초과되었습니다. 로컬 원고는 유지됩니다.") }
        catch (_: IOException) { throw EditorException("GitHub에 연결할 수 없습니다. 인터넷 연결을 확인해 주세요. 로컬 원고는 유지됩니다.") }
        finally { connection.disconnect() }
    }
    private fun json(method: String, path: String, payload: JSONObject? = null): JSONObject =
        JSONObject(String(request(method, path, payload).bytes, Charsets.UTF_8))
    private fun content(path: String, ref: String, missing: Boolean = false): ByteArray? {
        validatePath(path)
        val cacheKey = "$ref/$path"
        val immutableRef = ref.matches(Regex("[0-9a-f]{40}"))
        if (immutableRef && immutableContentCache.containsKey(cacheKey)) {
            val cached = immutableContentCache[cacheKey]
            if (cached == null && !missing) throw EditorException("GitHub에서 해당 콘텐츠 파일을 찾을 수 없습니다.")
            return cached
        }
        val response = request("GET", "/contents/${path.split('/').joinToString("/") { encoded(it) }}?ref=${encoded(ref)}",
            raw = true, allowMissing = missing)
        val result = if (response.status == 404) null else response.bytes
        if (immutableRef) immutableContentCache[cacheKey] = result
        return result
    }
    fun readText(path: String, ref: String = settings.branch): String = String(content(path, ref)!!, Charsets.UTF_8)
    fun readImage(path: String): ByteArray {
        validatePath(path)
        if (!path.matches(Regex("(?:novels/[^/]+/[^/]+|illust/.+)\\.(?:jpg|jpeg|png|webp)", RegexOption.IGNORE_CASE)))
            throw EditorException("이미지 경로가 올바르지 않습니다.")
        return content(path, settings.branch)!!
    }
    private fun head(branch: String = settings.branch): String =
        json("GET", "/git/ref/heads/${branch.split('/').joinToString("/") { encoded(it) }}").getJSONObject("object").getString("sha")
    private fun writable() {
        if (settings.branch == "main" || settings.branch == "master" || settings.branch == "gh-pages")
            throw EditorException("독자 사이트를 보호하기 위해 main/master/gh-pages에는 저장할 수 없습니다. editor-drafts 브랜치를 사용해 주세요.")
    }
    fun connect(): List<Novel> {
        json("GET", "")
        writable()
        val response = request("GET", "/git/ref/heads/${settings.branch.split('/').joinToString("/") { encoded(it) }}", allowMissing = true)
        if (response.status == 404) {
            val main = head("main")
            json("POST", "/git/refs", JSONObject().put("ref", "refs/heads/${settings.branch}").put("sha", main))
        }
        return loadLibrary()
    }
    fun loadLibrary(): List<Novel> {
        val snapshot = head()
        val index = JSONObject(readText("data/novels.json", snapshot))
        val result = index.optJSONArray("novels").objects().map { item ->
            val id = safeId(item.getString("id"))
            Novel(id, clone(item), JSONObject(readText("novels/$id/meta.json", snapshot)), snapshot)
        }
        latestHead = snapshot
        store?.cacheLibrary(settings, result)
        return result
    }
    fun loadNovel(id: String): Novel = loadLibrary().firstOrNull { it.id == id }
        ?: throw EditorException("작품이 삭제되었거나 목록에서 찾을 수 없습니다.")
    fun loadDraft(novel: Novel, chapter: Chapter): Draft {
        val cached = store?.draft(settings, novel.id, chapter.id)
        val raw = readText(chapterPath(novel, chapter), novel.head)
        val body = bodyWithoutHeader(raw, chapter.title)
        val savedTime = try {
            val versions = request("GET", "/commits?sha=${encoded(novel.head)}&path=${encoded(chapterPath(novel, chapter))}&per_page=1")
            JSONArray(String(versions.bytes, Charsets.UTF_8)).objects().firstOrNull()?.getJSONObject("commit")
                ?.getJSONObject("committer")?.getString("date")?.let { Instant.parse(it).toEpochMilli() } ?: 0
        } catch (_: Exception) { cached?.githubSavedAt ?: store?.githubSavedAt(settings, novel.id, chapter.id) ?: 0 }
        if (savedTime > 0) store?.rememberGithubSave(settings, novel.id, chapter.id, savedTime)
        val draft = Draft(chapter.title, body, chapter.visibility, novel.head,
            githubSavedAt = savedTime, originalRaw = raw)
        return draft
    }
    private fun chapterPath(novel: Novel, chapter: Chapter): String =
        "novels/${safeId(novel.id)}/${chapter.file}".also { validatePath(it) }

    private fun validatePath(path: String) {
        if (path.isBlank() || path.startsWith('/') || path.contains('\\') || path.split('/').any { it == ".." || it == "." || it.isEmpty() })
            throw EditorException("잘못된 콘텐츠 경로입니다.")
    }
    private fun allowedWrite(path: String) {
        validatePath(path)
        if (!(path == "data/novels.json" || path.startsWith("novels/") ||
                    (path.startsWith("illust/") && path.matches(Regex(".*\\.(jpg|jpeg|png|webp)", RegexOption.IGNORE_CASE)))))
            throw EditorException("콘텐츠 폴더 외에는 앱에서 수정할 수 없습니다.")
    }
    private fun conflict(): Nothing = throw EditorException("다른 기기 또는 GitHub에서 이 원고가 변경되었습니다. 로컬 원고는 보존했습니다. 최신 버전을 불러와 변경사항을 확인해 주세요.")

    /** Blob/tree/commit/ref sequence changes all metadata, prose, and images in one visible commit. */
    private fun commit(changes: Map<String, ByteArray?>, expectedHead: String, message: String,
                       requireExactHead: Boolean = false): String {
        writable()
        require(expectedHead.matches(Regex("[0-9a-f]{40}"))) { "Expected a saved Git revision" }
        changes.keys.forEach(::allowedWrite)
        val current = head()
        if (current != expectedHead) {
            if (requireExactHead) conflict() // A prior reference scan must still describe this entire snapshot.
            // Unrelated changes may be merged, but never overwrite a changed touched file.
            changes.keys.forEach { path ->
                val old = content(path, expectedHead, true)
                val now = content(path, current, true)
                if (old == null && now != null || old != null && (now == null || !old.contentEquals(now))) conflict()
            }
        }
        val parent = json("GET", "/git/commits/$current")
        val tree = JSONArray()
        changes.forEach { (path, bytes) ->
            val item = JSONObject().put("path", path).put("mode", "100644").put("type", "blob")
            if (bytes == null) item.put("sha", JSONObject.NULL)
            else {
                val blob = json("POST", "/git/blobs", JSONObject().put("content", Base64.getEncoder().encodeToString(bytes)).put("encoding", "base64"))
                item.put("sha", blob.getString("sha"))
            }
            tree.put(item)
        }
        if (tree.length() == 0) return current
        val newTree = json("POST", "/git/trees", JSONObject().put("base_tree", parent.getJSONObject("tree").getString("sha")).put("tree", tree))
        val newCommit = json("POST", "/git/commits", JSONObject().put("message", message)
            .put("tree", newTree.getString("sha")).put("parents", JSONArray().put(current))).getString("sha")
        // force=false is the compare-and-swap: concurrent commits cannot be discarded.
        json("PATCH", "/git/refs/heads/${settings.branch.split('/').joinToString("/") { encoded(it) }}",
            JSONObject().put("sha", newCommit).put("force", false))
        latestHead = newCommit
        return newCommit
    }
    fun saveChapter(novel: Novel, chapter: Chapter, draft: Draft): Draft {
        val base = draft.baseHead.ifBlank { novel.head }
        val snapshot = head()
        val path = chapterPath(novel, chapter)
        val originalMeta = JSONObject(readText("novels/${novel.id}/meta.json", base))
        val meta = JSONObject(readText("novels/${novel.id}/meta.json", snapshot))
        val originalEntry = originalMeta.getJSONArray("chapters").objects().firstOrNull { it.optString("id") == chapter.id }
            ?: conflict()
        val chapters = meta.getJSONArray("chapters")
        val entry = chapters.objects().firstOrNull { it.optString("id") == chapter.id }
            ?: throw EditorException("회차가 삭제되었습니다. 로컬 원고는 유지됩니다.")
        if (canonical(originalEntry) != canonical(entry)) conflict()
        val oldText = content(path, base, true)
        val nowText = content(path, snapshot, true)
        if (oldText == null || nowText == null || !oldText.contentEquals(nowText)) conflict()
        entry.put("title", draft.title).put("visibility", validatedVisibility(draft.visibility)).put("updated", today())
        val raw = if (draft.title == draft.originalTitle && draft.body == draft.originalBody) draft.originalRaw
            else draft.title.trim() + "\n\n" + draft.body
        val changes = linkedMapOf<String, ByteArray?>(path to raw.toByteArray(Charsets.UTF_8),
            "novels/${novel.id}/meta.json" to meta.bytes())
        draft.pendingImages.forEach { image ->
            if (draft.body.contains("](${image.path})")) changes[image.path] = store?.pendingBytes(image)
                ?: throw EditorException("기기 이미지 저장소가 없습니다.")
        }
        pruneUnusedEditorImages(draft.deletedImagePaths, changes, snapshot)
        val savedHead = commit(changes, snapshot,
            "Update ${novel.title} episode ${chapter.id} from STORY GPT Editor", requireExactHead = changes.values.any { it == null })
        val saved = draft.copy(baseHead = savedHead, githubSavedAt = System.currentTimeMillis(),
            originalTitle = draft.title, originalBody = draft.body, originalVisibility = draft.visibility,
            originalRaw = raw, pendingImages = emptyList(), deletedImagePaths = emptyList())
        store?.rememberGithubSave(settings, novel.id, chapter.id, saved.githubSavedAt)
        return store?.saveDraft(settings, novel.id, chapter.id, saved) ?: saved
    }
    fun saveNovel(novel: Novel, title: String, description: String, author: String, visibility: String,
                  cover: PendingImage? = null, hero: PendingImage? = null,
                  removeCover: Boolean = false, removeHero: Boolean = false): Novel {
        val snapshot = head()
        val root = JSONObject(readText("data/novels.json", snapshot))
        val entry = root.getJSONArray("novels").objects().firstOrNull { it.getString("id") == novel.id }
            ?: throw EditorException("작품이 삭제되었습니다.")
        val meta = JSONObject(readText("novels/${novel.id}/meta.json", snapshot))
        if (canonical(entry) != canonical(novel.index) ||
            canonical(clone(meta).apply { remove("chapters") }) != canonical(clone(novel.meta).apply { remove("chapters") })) conflict()
        listOf(entry, meta).forEach { j ->
            j.put("title", title.trim()).put("description", description).put("author", author)
                .put("visibility", validatedVisibility(visibility))
            if (removeCover) j.remove("cover")
            if (removeHero) j.remove("representative")
            if (cover != null) j.put("cover", imageFilename(cover, novel.id))
            if (hero != null) j.put("representative", imageFilename(hero, novel.id))
        }
        val changes = linkedMapOf<String, ByteArray?>("data/novels.json" to root.bytes(), "novels/${novel.id}/meta.json" to meta.bytes())
        listOfNotNull(cover, hero).forEach { changes[it.path] = store?.pendingBytes(it) ?: throw EditorException("기기 이미지 저장소가 없습니다.") }
        val removed = mutableListOf<String>()
        if ((removeCover || cover != null) && novel.cover.isNotBlank()) removed.add("novels/${novel.id}/${novel.cover}")
        if ((removeHero || hero != null) && novel.hero.isNotBlank()) removed.add("novels/${novel.id}/${novel.hero}")
        pruneUnusedEditorImages(removed, changes, snapshot)
        val newHead = commit(changes, snapshot, "Update ${title.trim()} information from STORY GPT Editor", requireExactHead = changes.values.any { it == null })
        return Novel(novel.id, clone(entry), meta, newHead)
    }
    fun createNovel(title: String, description: String, author: String, firstTitle: String, firstBody: String,
                    cover: PendingImage? = null, hero: PendingImage? = null): Novel {
        writable()
        val base = head()
        val id = "novel-${UUID.randomUUID().toString().take(12)}"
        val root = JSONObject(readText("data/novels.json", base))
        val index = JSONObject().put("id", id).put("title", title.trim()).put("description", description)
            .put("author", author).put("visibility", "private").put("tags", JSONArray()).put("status", "연재 중")
        val chapter = JSONObject().put("id", "001").put("title", firstTitle.trim()).put("file", "chapters/001.txt")
            .put("published", today()).put("visibility", "private")
        val meta = clone(index).apply { remove("id"); put("chapters", JSONArray().put(chapter)) }
        val changes = linkedMapOf<String, ByteArray?>()
        listOfNotNull(cover, hero).forEach { image ->
            // A newly selected cover may use a temporary novel id; relocate under the real new id.
            val name = image.path.substringAfterLast('/')
            val field = if (image === cover) "cover" else "representative"
            index.put(field, name); meta.put(field, name)
            changes["novels/$id/$name"] = store?.pendingBytes(image) ?: throw EditorException("기기 이미지 저장소가 없습니다.")
        }
        root.getJSONArray("novels").put(index)
        changes["data/novels.json"] = root.bytes()
        changes["novels/$id/meta.json"] = meta.bytes()
        changes["novels/$id/chapters/001.txt"] = (firstTitle.trim() + "\n\n" + firstBody).toByteArray(Charsets.UTF_8)
        val savedHead = commit(changes, base, "Create ${title.trim()} from STORY GPT Editor")
        return Novel(id, index, meta, savedHead)
    }
    fun createChapter(novel: Novel, title: String, body: String = ""): Chapter {
        val chapters = novel.chapters
        val number = (chapters.mapNotNull { it.id.toIntOrNull() }.maxOrNull() ?: 0) + 1
        var id = number.toString().padStart(3, '0')
        while (chapters.any { it.id == id }) id = "episode-${UUID.randomUUID().toString().take(8)}"
        val item = JSONObject().put("id", id).put("title", title.trim()).put("file", "chapters/$id.txt")
            .put("published", today()).put("visibility", "private")
        val meta = clone(novel.meta)
        meta.getJSONArray("chapters").put(item)
        commit(linkedMapOf("novels/${novel.id}/meta.json" to meta.bytes(),
            "novels/${novel.id}/chapters/$id.txt" to (title.trim() + "\n\n" + body).toByteArray(Charsets.UTF_8)),
            novel.head, "Create ${novel.title} episode $id from STORY GPT Editor")
        return Chapter(id, item)
    }
    fun deleteChapter(novel: Novel, chapter: Chapter) {
        val meta = clone(novel.meta)
        meta.put("chapters", JSONArray().also { a -> novel.chapters.filter { it.id != chapter.id }.forEach { a.put(it.json) } })
        commit(linkedMapOf("novels/${novel.id}/meta.json" to meta.bytes(), chapterPath(novel, chapter) to null),
            novel.head, "Delete ${novel.title} episode ${chapter.id} from STORY GPT Editor")
        store?.deleteDraft(settings, novel.id, chapter.id)
    }
    fun reorderChapters(novel: Novel, order: List<String>) {
        val chapters = novel.chapters.associateBy { it.id }
        if (order.size != chapters.size || order.toSet() != chapters.keys) throw EditorException("회차 순서가 올바르지 않습니다.")
        val meta = clone(novel.meta).put("chapters", JSONArray().also { a -> order.forEach { a.put(chapters.getValue(it).json) } })
        commit(mapOf("novels/${novel.id}/meta.json" to meta.bytes()), novel.head,
            "Reorder ${novel.title} episodes from STORY GPT Editor")
    }
    fun deleteNovel(novel: Novel) {
        val root = JSONObject(readText("data/novels.json", novel.head))
        root.put("novels", JSONArray().also { a -> root.getJSONArray("novels").objects().filter { it.getString("id") != novel.id }.forEach { a.put(it) } })
        val changes = linkedMapOf<String, ByteArray?>("data/novels.json" to root.bytes())
        val treeSha = json("GET", "/git/commits/${novel.head}").getJSONObject("tree").getString("sha")
        val tree = json("GET", "/git/trees/$treeSha?recursive=1")
        if (tree.optBoolean("truncated")) throw EditorException("저장소 파일이 너무 많아 안전하게 작품을 삭제할 수 없습니다.")
        tree.getJSONArray("tree").objects().filter { it.optString("type") == "blob" && it.getString("path").startsWith("novels/${novel.id}/") }
            .forEach { changes[it.getString("path")] = null }
        commit(changes, novel.head, "Delete ${novel.title} from STORY GPT Editor")
        novel.chapters.forEach { store?.deleteDraft(settings, novel.id, it.id) }
    }
    fun versions(novel: Novel, chapter: Chapter): List<Version> {
        val response = request("GET", "/commits?sha=${encoded(settings.branch)}&path=${encoded(chapterPath(novel, chapter))}&per_page=30")
        return JSONArray(String(response.bytes, Charsets.UTF_8)).objects().map { j ->
            val c = j.getJSONObject("commit")
            Version(j.getString("sha"), c.getJSONObject("committer").getString("date"), c.getString("message"))
        }
    }
    fun versionDraft(novel: Novel, chapter: Chapter, version: Version): Draft {
        val raw = readText(chapterPath(novel, chapter), version.sha)
        val oldMeta = JSONObject(readText("novels/${novel.id}/meta.json", version.sha))
        val old = oldMeta.getJSONArray("chapters").objects().firstOrNull { it.optString("id") == chapter.id }
        val title = old?.optString("title") ?: chapter.title
        val current = readText(chapterPath(novel, chapter), novel.head)
        return Draft(title, bodyWithoutHeader(raw, title), old?.optString("visibility", "public") ?: "public", novel.head,
            originalTitle = chapter.title, originalBody = bodyWithoutHeader(current, chapter.title),
            originalVisibility = chapter.visibility, originalRaw = current)
    }
    fun currentHead(): String = head().also { latestHead = it }
    fun publish(head: String? = null, onStatus: (String) -> Unit = {}): PublishResult {
        writable()
        if (store?.hasUnsavedDrafts(settings) == true)
            throw EditorException("기기에 GitHub에 저장하지 않은 원고가 있습니다. 작성 중인 회차를 먼저 저장한 뒤 공개해 주세요.")
        val revision = head ?: currentHead()
        if (!revision.matches(Regex("[0-9a-f]{40}"))) throw EditorException("공개할 저장 버전이 올바르지 않습니다.")
        val requestId = UUID.randomUUID().toString()
        onStatus("배포 요청 중…")
        request("POST", "/actions/workflows/publish-story.yml/dispatches", JSONObject().put("ref", "main")
            .put("inputs", JSONObject().put("content_ref", revision).put("content_branch", settings.branch).put("request_id", requestId)))
        val deadline = System.currentTimeMillis() + 15 * 60 * 1000
        var runId: Long? = null
        var runUrl = ""
        while (System.currentTimeMillis() < deadline) {
            if (Thread.currentThread().isInterrupted) throw EditorException("공개 상태 확인이 중단되었습니다. GitHub Actions에서 진행 상황을 확인해 주세요.")
            if (runId == null) {
                val runs = json("GET", "/actions/workflows/publish-story.yml/runs?event=workflow_dispatch&branch=main&per_page=50")
                    .optJSONArray("workflow_runs").objects()
                val run = runs.firstOrNull { it.optString("display_title").contains(requestId) }
                if (run != null) { runId = run.getLong("id"); runUrl = run.optString("html_url") }
            }
            if (runId != null) {
                val run = json("GET", "/actions/runs/$runId")
                val status = run.optString("status")
                if (status == "completed") {
                    if (run.optString("conclusion") != "success")
                        throw EditorException("공개 작업이 ${run.optString("conclusion")} 상태로 종료되었습니다. GitHub Actions에서 오류를 확인해 주세요.")
                    onStatus("웹사이트 반영 확인 중…")
                    verifyPublication(revision, requestId)
                    onStatus("✓ 공개 완료")
                    return PublishResult(revision, runUrl, settings.siteUrl)
                }
                onStatus(if (status == "queued" || status == "waiting") "배포 대기 중…" else "배포 중…")
            } else onStatus("배포 요청 확인 중…")
            Thread.sleep(6000)
        }
        throw EditorException("배포 완료 확인 시간이 초과되었습니다. 작업이 계속 실행될 수 있으므로 GitHub Actions에서 확인해 주세요.")
    }
    private fun imageFilename(image: PendingImage, novelId: String): String {
        if (!image.path.startsWith("novels/$novelId/") || image.path.removePrefix("novels/$novelId/").contains('/'))
            throw EditorException("이 작품에서 선택한 표지 또는 대표 이미지를 사용해 주세요.")
        return image.path.substringAfterLast('/')
    }
    private fun validatedVisibility(value: String): String {
        if (value !in setOf("public", "private", "draft")) throw EditorException("공개 상태를 확인해 주세요.")
        return value
    }

    /** Only delete files created by this app, after checking the prospective snapshot for every reference. */
    private fun pruneUnusedEditorImages(candidates: List<String>, changes: MutableMap<String, ByteArray?>, snapshot: String) {
        val generated = Regex("(?:illust/illustration-|novels/[^/]+/(?:cover-|representative-))[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(?:jpg|png|webp)")
        val paths = candidates.distinct().filter { generated.matches(it) }
        if (paths.isEmpty()) return
        // If a complete reference check is unavailable, keep the bytes rather than damage another chapter.
        try {
            val treeSha = json("GET", "/git/commits/$snapshot").getJSONObject("tree").getString("sha")
            val tree = json("GET", "/git/trees/$treeSha?recursive=1")
            if (tree.optBoolean("truncated")) return
            val files = tree.getJSONArray("tree").objects().filter { it.optString("type") == "blob" }
            val existing = files.map { it.getString("path") }.toSet()
            val searchable = files.filter { file ->
                val path = file.getString("path")
                !path.matches(Regex(".*\\.(?:jpg|jpeg|png|webp|gif|ico|woff2?|ttf|otf|zip|jar|apk|keystore|pdf)", RegexOption.IGNORE_CASE))
            }
            // A file too large to inspect makes deletion uncertain.
            if (searchable.any { it.optLong("size") > 5 * 1024 * 1024 }) return
            val texts = searchable.mapNotNull { file ->
                val path = file.getString("path")
                val bytes = if (changes.containsKey(path)) changes[path] else content(path, snapshot)
                bytes?.let { String(it, Charsets.UTF_8) }
            } + changes.filterKeys { it !in existing && !generated.matches(it) }.values.mapNotNull { bytes ->
                bytes?.let { String(it, Charsets.UTF_8) }
            }
            paths.filter { it in existing }.forEach { path ->
                val filename = path.substringAfterLast('/')
                val needles = listOf(path, filename, encoded(path), encoded(filename))
                if (texts.none { text -> needles.any { text.contains(it) } }) changes[path] = null
            }
        } catch (_: Exception) { /* The reference is removed; uncertain/shared bytes stay safely stored. */ }
    }

    /** Reader readback uses an independent unauthenticated HTTPS request: the token never leaves api.github.com. */
    private fun verifyPublication(revision: String, requestId: String) {
        val deadline = System.currentTimeMillis() + 90_000
        while (System.currentTimeMillis() < deadline) {
            val url = URL("https://${settings.owner}.github.io/${encoded(settings.repo)}/publication.json?verify=${encoded(requestId)}-${System.currentTimeMillis()}")
            check(url.protocol == "https" && url.host == "${settings.owner}.github.io")
            val connection = connectionFactory(url)
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.requestMethod = "GET"
            connection.setRequestProperty("Cache-Control", "no-cache")
            connection.setRequestProperty("Accept", "application/json")
            try {
                if (connection.responseCode == 200) {
                    val marker = connection.inputStream.use { JSONObject(String(it.readBytes(), Charsets.UTF_8)) }
                    if (marker.optString("content_sha") == revision && marker.optString("request_id") == requestId) return
                }
            } catch (_: IOException) { /* CDN changes can take a short time to appear. */ }
            catch (_: org.json.JSONException) { /* Retry while an old/error document is still cached. */ }
            finally { connection.disconnect() }
            Thread.sleep(5000)
        }
        throw EditorException("GitHub 배포 작업은 완료되었지만 웹사이트의 최신 버전 확인에 실패했습니다. 공개 완료로 표시하지 않았습니다. 잠시 후 웹사이트 또는 GitHub Actions를 확인해 주세요.")
    }
}

private fun JSONObject.bytes() = (toString(2) + "\n").toByteArray(Charsets.UTF_8)

private fun canonical(value: Any?): String = when (value) {
    is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { key ->
        JSONObject.quote(key) + ":" + canonical(value.get(key))
    }
    is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
    is String -> JSONObject.quote(value)
    else -> value.toString()
}

/** Apply camera orientation before writing a resized image, whose EXIF metadata is no longer present. */
@Suppress("DEPRECATION")
internal fun exifOrientationMatrix(orientation: Int): Matrix = Matrix().apply {
    when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
        ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
        ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(270f); postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(270f)
    }
}

/** Only strip the first paragraph if it is the current chapter title, matching Views.parseParagraphs. */
fun bodyWithoutHeader(raw: String, title: String): String {
    val prefix = Regex("^([\\s\\S]*?)(\\r?\\n[ \\t]*\\r?\\n)").find(raw)
    return when {
        prefix != null && prefix.groupValues[1].removePrefix("\uFEFF").trim() == title.trim() -> raw.substring(prefix.range.last + 1)
        raw.removePrefix("\uFEFF").trim() == title.trim() -> ""
        else -> raw
    }
}
