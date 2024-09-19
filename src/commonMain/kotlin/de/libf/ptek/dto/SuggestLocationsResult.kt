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

/**
 * @author Andreas Schildbach
 */
data class SuggestLocationsResult(
    val header: ResultHeader? = null,
    val status: Status,
    val suggestedLocations: List<SuggestedLocation>?
) {
    enum class Status { OK, SERVICE_DOWN }

    constructor(header: ResultHeader, suggestedLocations: List<SuggestedLocation>) : this(header, Status.OK, suggestedLocations)

    constructor(header: ResultHeader, status: Status) : this(header, status, null)

    fun getLocations(): List<Location>? {
        if (status == Status.OK)
            return suggestedLocations?.map { it.location }
        else
            return null
    }

    fun toShortString(): String {
        if (status == Status.OK)
            return suggestedLocations?.size.toString() + " locations"
        else
            return status.toString()
    }
}
