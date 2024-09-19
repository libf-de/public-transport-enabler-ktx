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
data class Departure(
    val plannedTime: Long?,
    val predictedTime: Long?,
    val line: Line,
    val position: Position?,
    val destination: Location?,
    val capacity: IntArray?,
    val message: String?
) {
    init {
        if(plannedTime == null && predictedTime == null) throw Exception("plannedTime and predictedTime cannot be null")
    }

    val time: Long?
        get() = predictedTime ?: plannedTime

    companion object {
        val TIME_COMPARATOR = Comparator<Departure> { departure0, departure1 -> departure0.time!!.compareTo(departure1.time!!) }
    }
}

