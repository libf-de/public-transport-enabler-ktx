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

package de.libf.ptek.dto

import kotlinx.serialization.Serializable

@Serializable
data class Style(
    val shape: Shape = Shape.ROUNDED,
    val backgroundColor: Int,
    val foregroundColor: Int,
    val backgroundColor2: Int = 0,
    val borderColor: Int = 0
) {
    enum class Shape { RECT, ROUNDED, CIRCLE }

    val hasBorder: Boolean
        get() = borderColor != 0

    companion object {
        const val BLACK: Int = -0x1000000
        const val DKGRAY: Int = -0xbbbbbc
        const val GRAY: Int = -0x777778
        const val LTGRAY: Int = -0x333334
        const val WHITE: Int = -0x1
        const val RED: Int = -0x10000
        const val GREEN: Int = -0xff0100
        const val BLUE: Int = -0xffff01
        const val YELLOW: Int = -0x100
        const val CYAN: Int = -0xff0001
        const val MAGENTA: Int = -0xff01
        const val TRANSPARENT: Int = 0

        fun parseColor(colorStr: String): Int {
            try {
                // Use a long to avoid rollovers on #ffXXXXXX
                var color = colorStr.substring(1).toLong(16)
                if (colorStr.length == 7) color = color or 0xff000000L // Amend the alpha value

                return color.toInt()
            } catch (x: NumberFormatException) {
                throw IllegalArgumentException("Not a number: $colorStr")
            }
        }

        fun argb(alpha: Int, red: Int, green: Int, blue: Int): Int {
            return (alpha shl 24) or (red shl 16) or (green shl 8) or blue
        }

        fun rgb(red: Int, green: Int, blue: Int): Int {
            return argb(0xFF, red, green, blue)
        }

        fun red(color: Int): Int {
            return (color shr 16) and 0xff
        }

        fun green(color: Int): Int {
            return (color shr 8) and 0xff
        }

        fun blue(color: Int): Int {
            return color and 0xff
        }

        fun perceivedBrightness(color: Int): Float {
            // formula for perceived brightness computation: http://www.w3.org/TR/AERT#color-contrast
            return (0.299f * red(color) + 0.587f * green(color) + 0.114f * blue(color)) / 256
        }

        fun deriveForegroundColor(backgroundColor: Int): Int {
            // dark colors, white font. Or light colors, black font
            return if (perceivedBrightness(backgroundColor) < 0.5) WHITE
            else BLACK
        }

    }

}