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
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.math.PI

/**
 * @author Andreas Schildbach
 */
object LocationUtils {
    fun computeDistance(p1: Point, p2: Point): Float {
        return computeDistance(
            p1.lat,
            p1.lon,
            p2.lat,
            p2.lon
        )
    }

    /**
     * @param lat1
     * latitude of origin point in decimal degrees
     * @param lon1
     * longitude of origin point in decimal degrees
     * @param lat2
     * latitude of destination point in decimal degrees
     * @param lon2
     * longitude of destination point in decimal degrees
     *
     * @return distance in meters
     */
    @Suppress("NAME_SHADOWING", "LocalVariableName", "SpellCheckingInspection")
    fun computeDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        // Based on http://www.ngs.noaa.gov/PUBS_LIB/inverse.pdf
        // using the "Inverse Formula" (section 4)

        var lat1 = lat1
        var lon1 = lon1
        var lat2 = lat2
        var lon2 = lon2
        val maxIters = 20
        // Convert lat/long to radians
        lat1 *= PI / 180.0
        lat2 *= PI / 180.0
        lon1 *= PI / 180.0
        lon2 *= PI / 180.0

        val a = 6378137.0 // WGS84 major axis
        val b = 6356752.3142 // WGS84 semi-major axis
        val f = (a - b) / a
        val aSqMinusBSqOverBSq = (a * a - b * b) / (b * b)

        val L = lon2 - lon1
        var A = 0.0
        val U1 = atan((1.0 - f) * tan(lat1))
        val U2 = atan((1.0 - f) * tan(lat2))

        val cosU1 = cos(U1)
        val cosU2 = cos(U2)
        val sinU1 = sin(U1)
        val sinU2 = sin(U2)
        val cosU1cosU2 = cosU1 * cosU2
        val sinU1sinU2 = sinU1 * sinU2

        var sigma = 0.0
        var deltaSigma = 0.0
        var cosSqAlpha = 0.0
        var cos2SM = 0.0
        var cosSigma = 0.0
        var sinSigma = 0.0
        var cosLambda = 0.0
        var sinLambda = 0.0

        var lambda = L // initial guess
        for (iter in 0 until maxIters) {
            val lambdaOrig = lambda
            cosLambda = cos(lambda)
            sinLambda = sin(lambda)
            val t1 = cosU2 * sinLambda
            val t2 = cosU1 * sinU2 - sinU1 * cosU2 * cosLambda
            val sinSqSigma = t1 * t1 + t2 * t2 // (14)
            sinSigma = sqrt(sinSqSigma)
            cosSigma = sinU1sinU2 + cosU1cosU2 * cosLambda // (15)
            sigma = atan2(sinSigma, cosSigma) // (16)
            val sinAlpha = if ((sinSigma == 0.0)) 0.0 else cosU1cosU2 * sinLambda / sinSigma // (17)
            cosSqAlpha = 1.0 - sinAlpha * sinAlpha
            cos2SM =
                if ((cosSqAlpha == 0.0)) 0.0 else cosSigma - 2.0 * sinU1sinU2 / cosSqAlpha // (18)

            val uSquared = cosSqAlpha * aSqMinusBSqOverBSq // defn
            A = 1 + (uSquared / 16384.0) *  // (3)
                    (4096.0 + uSquared * (-768 + uSquared * (320.0 - 175.0 * uSquared)))
            val B = (uSquared / 1024.0) *  // (4)
                    (256.0 + uSquared * (-128.0 + uSquared * (74.0 - 47.0 * uSquared)))
            val C = (f / 16.0) * cosSqAlpha * (4.0 + f * (4.0 - 3.0 * cosSqAlpha)) // (10)
            val cos2SMSq = cos2SM * cos2SM
            // (6)
            deltaSigma = B * sinSigma * (cos2SM + (B / 4.0) * (cosSigma * (-1.0 + 2.0 * cos2SMSq)
                    - (B / 6.0) * cos2SM * (-3.0 + 4.0 * sinSigma * sinSigma) * (-3.0 + 4.0 * cos2SMSq)))

            lambda = L + ((1.0 - C) * f * sinAlpha
                    * (sigma + C * sinSigma * (cos2SM + C * cosSigma * (-1.0 + 2.0 * cos2SM * cos2SM)))) // (11)

            val delta = (lambda - lambdaOrig) / lambda

            if (abs(delta) < 1.0e-12) break
        }

        return (b * A * (sigma - deltaSigma)).toFloat()
    }
}
