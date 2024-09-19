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
import de.libf.ptek.dto.Product
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.head
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import kotlinx.datetime.Clock
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.format
import kotlinx.datetime.format.DateTimeComponents
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.ClassDiscriminatorMode
import kotlinx.serialization.json.Json
import kotlin.math.min


/**
 * Provider implementation for Deutsche Bahn (Germany).
 *
 * @author Andreas Schildbach
 */
class DbProvider(apiClient: String?, apiAuthorization: String?, salt: ByteArray?, httpClient: HttpClient = defaultHttpClient) :
    AbstractHafasClientInterfaceProvider(NetworkId.DB, httpClient, API_BASE, PRODUCTS_MAP) {
    constructor(apiAuthorization: String?, salt: ByteArray?, httpClient: HttpClient = defaultHttpClient) : this(
        DEFAULT_API_CLIENT,
        apiAuthorization,
        salt,
        httpClient
    )

    override fun defaultProducts(): Set<Product> {
        return Product.ALL
    }

    override suspend fun getStationMapUrl(station: Location): String? {
        val sendJson = Json { encodeDefaults = true; explicitNulls = false; classDiscriminatorMode = ClassDiscriminatorMode.NONE }

        @Serializable
        data class StationInfoRequest(
            val anfragezeit: String,
            val datum: String,
            val ursprungsBahnhofId: String,
            val verkehrsmittel: List<String> = listOf("ALL"),
        )

        @Serializable
        data class ReqLocation(
            val evaNr: String,
            val locationId: String,
            val stationId: String
        )

        @Serializable
        data class DeparturePosition(
            val abfrageOrt: ReqLocation
        )

        @Serializable
        data class StationInfoResponse(val bahnhofstafelAbfahrtPositionen: List<DeparturePosition>)

        val now = Clock.System.now()

        val date = now.format(DateTimeComponents.Format {
            year()
            chars("-")
            monthNumber()
            chars("-")
            dayOfMonth()
        })

        val time = now.format(DateTimeComponents.Format {
            hour()
            chars(":")
            minute()
        })

        val request = sendJson.encodeToString(StationInfoRequest(
            anfragezeit = time,
            datum = date,
            ursprungsBahnhofId = station.id!!
        ))

        val rsp = httpClient.post("https://app.vendo.noncd.db.de/mob/bahnhofstafel/abfahrt") {
            headersOf("X-Correlation-ID", "yes")
            headersOf("Content-Type", "application/x.db.vendo.mob.bahnhofstafeln.v2+json")
            headersOf("Accept", "application/x.db.vendo.mob.bahnhofstafeln.v2+json")
            setBody(request)
        }

        val data = rsp.body<StationInfoResponse>()

        return data.bahnhofstafelAbfahrtPositionen
            .sortedBy { levenshtein(it.abfrageOrt.locationId, station.id) }
            .firstOrNull()
            ?.let { "https://www.bahnhof.de/downloads/station-plans/${it.abfrageOrt.stationId}.pdf" }
            ?.takeIf {
                httpClient.head(it).status == HttpStatusCode.OK
            }
    }

    init {
        setApiVersion("1.15")
        setApiExt("DB.R18.06.a")
        setApiClient(apiClient)
        setApiAuthorization(apiAuthorization)
        setRequestChecksumSalt(salt)
    }

    override fun splitStationName(name: String): Array<String?> {
        val m = P_SPLIT_NAME_ONE_COMMA.matchEntire(name)
        if (m != null) return arrayOf(m.groupValues[2], m.groupValues[1])
        return super.splitStationName(name)
    }

    override fun splitPOI(poi: String): Array<String?> {
        val m = P_SPLIT_NAME_FIRST_COMMA.matchEntire(poi)
        if (m != null) return arrayOf(m.groupValues[1], m.groupValues[2])
        return super.splitStationName(poi)
    }

    override fun splitAddress(address: String): Array<String?> {
        val m = P_SPLIT_NAME_FIRST_COMMA.matchEntire(address)
        if (m != null) return arrayOf(m.groupValues[1], m.groupValues[2])
        return super.splitStationName(address)
    }

    companion object {
        private val API_BASE: Url = Url("https://reiseauskunft.bahn.de/bin/")
        private val PRODUCTS_MAP: List<Product?> = listOf(
            Product.HIGH_SPEED_TRAIN,  // ICE-Züge
            Product.HIGH_SPEED_TRAIN,  // Intercity- und Eurocityzüge
            Product.HIGH_SPEED_TRAIN,  // Interregio- und Schnellzüge
            Product.REGIONAL_TRAIN,  // Nahverkehr, sonstige Züge
            Product.SUBURBAN_TRAIN,  // S-Bahn
            Product.BUS,  // Busse
            Product.FERRY,  // Schiffe
            Product.SUBWAY,  // U-Bahnen
            Product.TRAM,  // Straßenbahnen
            Product.ON_DEMAND,  // Anruf-Sammeltaxi
            null, null, null, null
        )
        private const val DEFAULT_API_CLIENT =
            "{\"id\":\"DB\",\"v\":\"16040000\",\"type\":\"AND\",\"name\":\"DB Navigator\"}"

        private val P_SPLIT_NAME_ONE_COMMA = Regex("([^,]*), ([^,]*)")
    }

    private fun levenshtein(lhs : CharSequence, rhs : CharSequence) : Int {
        if(lhs == rhs) { return 0 }
        if(lhs.isEmpty()) { return rhs.length }
        if(rhs.isEmpty()) { return lhs.length }

        val lhsLength = lhs.length + 1
        val rhsLength = rhs.length + 1

        var cost = Array(lhsLength) { it }
        var newCost = Array(lhsLength) { 0 }

        for (i in 1..rhsLength-1) {
            newCost[0] = i

            for (j in 1..lhsLength-1) {
                val match = if(lhs[j - 1] == rhs[i - 1]) 0 else 1

                val costReplace = cost[j - 1] + match
                val costInsert = cost[j] + 1
                val costDelete = newCost[j - 1] + 1

                newCost[j] = min(min(costInsert, costDelete), costReplace)
            }

            val swap = cost
            cost = newCost
            newCost = swap
        }

        return cost[lhsLength - 1]
    }
}


