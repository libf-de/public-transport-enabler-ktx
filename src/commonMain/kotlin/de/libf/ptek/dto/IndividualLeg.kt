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

data class IndividualLeg(
    val type: Type,
    override val departure: Location,
    override val departureTime: Long,
    override val arrival: Location,
    override val arrivalTime: Long,
    override val path: List<Point>?,
    val distance: Int,
) : Leg {
    enum class Type { WALK, BIKE, CAR, TRANSFER, CHECK_IN, CHECK_OUT }

    override val minTime: Long
        get() = departureTime

    override val maxTime: Long
        get() = arrivalTime

    override fun departureTime(preferPlanTime: Boolean): Long {
        return departureTime
    }

    override fun arrivalTime(preferPlanTime: Boolean): Long {
        return departureTime
    }

    fun movedClone(newDepartureTime: Long): IndividualLeg {
        val newArrivalTime = newDepartureTime + (arrivalTime - departureTime)
        return this.copy(
            departureTime = newDepartureTime,
            arrivalTime = newArrivalTime
        )
    }

}
