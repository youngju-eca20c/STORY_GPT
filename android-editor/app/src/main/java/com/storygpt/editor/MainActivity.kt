package com.storygpt.editor

import android.graphics.BitmapFactory
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity:ComponentActivity() {
    private lateinit var vm:EditorViewModel
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        vm=ViewModelProvider(this)[EditorViewModel::class.java]
        setContent { StoryEditor(vm) }
    }
    override fun onStop() { vm.flushLocal();super.onStop() }
}

private data class Confirmation(val message:String,val action:()->Unit)
private val lightColors=lightColorScheme(primary=Color(0xFF286C63),secondary=Color(0xFF6D6552),background=Color(0xFFF8F7F2),surface=Color(0xFFF8F7F2))
private val darkColors=darkColorScheme(primary=Color(0xFF8ED4C4),secondary=Color(0xFFD7C8A5),background=Color(0xFF151D1C),surface=Color(0xFF151D1C))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoryEditor(vm:EditorViewModel) {
    var confirmation by remember { mutableStateOf<Confirmation?>(null) }
    val ask:(String,()->Unit)->Unit={ message,action->confirmation=Confirmation(message,action) }
    val context=LocalContext.current
    MaterialTheme(colorScheme=if(vm.settings.darkMode)darkColors else lightColors) {
        BackHandler(vm.screen!="library") { vm.back() }
        Scaffold(
            topBar={ TopAppBar(
                title={Column {Text(if(vm.screen=="library"||vm.screen=="settings")"STORY GPT Editor" else vm.novel?.title ?: "새 작품",maxLines=1);if(vm.screen=="library")Text("나의 이야기를 가꾸는 서재",style=MaterialTheme.typography.labelSmall)}},
                navigationIcon={if(vm.screen!="library")TextButton(onClick={vm.back()},enabled=!vm.busy){Text("‹ 뒤로")}},
                actions={if(vm.screen!="settings")TextButton(onClick={vm.show("settings")},enabled=!vm.busy){Text("설정")}}
            )},
            bottomBar={if(vm.screen=="chapter")Surface(tonalElevation=3.dp){Row(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(8.dp),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                TextButton({vm.showPreview()},enabled=!vm.busy,modifier=Modifier.weight(1f)){Text("미리보기")}
                TextButton({vm.show("diff")},enabled=!vm.busy,modifier=Modifier.weight(1f)){Text("변경사항")}
                Button({vm.saveChapter()},enabled=!vm.busy,modifier=Modifier.weight(1f)){Text("저장")}
            }}}
        ) { padding->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if(vm.busy)LinearProgressIndicator(Modifier.fillMaxWidth())
                if(vm.status.isNotEmpty())Text(vm.status,Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal=16.dp,vertical=9.dp),style=MaterialTheme.typography.labelMedium)
                vm.error?.let { message->Card(Modifier.fillMaxWidth().padding(12.dp),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(12.dp)){Text(message,color=MaterialTheme.colorScheme.onErrorContainer);TextButton(onClick={vm.clearError()}){Text("닫기")}}
                }}
                Box(Modifier.weight(1f)) {
                    when(vm.screen) {
                        "settings"->SettingsScreen(vm)
                        "library"->LibraryScreen(vm,ask)
                        "novel"->NovelScreen(vm,ask)
                        "new-novel"->NewNovelScreen(vm)
                        "new-chapter"->NewChapterScreen(vm)
                        "chapter"->ChapterScreen(vm,ask)
                        "preview"->PreviewScreen(vm)
                        "history"->HistoryScreen(vm,ask)
                        "diff"->DiffScreen(vm)
                    }
                }
            }
        }
        confirmation?.let { current->AlertDialog(onDismissRequest={confirmation=null},title={Text("확인")},text={Text(current.message)},dismissButton={TextButton({confirmation=null}){Text("취소")}},confirmButton={Button({confirmation=null;current.action()}){Text("진행")}}) }
    }
}

