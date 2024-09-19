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
import de.libf.ptek.dto.NearbyLocationsResult
import de.libf.ptek.dto.Point
import de.libf.ptek.dto.Product
import de.libf.ptek.dto.QueryDeparturesResult
import de.libf.ptek.dto.QueryTripsResult
import de.libf.ptek.dto.ResultHeader
import de.libf.ptek.dto.Style
import de.libf.ptek.dto.SuggestLocationsResult
import de.libf.ptek.dto.Trip
import de.libf.ptek.dto.TripOptions
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.headersOf
import kotlinx.datetime.Instant
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.format
import kotlinx.datetime.format.DateTimeComponents
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.ClassDiscriminatorMode
import kotlinx.serialization.json.Json

class DbNextProvider(private val httpClient: HttpClient = HttpClient(CIO)) : NetworkProvider {
    private val sendJson = Json { encodeDefaults = true; explicitNulls = false; classDiscriminatorMode = ClassDiscriminatorMode.NONE }
    private val _httpClient = httpClient.config {
        headersOf("X-Correlation-ID", "yes")
    }

    private val bahnDateFormat = DateTimeComponents.Format {
        year()
        chars("-")
        monthNumber()
        chars("-")
        dayOfMonth()
        chars("T")
        hour()
        chars(":")
        minute()
        chars(":")
        second()
        chars(".000000")
        offset(UtcOffset.Formats.ISO)
    }

    override fun id(): NetworkId = NetworkId.DB_NEXT

    private val CAPABILITIES = listOf(
        NetworkProvider.Capability.SUGGEST_LOCATIONS,
        NetworkProvider.Capability.NEARBY_LOCATIONS,
        NetworkProvider.Capability.DEPARTURES,
        NetworkProvider.Capability.TRIPS,
        NetworkProvider.Capability.TRIPS_VIA
    )

    override fun hasCapabilities(vararg capabilities: NetworkProvider.Capability): Boolean {
        return CAPABILITIES.containsAll(capabilities.toList())
    }

    override suspend fun queryNearbyLocations(
        types: Set<Location.Type>,
        location: Location,
        maxDistance: Int,
        maxLocations: Int
    ): NearbyLocationsResult {
        return NearbyLocationsResult(
            status = NearbyLocationsResult.Status.SERVICE_DOWN
        )
    }

    override suspend fun queryDepartures(
        stationId: String,
        time: Long?,
        maxDepartures: Int,
        equivs: Boolean
    ): QueryDeparturesResult {
        TODO("Not yet implemented")
    }

    override suspend fun suggestLocations(
        constraint: CharSequence,
        types: Set<Location.Type>?,
        maxLocations: Int
    ): SuggestLocationsResult {
        TODO("Not yet implemented")
    }

    override suspend fun suggestLocations(constraint: CharSequence): SuggestLocationsResult {
        TODO("Not yet implemented")
    }

    override fun defaultProducts(): Set<Product> {
        TODO("Not yet implemented")
    }

    override suspend fun queryTrips(
        from: Location,
        via: Location?,
        to: Location,
        date: Long,
        dep: Boolean,
        options: TripOptions?
    ): QueryTripsResult? {
        TODO("Not yet implemented")
    }

