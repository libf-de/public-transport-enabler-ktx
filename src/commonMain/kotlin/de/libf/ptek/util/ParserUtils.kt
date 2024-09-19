/*
 * Copyright the original author or authors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package de.libf.ptek.util

/**
 * @author Andreas Schildbach
 */
object ParserUtils {
    private val P_HTML_UNORDERED_LIST: Regex = Regex(
        "<ul>(.*?)</ul>",
        RegexOption.IGNORE_CASE
    )
    private val P_HTML_LIST_ITEM: Regex = Regex(
        "<li>(.*?)</li>",
        RegexOption.IGNORE_CASE
    )
    private val P_HTML_BREAKS: Regex = Regex(
        "(<br\\s*/>)+",
        RegexOption.IGNORE_CASE
    )


    fun formatHtml(html: CharSequence?): String? {
        if (html == null) return null

        // list item
        val builder: StringBuilder = StringBuilder(html.length)
        val mListItem = P_HTML_LIST_ITEM.matchEntire(html)
        var pListItem = 0
        while (mListItem?.next() != null) {
            builder.append(html.subSequence(pListItem, mListItem.range.start))
            builder.append("• ")
            builder.append(mListItem.groupValues[1])
            builder.append('\n')
            pListItem = mListItem.range.endInclusive + 1
        }
        builder.append(html.subSequence(pListItem, html.length))
        val html1: String = builder.toString()

        // unordered list
        builder.setLength(0)
        val mUnorderedList = P_HTML_UNORDERED_LIST.matchEntire(html1)
        var pUnorderedList = 0
        while (mUnorderedList?.next() != null) {
            builder.append(html1.subSequence(pUnorderedList, mUnorderedList.range.start))
            builder.append('\n')
            builder.append(mUnorderedList.groupValues[1])
            pUnorderedList = mUnorderedList.range.endInclusive + 1
        }
        builder.append(html1.subSequence(pUnorderedList, html1.length))
        val html2: String = builder.toString()

        // breaks
        builder.setLength(0)
        val mBreaks = P_HTML_BREAKS.matchEntire(html2)
        var pBreaks = 0
        while (mBreaks?.next() != null) {
            builder.append(html2.subSequence(pBreaks, mBreaks.range.start))
            builder.append(' ')
            pBreaks = mBreaks.range.endInclusive + 1
        }
        builder.append(html2.subSequence(pBreaks, html2.length))
        val html3: String = builder.toString()

        return resolveEntities(html3)
    }

    private val P_ENTITY: Regex =
        Regex("&(?:#(x[\\da-f]+|\\d+)|(amp|quot|apos|szlig|nbsp));")

    fun resolveEntities(str: CharSequence?): String? {
        if (str == null) return null

        val matcher = P_ENTITY.matchEntire(str)
        val builder: StringBuilder = StringBuilder(str.length)
        var pos = 0
        while (matcher?.next() != null) {
            val c: Char
            val code = matcher.groupValues.getOrNull(1)
            if (code != null) {
                c = if (code[0] == 'x') code.substring(1).toInt(16).toChar()
                else code.toInt().toChar()
            } else {
                val namedEntity = matcher.groupValues.getOrNull(2)
                c = if (namedEntity == "amp") '&'
                else if (namedEntity == "quot") '"'
                else if (namedEntity == "apos") '\''
                else if (namedEntity == "szlig") '\u00df'
                else if (namedEntity == "nbsp") ' '
                else throw IllegalStateException("unknown entity: $namedEntity")
            }
            builder.append(str.subSequence(pos, matcher.range.start))
            builder.append(c)
            pos = matcher.range.endInclusive + 1
        }
        builder.append(str.subSequence(pos, str.length))
        return builder.toString()
    }

    private val P_ISO_DATE: Regex =
        Regex("(\\d{4})-?(\\d{2})-?(\\d{2})")
    private val P_ISO_DATE_REVERSE: Regex =
        Regex("(\\d{2})[-\\.](\\d{2})[-\\.](\\d{4})")

    private val P_ISO_TIME: Regex =
        Regex("(\\d{2})[-:]?(\\d{2})([-:]?(\\d{2}))?")


    private val P_GERMAN_DATE: Regex =
        Regex("(\\d{2})[\\./-](\\d{2})[\\./-](\\d{2,4})")


    private val P_AMERICAN_DATE: Regex =
        Regex("(\\d{2})/(\\d{2})/(\\d{2,4})")


    private val P_EUROPEAN_TIME: Regex =
        Regex("(\\d{1,2}):(\\d{2})(?::(\\d{2}))?")

    private val P_AMERICAN_TIME: Regex =
        Regex("(\\d{1,2}):(\\d{2})(?::(\\d{2}))? (AM|PM)")


    fun parseMinutesFromTimeString(duration: String): Int {
        val durationElem =
            duration.split(":".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
        return (durationElem[0].toInt() * 60) + durationElem[1].toInt()
    }

    fun printGroups(m: MatchResult) {
        val groupCount: Int = m.groupValues.size
        for (i in 1..groupCount) println(
            "group " + i + ":" + (if (m.groupValues[i] != null) "'" + m.groupValues[i]
                    + "'" else "null")
        )
    }

    fun printXml(xml: CharSequence) {
        val m = Regex("(<.{80}.*?>)\\s*").matchEntire(xml)
        while (m?.next() != null) println(m.value)
    }

    fun printPlain(plain: CharSequence) {
        val m = Regex("(.{1,80})").matchEntire(plain)
        while (m?.next() != null) println(m.value)
    }
}