@Composable
private fun Form(content:@Composable ColumnScope.()->Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp),content=content)
}
@Composable
private fun Field(label:String,value:String,onChange:(String)->Unit,single:Boolean=true,enabled:Boolean=true) {
    OutlinedTextField(value,onChange,label={Text(label)},singleLine=single,minLines=if(single)1 else 3,enabled=enabled,modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(12.dp))
}
@Composable
private fun Heading(text:String) { Text(text,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold) }
private fun timeLabel(value:Long)=if(value<=0)"아직 없음" else SimpleDateFormat("MM/dd HH:mm:ss",Locale.KOREA).format(Date(value))
private fun commitDateLabel(value:String)=try {java.time.Instant.parse(value).atZone(java.time.ZoneId.of("Asia/Seoul")).format(java.time.format.DateTimeFormatter.ofPattern("MM/dd HH:mm"))} catch(_:Exception){value}
private fun visibleLabel(value:String)=when(value){"private"->"비공개";"draft"->"작성 중";else->"공개"}
@Composable
private fun Visibility(value:String,onChange:(String)->Unit,enabled:Boolean=true,chapter:Boolean=false) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        (if(chapter)listOf("public","private","draft") else listOf("public","private")).forEach { option->FilterChip(value==option,{onChange(option)},label={Text(visibleLabel(option))},enabled=enabled) }
    }
}
@Composable
private fun SettingsScreen(vm:EditorViewModel) {
    var token by remember(vm.settings){mutableStateOf(vm.settings.token)}
    var owner by remember(vm.settings){mutableStateOf(vm.settings.owner)}
    var repo by remember(vm.settings){mutableStateOf(vm.settings.repo)}
    var branch by remember(vm.settings){mutableStateOf(vm.settings.branch)}
    var dark by remember(vm.settings){mutableStateOf(vm.settings.darkMode)}
    var autosave by remember(vm.settings){mutableStateOf(vm.settings.autosave)}
    var optimize by remember(vm.settings){mutableStateOf(vm.settings.optimizeImages)}
    var tokenVisible by remember{mutableStateOf(false)}
    Form {
        Heading("GitHub 연결")
        Text("최초 한 번만 연결하면 작품과 원고를 앱에서 관리할 수 있습니다.",style=MaterialTheme.typography.bodyMedium)
        OutlinedTextField(token,{token=it},label={Text("Personal Access Token")},visualTransformation=if(tokenVisible)VisualTransformation.None else PasswordVisualTransformation(),singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),trailingIcon={TextButton({tokenVisible=!tokenVisible}){Text(if(tokenVisible)"숨김" else "보기")}},modifier=Modifier.fillMaxWidth(),enabled=!vm.busy)
        Field("GitHub 사용자명",owner,{owner=it},enabled=!vm.busy)
        Field("Repository 이름",repo,{repo=it},enabled=!vm.busy)
        Field("원고 저장 Branch",branch,{branch=it},enabled=!vm.busy)
        Text("기본 원고 브랜치는 editor-drafts입니다. 처음 연결하면 main의 작품을 복사해 준비합니다.",style=MaterialTheme.typography.bodySmall)
        Text("Token 권한: 선택한 저장소의 Contents 읽기·쓰기, Actions 읽기·쓰기. Token은 이 기기에만 저장됩니다.",style=MaterialTheme.typography.bodySmall)
        HorizontalDivider();Heading("앱 설정")
        Toggle("다크모드",dark){dark=it};Toggle("로컬 자동저장",autosave){autosave=it};Toggle("큰 이미지 최적화",optimize){optimize=it}
        Button(onClick={vm.configure(Settings(token=token.trim(),owner=owner.trim(),repo=repo.trim(),branch=branch.trim(),darkMode=dark,autosave=autosave,optimizeImages=optimize));vm.connect()},enabled=!vm.busy&&token.isNotBlank()&&owner.isNotBlank()&&repo.isNotBlank()&&branch.isNotBlank(),modifier=Modifier.fillMaxWidth()){Text("설정 저장 · GitHub 연결 테스트")}
        Text("공개 저장소의 비공개 설정은 독자 사이트에서 숨기는 기능입니다. GitHub 저장소에서는 원고를 볼 수 있습니다.",style=MaterialTheme.typography.bodySmall)
    }
}
@Composable
private fun Toggle(text:String,value:Boolean,onChange:(Boolean)->Unit) { Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text(text,Modifier.weight(1f));Switch(value,onChange)} }
@Composable
private fun LibraryScreen(vm:EditorViewModel,ask:(String,()->Unit)->Unit) {
    val context=LocalContext.current
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        item {Heading("내 작품")}
        item {Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
            Button({vm.show("new-novel")},enabled=!vm.busy&&vm.settings.token.isNotBlank(),modifier=Modifier.weight(1f)){Text("＋ 새 작품")}
            OutlinedButton({vm.refresh()},enabled=!vm.busy&&vm.settings.token.isNotBlank()){Text("새로고침")}
        }}
        if(vm.library.isEmpty())item {Card {Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
            Text(if(vm.settings.token.isBlank())"설정에서 GitHub를 연결하면 기존 작품이 표시됩니다." else "작품을 불러오거나 새 작품을 만들어보세요.")
            Button({if(vm.settings.token.isBlank())vm.show("settings") else vm.connect()},enabled=!vm.busy){Text(if(vm.settings.token.isBlank())"연결 설정" else "작품 불러오기")}
        }}}
        items(vm.library,key={it.id}) { n->Card(onClick={vm.openNovel(n)},enabled=!vm.busy,modifier=Modifier.fillMaxWidth()) {
            Row(Modifier.padding(14.dp),horizontalArrangement=Arrangement.spacedBy(16.dp),verticalAlignment=Alignment.CenterVertically) {
                RepositoryImage(vm,if(n.cover.isNotBlank())"novels/${n.id}/${n.cover}" else "",null,Modifier.size(76.dp,108.dp))
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)){Text(n.title,style=MaterialTheme.typography.titleMedium);Text("${n.chapters.size}화 · ${visibleLabel(n.visibility)}",style=MaterialTheme.typography.labelLarge);Text(n.author,style=MaterialTheme.typography.bodySmall);Text(n.description,maxLines=2,style=MaterialTheme.typography.bodySmall)}
            }
        }}
        item {HorizontalDivider()}
        item {Button({ask("저장된 최신 원고를 기존 STORY_GPT 사이트에 공개하시겠습니까?"){vm.publish()}},enabled=!vm.busy&&vm.settings.token.isNotBlank(),modifier=Modifier.fillMaxWidth()){Text("지금 공개")}}
        item {OutlinedButton({context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(vm.publication?.siteUrl ?: "https://${vm.settings.owner}.github.io/${vm.settings.repo}/#/")))},modifier=Modifier.fillMaxWidth()){Text("웹사이트에서 확인")}}
        item {Text("저장한 원고는 지금 공개하거나 매일 한국 시간 자정 예약 실행 시 반영됩니다.",style=MaterialTheme.typography.bodySmall)}
    }
}

