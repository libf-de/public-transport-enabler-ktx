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
data class SuggestedLocation(
    val location: Location,
    val priority: Int
) : Comparable<SuggestedLocation> {
    constructor(location: Location) : this(location, 0)

    override fun compareTo(other: SuggestedLocation): Int {
        if (this.priority > other.priority)
            return -1
        else if (this.priority < other.priority)
            return 1

        other.location.type?.let { type ->
            val compareLocationType = this.location.type?.compareTo(other.location.type)
            if (compareLocationType != null && compareLocationType != 0)
                return compareLocationType
        }

        return 0
    }

}
