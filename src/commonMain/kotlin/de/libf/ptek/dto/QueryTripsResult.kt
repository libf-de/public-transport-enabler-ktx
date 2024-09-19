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

import de.libf.ptek.QueryTripsContext
import kotlinx.serialization.Serializable

@Serializable
data class QueryTripsResult(
    val header: ResultHeader? = null,
    val status: Status,
    val statusHint: String? = null,

    val ambiguousFrom: List<Location>? = null,
    val ambiguousVia: List<Location>? = null,
    val ambiguousTo: List<Location>? = null,

    val queryUri: String? = null,
    val from: Location? = null,
    val via: Location? = null,
    val to: Location? = null,
    val context: QueryTripsContext? = null,
    val trips: List<Trip> = emptyList()
) {
    enum class Status { OK, AMBIGUOUS, TOO_CLOSE, UNKNOWN_FROM, UNKNOWN_VIA, UNKNOWN_TO, UNKNOWN_LOCATION, UNRESOLVABLE_ADDRESS, NO_TRIPS, INVALID_DATE, SERVICE_DOWN }

    //final ResultHeader header, final String queryUri, final Location from, final Location via,
    //            final Location to, final QueryTripsContext context, final List<Trip> trips
    constructor(header: ResultHeader?, queryUri: String?, from: Location, via: Location?, to: Location, context: QueryTripsContext, trips: List<Trip>) :
            this(header = header, status = Status.OK, queryUri = queryUri, from = from, via = via, to = to, context = context, trips = trips)

    constructor(header: ResultHeader?, ambiguousFrom: List<Location>, ambiguousVia: List<Location>, ambiguousTo: List<Location>) :
            this(header = header, status = Status.AMBIGUOUS, ambiguousFrom = ambiguousFrom, ambiguousVia = ambiguousVia, ambiguousTo = ambiguousTo)


    fun toShortString(): String {
        if (status == Status.OK)
            return trips.size.toString() + " trips"
        else
            return status.toString()
    }

}