@Composable
private fun NovelScreen(vm:EditorViewModel,ask:(String,()->Unit)->Unit) {
    val n=vm.novel ?: return
    var title by rememberSaveable(n.head,n.id){mutableStateOf(n.title)}
    var description by rememberSaveable(n.head,n.id){mutableStateOf(n.description)}
    var author by rememberSaveable(n.head,n.id){mutableStateOf(n.author)}
    var visibility by rememberSaveable(n.head,n.id){mutableStateOf(n.visibility)}
    var cover by remember(n.head,n.id){mutableStateOf<PendingImage?>(null)}
    var hero by remember(n.head,n.id){mutableStateOf<PendingImage?>(null)}
    var removeCover by rememberSaveable(n.head,n.id){mutableStateOf(false)}
    var removeHero by rememberSaveable(n.head,n.id){mutableStateOf(false)}
    val coverPicker=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()){uri->uri?.let {vm.stageImage(it,"cover"){image->cover=image;removeCover=false}}}
    val heroPicker=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()){uri->uri?.let {vm.stageImage(it,"representative"){image->hero=image;removeHero=false}}}
    Form {
        Heading("작품 정보")
        PhotoCard(vm,"표지",if(removeCover)"" else "novels/${n.id}/${n.cover}",cover,{coverPicker.launch("image/*")},{ask("표지 연결을 삭제하시겠습니까?"){cover=null;removeCover=true}})
        Field("작품명",title,{title=it},enabled=!vm.busy);Field("작품 설명",description,{description=it},single=false,enabled=!vm.busy);Field("작가명",author,{author=it},enabled=!vm.busy)
        Visibility(visibility,{visibility=it},!vm.busy)
        PhotoCard(vm,"대표 이미지 (선택)",if(removeHero||n.hero.isBlank())"" else "novels/${n.id}/${n.hero}",hero,{heroPicker.launch("image/*")},{ask("대표 이미지 연결을 삭제하시겠습니까?"){hero=null;removeHero=true}})
        Button({vm.saveNovelInfo(title,description,author,visibility,cover,hero,removeCover,removeHero)},enabled=!vm.busy,modifier=Modifier.fillMaxWidth()){Text("작품 정보 수정 · 저장")}
        HorizontalDivider();Heading("회차 목록 · ${n.chapters.size}화")
        n.chapters.forEachIndexed { index,c->Card(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically){
                Column(Modifier.weight(1f).clickable(enabled=!vm.busy){vm.openChapter(c)}){Text(c.title,style=MaterialTheme.typography.titleSmall);Text(visibleLabel(c.visibility),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.primary)}
                TextButton({vm.moveChapter(c,-1)},enabled=!vm.busy&&index>0,contentPadding=PaddingValues(5.dp)){Text("↑")}
                TextButton({vm.moveChapter(c,1)},enabled=!vm.busy&&index<n.chapters.lastIndex,contentPadding=PaddingValues(5.dp)){Text("↓")}
                TextButton({ask("‘${c.title}’ 회차를 삭제하시겠습니까?"){vm.deleteChapter(c)}},enabled=!vm.busy,contentPadding=PaddingValues(5.dp)){Text("삭제",color=MaterialTheme.colorScheme.error)}
            }
        }}
        Button({vm.show("new-chapter")},enabled=!vm.busy,modifier=Modifier.fillMaxWidth()){Text("＋ 새 회차")}
        OutlinedButton({vm.showPreview()},enabled=!vm.busy,modifier=Modifier.fillMaxWidth()){Text("미리보기")}
        OutlinedButton({ask("‘${n.title}’ 작품과 회차를 삭제하시겠습니까? GitHub 기록에서 복구할 수 있습니다."){vm.deleteNovel()}},enabled=!vm.busy,modifier=Modifier.fillMaxWidth()){Text("작품 삭제",color=MaterialTheme.colorScheme.error)}
    }
}

