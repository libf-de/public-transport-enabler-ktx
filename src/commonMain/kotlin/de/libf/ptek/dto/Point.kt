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
import kotlin.math.roundToInt

@Serializable
data class Point private constructor (
    val lat: Double,
    val lon: Double
) {
    companion object {
        fun fromDouble(lat: Double, lon: Double) = Point(lat, lon)
        fun from1E6(lat: Int, lon: Int) = Point(lat / 1E6, lon / 1E6)
        fun from1E5(lat: Int, lon: Int) = Point(lat / 1E5, lon / 1E5)
    }

    val latAs1E6: Int
        get() = (lat * 1E6).roundToInt()
    val lonAs1E6: Int
        get() = (lon * 1E6).roundToInt()

    val latAs1E5: Int
        get() = (lat * 1E5).roundToInt()
    val lonAs1E5: Int
        get() = (lon * 1E5).roundToInt()

    override fun toString(): String {
        return "${lat.formatTo7DecimalPlaces()}/${lon.formatTo7DecimalPlaces()}"
    }
}

fun Double.formatTo7DecimalPlaces(): String {
    val rounded = (this * 10000000).toLong() / 10000000.0
    return "$rounded".padEnd(9, '0')
}