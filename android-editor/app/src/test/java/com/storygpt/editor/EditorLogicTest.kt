package com.storygpt.editor

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class EditorLogicTest {
    @Test fun backspaceDeletesAnEntireImageWithoutDamagingNearbyProse() {
        val marker="![일러스트](illust/example.png)"
        val before="앞\n\n$marker\n\n뒤"
        val cursor=3+marker.length
        val candidate=TextFieldValue(before.removeRange(cursor-1,cursor),TextRange(cursor-1))
        val normalized=normalizeIllustrationEdit(TextFieldValue(before,TextRange(cursor)),candidate)
        assertEquals("앞\n\n\n\n뒤",normalized.text)
        assertFalse(normalized.text.contains("illust/"))
        val moved=normalizeIllustrationEdit(TextFieldValue(before),TextFieldValue(before,TextRange(8)))
        assertEquals(3,moved.selection.start)
    }
    @Test fun imageLabelsHidePathsAndKeepAllCursorOffsetsValid() {
        val source="앞 문장\n\n![일러스트](illust/illustration-example.png)\n\n뒤 문장\n\n![세라](illust/sera.webp)"
        val transformed=IllustrationTransformation().filter(AnnotatedString(source))
        assertFalse(transformed.text.text.contains("illust/"))
        assertTrue(transformed.text.text.contains("▧ 일러스트 1"))
        assertTrue(transformed.text.text.contains("▧ 일러스트 2"))
        var previous=0
        for(offset in 0..source.length){val mapped=transformed.offsetMapping.originalToTransformed(offset);assertTrue(mapped in previous..transformed.text.length);previous=mapped}
        previous=0
        for(offset in 0..transformed.text.length){val mapped=transformed.offsetMapping.transformedToOriginal(offset);assertTrue(mapped in previous..source.length);previous=mapped}
        assertEquals(source.length,transformed.offsetMapping.transformedToOriginal(transformed.text.length))
    }
    @Test fun lineComparisonShowsAddedDeletedAndUnchangedProse() {
        val result=proseDiff("같은 문장\n삭제 문장\n끝","같은 문장\n추가 문장\n끝")
        assertTrue(result.contains(DiffLine(' ',"같은 문장")))
        assertTrue(result.contains(DiffLine('-',"삭제 문장")))
        assertTrue(result.contains(DiffLine('+',"추가 문장")))
        assertTrue(proseDiff("동일","동일").all{it.type==' '})
    }
    @Test fun previewProvidesActualReaderDependencyAndEscapesEmbeddedScriptText() {
        val title="회차 </script><script>alert(1)</script>"
        val chapter=Chapter("001",JSONObject().put("id","001").put("title",title).put("file","chapters/001.txt"))
        val novel=Novel("sample",JSONObject().put("title","작품"),JSONObject().put("title","작품").put("chapters",JSONArray().put(chapter.json)),"a".repeat(40))
        val html=PreviewDocument.build(novel,chapter,Draft(title,"본문 </script><img src=x>"),false)
        assertTrue(html.contains("const Storage={getFontSize:()=>18}"))
        assertTrue(html.contains("Views.renderReader"))
        assertFalse(html.contains("<script>alert(1)</script>"))
        assertFalse(html.contains("<img src=x>"))
        assertTrue(html.contains("\\u003c"))
    }
}