@Composable
private fun NewNovelScreen(vm:EditorViewModel) {
    var title by rememberSaveable{mutableStateOf("")};var description by rememberSaveable{mutableStateOf("")};var author by rememberSaveable{mutableStateOf("")};var firstTitle by rememberSaveable{mutableStateOf("1화. ")};var firstBody by rememberSaveable{mutableStateOf("")}
    var cover by remember{mutableStateOf<PendingImage?>(null)};var hero by remember{mutableStateOf<PendingImage?>(null)}
    val coverPicker=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()){uri->uri?.let {vm.stageImage(it,"cover"){image->cover=image}}}
    val heroPicker=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()){uri->uri?.let {vm.stageImage(it,"representative"){image->hero=image}}}
    Form {
        Heading("새로운 이야기")
        Field("작품명",title,{title=it},enabled=!vm.busy);Field("작품 설명",description,{description=it},false,!vm.busy);Field("작가명",author,{author=it},enabled=!vm.busy)
        PhotoCard(vm,"표지 (선택)","",cover,{coverPicker.launch("image/*")},{cover=null});PhotoCard(vm,"대표 이미지 (선택)","",hero,{heroPicker.launch("image/*")},{hero=null})
        Field("첫 번째 회차 제목",firstTitle,{firstTitle=it},enabled=!vm.busy);Field("첫 번째 회차 본문",firstBody,{firstBody=it},false,!vm.busy)
        Text("새 작품은 비공개로 생성됩니다. 원고를 확인한 뒤 작품 정보에서 공개로 바꿀 수 있습니다.",style=MaterialTheme.typography.bodySmall)
        Button({vm.createNovel(title,description,author,firstTitle,firstBody,cover,hero)},enabled=!vm.busy,modifier=Modifier.fillMaxWidth()){Text("작품 만들기")}
    }
}
@Composable
private fun NewChapterScreen(vm:EditorViewModel) {
    var title by rememberSaveable{mutableStateOf("${(vm.novel?.chapters?.size ?: 0)+1}화. ")};var body by rememberSaveable{mutableStateOf("")}
    Form {Heading("새 회차");Field("회차 제목",title,{title=it},enabled=!vm.busy);Text("본문은 생성 후 편집 화면에서 자동저장하며 작성할 수 있습니다.",style=MaterialTheme.typography.bodySmall);Field("초기 본문 (선택)",body,{body=it},false,!vm.busy);Button({vm.createChapter(title,body)},enabled=!vm.busy,modifier=Modifier.fillMaxWidth()){Text("새 회차 만들기")}}
}

