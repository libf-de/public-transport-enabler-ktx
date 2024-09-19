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
data class Trip private constructor(
    val id: String,
    val from: Location,
    val to: Location,
    val legs: List<Leg>,
    val fares: List<Fare>? = null,
    val capacity: List<Int>? = null,
    private val changes: Int? = null,
    private val ignore: Boolean = true
) {
    constructor(
        id: String? = null,
        from: Location,
        to: Location,
        legs: List<Leg>,
        fares: List<Fare>? = null,
        capacity: List<Int>? = null,
        changes: Int? = null
    ) : this(
        id = id ?: generateId(legs),
        from = from,
        to = to,
        legs = legs,
        fares = fares,
        capacity = capacity,
        changes = changes
    ) {
        if(legs.isEmpty()) { throw Exception("Legs cannot be empty") }
    }

    companion object {
        internal fun generateId(legs: List<Leg>): String {
            if(legs.isEmpty()) { throw Exception("Legs cannot be empty") }

            val builder = StringBuilder()

            legs.forEach { leg ->
                builder.append(leg.departure.id ?: leg.departure.coord).append('-')
                builder.append(leg.arrival.id ?: leg.arrival.coord).append('-')

                if(leg !is PublicLeg)
                    builder.append("individual")
                else {
                    leg.departureStop.plannedDepartureTime?.let {
                        builder.append(it).append('-')
                    }
                    leg.arrivalStop.plannedArrivalTime?.let {
                        builder.append(it).append('-')
                    }
                    leg.line.let {
                        builder.append(it.productCode).append(it.label)
                    }
                }

                builder.append("|")
            }

            builder.setLength(builder.length - 1);

            return builder.toString();
        }
    }

    val firstDepartureTime: Long
        get() = legs.first().departureTime

    val firstPublicLeg: PublicLeg?
        get() = legs.firstOrNull { it is PublicLeg } as PublicLeg?

    val firstPublicLegDepartureTime: Long?
        get() = firstPublicLeg?.departureTime

    val lastArrivalTime: Long
        get() = legs.last().arrivalTime

    val lastPublicLeg: PublicLeg?
        get() = legs.lastOrNull { it is PublicLeg } as PublicLeg?

    val lastPublicLegArrivalTime: Long?
        get() = lastPublicLeg?.arrivalTime

    /**
     * Duration of whole trip in milliseconds, including leading and trailing individual legs.
     *
     * @return duration in ms
     */
    val duration: Long
        get() = lastArrivalTime.minus(firstDepartureTime ?: 0L)

    /**
     * Duration of the public leg part in milliseconds. This includes individual legs between public legs, but
     * excludes individual legs that lead or trail the trip.
     *
     * @return duration in ms, or null if there are no public legs
     */
    val publicDuration: Long?
        get() = lastPublicLegArrivalTime?.minus(firstPublicLegDepartureTime ?: 0L)

    /** Minimum time occurring in this trip. */
    val minTime: Long?
        get() = legs.minOfOrNull { it.minTime }

    /** Maximum time occurring in this trip. */
    val maxTime: Long?
        get() = legs.maxOfOrNull { it.maxTime }

    /**
     * <p>
     * Number of changes on the trip.
     * </p>
     *
     * <p>
     * Returns {@link #changes} if it isn't null. Otherwise, it tries to compute the number by counting
     * public legs of the trip. The number of changes for a Trip consisting of only individual Legs is null.
     * </p>
     *
     * @return number of changes on the trip, or null if no public legs are involved
     */
    val numChanges: Int
        get() = changes ?: (legs.count { it is PublicLeg } - 1)

    /**
     * Returns true if it looks like the trip can be traveled. Returns false if legs overlap, important
     * departures or arrivals are cancelled and that sort of thing.
     */
    val isTravelable: Boolean
        get() {
            var time: Long? = null

            legs.forEach { leg ->
                if(leg is PublicLeg && (leg.departureStop.departureCancelled
                            || leg.arrivalStop.arrivalCancelled))
                    return false

                if(time != null && leg.departureTime.let { it < (time ?: 0L) })
                    return false
                time = leg.departureTime

                if(time != null && leg.arrivalTime.let { it < (time ?: 0L) })
                    return false
                time = leg.arrivalTime
            }

            return true
        }

    /** If an individual leg overlaps, try to adjust so that it doesn't. */
    fun adjustedUntravelableIndividualLegs(): Trip {
        if(legs.isEmpty()) return this

        val modifiedLegs = legs.toMutableList()

        legs.forEachIndexed { index, leg ->
            if (index == 0) return@forEachIndexed

            if (leg is IndividualLeg) {
                val prevLeg = legs[index - 1]

                if (leg.departureTime < prevLeg.arrivalTime) {
                    modifiedLegs[index] = leg.movedClone(prevLeg.arrivalTime)
                }
            }
        }

        return this.copy(legs = modifiedLegs.toList())
    }

    val products: Set<Product>
        get() = legs.filterIsInstance<PublicLeg>().map { it.line.product }.toSet()

    override fun equals(other: Any?): Boolean {
        if(other === this) return true
        if(other !is Trip) return false
        return id == other.id
    }

    override fun hashCode(): Int {
        return id.hashCode()
    }
}