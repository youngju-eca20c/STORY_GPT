package com.storygpt.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/** Image markers behave as single blocks: Backspace cannot expose or corrupt their stored URL. */
internal fun normalizeIllustrationEdit(previous:TextFieldValue,candidate:TextFieldValue):TextFieldValue {
    val pattern=Regex("!\\[[^\\]\\n]*]\\(illust/[^)\\n]+\\)")
    val before=previous.text;val after=candidate.text
    val markers=pattern.findAll(before).toList()
    if(before==after) {
        fun snap(offset:Int):Int {
            val marker=markers.firstOrNull {offset>it.range.first&&offset<=it.range.last} ?: return offset
            return if(offset-marker.range.first<marker.value.length/2)marker.range.first else marker.range.last+1
        }
        return candidate.copy(selection=TextRange(snap(candidate.selection.start),snap(candidate.selection.end)))
    }
    var prefix=0;while(prefix<minOf(before.length,after.length)&&before[prefix]==after[prefix])prefix++
    var suffix=0;while(suffix<minOf(before.length-prefix,after.length-prefix)&&before[before.lastIndex-suffix]==after[after.lastIndex-suffix])suffix++
    val removedEnd=before.length-suffix
    val affected=markers.filter { match->
        if(prefix==removedEnd)prefix>match.range.first&&prefix<=match.range.last
        else prefix<=match.range.last&&removedEnd>match.range.first
    }
    if(affected.isEmpty())return candidate
    val start=minOf(prefix,affected.first().range.first)
    val end=maxOf(removedEnd,affected.last().range.last+1)
    val inserted=after.substring(prefix,after.length-suffix)
    val normalized=before.substring(0,start)+inserted+before.substring(end)
    return TextFieldValue(normalized,TextRange(start+inserted.length))
}