    override suspend fun queryTrips(
        from: Location,
        via: Location?,
        to: Location,
        date: Long,
        dep: Boolean,
        products: Set<Product>?,
        optimize: NetworkProvider.Optimize?,
        walkSpeed: NetworkProvider.WalkSpeed?,
        accessibility: NetworkProvider.Accessibility?,
        flags: Set<NetworkProvider.TripFlag>?
    ): QueryTripsResult? {
        val requestString = sendJson.encodeToString(
            QueryTripsRequest(
                reiseHin = QueryTripsRequest.TripRequestWrap(
                    QueryTripsRequest.TripRequest(
                        abgangsLocationId = from.id!!,
                        viaLocations = via?.let {
                            listOf(
                                QueryTripsRequest.TripRequest.ViaLocation(
                                    locationId = it.id!!,
                                )
                            )
                        },
                        zielLocationId = to.id!!,
                        zeitWunsch = QueryTripsRequest.TripRequest.TimeRequest(
                            reiseDatum = Instant.fromEpochMilliseconds(date).format(bahnDateFormat),
                            zeitPunktArt = if(dep)
                                QueryTripsRequest.TripRequest.TimeRequest.TimeType.DEPARTURE
                            else QueryTripsRequest.TripRequest.TimeRequest.TimeType.ARRIVAL
                        )
                    )
                ),
                reisendenProfil = QueryTripsRequest.TravellerProfileWrapper(
                    listOf(
                        QueryTripsRequest.TravellerProfile(
                            reisendenTyp = QueryTripsRequest.TravellerProfile.TravellerType.ADULT,
                            ermaessigungen = listOf(QueryTripsRequest.TravellerProfile.Discounts.NONE)
                        )
                    )
                )
            )
        )

        val rsp = _httpClient.post("https://app.vendo.noncd.db.de/mob/angebote/fahrplan") {
            setBody(requestString)
            headersOf("Content-Type", "application/x.db.vendo.mob.verbindungssuche.v7+json")
            headersOf("Accept", "application/x.db.vendo.mob.verbindungssuche.v7+json")
        }

        val data = rsp.body<QueryTripsResponse>()

        if(data.verbindungen.isNotEmpty()) {
            val trips = data.verbindungen.map {
                val fromLocation = it.verbindung.verbindungsAbschnitte.first().abgangsOrt.toLocation()
                val toLocation = it.verbindung.verbindungsAbschnitte.last().ankunftsOrt.toLocation()



                //id: String? = null,
                //        from: Location,
                //        to: Location,
                //        legs: List<Leg>,
                //        fares: List<Fare>? = null,
                //        capacity: List<Int>? = null,
                //        changes: Int? = null
//                Trip(
//                    id = it.verbindung.kontext,
//                    from = Location
//
//                )
            }


            val resultHeader = ResultHeader(
                network = id(),
                serverProduct = "DBNext"
            )
            return QueryTripsResult(
                header = resultHeader,
                queryUri = "yes",
                from = from,
                via = via,
                to = to,
                context = QueryTripsResponse.DbNextContext(
                    data.frueherContext,
                    data.spaeterContext
                ), listOf())
        }

//        return QueryTripsResult()

        return null
    }

    override suspend fun queryMoreTrips(
        context: QueryTripsContext,
        later: Boolean
    ): QueryTripsResult {
        TODO("Not yet implemented")
    }

    override fun lineStyle(network: String?, product: Product?, label: String?): Style {
        TODO("Not yet implemented")
    }

    override suspend fun getArea(): List<Point>? {
        TODO("Not yet implemented")
    }

    override suspend fun getStationMapUrl(station: Location): String? {
        TODO("Not yet implemented")
    }

    @Serializable
    private data class QueryTripsResponse(
        val frueherContext: String? = null,
        val spaeterContext: String? = null,
        val verbindungen: List<TripObj>
    ) {
        @Serializable
        data class DbNextContext(
            val frueherContext: String? = null,
            val spaeterContext: String? = null
        ) : QueryTripsContext {
            override val canQueryLater: Boolean
                get() = frueherContext != null
            override val canQueryEarlier: Boolean
                get() = spaeterContext != null
        }


        @Serializable
        data class PointObj(val latitude: Double, val longitude: Double)

        @Serializable
        data class LocationObj(
            val evaNr: String,
            val locationId: String,
            val name: String,
            val position: PointObj,
            val stationId: String
        )

        @Serializable
        data class TripObj(
            val verbindung: ConnectionObj
        ) {

            @Serializable
            data class ConnectionObj(
                val kontext: String,
                val reiseDauer: Int,
                val alternative: Boolean,
                val echtzeitNotizen: List<NoteObj>,
                val himNotizen: List<String>? = null, //TODO
                val topNotiz: NoteObj? = null,
                val umstiegeAnzahl: Int,
                val verbindungsAbschnitte: List<TripSegmentObj>
            ) {
                @Serializable
                data class NoteObj(val text: String, val prio: String? = null, val key: String? = null)

                @Serializable
                data class TripSegmentObj(
                    val kurztext: String? = null,
                    val mitteltext: String? = null,
                    val langtext: String? = null,
                    val verkehrsmittelNummer: String? = null,
                    val zugverlaufId: String? = null,
                    val richtung: String? = null,
                    val attributNotizen: List<NoteObj> = emptyList(),
                    val wagenreihung: Boolean = false,
                    val typ: String? = null,
                    val abgangsDatum: String,
                    val abgangsOrt: LocationObj,
                    val abschnittsDauer: Int,
                    val ankunftsDatum: String,
                    val ankunftsOrt: LocationObj,
                    val ezAbgangsDatum: String,
                    val ezAnkunftsDatum: String,
                    val halte: List<StopObj>,
                ) {
                    @Serializable
                    data class StopObj(
                        val abgangsDatum: String,
                        val ankunftsDatum: String? = null,
                        val ezAbgangsDatum: String? = null,
                        val ezAnkunftsDatum: String? = null,
                        val ezGleis: String? = null,
                        val gleis: String,
                        val ort: LocationObj,
                        val serviceNotiz: NoteObj? = null
                    )
                }
            }
        }

    }

