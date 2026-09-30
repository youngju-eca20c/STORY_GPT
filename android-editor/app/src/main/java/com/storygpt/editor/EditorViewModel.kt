package com.storygpt.editor

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class EditorViewModel(application: Application) : AndroidViewModel(application) {
    val store = LocalStore(application)
    var settings by mutableStateOf(store.settings()); private set
    var screen by mutableStateOf("library"); private set
    var library by mutableStateOf<List<Novel>>(emptyList()); private set
    var novel by mutableStateOf<Novel?>(null); private set
    var chapter by mutableStateOf<Chapter?>(null); private set
    var draft by mutableStateOf<Draft?>(null); private set
    var bodyValue by mutableStateOf(TextFieldValue()); private set
    var busy by mutableStateOf(false); private set
    var status by mutableStateOf(""); private set
    var error by mutableStateOf<String?>(null); private set
    var previewHtml by mutableStateOf(""); private set
    var previewReturn = "chapter"
    var versions by mutableStateOf<List<Version>>(emptyList()); private set
    var selectedVersion by mutableStateOf<Draft?>(null); private set
    var selectedVersionInfo by mutableStateOf<Version?>(null); private set
    var publication by mutableStateOf<PublishResult?>(null); private set
    private var localJob: Job? = null
    private var revision = 0L
    private val imagePattern = Regex("!\\[([^\\]\\n]*)]\\((illust/[^)\\n]+)\\)")
    val hasChanges: Boolean get() = draft?.let {
        it.title != it.originalTitle || it.body != it.originalBody || it.visibility != it.originalVisibility || it.pendingImages.isNotEmpty() || it.deletedImagePaths.isNotEmpty()
    } ?: false
    val imageReferences: List<Pair<String,String>> get() = imagePattern.findAll(draft?.body.orEmpty()).map { it.value to it.groupValues[2] }.toList()
    init {
        library = store.cachedLibrary(settings)
        if (settings.token.isBlank()) screen = "settings" else refresh()
    }
    private fun repository() = Repository(settings, store)
    private fun rememberSavedNovel(value:Novel) {
        novel=value
        library=if(library.any {it.id==value.id})library.map {if(it.id==value.id)value else it} else library+value
        store.cacheLibrary(settings,library)
    }
    private suspend fun refreshSavedNovel(fallback:Novel):Novel = try {
        io { repository().loadNovel(fallback.id) }
    } catch(failure:Exception) {
        error="GitHub 저장은 완료되었습니다. 최신 목록을 다시 불러오지 못해 저장된 원고를 표시합니다. ${failure.message.orEmpty()}"
        fallback
    }
    private suspend fun refreshSavedLibrary() {
        try { library=io {repository().loadLibrary()} }
        catch(failure:Exception) {error="GitHub 변경은 저장되었습니다. 목록 새로고침에 실패했습니다. ${failure.message.orEmpty()}";store.cacheLibrary(settings,library)}
    }
    private fun chapterFallback(n:Novel,c:Chapter,d:Draft):Novel {
        val meta=JSONObject(n.meta.toString());val list=meta.getJSONArray("chapters")
        for(i in 0 until list.length())if(list.getJSONObject(i).optString("id")==c.id)list.getJSONObject(i).put("title",d.title).put("visibility",d.visibility)
        return n.copy(meta=meta,head=d.baseHead)
    }
    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }
    private fun task(message: String, work: suspend () -> Unit) {
        if (busy) return
        busy = true; error = null; status = message
        viewModelScope.launch {
            try { work() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "작업에 실패했습니다. 연결 상태를 확인해주세요."; status = "작업 실패" }
            finally { busy = false }
        }
    }
    fun clearError() { error = null }
    fun show(target: String) { flushLocal(); if (!busy) { screen = target; error = null } }
    fun configure(next: Settings) {
        flushLocal(); store.saveSettings(next); settings = next
        library = emptyList(); novel = null; chapter = null; draft = null; publication = null
        status = "설정을 저장했습니다. 연결 테스트로 작품을 불러오세요."
    }
    fun connect() = task("작품을 불러오는 중…") {
        val result = io { repository().connect() }
        library = result; screen = "library"; status = "✓ STORY_GPT Repository 연결 성공"
    }
    fun refresh() = task("작품을 불러오는 중…") {
        try {
            library = io { repository().loadLibrary() }; status = "최신 작품을 불러왔습니다."
        } catch (failure: Exception) {
            if (library.isEmpty()) throw failure
            status = "오프라인 서재 · 기기에 복구된 원고를 계속 작성할 수 있습니다."
            error = failure.message
        }
    }
    fun openNovel(item: Novel) = task("작품을 여는 중…") {
        novel = try { io { repository().loadNovel(item.id) } } catch (failure: Exception) {
            if (store.cachedLibrary(settings).none { it.id==item.id }) throw failure
            status = "오프라인 서재 · 저장은 인터넷 연결 후 가능합니다."
            item
        }
        screen = "novel"; chapter = null; draft = null
    }
    fun openChapter(item: Chapter) {
        val n = novel ?: return
        flushLocal()
        task("원고를 여는 중…") {
            val restored = io { store.draft(settings,n.id,item.id) }
            val loaded = if(restored?.changed==true)restored else try { io { repository().loadDraft(n,item) } } catch(failure:Exception) { restored ?: throw failure }
            chapter = item; draft = loaded
            bodyValue = TextFieldValue(loaded.body, TextRange(loaded.selectionStart.coerceIn(0,loaded.body.length)))
            screen = "chapter"; revision++
            status = if (restored != null) "기기에 저장된 작성 원고를 복구했습니다." else "원고를 불러왔습니다."
        }
    }
    fun updateTitle(text: String) { draft = draft?.copy(title=text); changed() }
    fun updateVisibility(value: String) { draft = draft?.copy(visibility=value); changed() }
    fun updateBody(value: TextFieldValue, normalize:Boolean=true) {
        val safeValue=if(normalize)normalizeIllustrationEdit(bodyValue,value) else value
        val contentChanged = bodyValue.text != safeValue.text
        bodyValue = safeValue; draft = draft?.copy(body=safeValue.text,selectionStart=safeValue.selection.start)
        if (contentChanged) changed()
    }
    private fun changed() {
        revision++; status = "수정 중 · 아직 GitHub에 저장하지 않았습니다."
        localJob?.cancel()
        if (settings.autosave) localJob = viewModelScope.launch { delay(1000); flushLocal() }
    }
    fun flushLocal() {
        localJob?.cancel(); localJob = null
        if (!settings.autosave) return
        val n=novel ?: return; val c=chapter ?: return; val d=draft ?: return
        try {
            val saved=d.copy(localSavedAt=System.currentTimeMillis(),selectionStart=bodyValue.selection.start)
            draft=store.saveDraft(settings,n.id,c.id,saved)
        } catch (e:Exception) { error = "기기 자동저장 실패: ${e.message}" }
    }
    fun saveChapter(after: (() -> Unit)? = null) {
        val n=novel ?: return; val c=chapter ?: return; val d=draft ?: return
        if (d.title.isBlank()) { error="회차 제목을 입력해주세요."; return }
        // An explicit save always records a recovery copy even if timed autosave is disabled.
        try { draft=store.saveDraft(settings,n.id,c.id,d.copy(selectionStart=bodyValue.selection.start)) }
        catch(failure:Exception){error="기기 복구 사본을 저장하지 못했습니다: ${failure.message}";return}
        val savedRevision=revision
        task("GitHub에 저장 중…") {
            val committed=io { repository().saveChapter(n,c,d) }
            if (savedRevision == revision) {
                draft=committed; bodyValue=bodyValue.copy(text=committed.body)
                io { store.deleteDraft(settings,n.id,c.id) }
            } else {
                draft=draft?.copy(baseHead=committed.baseHead,originalTitle=committed.originalTitle,originalBody=committed.originalBody,originalVisibility=committed.originalVisibility,originalRaw=committed.originalRaw,githubSavedAt=committed.githubSavedAt)
                flushLocal()
            }
            val fallback=chapterFallback(n,c,committed)
            rememberSavedNovel(fallback)
            val fresh=refreshSavedNovel(fallback)
            rememberSavedNovel(fresh);chapter=fresh.chapters.find {it.id==c.id} ?: c
            status="✓ GitHub에 저장되었습니다. 공개는 별도로 진행합니다."
            after?.invoke()
        }
    }
    fun discardLocalAndReload() {
        val n=novel ?: return; val c=chapter ?: return
        task("GitHub 원고를 다시 불러오는 중…") {
            val fresh=io { repository().loadNovel(n.id) }
            val found=fresh.chapters.find { it.id==c.id } ?: error("이 회차가 GitHub에서 삭제되었습니다.")
            val loaded=io { repository().loadDraft(fresh,found) }
            io { store.deleteDraft(settings,n.id,c.id) }
            novel=fresh; chapter=found; draft=loaded; bodyValue=TextFieldValue(loaded.body); revision++
            status="GitHub 원고를 불러왔습니다."
        }
    }
    fun stageImage(uri: Uri, kind: String, complete: (PendingImage) -> Unit) {
        val novelId=novel?.id ?: "new"
        task("사진을 준비하는 중…") {
            val image=io { store.stageImage(settings,uri,novelId,kind) }
            complete(image); status="사진 준비 완료 · 저장하면 GitHub에 함께 올라갑니다."
        }
    }
    fun insertIllustration(image: PendingImage, replacePath: String? = null) {
        val d=draft ?: return
        val marker="![일러스트](${image.path})"
        val value=if(replacePath!=null) {
            val match=imagePattern.findAll(d.body).firstOrNull { it.groupValues[2]==replacePath }
            if(match!=null) TextFieldValue(d.body.replaceRange(match.range,marker),TextRange(match.range.first+marker.length)) else bodyValue
        } else {
            val a=bodyValue.selection.min.coerceIn(0,d.body.length)
            val b=bodyValue.selection.max.coerceIn(a,d.body.length)
            val insertion="\n\n$marker\n\n"
            TextFieldValue(d.body.substring(0,a)+insertion+d.body.substring(b),TextRange(a+insertion.length))
        }
        draft=d.copy(pendingImages=d.pendingImages.filterNot { it.path==replacePath }+image,deletedImagePaths=if(replacePath!=null)(d.deletedImagePaths+replacePath).distinct() else d.deletedImagePaths)
        updateBody(value,normalize=false); flushLocal()
    }
    fun removeIllustration(path: String) {
        val d=draft ?: return
        val text=imagePattern.replace(d.body) { if(it.groupValues[2]==path) "" else it.value }
        draft=d.copy(pendingImages=d.pendingImages.filterNot { it.path==path },deletedImagePaths=(d.deletedImagePaths+path).distinct())
        updateBody(TextFieldValue(text,TextRange(bodyValue.selection.start.coerceAtMost(text.length))))
    }
    fun saveNovelInfo(title:String,description:String,author:String,visibility:String,cover:PendingImage?,hero:PendingImage?,removeCover:Boolean,removeHero:Boolean) {
        val n=novel ?: return
        if(title.isBlank()) { error="작품명을 입력해주세요.";return }
        task("작품 정보를 저장 중…") {
            rememberSavedNovel(io { repository().saveNovel(n,title,description,author,visibility,cover,hero,removeCover,removeHero) })
            refreshSavedLibrary();status="✓ 작품 정보를 GitHub에 저장했습니다."
        }
    }
    fun createNovel(title:String,description:String,author:String,firstTitle:String,firstBody:String,cover:PendingImage?,hero:PendingImage?) {
        if(title.isBlank()||firstTitle.isBlank()) { error="작품명과 첫 회차 제목을 입력해주세요.";return }
        task("새 작품을 만드는 중…") {
            rememberSavedNovel(io { repository().createNovel(title,description,author,firstTitle,firstBody,cover,hero) })
            chapter=null;draft=null;bodyValue=TextFieldValue();refreshSavedLibrary(); screen="novel"; status="✓ 새 작품을 저장했습니다. 작품 정보에서 공개 상태를 선택하세요."
        }
    }
    fun createChapter(title:String,body:String) {
        val n=novel ?: return
        if(title.isBlank()) { error="회차 제목을 입력해주세요.";return }
        task("새 회차를 만드는 중…") {
            val api=repository()
            val created=io { api.createChapter(n,title,body) }
            val meta=JSONObject(n.meta.toString());meta.getJSONArray("chapters").put(created.json)
            val fallback=n.copy(meta=meta,head=api.lastSavedHead)
            rememberSavedNovel(fallback)
            val fresh=refreshSavedNovel(fallback);rememberSavedNovel(fresh)
            chapter=fresh.chapters.find { it.id==created.id } ?: created
            val loaded=try {io {repository().loadDraft(fresh,chapter!!)}} catch(failure:Exception) {
                error="새 회차는 GitHub에 저장되었습니다. 본문을 다시 불러오지 못해 작성한 원고를 표시합니다."
                Draft(created.title,body,created.visibility,api.lastSavedHead,githubSavedAt=System.currentTimeMillis(),originalRaw=created.title+"\n\n"+body)
            }
            draft=loaded;bodyValue=TextFieldValue(loaded.body)
            screen="chapter";status="✓ 새 회차를 저장했습니다."
        }
    }
    fun deleteChapter(item:Chapter) {
        val n=novel ?: return
        task("회차를 삭제 중…") {
            val api=repository();io { api.deleteChapter(n,item) }
            val meta=JSONObject(n.meta.toString()).put("chapters",org.json.JSONArray().also {a->n.chapters.filter {it.id!=item.id}.forEach {a.put(it.json)} })
            val fallback=n.copy(meta=meta,head=api.lastSavedHead);rememberSavedNovel(fallback)
            chapter=null;draft=null;screen="novel";rememberSavedNovel(refreshSavedNovel(fallback))
            status="회차를 삭제했습니다. GitHub 기록은 보존됩니다."
        }
    }
    fun deleteNovel() {
        val n=novel ?: return
        task("작품을 삭제 중…") {
            io { repository().deleteNovel(n) }
            library=library.filterNot {it.id==n.id};store.cacheLibrary(settings,library)
            novel=null;chapter=null;draft=null;screen="library";refreshSavedLibrary()
            status="작품을 삭제했습니다. GitHub 기록은 보존됩니다."
        }
    }
    fun moveChapter(item:Chapter,direction:Int) {
        val n=novel ?: return
        val ordered=n.chapters.map { it.id }.toMutableList();val from=ordered.indexOf(item.id);val to=from+direction
        if(from<0||to !in ordered.indices)return
        ordered.add(to,ordered.removeAt(from))
        task("회차 순서를 저장 중…") {
            val api=repository();io {api.reorderChapters(n,ordered)}
            val existing=n.chapters.associateBy {it.id}
            val meta=JSONObject(n.meta.toString()).put("chapters",org.json.JSONArray().also {a->ordered.forEach {a.put(existing.getValue(it).json)} })
            val fallback=n.copy(meta=meta,head=api.lastSavedHead);rememberSavedNovel(fallback);rememberSavedNovel(refreshSavedNovel(fallback))
            status="✓ 회차 순서를 저장했습니다."
        }
    }
    fun openHistory() {
        val n=novel ?: return;val c=chapter ?: return
        flushLocal();task("수정 기록을 불러오는 중…") {
            versions=io { repository().versions(n,c) };selectedVersion=null;selectedVersionInfo=null;screen="history";status=""
        }
    }
    fun readVersion(version:Version) {
        val n=novel ?: return;val c=chapter ?: return
        task("이전 원고를 불러오는 중…") {
            selectedVersion=io { repository().versionDraft(n,c,version) };selectedVersionInfo=version;status=""
        }
    }
    fun restoreVersion() {
        val n=novel ?: return;val c=chapter ?: return;val old=selectedVersion ?: return
        task("선택한 버전으로 복원 중…") {
            val latest=io { repository().loadNovel(n.id) }
            val item=latest.chapters.find { it.id==c.id } ?: error("삭제된 회차는 먼저 회차를 만들어주세요.")
            val baseline=io { repository().loadDraft(latest,item) }
            val restored=io { repository().saveChapter(latest,item,baseline.copy(title=old.title,body=old.body,pendingImages=emptyList(),deletedImagePaths=emptyList())) }
            draft=restored;bodyValue=TextFieldValue(restored.body);io { store.deleteDraft(settings,n.id,c.id) }
            val fallback=chapterFallback(latest,item,restored);rememberSavedNovel(fallback);rememberSavedNovel(refreshSavedNovel(fallback));chapter=novel!!.chapters.first { it.id==c.id }
            screen="chapter";status="✓ 이전 원고를 새 커밋으로 복원했습니다."
        }
    }
    fun showPreview() {
        val n=novel ?: return
        val d=draft
        val c=chapter ?: n.chapters.firstOrNull()
        if(c==null){error="미리볼 회차가 없습니다.";return}
        task("미리보기를 준비 중…") {
            val content=d ?: io { repository().loadDraft(n,c) }
            previewHtml=io { PreviewDocument.build(n,c,content,settings.darkMode) }
            previewReturn=screen;screen="preview";status=""
        }
    }
    fun publish() {
        if(hasChanges) { error="작성 중인 원고를 먼저 저장해주세요. 저장 후 지금 공개를 누르세요.";return }
        task("공개를 준비 중…") {
            publication=io { repository().publish(null) { message -> viewModelScope.launch { status=message } } }
            status="✓ 공개 완료 · 웹사이트에서 확인할 수 있습니다."
        }
    }
    suspend fun imageBytes(path:String):ByteArray = io {
        val pending=draft?.pendingImages?.firstOrNull { it.path==path }
        if(pending!=null)store.pendingBytes(pending) else repository().readImage(path)
    }
    fun back() {
        if(busy)return
        flushLocal()
        screen=when(screen){"preview"->previewReturn;"history","diff"->"chapter";"chapter","new-chapter"->"novel";"novel","new-novel","settings"->"library";else->"library"}
    }
}