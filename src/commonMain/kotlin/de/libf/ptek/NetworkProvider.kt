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

package de.libf.ptek

import de.libf.ptek.dto.Location
import de.libf.ptek.dto.Point
import de.libf.ptek.dto.Product
import de.libf.ptek.dto.Style
import de.libf.ptek.dto.NearbyLocationsResult
import de.libf.ptek.dto.QueryDeparturesResult
import de.libf.ptek.dto.QueryTripsResult
import de.libf.ptek.dto.SuggestLocationsResult
import de.libf.ptek.dto.TripOptions
import io.ktor.utils.io.errors.IOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Interface to be implemented by providers of transportation networks.
 *
 * @author Andreas Schildbach
 * @author Fabian Schillig
 */
interface NetworkProvider {
    enum class Capability {
        /* can suggest locations */
        SUGGEST_LOCATIONS,

        /* can determine nearby locations */
        NEARBY_LOCATIONS,

        /* can query for departures */
        DEPARTURES,

        /* can query trips */
        TRIPS,

        /* supports trip queries passing by a specific location */
        TRIPS_VIA
    }

    enum class Optimize {
        LEAST_DURATION, LEAST_CHANGES, LEAST_WALKING
    }

    enum class WalkSpeed {
        SLOW, NORMAL, FAST
    }

    enum class Accessibility {
        NEUTRAL, LIMITED, BARRIER_FREE
    }

    enum class TripFlag {
        BIKE
    }

    fun id(): NetworkId?

    fun hasCapabilities(vararg capabilities: Capability): Boolean

    /**
     * Find locations near to given location. At least one of lat/lon pair or station id must be present in
     * that location.
     *
     * @param types
     *            types of locations to find
     * @param location
     *            location to determine nearby stations
     * @param maxDistance
     *            maximum distance in meters, or {@code 0}
     * @param maxLocations
     *            maximum number of locations, or {@code 0}
     * @return nearby stations
     * @throws IOException
     */
    @Throws(IOException::class, CancellationException::class)
    suspend fun queryNearbyLocations(types: Set<Location.Type>, location: Location, maxDistance: Int, maxLocations: Int): NearbyLocationsResult

    /**
     * Get departures at a given station, probably live
     *
     * @param stationId
     *            id of the station
     * @param time
     *            desired time for departing, or {@code null} for the provider default
     * @param maxDepartures
     *            maximum number of departures to get or {@code 0}
     * @param equivs
     *            also query equivalent stations?
     * @return result object containing the departures
     * @throws IOException
     */
    @Throws(IOException::class, CancellationException::class)
    suspend fun queryDepartures(stationId: String, time: Long?, maxDepartures: Int, equivs: Boolean): QueryDeparturesResult

    /**
     * Meant for auto-completion of location names, like in an Android AutoCompleteTextView.
     *
     * @param constraint
     *            input by user so far
     * @param types
     *            types of locations to suggest, or {@code null} for any
     * @param maxLocations
     *            maximum number of locations to suggest or {@code 0}
     * @return location suggestions
     * @throws IOException
     */
    @Throws(IOException::class, CancellationException::class)
    suspend fun suggestLocations(constraint: CharSequence, types: Set<Location.Type>?, maxLocations: Int): SuggestLocationsResult

    @Deprecated("Deprecated in Java")
    suspend fun suggestLocations(constraint: CharSequence): SuggestLocationsResult

    /**
     * Typical products for a network
     *
     * @return products
     */
    fun defaultProducts(): Set<Product>

    /**
     * Query trips, asking for any ambiguousnesses
     *
     * @param from
     *            location to route from, mandatory
     * @param via
     *            location to route via, may be {@code null}
     * @param to
     *            location to route to, mandatory
     * @param date
     *            desired date for departing, mandatory
     * @param dep
     *            date is departure date? {@code true} for departure, {@code false} for arrival
     * @param options
     *            additional trip options such as products, optimize, walkSpeed and accessibility, or
     *            {@code null} for the provider default
     * @return result object that can contain alternatives to clear up ambiguousnesses, or contains possible
     *         trips
     * @throws IOException
     */
    @Throws(IOException::class, CancellationException::class)
    suspend fun queryTrips(from: Location, via: Location?, to: Location, date: Long, dep: Boolean, options: TripOptions?): QueryTripsResult?

    @Deprecated("Deprecated in Java")
    suspend fun queryTrips(from: Location, via: Location?, to: Location, date: Long, dep: Boolean, products: Set<Product>?, optimize: Optimize?, walkSpeed: WalkSpeed?, accessibility: Accessibility?, flags: Set<TripFlag>?): QueryTripsResult?

    /**
     * Query more trips (e.g. earlier or later)
     *
     * @param context
     *            context to query more trips from
     * @param later
     *            {@code true} to get later trips, {@code false} to get earlier trips
     * @return result object that contains possible trips
     * @throws IOException
     */
    @Throws(IOException::class, CancellationException::class)
    suspend fun queryMoreTrips(context: QueryTripsContext, later: Boolean): QueryTripsResult

    /**
     * Get style of line
     *
     * @param network
     *            network to disambiguate line, may be {@code null}
     * @param product
     *            line product to get style of, may be {@code null}
     * @param label
     *            line label to get style of, may be {@code null}
     * @return object containing background, foreground and optional border colors
     */
    fun lineStyle(network: String?, product: Product?, label: String?): Style

    /**
     * Gets the primary covered area of the network
     *
     * @return array containing points of a polygon (special case: just one coordinate defines just a center
     *         point)
     * @throws IOException
     */
    @Throws(IOException::class, CancellationException::class)
    suspend fun getArea(): List<Point>?

    suspend fun getStationMapUrl(station: Location): String?
}