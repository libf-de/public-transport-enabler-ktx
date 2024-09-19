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
data class Stop(
    val location: Location,
    val plannedArrivalTime: Long? = null,
    val predictedArrivalTime: Long? = null,
    val plannedArrivalPosition: Position? = null,
    val predictedArrivalPosition: Position? = null,
    val arrivalCancelled: Boolean = false,

    val plannedDepartureTime: Long? = null,
    val predictedDepartureTime: Long? = null,
    val plannedDeparturePosition: Position? = null,
    val predictedDeparturePosition: Position? = null,
    val departureCancelled: Boolean = false
) {
    fun getArrivalTime(preferPlanTime: Boolean = false): Long? {
        return if (preferPlanTime && plannedArrivalTime != null)
            plannedArrivalTime;
        else if (predictedArrivalTime != null)
            predictedArrivalTime;
        else if (plannedArrivalTime != null)
            plannedArrivalTime;
        else
            null;
    }

    fun isArrivalTimePredicted(preferPlanTime: Boolean = false): Boolean {
        return if (preferPlanTime && plannedArrivalTime != null)
            false;
        else
            predictedArrivalTime != null;
    }

    val arrivalPosition: Position?
        get() = predictedArrivalPosition ?: plannedArrivalPosition

    val arrivalDelay: Long?
        get() = if (plannedArrivalTime != null && predictedArrivalTime != null)
                    predictedArrivalTime - plannedArrivalTime
                else
                    null

    val isArrivalPositionPredicted: Boolean
        get() = predictedArrivalPosition != null

    fun getDepartureTime(preferPlanTime: Boolean = false): Long? {
        return if (preferPlanTime && plannedDepartureTime != null)
            plannedDepartureTime;
        else if (predictedDepartureTime != null)
            predictedDepartureTime;
        else if (plannedDepartureTime != null)
            plannedDepartureTime;
        else
            null;
    }

    fun isDepartureTimePredicted(preferPlanTime: Boolean = false): Boolean {
        return if (preferPlanTime && plannedDepartureTime != null)
            false;
        else
            predictedDepartureTime != null;
    }

    val isDeparturePositionPredicted: Boolean
        get() = predictedDeparturePosition != null

    val departurePosition: Position?
        get() = predictedDeparturePosition ?: plannedDeparturePosition

    val departureDelay: Long?
        get() = if (plannedDepartureTime != null && predictedDepartureTime != null)
                    predictedDepartureTime - plannedDepartureTime
                else
                    null

    val minTime: Long?
        get() = if (plannedDepartureTime == null
                    || (predictedDepartureTime != null && predictedDepartureTime < plannedDepartureTime))
                predictedDepartureTime;
            else
                plannedDepartureTime;

    val maxTime: Long?
        get() = if (plannedArrivalTime == null
                    || (predictedArrivalTime != null && predictedArrivalTime > plannedArrivalTime))
                predictedArrivalTime
            else
                plannedArrivalTime
}