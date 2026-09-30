package com.storygpt.editor

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.io.ByteArrayInputStream

object PreviewDocument {
    const val BASE = "https://preview.storygpt.invalid/"
    fun build(novel:Novel,chapter:Chapter,draft:Draft,dark:Boolean):String {
        val meta=JSONObject(novel.meta.toString()).put("id",novel.id)
        val chapterJson=JSONObject(chapter.json.toString()).put("title",draft.title)
        fun literal(value:String)=JSONObject.quote(value).replace("<","\\u003c").replace("&","\\u0026")
        return """<!doctype html><html lang="ko" data-theme="${if(dark) "dark" else "light"}">
<head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<link rel="stylesheet" href="css/style.css">
<style>body{padding:0;margin:0}.reader-scroll{height:100vh!important}.reader-scroll-inner{padding-bottom:64px}.topbar,.footer,.reader-controls,.reader-tap-overlay{display:none!important}</style>
</head><body data-page="reader" data-reader-ui="hidden"><main id="app" class="app"></main>
<script>const Storage={getFontSize:()=>18};</script><script src="js/views.js"></script><script>
const novel=JSON.parse(${literal(meta.toString())});const chapter=JSON.parse(${literal(chapterJson.toString())});
const paragraphs=Views.parseParagraphs(${literal(draft.body)},chapter.title);
document.getElementById('app').innerHTML=Views.renderReader(novel,chapter,paragraphs,null,null,'scroll');
</script></body></html>"""
    }
}

/** All manuscript requests stay inside the preview origin. The token never enters JavaScript. */
class PreviewClient(private val context:Context,private val settings:Settings,private val store:LocalStore,private val pending:List<PendingImage>):WebViewClient() {
    private fun missing()=WebResourceResponse("text/plain","utf-8",404,"Not Found",emptyMap(),ByteArrayInputStream(ByteArray(0)))
    override fun shouldOverrideUrlLoading(view:WebView?,request:WebResourceRequest?):Boolean=true
    override fun shouldInterceptRequest(view:WebView?,request:WebResourceRequest?):WebResourceResponse {
        val url=request?.url ?: return missing()
        if(url.scheme!="https"||url.host!="preview.storygpt.invalid")return missing()
        val resource=url.path.orEmpty().removePrefix("/")
        if(resource.split('/').any { it==".."||it=="." }||resource.contains('\\')||resource.contains('%'))return missing()
        return try {
            when(resource) {
                "css/style.css"->WebResourceResponse("text/css","utf-8",context.assets.open(resource))
                "js/views.js"->WebResourceResponse("application/javascript","utf-8",context.assets.open(resource))
                else->{
                    if(!resource.startsWith("illust/")&&!resource.startsWith("novels/"))return missing()
                    val staged=pending.firstOrNull { it.path==resource }
                    val bytes=if(staged!=null)store.pendingBytes(staged) else Repository(settings,store).readImage(resource)
                    val mime=when(resource.substringAfterLast('.').lowercase()){"jpg","jpeg"->"image/jpeg";"png"->"image/png";"webp"->"image/webp";else->return missing()}
                    WebResourceResponse(mime,null,ByteArrayInputStream(bytes))
                }
            }
        } catch (_:Exception){missing()}
    }
}

data class DiffLine(val type:Char,val text:String)
fun proseDiff(before:String,after:String):List<DiffLine> {
    val a=before.split('\n');val b=after.split('\n')
    if(a.size.toLong()*b.size>1_000_000L) {
        // Bound memory use for exceptionally long manuscripts.
        return (0 until maxOf(a.size,b.size)).flatMap { i ->
            if(a.getOrNull(i)==b.getOrNull(i))listOf(DiffLine(' ',a.getOrNull(i).orEmpty()))
            else listOfNotNull(a.getOrNull(i)?.let { DiffLine('-',it) },b.getOrNull(i)?.let { DiffLine('+',it) })
        }
    }
    val lengths=Array(a.size+1){IntArray(b.size+1)}
    for(i in a.lastIndex downTo 0)for(j in b.lastIndex downTo 0)lengths[i][j]=if(a[i]==b[j])1+lengths[i+1][j+1] else maxOf(lengths[i+1][j],lengths[i][j+1])
    val result=mutableListOf<DiffLine>();var i=0;var j=0
    while(i<a.size||j<b.size) {
        when {
            i<a.size&&j<b.size&&a[i]==b[j]->{result+=DiffLine(' ',a[i]);i++;j++}
            j<b.size&&(i==a.size||lengths[i][j+1]>=lengths[i+1][j])->{result+=DiffLine('+',b[j]);j++}
            else->{result+=DiffLine('-',a[i]);i++}
        }
    }
    return result
}