/** Show friendly image labels while cursor offsets still address the original TXT manuscript. */
internal class IllustrationTransformation:VisualTransformation {
    override fun filter(text:AnnotatedString):TransformedText {
        val source=text.text;val pattern=Regex("!\\[([^\\]\\n]*)]\\((illust/[^)\\n]+)\\)")
        val output=StringBuilder();val original=IntArray(source.length+1);val transformed=mutableListOf(0);var cursor=0;var number=0
        for(match in pattern.findAll(source)) {
            while(cursor<match.range.first){original[cursor]=output.length;output.append(source[cursor]);cursor++;transformed+=cursor}
            val start=cursor;val position=output.length;val label="▧ 일러스트 ${++number}"
            for(k in 0 until match.value.length)original[start+k]=position+(k*label.length/match.value.length)
            output.append(label);for(k in 1..label.length)transformed+=(start+k*match.value.length/label.length)
            cursor=match.range.last+1;original[cursor]=output.length
        }
        while(cursor<source.length){original[cursor]=output.length;output.append(source[cursor]);cursor++;transformed+=cursor};original[source.length]=output.length
        return TransformedText(AnnotatedString(output.toString()),object:OffsetMapping {
            override fun originalToTransformed(offset:Int)=original[offset.coerceIn(0,source.length)]
            override fun transformedToOriginal(offset:Int)=transformed[offset.coerceIn(0,transformed.lastIndex)]
        })
    }
}
@Composable
private fun ChapterScreen(vm:EditorViewModel,ask:(String,()->Unit)->Unit) {
    val d=vm.draft ?: return
    var replacePath by remember{mutableStateOf<String?>(null)}
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()){uri->uri?.let { selected->val target=replacePath;vm.stageImage(selected,"illustration"){image->vm.insertIllustration(image,target)} };replacePath=null}
    Form {
        Field("회차 제목",d.title,{vm.updateTitle(it)},enabled=!vm.busy)
        Visibility(d.visibility,{vm.updateVisibility(it)},!vm.busy,true)
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("${friendlyBody(d.body).codePointCount(0,friendlyBody(d.body).length)}자",style=MaterialTheme.typography.labelMedium);Text(if(vm.hasChanges)"미저장 변경 있음" else "GitHub 저장됨",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary)}
        Text("로컬 ${timeLabel(d.localSavedAt)}  ·  GitHub ${timeLabel(d.githubSavedAt)}",style=MaterialTheme.typography.labelSmall)
        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedButton({replacePath=null;picker.launch("image/*")},enabled=!vm.busy){Text("＋ 일러스트 추가")}
            OutlinedButton({vm.openHistory()},enabled=!vm.busy){Text("수정 기록")}
        }
        OutlinedTextField(vm.bodyValue,{vm.updateBody(it)},modifier=Modifier.fillMaxWidth().heightIn(min=400.dp),label={Text("본문")},placeholder={Text("이야기를 써보세요. 일러스트는 커서 위치에 들어갑니다.")},textStyle=TextStyle(fontSize=18.sp,lineHeight=30.sp,fontFamily=FontFamily.Serif),visualTransformation=remember{IllustrationTransformation()},keyboardOptions=KeyboardOptions(capitalization=KeyboardCapitalization.Sentences),enabled=!vm.busy,shape=RoundedCornerShape(12.dp))
        if(vm.imageReferences.isNotEmpty())Heading("본문 일러스트")
        vm.imageReferences.forEachIndexed { index,ref->PhotoCard(vm,"일러스트 ${index+1}",ref.second,d.pendingImages.firstOrNull {it.path==ref.second},{replacePath=ref.second;picker.launch("image/*")},{ask("일러스트 ${index+1}을 본문에서 삭제하시겠습니까?"){vm.removeIllustration(ref.second)}}) }
        Text("일러스트는 본문의 ▧ 표시 위치에 나타납니다. 입력 중 자동저장은 기기에 저장하며, 아래 저장 버튼으로 GitHub에 보냅니다.",style=MaterialTheme.typography.bodySmall)
        TextButton({ask("기기에 복구된 수정 내용을 버리고 GitHub 원고를 다시 불러오시겠습니까?"){vm.discardLocalAndReload()}},enabled=!vm.busy){Text("GitHub 원고 다시 불러오기")}
    }
}

