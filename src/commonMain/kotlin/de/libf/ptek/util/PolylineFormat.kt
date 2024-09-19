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

import de.libf.ptek.dto.Point

/**
 *
 *
 * Implementation of the
 * [Encoded Polyline
 * Algorithm Format](https://developers.google.com/maps/documentation/utilities/polylinealgorithm).
 *
 *
 * @author Andreas Schildbach
 */
object PolylineFormat {
    fun decode(encodedPolyline: String): List<Point> {
        val len = encodedPolyline.length
        val path: MutableList<Point> = mutableListOf()

        var lat = 0
        var lon = 0
        var index = 0
        while (index < len) {
            var latResult = 1
            var latShift = 0
            var latB: Int
            do {
                latB = encodedPolyline[index++].code - 63 - 1
                latResult += latB shl latShift
                latShift += 5
            } while (latB >= 0x1f)
            lat += if ((latResult and 1) != 0) (latResult shr 1).inv() else (latResult shr 1)

            var lonResult = 1
            var lonShift = 0
            var lonB: Int
            do {
                lonB = encodedPolyline[index++].code - 63 - 1
                lonResult += lonB shl lonShift
                lonShift += 5
            } while (lonB >= 0x1f)
            lon += if ((lonResult and 1) != 0) (lonResult shr 1).inv() else (lonResult shr 1)

            path.add(Point.from1E5(lat, lon))
        }
        return path
    }
}
