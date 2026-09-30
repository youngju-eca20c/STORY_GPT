package com.storygpt.editor

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

class CoreTest {
    private val base = "a".repeat(40)
    private val current = "b".repeat(40)
    private val saved = "c".repeat(40)
    private val raw = "1화 — 시작\r\n\r\n본문은 그대로.\r\n\r\n두 번째 문단.\r\n"
    private val first = JSONObject().put("id", "001").put("title", "1화 — 시작")
        .put("file", "chapters/001.txt").put("published", "2026-09-15")
        .put("futureChapterField", JSONObject().put("key", 7))
    private fun meta(): JSONObject = JSONObject().put("title", "기존 작품")
        .put("worldbuilding", JSONObject().put("keep", true))
        .put("chapters", JSONArray().put(JSONObject(first.toString())).put(JSONObject()
            .put("id", "002").put("title", "원래 2화").put("file", "chapters/002.txt")))
    private fun novel() = Novel("test-novel", JSONObject().put("id", "test-novel").put("title", "기존 작품"), meta(), base)
    private fun draft() = Draft(first.getString("title"), bodyWithoutHeader(raw, first.getString("title")),
        baseHead = base, originalRaw = raw)

    @Test fun titleRemovalPreservesBodyLineEndingsAndWhitespace() {
        assertEquals("본문은 그대로.\r\n\r\n두 번째 문단.\r\n", bodyWithoutHeader(raw, "1화 — 시작"))
        assertEquals("  본문  \n", bodyWithoutHeader("\uFEFF1화 — 시작\n\n  본문  \n", "1화 — 시작"))
        assertEquals("전혀 다른 첫 문단\n\n본문\n", bodyWithoutHeader("전혀 다른 첫 문단\n\n본문\n", "1화 — 시작"))
        assertEquals("", bodyWithoutHeader("1화 — 시작\r\n", "1화 — 시작"))
    }
    @Test fun existingPublicationDatesAreNotVisibilityFlags() {
        assertEquals("public", Chapter("001", first).visibility)
        assertEquals("private", Chapter("001", JSONObject(first.toString()).put("visibility", "private")).visibility)
        assertEquals("2026-09-15", first.getString("published"))
    }
    @Test fun unchangedProseIsByteIdenticalAndMetadataIsPreserved() {
        val fake = FakeGit(meta(), meta(), base)
        val result = Repository(Settings(token = "test-only-placeholder"), null, fake::connection)
            .saveChapter(novel(), Chapter("001", first), draft().copy(visibility = "private"))
        assertEquals(saved, result.baseHead)
        assertFalse(result.changed)
        assertArrayEquals(raw.toByteArray(Charsets.UTF_8), fake.written.getValue("novels/test-novel/chapters/001.txt"))
        val writtenMeta = JSONObject(String(fake.written.getValue("novels/test-novel/meta.json"), Charsets.UTF_8))
        assertTrue(writtenMeta.getJSONObject("worldbuilding").getBoolean("keep"))
        val chapter = writtenMeta.getJSONArray("chapters").getJSONObject(0)
        assertEquals(7, chapter.getJSONObject("futureChapterField").getInt("key"))
        assertEquals("2026-09-15", chapter.getString("published"))
        assertEquals("private", chapter.getString("visibility"))
        assertFalse(fake.patch!!.getBoolean("force"))
        assertEquals(base, fake.commitParent)
    }
    @Test fun unrelatedChapterChangesAreMergedIntoCurrentSnapshot() {
        val latest = meta().also { it.getJSONArray("chapters").getJSONObject(1).put("title", "다른 기기에서 저장한 2화") }
        val fake = FakeGit(meta(), latest, current)
        Repository(Settings(token = "test-only-placeholder"), null, fake::connection)
            .saveChapter(novel(), Chapter("001", first), draft().copy(body = "새 본문"))
        val merged = JSONObject(String(fake.written.getValue("novels/test-novel/meta.json"), Charsets.UTF_8))
        assertEquals("다른 기기에서 저장한 2화", merged.getJSONArray("chapters").getJSONObject(1).getString("title"))
        assertEquals(current, fake.commitParent)
        assertEquals("1화 — 시작\n\n새 본문", String(fake.written.getValue("novels/test-novel/chapters/001.txt"), Charsets.UTF_8))
    }
    @Test fun overlappingChapterEditIsRejectedBeforeCreatingAnyObjects() {
        val latest = meta().also { it.getJSONArray("chapters").getJSONObject(0).put("title", "충돌하는 제목") }
        val fake = FakeGit(meta(), latest, current)
        try {
            Repository(Settings(token = "test-only-placeholder"), null, fake::connection)
                .saveChapter(novel(), Chapter("001", first), draft().copy(body = "로컬 원고"))
            fail("Expected conflict")
        } catch (failure: EditorException) { assertTrue(failure.message!!.contains("로컬 원고는 보존")) }
        assertTrue(fake.written.isEmpty())
        assertNull(fake.patch)
    }
    @Test fun readerBranchesCannotBeWritten() {
        val fake = FakeGit(meta(), meta(), base)
        for (branch in listOf("main", "master", "gh-pages")) {
            try {
                Repository(Settings(token = "test-only-placeholder", branch = branch), null, fake::connection).createNovel("작품", "", "", "1화", "본문")
                fail("Expected protected branch failure")
            } catch (failure: EditorException) { assertTrue(failure.message!!.contains("저장할 수 없습니다")) }
        }
        assertEquals(0, fake.calls)
    }
    @Test fun tokenIsRedactedInSettingsRepresentation() {
        assertFalse(Settings(token = "private-test-value").toString().contains("private-test-value"))
    }
    @Test fun newChaptersAndNovelsDefaultToPrivate() {
        val chapterGit = FakeGit(meta(), meta(), base)
        val chapter = Repository(Settings(token = "test-only-placeholder"), null, chapterGit::connection)
            .createChapter(novel(), "새 회차", "원고")
        assertEquals("private", chapter.visibility)
        val novelGit = FakeGit(meta(), meta(), base)
        val created = Repository(Settings(token = "test-only-placeholder"), null, novelGit::connection)
            .createNovel("새 작품", "설명", "작가", "첫 회차", "본문")
        assertEquals("private", created.visibility)
        assertEquals("private", created.chapters.single().visibility)
        assertTrue(novelGit.written.containsKey("data/novels.json"))
        assertTrue(novelGit.written.containsKey("novels/${created.id}/meta.json"))
        assertTrue(novelGit.written.containsKey("novels/${created.id}/chapters/001.txt"))
        assertFalse(novelGit.patch!!.getBoolean("force"))
    }
    @Test fun publishCorrelatesItsRunAndVerifiesMarkerWithoutSendingTokenToReader() {
        val fake = FakeGit(meta(), meta(), base)
        val result = Repository(Settings(token = "test-only-placeholder"), null, fake::connection).publish(base)
        assertEquals(base, result.head)
        assertTrue(fake.publicationChecked)
        assertNotNull(fake.publicationRequestId)
        assertEquals("https://youngju-eca20c.github.io/STORY_GPT/#/", result.siteUrl)
    }
    @Test fun sharedEditorImageBytesArePreservedAndUnusedBytesAreDeletedAtomically() {
        for (shared in listOf(false, true)) {
            val fake = FakeGit(meta(), meta(), base)
            fake.imageInTree = true; fake.sharedImage = shared
            Repository(Settings(token = "test-only-placeholder"), null, fake::connection)
                .saveChapter(novel(), Chapter("001", first), draft().copy(deletedImagePaths = listOf(fake.imagePath)))
            assertEquals(!shared, fake.deleted.contains(fake.imagePath))
            assertTrue(fake.written.containsKey("novels/test-novel/meta.json"))
            assertTrue(fake.written.containsKey("novels/test-novel/chapters/001.txt"))
        }
    }
    @Test fun invalidTokenCannotBeAttachedAsAnHttpHeader() {
        val fake = FakeGit(meta(), meta(), base)
        try {
            Repository(Settings(token = "private\nvalue"), null, fake::connection).currentHead()
            fail("Expected invalid token rejection")
        } catch (failure: EditorException) { assertFalse(failure.message!!.contains("private")) }
        assertEquals(0, fake.calls)
    }

