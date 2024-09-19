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

data class PublicLeg(
    val line: Line,
    val destination: Location?,
    val departureStop: Stop,
    val arrivalStop: Stop,
    val intermediateStops: List<Stop>?,
    override val path: List<Point>?,
    val message: String?
) : Leg {
    override val departure: Location
        get() = departureStop.location
    override val arrival: Location
        get() = arrivalStop.location
    override val departureTime: Long
        get() = departureStop.getDepartureTime(false)!!
    override val arrivalTime: Long
        get() = arrivalStop.getArrivalTime(false)!!
    override val minTime: Long
        get() = departureStop.minTime!!
    override val maxTime: Long
        get() = arrivalStop.maxTime!!

    override fun departureTime(preferPlanTime: Boolean): Long {
        return departureStop.getDepartureTime(preferPlanTime)!!
    }
    override fun arrivalTime(preferPlanTime: Boolean): Long {
        return arrivalStop.getArrivalTime(preferPlanTime)!!
    }

    val isDepartureTimePredicted: Boolean
        get() = departureStop.isDepartureTimePredicted(false)
    val departureDelay: Long?
        get() = departureStop.departureDelay
    val departurePosition: Position?
        get() = departureStop.departurePosition

    val isArrivalTimePredicted: Boolean
        get() = arrivalStop.isArrivalTimePredicted(false)
    val arrivalDelay: Long?
        get() = arrivalStop.arrivalDelay
    val arrivalPosition: Position?
        get() = arrivalStop.arrivalPosition
}