@Composable
private fun RepositoryImage(vm:EditorViewModel,path:String,pending:PendingImage?,modifier:Modifier=Modifier) {
    val bitmap by produceState<android.graphics.Bitmap?>(null,path,pending?.localPath,vm.settings.branch) {
        value=try {withContext(Dispatchers.IO){val bytes=if(pending!=null)vm.store.pendingBytes(pending) else if(path.isNotBlank())vm.imageBytes(path) else ByteArray(0);val options=BitmapFactory.Options().apply {inJustDecodeBounds=true};BitmapFactory.decodeByteArray(bytes,0,bytes.size,options);var sample=1;while(options.outWidth/sample>1200||options.outHeight/sample>1600)sample*=2;BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply {inSampleSize=sample})?.let {decoded->
            val orientation=try {android.media.ExifInterface(java.io.ByteArrayInputStream(bytes)).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION,android.media.ExifInterface.ORIENTATION_NORMAL)} catch(_:Exception){1}
            if(orientation in 2..8)android.graphics.Bitmap.createBitmap(decoded,0,0,decoded.width,decoded.height,exifOrientationMatrix(orientation),true).also {if(it!==decoded)decoded.recycle()} else decoded
        }}}catch(_:Exception){null}
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant,RoundedCornerShape(10.dp)),contentAlignment=Alignment.Center) {
        if(bitmap!=null)Image(bitmap!!.asImageBitmap(),"이미지",Modifier.fillMaxSize(),contentScale=ContentScale.Fit) else Text("▧",fontSize=28.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable
private fun PhotoCard(vm:EditorViewModel,label:String,path:String,pending:PendingImage?,change:()->Unit,delete:()->Unit) {
    var viewing by remember{mutableStateOf(false)}
    val hasImage=pending!=null||path.isNotBlank()&&!path.endsWith('/')
    Card(Modifier.fillMaxWidth()){Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
        Text(label,style=MaterialTheme.typography.titleSmall)
        if(hasImage)RepositoryImage(vm,path,pending,Modifier.fillMaxWidth().height(155.dp).clickable{viewing=true})
        Row(Modifier.horizontalScroll(rememberScrollState())){TextButton(change,enabled=!vm.busy){Text(if(hasImage)"이미지 변경" else "사진 선택")};if(hasImage){TextButton({viewing=true}){Text("이미지 보기")};TextButton(delete,enabled=!vm.busy){Text("삭제",color=MaterialTheme.colorScheme.error)}}}
    }}
    if(viewing)Dialog(onDismissRequest={viewing=false}){Surface(shape=RoundedCornerShape(20.dp)){Column(Modifier.padding(12.dp)){RepositoryImage(vm,path,pending,Modifier.fillMaxWidth().height(420.dp));TextButton({viewing=false}){Text("닫기")}}}}
}
@Composable
private fun PreviewScreen(vm:EditorViewModel) {
    val context=LocalContext.current
    DisposableWebPreview(vm,context)
}
@Composable
private fun DisposableWebPreview(vm:EditorViewModel,context:android.content.Context) {
    val web=remember(vm.previewHtml){WebView(context).apply {
        settings.javaScriptEnabled=true;settings.allowFileAccess=false;settings.allowContentAccess=false;settings.domStorageEnabled=false
        webViewClient=PreviewClient(context,vm.settings,vm.store,vm.draft?.pendingImages.orEmpty())
        loadDataWithBaseURL(PreviewDocument.BASE,vm.previewHtml,"text/html","utf-8",null)
    }}
    AndroidView(factory={web},modifier=Modifier.fillMaxSize())
    DisposableEffect(web){onDispose{web.stopLoading();web.destroy()}}
}
@Composable
private fun HistoryScreen(vm:EditorViewModel,ask:(String,()->Unit)->Unit) {
    Form {
        Heading("회차 수정 기록")
        Text("이전 원고를 확인하고 새 커밋으로 복원할 수 있습니다.",style=MaterialTheme.typography.bodySmall)
        vm.versions.forEach { version->OutlinedButton({vm.readVersion(version)},enabled=!vm.busy,modifier=Modifier.fillMaxWidth()){Column(Modifier.fillMaxWidth()){Text(commitDateLabel(version.date));Text(version.message,maxLines=2,style=MaterialTheme.typography.bodySmall)}}}
        if(vm.versions.isEmpty())Text("이 회차의 수정 기록이 없습니다.")
        vm.selectedVersion?.let { old->HorizontalDivider();Heading(old.title);Text(friendlyBody(old.body));Button({ask("선택한 버전으로 복원하시겠습니까? 현재 편집 중인 내용은 선택한 원고로 바뀌고 새 커밋으로 저장됩니다."){vm.restoreVersion()}},enabled=!vm.busy,modifier=Modifier.fillMaxWidth()){Text("이 버전으로 복원")}}
    }
}
private fun friendlyBody(text:String)=Regex("!\\[[^\\]\\n]*]\\(illust/[^)\\n]+\\)").replace(text,"▧ 일러스트")
@Composable
private fun DiffScreen(vm:EditorViewModel) {
    val draft=vm.draft ?: return
    val lines by produceState<List<DiffLine>>(emptyList(),draft.originalBody,draft.body){value=withContext(Dispatchers.Default){proseDiff(draft.originalBody,draft.body)}}
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
        item {Heading("변경사항")}
        item {Text("초록은 추가된 문장, 빨강은 삭제된 문장입니다.",style=MaterialTheme.typography.bodySmall)}
        if(draft.title!=draft.originalTitle)item {Text("제목: ${draft.originalTitle} → ${draft.title}")}
        if(draft.visibility!=draft.originalVisibility)item {Text("공개 상태: ${visibleLabel(draft.originalVisibility)} → ${visibleLabel(draft.visibility)}")}
        items(lines){line->Text("${line.type} ${friendlyBody(line.text)}",Modifier.fillMaxWidth().background(when(line.type){'+'->Color(0x2233B472);'-'->Color(0x22DF4D4D);else->Color.Transparent}).padding(5.dp),style=MaterialTheme.typography.bodyMedium)}
    }
}