    private inner class FakeGit(private val oldMeta: JSONObject, private val newMeta: JSONObject, private val liveHead: String) {
        val written = linkedMapOf<String, ByteArray>()
        private val blobs = mutableMapOf<String, ByteArray>()
        val deleted = mutableSetOf<String>()
        val imagePath = "illust/illustration-11111111-1111-4111-8111-111111111111.jpg"
        var imageInTree = false
        var sharedImage = false
        var publicationRequestId: String? = null
        var publicationChecked = false
        var patch: JSONObject? = null
        var commitParent: String? = null
        var calls = 0
        fun connection(url: URL): HttpURLConnection = object : HttpURLConnection(url) {
            private val output = ByteArrayOutputStream()
            private var response: ByteArray? = null
            override fun connect() {}
            override fun disconnect() {}
            override fun usingProxy() = false
            override fun setRequestMethod(value: String) { method = value }
            override fun getOutputStream(): OutputStream = output
            override fun getResponseCode(): Int {
                if (response == null) {
                    calls++
                    assertFalse(instanceFollowRedirects)
                    if (url.host == "api.github.com") assertEquals("Bearer test-only-placeholder", getRequestProperty("Authorization"))
                    else {
                        assertEquals("youngju-eca20c.github.io", url.host)
                        assertNull(getRequestProperty("Authorization"))
                    }
                    val path = url.path.removePrefix("/repos/youngju-eca20c/STORY_GPT")
                    val payload = if (output.size() == 0) null else JSONObject(output.toString("UTF-8"))
                    val text = when {
                        requestMethod == "GET" && path == "/git/ref/heads/editor-drafts" ->
                            JSONObject().put("object", JSONObject().put("sha", liveHead)).toString()
                        requestMethod == "GET" && path == "/contents/novels/test-novel/meta.json" ->
                            if (url.query.endsWith(base)) oldMeta.toString() else newMeta.toString()
                        requestMethod == "GET" && path == "/contents/novels/test-novel/chapters/001.txt" -> raw
                        requestMethod == "GET" && path == "/contents/data/novels.json" -> JSONObject().put("novels", JSONArray()).toString()
                        requestMethod == "GET" && path == "/contents/novels/test-novel/worldbuilding.json" ->
                            if (sharedImage) JSONObject().put("image", imagePath).toString() else "{}"
                        requestMethod == "GET" && path == "/git/commits/$liveHead" ->
                            JSONObject().put("tree", JSONObject().put("sha", "tree-old")).toString()
                        requestMethod == "GET" && path == "/git/trees/tree-old" ->
                            JSONObject().put("truncated", false).put("tree", JSONArray().apply {
                                listOf("novels/test-novel/meta.json", "novels/test-novel/chapters/001.txt", "novels/test-novel/worldbuilding.json")
                                    .plus(if (imageInTree) listOf(imagePath) else emptyList()).forEach { file ->
                                        put(JSONObject().put("path", file).put("type", "blob").put("size", 200))
                                    }
                            }).toString()
                        requestMethod == "POST" && path == "/git/blobs" -> {
                            val id = "blob-${blobs.size}"
                            blobs[id] = Base64.getDecoder().decode(payload!!.getString("content"))
                            JSONObject().put("sha", id).toString()
                        }
                        requestMethod == "POST" && path == "/git/trees" -> {
                            assertEquals("tree-old", payload!!.getString("base_tree"))
                            val tree = payload.getJSONArray("tree")
                            for (i in 0 until tree.length()) {
                                val item = tree.getJSONObject(i)
                                if (item.isNull("sha")) deleted.add(item.getString("path"))
                                else written[item.getString("path")] = blobs.getValue(item.getString("sha"))
                            }
                            JSONObject().put("sha", "tree-new").toString()
                        }
                        requestMethod == "POST" && path == "/git/commits" -> {
                            commitParent = payload!!.getJSONArray("parents").getString(0)
                            JSONObject().put("sha", saved).toString()
                        }
                        requestMethod == "PATCH" && path == "/git/refs/heads/editor-drafts" -> {
                            patch = payload
                            JSONObject().put("object", JSONObject().put("sha", saved)).toString()
                        }
                        requestMethod == "POST" && path == "/actions/workflows/publish-story.yml/dispatches" -> {
                            assertEquals("main", payload!!.getString("ref"))
                            val inputs = payload.getJSONObject("inputs")
                            assertEquals(base, inputs.getString("content_ref"))
                            assertEquals("editor-drafts", inputs.getString("content_branch"))
                            publicationRequestId = inputs.getString("request_id")
                            "{}"
                        }
                        requestMethod == "GET" && path == "/actions/workflows/publish-story.yml/runs" ->
                            JSONObject().put("workflow_runs", JSONArray().put(JSONObject().put("id", 42)
                                .put("display_title", "STORY GPT publication $publicationRequestId")
                                .put("html_url", "https://github.com/youngju-eca20c/STORY_GPT/actions/runs/42"))).toString()
                        requestMethod == "GET" && path == "/actions/runs/42" ->
                            JSONObject().put("status", "completed").put("conclusion", "success").toString()
                        requestMethod == "GET" && path == "/STORY_GPT/publication.json" -> {
                            publicationChecked = true
                            JSONObject().put("content_sha", base).put("request_id", publicationRequestId).toString()
                        }
                        else -> error("Unexpected fake request: $requestMethod $path")
                    }
                    response = text.toByteArray(Charsets.UTF_8)
                }
                return 200
            }
            override fun getInputStream(): InputStream {
                responseCode
                return ByteArrayInputStream(response!!)
            }
        }
    }
}
