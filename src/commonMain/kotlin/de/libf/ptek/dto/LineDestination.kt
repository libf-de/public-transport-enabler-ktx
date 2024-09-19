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
data class LineDestination(
    val line: Line,
    val destination: Location?,
) {
    override fun equals(o: Any?): Boolean {
        if (o === this) return true
        if (o !is LineDestination) return false
        if (this.line != o.line) return false

        // This workaround is necessary because in rare cases destinations have IDs of other locations.
        val thisDestinationName: String? = this.destination?.uniqueShortName
        val otherDestinationName: String? = o.destination?.uniqueShortName

        if(thisDestinationName != otherDestinationName) return false

        return true
    }

    override fun hashCode(): Int {
        // This workaround is necessary because in rare cases destinations have IDs of other locations.
        return this.line.hashCode() + 31 * (this.destination?.uniqueShortName?.hashCode() ?: 0)
    }
}