    @Serializable
    private data class QueryTripsRequest(
        val autonomeReservierung: Boolean = false,
        val einstiegsTypList: List<String> = listOf("STANDARD"),
        val klasse: TripClass = TripClass.KL2,
        val reiseHin: TripRequestWrap,
        val reisendenProfil: TravellerProfileWrapper,
        val reservierungsKontingenteVorhanden: Boolean = false
    ) {
        enum class TripClass(val value: String) {
            KL1("KLASSE_1"),
            KL2("KLASSE_2"),
        }

        @Serializable
        data class TripRequestWrap(val wunsch: TripRequest)

        @Serializable
        data class TripRequest(
            val abgangsLocationId: String,
            val minUmstiegsdauer: Int? = null,
            val verkehrsmittel: List<String> = listOf("ALL"),
            val zeitWunsch: TimeRequest,
            val zielLocationId: String,
            val viaLocations: List<ViaLocation>? = null
        ) {
            @Serializable
            data class ViaLocation(
                val locationId: String,
                val minUmstiegsdauer: Int? = null,
                val verkehrsmittel: List<String> = listOf("ALL")
            )

            @Serializable
            data class TimeRequest(
                val reiseDatum: String, //2024-09-13T15:15:31.151013+02:00
                val zeitPunktArt: TimeType
            ) {
                enum class TimeType(val value: String) {
                    DEPARTURE("ABFAHRT"),
                    ARRIVAL("ANKUNFT")
                }
            }
        }

        @Serializable
        data class TravellerProfileWrapper(val reisende: List<TravellerProfile>)

        @Serializable
        data class TravellerProfile(
            val ermaessigungen: List<Discounts> = listOf(Discounts.NONE),
            val reisendenTyp: TravellerType
        ) {
            enum class TravellerType(val value: String) {
                NEWBORN("KLEINKIND"),
                CHILD("FAMILIENKIND"),
                TEENAGER("JUGENDLICHER"),
                ADULT("ERWACHSENER"),
                SENIOR("SENIOR"),
                DOG("HUND"),
                BIKE("FAHRRAD")
            }

            enum class Discounts(val value: String) {
                NONE("KEINE_ERMAESSIGUNG KLASSENLOS"),
                BAHNCARD25_KL2("BAHNCARD25 KLASSE_2"),
                BAHNCARD25_KL1("BAHNCARD25 KLASSE_1"),
                BAHNCARD50_KL2("BAHNCARD50 KLASSE_2"),
                BAHNCARD50_KL1("BAHNCARD50 KLASSE_1"),
                BAHNCARD100_KL2("BAHNCARD100 KLASSE_2"),
                BAHNCARD100_KL1("BAHNCARD100 KLASSE_1"),
                BCBUSINESS25_KL2("BAHNCARDBUSINESS25 KLASSE_2"),
                BCBUSINESS25_KL1("BAHNCARDBUSINESS25 KLASSE_1"),
                BCBUSINESS50_KL2("BAHNCARDBUSINESS50 KLASSE_2"),
                BCBUSINESS50_KL1("BAHNCARDBUSINESS50 KLASSE_1"),
                CH_GENERAL_KL2("CH-GENERAL-ABONNEMENT KLASSE_2"),
                CH_GENERAL_KL1("CH-GENERAL-ABONNEMENT KLASSE_1"),
                HALBTAX("CH-HALBTAXABO_OHNE_RAILPLUS KLASSENLOS"),
                AT_VORTEILSCARD("A-VORTEILSCARD KLASSENLOS"),
                NL_40("NL-40_OHNE_RAILPLUS KLASSENLOS"),
                AT_KLIMATICKET("KLIMATICKET_OE KLASSENLOS")
            }
        }


    }

    private suspend fun DbNextProvider.QueryTripsResponse.LocationObj.toLocation(): Location {
        //TODO: Query products from https://app.vendo.noncd.db.de/mob/location/details/<EvaNr>
        return Location(
            id = listOf(this.locationId, this.evaNr, this.stationId).joinToString("@@@"),
            type = this.locationId.getLocationType(),
            name = this.name,
            place = this.name,
            coord = Point.fromDouble(this.position.latitude, this.position.longitude),
            products = emptySet()
        )
    }

    private fun String.getLocationType(): Location.Type {
        if(this.startsWith("A=1")) {
            return Location.Type.STATION
        } else if(this.startsWith("A=2")) {
            return Location.Type.COORD
        } else if(this.startsWith("A=4")) {
            return Location.Type.POI
        } else {
            return Location.Type.ANY
        }

    }
}


