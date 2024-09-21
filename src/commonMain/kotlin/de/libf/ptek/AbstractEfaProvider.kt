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

import de.libf.ptek.AbstractEfaProvider.Companion.P_STATION_NAME_WHITESPACE
import de.libf.ptek.AbstractEfaProvider.ItdTripsResponse.ItdTripRequest.ItdItinerary.ItdRouteList.ItdRoute.ItdPartialRouteList.ItdPartialRoute.ItdPoint
import de.libf.ptek.AbstractEfaProvider.JsonMessage
import de.libf.ptek.NetworkProvider.WalkSpeed
import de.libf.ptek.dto.Departure
import de.libf.ptek.dto.Fare
import de.libf.ptek.dto.IndividualLeg
import de.libf.ptek.dto.Leg
import de.libf.ptek.dto.Line
import de.libf.ptek.dto.LineDestination
import de.libf.ptek.dto.Location
import de.libf.ptek.dto.NearbyLocationsResult
import de.libf.ptek.dto.Point
import de.libf.ptek.dto.Product
import de.libf.ptek.dto.PublicLeg
import de.libf.ptek.dto.QueryDeparturesResult
import de.libf.ptek.dto.QueryTripsResult
import de.libf.ptek.dto.ResultHeader
import de.libf.ptek.dto.StationDepartures
import de.libf.ptek.dto.Stop
import de.libf.ptek.dto.SuggestLocationsResult
import de.libf.ptek.dto.SuggestedLocation
import de.libf.ptek.dto.Trip
import de.libf.ptek.dto.TripOptions
import de.libf.ptek.dto.formatTo7DecimalPlaces
import de.libf.ptek.exception.ParserException
import de.libf.ptek.util.AbstractLogger
import de.libf.ptek.util.PathUtil
import de.libf.ptek.util.PrintlnLogger
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.appendPathSegments
import io.ktor.serialization.kotlinx.xml.xml
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.asTimeZone
import kotlinx.datetime.format
import kotlinx.datetime.format.DateTimeComponents
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.modules.serializersModuleOf
import net.thauvin.erik.urlencoder.UrlEncoderUtil
import nl.adaptivity.xmlutil.serialization.XML
import nl.adaptivity.xmlutil.serialization.XmlElement
import nl.adaptivity.xmlutil.serialization.XmlSerialName
import nl.adaptivity.xmlutil.serialization.XmlValue
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.time.Duration.Companion.minutes

/**
 * @author Andreas Schildbach
 */
abstract class AbstractEfaProvider(
    network: NetworkId?,
    private val departureMonitorEndpoint: Url,
    private val tripEndpoint: Url,
    private val stopFinderEndpoint: Url,
    private val coordEndpoint: Url,
    httpClient: HttpClient = defaultHttpClient,
    private val xml: XML = defaultXml,
    private val xmlHttpClient: HttpClient = httpClient.installDefaultXml(),
) : AbstractNetworkProvider(network!!, httpClient) {
    private val CAPABILITIES: List<NetworkProvider.Capability> = listOf(
        NetworkProvider.Capability.SUGGEST_LOCATIONS,
        NetworkProvider.Capability.NEARBY_LOCATIONS,
        NetworkProvider.Capability.DEPARTURES,
        NetworkProvider.Capability.TRIPS,
        NetworkProvider.Capability.TRIPS_VIA
    )

    private var language = "de"
    private var needsSpEncId = false
    private var includeRegionId = true
    private var useProxFootSearch = true

    private var httpReferer: String? = null

    private var httpRefererTrip: String? = null
    private var useRouteIndexAsTripId = true
    private var useLineRestriction = true
    private var useStringCoordListOutputFormat = true
    private var fareCorrectionFactor = 1f

    private val timezone: TimeZone = TimeZone.UTC

//    private var parserFactory: XmlPullParserFactory? = null

    private data class Context(val context: String?) : QueryTripsContext {
        override val canQueryEarlier: Boolean
            get() = false   //TODO enable earlier querying

        override val canQueryLater: Boolean
            get() = context != null
    }

    constructor(network: NetworkId?, apiBase: Url, httpClient: HttpClient = defaultHttpClient) : this(
        network,
        apiBase,
        null,
        null,
        null,
        null,
        httpClient
    )

    constructor(
        network: NetworkId?, apiBase: Url, departureMonitorEndpoint: String?,
        tripEndpoint: String?, stopFinderEndpoint: String?, coordEndpoint: String?,
        httpClient: HttpClient = defaultHttpClient
    ) : this(
        network,
        URLBuilder(apiBase)
            .appendPathSegments(departureMonitorEndpoint ?: DEFAULT_DEPARTURE_MONITOR_ENDPOINT)
            .build(),
        URLBuilder(apiBase)
            .appendPathSegments(tripEndpoint ?: DEFAULT_TRIP_ENDPOINT)
            .build(),
        URLBuilder(apiBase)
            .appendPathSegments(stopFinderEndpoint ?: DEFAULT_STOPFINDER_ENDPOINT)
            .build(),
        URLBuilder(apiBase)
            .appendPathSegments(coordEndpoint ?: DEFAULT_COORD_ENDPOINT)
            .build(),
        httpClient
    )

    protected fun setLanguage(language: String): AbstractEfaProvider {
        this.language = language
        return this
    }

    protected fun setHttpReferer(httpReferer: String?): AbstractEfaProvider {
        this.httpReferer = httpReferer
        this.httpRefererTrip = httpReferer
        return this
    }

    fun setHttpRefererTrip(httpRefererTrip: String?): AbstractEfaProvider {
        this.httpRefererTrip = httpRefererTrip
        return this
    }

    protected fun setIncludeRegionId(includeRegionId: Boolean): AbstractEfaProvider {
        this.includeRegionId = includeRegionId
        return this
    }

    protected fun setUseProxFootSearch(useProxFootSearch: Boolean): AbstractEfaProvider {
        this.useProxFootSearch = useProxFootSearch
        return this
    }

    protected fun setUseRouteIndexAsTripId(useRouteIndexAsTripId: Boolean): AbstractEfaProvider {
        this.useRouteIndexAsTripId = useRouteIndexAsTripId
        return this
    }

    protected fun setUseLineRestriction(useLineRestriction: Boolean): AbstractEfaProvider {
        this.useLineRestriction = useLineRestriction
        return this
    }

    protected fun setUseStringCoordListOutputFormat(useStringCoordListOutputFormat: Boolean): AbstractEfaProvider {
        this.useStringCoordListOutputFormat = useStringCoordListOutputFormat
        return this
    }

    protected fun setNeedsSpEncId(needsSpEncId: Boolean): AbstractEfaProvider {
        this.needsSpEncId = needsSpEncId
        return this
    }

    protected fun setFareCorrectionFactor(fareCorrectionFactor: Float): AbstractEfaProvider {
        this.fareCorrectionFactor = fareCorrectionFactor
        return this
    }

    // this should be overridden by networks not providing one of the default capabilities
    override fun hasCapability(capability: NetworkProvider.Capability?): Boolean {
        return CAPABILITIES.contains(capability)
    }

    private fun appendCommonRequestParams(url: URLBuilder, outputFormat: String) {
        url.parameters.append("outputFormat", outputFormat)
        url.parameters.append("language", language)
        url.parameters.append("stateless", "1")
        url.parameters.append("coordOutputFormat", COORD_FORMAT)
        url.parameters.append("coordOutputFormatTail", COORD_FORMAT_TAIL.toString())
    }

    @Serializable
    data class JsonMessage(
        val name: String,
        val value: String
    )

    @Serializable
    data class JsonPoint(
        val type: String,
        val anyType: String? = null,
        val stateless: String,
        @SerialName("name") private val denormalizedName: String? = null,
        @SerialName("object") private val denormalizedObject: String? = null,
        val postcode: String? = null,
        val quality: Int,
        val ref: RefObj,
    ) {

        @Serializable
        data class RefObj(
            val id: String,
            @SerialName("place") internal val denormalizedPlace: String? = null,
            @SerialName("coords") internal val coordsStr: String? = null,
        )

        val name: String?
            get() = denormalizedName.normalize()

        val objekt: String?
            get() = denormalizedObject.normalize()

        val place: String?
            get() = ref.denormalizedPlace?.takeIf { it.isNotBlank() }

        val coord: Point?
            get() = ref.coordsStr.parseCoord()

        val location: Location
            get() = when(type.takeIf { it != "any" } ?: anyType) {
                "stop" -> {
                    if (!stateless.startsWith(ref.id)) throw RuntimeException("id mismatch: '${ref.id}' vs '$stateless'")
                    Location(ref.id, Location.Type.STATION, coord, place, objekt)
                }

                "poi" -> {
                    Location(stateless, Location.Type.POI, coord, place, objekt)
                }

                "crossing" -> {
                    Location(stateless, Location.Type.ADDRESS, coord, place, objekt)
                }

                "street", "address", "singlehouse", "buildingname", "loc" -> {
                    Location(stateless, Location.Type.ADDRESS, coord, place, name)
                }

                "postcode" -> {
                    Location(stateless, Location.Type.ADDRESS, coord, place, postcode)
                }

                else -> throw RuntimeException("unknown type: $type")
            }

        val suggestedLocation: SuggestedLocation
            get() = SuggestedLocation(location, quality)
    }

    @Serializable
    data class StopfinderResponse(
        @Serializable(with = StopFinderSerializer::class) val stopFinder: Any,
        val message: String? = null
    ) {
        @Serializable
        data class StopFinderObject(
            val message: List<JsonMessage>? = null,
            @Serializable(with = SoloPointSerializer::class) val points: Any,
        ) {
            @Serializable
            data class SoloPoint(val point: JsonPoint)
        }

        object SoloPointSerializer : KSerializer<Any> {
            override val descriptor: SerialDescriptor = buildClassSerialDescriptor("Points") {
                element("points", String.serializer().descriptor) // Placeholder
            }

            override fun serialize(encoder: Encoder, value: Any) {
                when (value) {
                    is StopFinderObject.SoloPoint -> encoder.encodeSerializableValue(StopFinderObject.SoloPoint.serializer(), value)
                    is List<*> -> encoder.encodeSerializableValue(ListSerializer(JsonPoint.serializer()),
                        value as List<JsonPoint>
                    )
                    else -> throw SerializationException("Unknown type")
                }
            }

            override fun deserialize(decoder: Decoder): Any {
                val jsonDecoder = decoder as? JsonDecoder ?: throw SerializationException("Expected JsonDecoder")
                val jsonElement = jsonDecoder.decodeJsonElement()

                return when {
                    jsonElement is JsonObject -> jsonDecoder.json.decodeFromJsonElement(StopFinderObject.SoloPoint.serializer(), jsonElement)
                    jsonElement is JsonArray -> jsonDecoder.json.decodeFromJsonElement(ListSerializer(JsonPoint.serializer()), jsonElement)
                    else -> throw SerializationException("Unknown JSON structure")
                }
            }
        }

        object StopFinderSerializer : KSerializer<Any> {
            override val descriptor: SerialDescriptor = buildClassSerialDescriptor("StopFinder") {
                element("stopFinder", String.serializer().descriptor) // Placeholder
            }

            override fun serialize(encoder: Encoder, value: Any) {
                when (value) {
                    is StopFinderObject -> encoder.encodeSerializableValue(StopFinderObject.serializer(), value)
                    is List<*> -> encoder.encodeSerializableValue(ListSerializer(JsonPoint.serializer()),
                        value as List<JsonPoint>
                    )
                    else -> throw SerializationException("Unknown type")
                }
            }

            override fun deserialize(decoder: Decoder): Any {
                val jsonDecoder = decoder as? JsonDecoder ?: throw SerializationException("Expected JsonDecoder")
                val jsonElement = jsonDecoder.decodeJsonElement()

                return when {
                    jsonElement is JsonObject -> jsonDecoder.json.decodeFromJsonElement(StopFinderObject.serializer(), jsonElement)
                    jsonElement is JsonArray -> jsonDecoder.json.decodeFromJsonElement(ListSerializer(JsonPoint.serializer()), jsonElement)
                    else -> throw SerializationException("Unknown JSON structure")
                }
            }
        }
    }

    protected suspend fun jsonStopfinderRequest(
        constraint: CharSequence,
        types: Set<Location.Type>?,
        maxLocations: Int
    ): SuggestLocationsResult {
        val url = URLBuilder(stopFinderEndpoint)
        appendStopfinderRequestParameters(url, constraint, "JSON", types, maxLocations)

        val rsp = httpClient.get(url.build())

        println("jsonStopfinderRequest: ${url.build().toString()}")

        //val page: CharSequence = httpClient.get(url.build())
        val header: ResultHeader = ResultHeader(network, SERVER_PRODUCT)

        try {
            //val data = rsp.body<StopfinderResponse>()
            val json = Json { ignoreUnknownKeys = true; isLenient = true }

            val data = json.decodeFromString<StopfinderResponse>(rsp.bodyAsText())

            val location = if(data.stopFinder is StopfinderResponse.StopFinderObject) {
                data.stopFinder.message?.parseToStatus()?.let {
                    return SuggestLocationsResult(header, it)
                }

                if(data.stopFinder.points is StopfinderResponse.StopFinderObject.SoloPoint) {
                    listOf(data.stopFinder.points.point.suggestedLocation)
                } else if(data.stopFinder.points is List<*>) {
                    (data.stopFinder.points as List<JsonPoint>).map { it.suggestedLocation }
                } else null
            } else if(data.stopFinder is List<*>) {
                (data.stopFinder as List<JsonPoint>).map { it.suggestedLocation }
            } else {
                null
            }

            return SuggestLocationsResult(header, location!!)
        } catch (x: Exception) {
            x.printStackTrace()
            throw RuntimeException("cannot parse: '${rsp.bodyAsText()}' on $url", x)
        }
    }

    private fun appendStopfinderRequestParameters(
        url: URLBuilder, 
        constraint: CharSequence,
        outputFormat: String,
        types: Set<Location.Type>?, 
        maxLocations: Int
    ) {
        appendCommonRequestParams(url, outputFormat)
        url.parameters.append("locationServerActive", "1")
        if (includeRegionId) url.parameters.append("regionID_sf", "1") // prefer own region

        url.parameters.append("type_sf", "any")
        url.parameters.append("name_sf", UrlEncoderUtil.encode(constraint.toString()))
        if (needsSpEncId) url.parameters.append("SpEncId", "0")
        var filter = 0
        if (types == null || types.contains(Location.Type.STATION)) filter += 2 // stop

        if (types == null || types.contains(Location.Type.POI)) filter += 32 // poi

        if (types == null || types.contains(Location.Type.ADDRESS)) filter += 4 + 8 + 16 + 64 // street + address + crossing + postcode

        url.parameters.append("anyObjFilter_sf", filter.toString())
        url.parameters.append("reducedAnyPostcodeObjFilter_sf", "64")
        url.parameters.append("reducedAnyTooManyObjFilter_sf", "2")
        url.parameters.append("useHouseNumberList", "true")
        if (maxLocations > 0) url.parameters.append(
            "anyMaxSizeHitList",
            maxLocations.toString()
        )
    }

    @Serializable
    data class Pas(
        val pa: List<Pa>
    ) {
        @Serializable
        data class Pa(
            val n: String,
            val v: String
        )
    }

    @Serializable
    @XmlSerialName("efa", "", "")
    data class StopfinderXmlResponse(
        @XmlElement(true)
        val error: String? = null,
        @XmlElement(true)
        val now: String? = null,
        val pas: Pas? = null,
        val ers: Ers? = null,
        val sf: Sf? = null,
    ) {

        @Serializable
        data class Ers(
            val err: List<Err>
        ) {
            @Serializable
            data class Err(
                val mod: String,
                val co: String,
                val u: String,
                val tx: String? = null
            )
        }

        @Serializable
        data class Sf(
            val p: List<P>
        ) {
            @Serializable
            data class P(
                val n: String,
                val u: String,
                val ty: String,
                val r: R,
                val qal: Int? = null
            ) {
                @Serializable
                data class R(
                    val id: String,
                    val gid: String? = null,
                    val stateless: String,
                    val omc: Int,
                    val pc: String? = null,
                    val pid: Int,
                    val c: String? = null
                )
            }
        }
    }

    protected suspend fun mobileStopfinderRequest(
        constraint: CharSequence,
        types: Set<Location.Type>?,
        maxLocations: Int
    ): SuggestLocationsResult {
        val url = URLBuilder(stopFinderEndpoint)
        appendStopfinderRequestParameters(url, constraint, "XML", types, maxLocations)

        val rsp = httpClient.config {
            install(ContentNegotiation) {
                xml()
            }
        }.get(url.build()) {
            setHttpReferer(httpReferer)
        }

        println("mobileStopfinderRequest: ${url.buildString()}")

        try {
            val data = xml.decodeFromString(StopfinderXmlResponse.serializer(), rsp.bodyAsText())

            if(data.error != null) throw RuntimeException("error: ${data.error}")

            val header = data.pas.parseHeader(data.now!!)

            val locations = data.sf?.p?.map {
                val name = it.n.normalize()
                require(it.u == "sf") { "unknown usage ${it.u}"}
                val type = when(it.ty) {
                    "stop" -> Location.Type.STATION
                    "poi" -> Location.Type.POI
                    "street" -> Location.Type.ADDRESS
                    "singlehouse" -> Location.Type.ADDRESS
                    else -> throw RuntimeException("unknown type ${it.ty}")
                }

                val id = it.r.id
                val stateless = it.r.stateless
                val place = it.r.pc.normalize()
                val coord = it.r.c.parseCoord()
                val quality = it.qal ?: 0

                Location(
                    id = if(type == Location.Type.STATION) id else stateless,
                    type = type,
                    coord = coord,
                    place = place,
                    name = name
                ).let { loc ->
                    SuggestedLocation(loc, quality)
                }
            }

            return SuggestLocationsResult(header, locations ?: emptyList())
        } catch (x: Exception) {
            throw ParserException("cannot parse xml: ${rsp.bodyAsText()}", x)
        }
    }

    private fun appendCoordRequestParameters(
        url: URLBuilder,
        types: Set<Location.Type>,
        coord: Point,
        maxDistance: Int,
        maxLocations: Int
    ) {
        appendCommonRequestParams(url, "XML")
        url.parameters.append(
            "coord",
            UrlEncoderUtil.encode(
                coord.lat.formatTo7DecimalPlaces() +
                    ":${coord.lon.formatTo7DecimalPlaces()}" +
                    ":${COORD_FORMAT}")
        )
        url.parameters.append(
            "coordListOutputFormat",
            if (useStringCoordListOutputFormat) "string" else "list"
        )
        url.parameters.append(
            "max",
            (if (maxLocations != 0) maxLocations else 50).toString()
        )
        url.parameters.append("inclFilter", "1")
        var i = 1
        for (type in types) {
            url.parameters.append(
                "radius_$i",
                (if (maxDistance != 0) maxDistance else 1320).toString()
            )
            if (type === Location.Type.STATION) url.parameters.append("type_$i", "STOP")
            else if (type === Location.Type.POI) url.parameters.append("type_$i", "POI_POINT")
            else throw IllegalArgumentException("cannot handle location type: $type")
            i++
        }
    }


    @Serializable
    sealed interface ItdRequest {
        @XmlElement(false) val version: String
        @XmlElement(false) val now: String
        @XmlElement(false) val sessionID: String
        @XmlElement(false) val serverID: String
    }

    @Serializable
    @XmlSerialName("itdPathCoordinates")
    data class ItdPathCoordinates(
        @XmlElement(true) val coordEllipsoid: String,
        @XmlElement(true) val coordType: String,
        @XmlElement(true) val itdCoordinateBaseElemList: ItdCoordinateBaseElemList? = null,
        @XmlElement(true) val itdCoordinateString: String? = null
    ) {
        @Serializable
        @XmlSerialName("itdCoordinateBaseElemList")
        data class ItdCoordinateBaseElemList(
            @XmlElement(true) val itdCoordinateBaseElem: List<ItdCoordinateBaseElem>
        ) {

            @Serializable
            @XmlSerialName("itdCoordinateBaseElem")
            data class ItdCoordinateBaseElem(
                @XmlElement(true) val x: String,
                @XmlElement(true) val y: String
            )

        }

    }


    @Serializable
    @XmlSerialName("itdRequest", "", "")
    data class XmlCoordResponse(
        @XmlElement(false) override val version: String,
        @XmlElement(false) override val now: String,
        @XmlElement(false) override val sessionID: String,
        @XmlElement(false) override val serverID: String,
        @XmlElement(true) val itdCoordInfoRequest: ItdCoordInfoRequest,
    ) : ItdRequest {

        @Serializable
        @XmlSerialName("itdCoordInfoRequest")
        data class ItdCoordInfoRequest(
            @XmlElement(false) val requestID: String,
            @XmlElement(true) val itdCoordInfo: ItdCoordInfo
        ) {

            @Serializable
            @XmlSerialName("itdCoordInfo")
            data class ItdCoordInfo(
                @XmlElement val coordInfoRequest: CoordInfoRequest,
                @XmlElement val coordInfoItemList: CoordInfoItemList
            ) {

                @Serializable
                @XmlSerialName("coordInfoRequest")
                data class CoordInfoRequest(
                    @XmlElement(false) val deadline: String,
                    @XmlElement(false) val max: String,
                    @XmlElement(false) val purpose: String,
                    @XmlElement(true) val itdCoord: ItdCoord,
                    @XmlElement(true) val coordInfoFilterItemList: CoordInfoFilterItemList
                ) {

                    @Serializable
                    @XmlSerialName("itdCoord")
                    data class ItdCoord(
                        @XmlElement(false) val mapName: String,
                        @XmlElement(false) val x: String,
                        @XmlElement(false) val y: String
                    )

                    @Serializable
                    @XmlSerialName("coordInfoFilterItemList")
                    data class CoordInfoFilterItemList(
                        @XmlElement(true) val coordInfoFilterItem: CoordInfoFilterItem
                    ) {

                        @Serializable
                        @XmlSerialName("coordInfoFilterItem")
                        data class CoordInfoFilterItem(
                            @XmlElement(false) val name: String,
                            @XmlElement(false) val clustering: String,
                            @XmlElement(false) val exclLayers: String,
                            @XmlElement(false) val exclMOT: String,
                            @XmlElement(false) val inclDrawClasses: String,
                            @XmlElement(false) val inclMOT: String,
                            @XmlElement(false) val inclPOIHierarchy: String,
                            @XmlElement(false) val radius: String,
                            @XmlElement(false) val ratingMethod: String,
                            @XmlElement(false) val type: String
                        )

                    }

                }


                @Serializable
                @XmlSerialName("coordInfoItemList")
                data class CoordInfoItemList(
                    @XmlElement val coordInfoItem: List<CoordInfoItem>
                ) {

                    @Serializable
                    @XmlSerialName("coordInfoItem")
                    data class CoordInfoItem(
                        @XmlElement(false) val name: String? = null,
                        @XmlElement(false) val distance: String,
                        @XmlElement(false) val gisID: String,
                        @XmlElement(false) val gisLayer: String,
                        @XmlElement(false) val id: String,
                        @XmlElement(false) val locality: String? = null,
                        @XmlElement(false) val omc: String,
                        @XmlElement(false) val placeID: String,
                        @XmlElement(false) val stateless: String? = null,
                        @XmlElement(false) val type: String,
                        @XmlElement(true) val itdPathCoordinates: ItdPathCoordinates,
                        @XmlElement(true) val genAttrList: GenAttrList? = null,
                        //@XmlElement val itdIndexInfoList: ItdIndexInfoList
                    )
                }
            }
        }
    }

    @Serializable
    @XmlSerialName("genAttrList")
    data class GenAttrList(
        @XmlElement(true) val genAttrElem: List<GenAttrElem> = emptyList()
    ) {
        @Serializable
        @XmlSerialName("genAttrElem")
        data class GenAttrElem(
            @XmlElement(true) val name: String,
            @XmlElement(true) val value: String
        )
    }

    protected suspend fun xmlCoordRequest(
        types: Set<Location.Type>, coord: Point,
        maxDistance: Int, maxStations: Int
    ): NearbyLocationsResult {
        val url = URLBuilder(coordEndpoint)
        appendCoordRequestParameters(url, types, coord, maxDistance, maxStations)

        val rsp = httpClient.config {
            install(ContentNegotiation) {
                xml()
            }
        }.get(url.build()) {
            setHttpReferer(httpReferer)
        }

        println("xmlCoordRequest: ${url.buildString()}")

        try {
            val data = xml.decodeFromString(XmlCoordResponse.serializer(), rsp.bodyAsText())

            val header = data.parseHeader()

            val locations = data.itdCoordInfoRequest.itdCoordInfo.coordInfoItemList.coordInfoItem.mapNotNull {
                val locationType = when(it.type) {
                    "STOP" -> Location.Type.STATION
                    "POI_POINT" -> Location.Type.POI
                    else -> throw IllegalStateException("unknown type: ${it.type}")
                }

                val id = it.stateless ?: it.id

                val name = it.name.normalize()
                val place = it.locality.normalize()

                val path = it.itdPathCoordinates.toPointList()?.firstOrNull()
                val products = it.genAttrList?.genAttrElem?.flatMap {
                    if(it.name == "STOP_MAJOR_MEANS" || it.name == "STOP_MEANS_LIST") {
                        it.value.split(",").mapNotNull { mean ->
                            when(mean) {
                                "1" -> Product.SUBWAY
                                "2" -> Product.SUBURBAN_TRAIN
                                "3" -> Product.BUS
                                "4" -> Product.TRAM
                                else -> null
                            }
                        }
                    } else emptyList()
                }

                name?.let {
                    Location(
                        id = id,
                        type = locationType,
                        coord = path,
                        place = place,
                        name = name,
                        products = products?.toSet()
                    )
                }
            }

            return NearbyLocationsResult(header, NearbyLocationsResult.Status.OK, locations)
        } catch (e: Exception) {
            e.printStackTrace()
            throw RuntimeException("Could not parse xml: ${rsp.bodyAsText()}", e)
        }
    }

    @Serializable
    @XmlSerialName("efa", "", "")
    data class MobileCoordEfa(
        @XmlElement(true) val now: String,
        @XmlElement(true) val pas: Pas,
        @XmlElement(true) val ci: Ci
    ) {
        @Serializable
        @XmlSerialName("ci")
        data class Ci(
            @XmlElement(true) val request: Request,
            @XmlElement(true) val pis: Pis
        ) {
            @Serializable
            @XmlSerialName("request")
            data class Request(
                @XmlElement(true) val c: C
            ) {
                @Serializable
                @XmlSerialName("c")
                data class C(
                    @XmlElement(true) val x: String,
                    @XmlElement(true) val y: String,
                    @XmlElement(true) val mapName: String
                )
            }

            @Serializable
            @XmlSerialName("pis")
            data class Pis(
                @XmlElement(true) val pi: List<Pi>
            ) {
                @Serializable
                @XmlSerialName("pi")
                data class Pi(
                    @XmlElement(true) val de: String? = null,
                    @XmlElement(true) val ty: String,
                    @XmlElement(true) val id: String,
                    @XmlElement(true) val omc: String,
                    @XmlElement(true) val pid: String? = null,
                    @XmlElement(true) val locality: String? = null,
                    @XmlElement(true) val layer: String,
                    @XmlElement(true) val gisID: String,
                    @XmlElement(true) val ds: String,
                    @XmlElement(true) val stateless: String,
                    @XmlElement(true) val attrs: Attrs? = null,
                    @XmlElement(true) val c: String? = null
                ) {
                    @Serializable
                    @XmlSerialName("attrs")
                    data class Attrs(
                        @XmlElement val attr: List<Attr>
                    ) {
                        @Serializable
                        @XmlSerialName("attr")
                        data class Attr(
                            @XmlElement val n: String,
                            @XmlElement val v: String
                        )
                    }
                }
            }
        }

    }


    protected suspend fun mobileCoordRequest(
        types: Set<Location.Type>, coord: Point,
        maxDistance: Int, maxStations: Int
    ): NearbyLocationsResult {
        val url = URLBuilder(coordEndpoint)
        appendCoordRequestParameters(url, types, coord, maxDistance, maxStations)

        val rsp = httpClient.config {
            install(ContentNegotiation) {
                xml()
            }
        }.get(url.build()) {
            setHttpReferer(httpReferer)
        }

        println("mobileCoordRequest: ${url.buildString()}")

        try {
            val data = xml.decodeFromString(MobileCoordEfa.serializer(), rsp.bodyAsText())

            val header = data.pas.parseHeader(data.now)

            val stations = data.ci.pis.pi.map {
                val name = it.de.normalize()
                val locationType = when(it.ty) {
                    "STOP" -> Location.Type.STATION
                    "POI_POINT" -> Location.Type.POI
                    else -> throw IllegalStateException("unknown type: ${it.ty}")
                }

                val place = it.locality.normalize()
                val locationId = if(locationType == Location.Type.STATION) it.id else it.stateless
                val coord1 = it.c.parseCoord()

                val products = it.attrs?.attr?.flatMap {
                    if(it.n == "STOP_MAJOR_MEANS" || it.n == "STOP_MEANS_LIST") {
                        it.v.split(",").mapNotNull { mean ->
                            when(mean) {
                                "1" -> Product.SUBWAY
                                "2" -> Product.SUBURBAN_TRAIN
                                "3" -> Product.BUS
                                "4" -> Product.TRAM
                                else -> null
                            }
                        }
                    } else emptyList()
                }

                if(name != null)
                    Location(
                        id = locationId,
                        type = locationType,
                        coord = coord1,
                        place = place,
                        name = name,
                        products = products?.toSet()
                    )
                else
                    Location(
                        id = locationId,
                        type = locationType,
                        coord = coord1,
                        place = null,
                        name = place,
                        products = products?.toSet()
                    )
            }

            return NearbyLocationsResult(header, NearbyLocationsResult.Status.OK, stations)
        } catch (e: Exception) {
            e.printStackTrace()
            throw RuntimeException("Could not parse xml: ${rsp.bodyAsText()}", e)
        }
    }

    override suspend fun suggestLocations(
        constraint: CharSequence,
        types: Set<Location.Type>?, maxLocations: Int
    ): SuggestLocationsResult {
        return jsonStopfinderRequest(constraint, types, maxLocations)
    }
//
//    private interface ProcessItdOdvCallback {
//        fun location(nameState: String?, location: Location?, matchQuality: Int)
//    }
//
//    @Throws(XmlPullParserException::class, IOException::class)
//    private fun processItdOdv(
//        pp: XmlPullParser, expectedUsage: String?,
//        callback: ProcessItdOdvCallback
//    ): String {
//        XmlPullUtil.require(pp, "itdOdv")
//
//        val usage: String = XmlPullUtil.attr(pp, "usage")
//        if (expectedUsage != null && usage != expectedUsage) throw java.lang.IllegalStateException("expecting <itdOdv usage=\"$expectedUsage\" />")
//
//        val type: String = XmlPullUtil.attr(pp, "type")
//
//        XmlPullUtil.enter(pp, "itdOdv")
//
//        val place = processItdOdvPlace(pp)
//
//        XmlPullUtil.require(pp, "itdOdvName")
//        val nameState: String = XmlPullUtil.attr(pp, "state")
//        XmlPullUtil.enter(pp, "itdOdvName")
//
//        XmlPullUtil.optSkip(pp, "itdMessage")
//
//        if ("identified" == nameState) {
//            val location: Location = processOdvNameElem(pp, type, place)
//            if (location != null) callback.location(nameState, location, Int.MAX_VALUE)
//        } else if ("list" == nameState) {
//            while (XmlPullUtil.test(pp, "odvNameElem")) {
//                val matchQuality: Int = XmlPullUtil.intAttr(pp, "matchQuality")
//                val location: Location = processOdvNameElem(pp, type, place)
//                if (location != null) callback.location(nameState, location, matchQuality)
//            }
//        } else if ("notidentified" == nameState || "empty" == nameState) {
//            XmlPullUtil.optSkip(pp, "odvNameElem")
//        } else {
//            throw java.lang.RuntimeException("cannot handle nameState '$nameState'")
//        }
//
//        XmlPullUtil.optSkipMultiple(pp, "infoLink")
//        XmlPullUtil.optSkip(pp, "itdMapItemList")
//        XmlPullUtil.optSkip(pp, "odvNameInput")
//
//        XmlPullUtil.exit(pp, "itdOdvName")
//
//        XmlPullUtil.optSkip(pp, "odvInfoList")
//
//        XmlPullUtil.optSkip(pp, "itdPoiHierarchyRoot")
//
//        if (XmlPullUtil.optEnter(pp, "itdOdvAssignedStops")) {
//            while (XmlPullUtil.test(pp, "itdOdvAssignedStop")) {
//                val stop: Location? = processItdOdvAssignedStop(pp)
//
//                if (stop != null) callback.location("assigned", stop, 0)
//            }
//
//            XmlPullUtil.exit(pp, "itdOdvAssignedStops")
//        }
//
//        XmlPullUtil.optSkip(pp, "itdServingModes")
//
//        XmlPullUtil.optSkip(pp, "genAttrList")
//
//        XmlPullUtil.exit(pp, "itdOdv")
//
//        return nameState
//    }
//
//    @Throws(XmlPullParserException::class, IOException::class)
//    private fun processItdOdvPlace(pp: XmlPullParser): String? {
//        XmlPullUtil.require(pp, "itdOdvPlace")
//
//        val placeState: String = XmlPullUtil.attr(pp, "state")
//
//        XmlPullUtil.enter(pp, "itdOdvPlace")
//        var place: String? = null
//        if ("identified" == placeState) {
//            if (XmlPullUtil.test(pp, "odvPlaceElem")) place =
//                normalizeLocationName(XmlPullUtil.valueTag(pp, "odvPlaceElem"))
//        }
//        XmlPullUtil.skipExit(pp, "itdOdvPlace")
//
//        return place
//    }
//
//    @Throws(XmlPullParserException::class, IOException::class)
//    private fun processOdvNameElem(
//        pp: XmlPullParser,
//        type: String,
//        defaultPlace: String?
//    ): Location {
//        var type = type
//        XmlPullUtil.require(pp, "odvNameElem")
//
//        if ("any" == type) type = XmlPullUtil.attr(pp, "anyType")
//        val id: String = XmlPullUtil.optAttr(pp, "id", null)
//        val stateless: String = XmlPullUtil.attr(pp, "stateless")
//        val locality = normalizeLocationName(XmlPullUtil.optAttr(pp, "locality", null))
//        val objectName = normalizeLocationName(XmlPullUtil.optAttr(pp, "objectName", null))
//        val buildingName: String = XmlPullUtil.optAttr(pp, "buildingName", null)
//        val buildingNumber: String = XmlPullUtil.optAttr(pp, "buildingNumber", null)
//        val postCode: String = XmlPullUtil.optAttr(pp, "postCode", null)
//        val streetName: String = XmlPullUtil.optAttr(pp, "streetName", null)
//        val coord: Point? = processCoordAttr(pp)
//
//        XmlPullUtil.enter(pp, "odvNameElem")
//        XmlPullUtil.optSkip(pp, "itdMapItemList")
//        val nameElem: String?
//        if (pp.getEventType() === XmlPullParser.TEXT) {
//            nameElem = normalizeLocationName(pp.getText())
//            pp.next()
//        } else {
//            nameElem = null
//        }
//        XmlPullUtil.exit(pp, "odvNameElem")
//
//        if ("stop" == type) {
//            if (id != null && !stateless.startsWith(id)) throw java.lang.RuntimeException("id mismatch: '$id' vs '$stateless'")
//            return Location(
//                LocationType.STATION, id ?: stateless, coord,
//                locality ?: defaultPlace, objectName ?: nameElem
//            )
//        } else if ("poi" == type) {
//            return Location(
//                LocationType.POI, stateless, coord, locality ?: defaultPlace,
//                objectName ?: nameElem
//            )
//        } else if ("loc" == type) {
//            return if (locality != null) {
//                Location(LocationType.ADDRESS, stateless, coord, null, locality)
//            } else if (nameElem != null) {
//                Location(LocationType.ADDRESS, stateless, coord, null, nameElem)
//            } else if (coord != null) {
//                Location(LocationType.COORD, stateless, coord, null, null)
//            } else {
//                throw java.lang.IllegalArgumentException("not enough data for type/anyType: $type")
//            }
//        } else if ("address" == type || "singlehouse" == type) {
//            return Location(
//                LocationType.ADDRESS, stateless, coord, locality ?: defaultPlace,
//                objectName + (if (buildingNumber != null) " $buildingNumber" else "")
//            )
//        } else if ("street" == type || "crossing" == type) {
//            return Location(
//                LocationType.ADDRESS, stateless, coord, locality ?: defaultPlace,
//                objectName ?: nameElem
//            )
//        } else if ("postcode" == type) {
//            return Location(
//                LocationType.ADDRESS, stateless, coord, locality ?: defaultPlace,
//                postCode
//            )
//        } else if ("buildingname" == type) {
//            return Location(
//                LocationType.ADDRESS, stateless, coord, locality ?: defaultPlace,
//                buildingName ?: streetName
//            )
//        } else if ("coord" == type) {
//            return Location(LocationType.ADDRESS, stateless, coord, defaultPlace, nameElem)
//        } else {
//            throw java.lang.IllegalArgumentException("unknown type/anyType: $type")
//        }
//    }
//
//    @Throws(XmlPullParserException::class, IOException::class)
//    private fun processItdOdvAssignedStop(pp: XmlPullParser): Location? {
//        val id: String = XmlPullUtil.attr(pp, "stopID")
//        val coord: Point? = processCoordAttr(pp)
//        val place = normalizeLocationName(XmlPullUtil.optAttr(pp, "place", null))
//        val name = normalizeLocationName(XmlPullUtil.optValueTag(pp, "itdOdvAssignedStop", null))
//
//        return if (name != null) Location(LocationType.STATION, id, coord, place, name)
//        else null
//    }
//

    override suspend fun queryNearbyLocations(
        types: Set<Location.Type>, location: Location,
        maxDistance: Int, maxLocations: Int
    ): NearbyLocationsResult {
        if (location.hasCoords) return xmlCoordRequest(
            types,
            location.coord!!,
            maxDistance,
            maxLocations
        )

        if (location.type !== Location.Type.STATION) throw IllegalArgumentException("cannot handle: " + location.type)

        if (!location.hasId) throw IllegalArgumentException("at least one of stationId or lat/lon must be given")

        return nearbyStationsRequest(location.id!!, maxLocations)
    }

    @Serializable
    @XmlSerialName("itdDateTime")
    data class ItdDateTime(
        @XmlElement(true) val itdDate: ItdDate,
        @XmlElement(true) val itdTime: ItdTime
    )

    @Serializable
    @XmlSerialName("itdDate")
    data class ItdDate(
        @XmlElement(false) val year: Int,
        @XmlElement(false) val month: Int,
        @XmlElement(false) val day: Int,
        @XmlElement(false) val weekday: Int,
        @XmlElement(true) val itdMessage: List<ItdMessage>? = null
    ) {
        fun toLocalDate(): LocalDate? {
            if(year == 0 || weekday < 0) return null
            return LocalDate(year, month + 1, day)
        }
    }

    @Serializable
    @XmlSerialName("itdTime")
    data class ItdTime(
        @XmlElement(false) val hour: Int,
        @XmlElement(false) val minute: Int,
        @XmlElement(false) val second: Int = 0,
        @XmlElement(false) val ap: String? = null
    ) {
        fun toLocalTime(): LocalTime {
            return LocalTime(hour, minute, second)
        }
    }

    @Serializable
    @XmlSerialName("itdOdv")
    data class ItdOdv(
        @XmlElement(false) val type: String,
        @XmlElement(false) val usage: String,
        @XmlElement(true) val itdOdvPlace: ItdOdvPlace,
        @XmlElement(true) val itdOdvName: ItdOdvName,
        @XmlElement(true) val itdOdvAssignedStops: ItdOdvAssignedStops? = null
    ) {
        @Serializable
        @XmlSerialName("itdOdvPlace")
        data class ItdOdvPlace(
            @XmlElement(false) val state: String,
            @XmlElement(false) val method: String? = null,
            @XmlElement(true) val odvPlaceElem: OdvPlaceElem? = null,
            //@XmlElement(true) val odvPlaceInput: OdvPlaceInput
        ) {
            @Serializable
            @XmlSerialName("odvPlaceElem")
            data class OdvPlaceElem(
                @XmlElement(false) val omc: String? = null,
                @XmlElement(false) val placeID: String? = null,
                @XmlElement(false) val value: String? = null,
                @XmlElement(false) val mainPlace: String? = null,
                @XmlElement(false) val stateless: String? = null,
                @XmlValue val place: String? = null
            )
        }

        @Serializable
        @XmlSerialName("itdOdvName")
        data class ItdOdvName(
            @XmlElement(false) val state: String,
            @XmlElement(false) val method: String? = null,
            @XmlElement(true) val odvNameElem: List<OdvNameElem>,
            //@XmlElement(true) val odvNameInput: OdvNameInput
        ) {
            @Serializable
            @XmlSerialName("odvNameElem")
            data class OdvNameElem(
                @XmlElement(false) val anyType: String? = null,
                @XmlElement(false) val id: String? = null,
                @XmlElement(false) val locality: String? = null,
                @XmlElement(false) val objectName: String? = null,
                @XmlElement(false) val buildingName: String? = null,
                @XmlElement(false) val buildingNumber: String? = null,
                @XmlElement(false) val postCode: String? = null,
                @XmlElement(false) val streetName: String? = null,
                @XmlElement(false) val selected: String? = null,
                @XmlElement(false) val matchQuality: Int? = null,
                @XmlElement(false) val x: String? = null,
                @XmlElement(false) val y: String? = null,
                @XmlElement(false) val mapName: String? = null,
                @XmlElement(false) val stopID: String? = null,
                @XmlElement(false) val value: String? = null,
                @XmlElement(false) val isTransferStop: Int? = null,
                @XmlElement(false) val stateless: String? = null,
                @XmlValue val name: String? = null
            )
        }

        @Serializable
        @XmlSerialName("itdOdvAssignedStops")
        data class ItdOdvAssignedStops(
            @XmlElement(false) val select: String,
            @XmlElement(true) val itdOdvAssignedStop: List<ItdOdvAssignedStop>
        ) {
            @Serializable
            @XmlSerialName("itdOdvAssignedStop")
            data class ItdOdvAssignedStop(
                @XmlElement(false) val stopID: String,
                @XmlElement(false) val x: String? = null,
                @XmlElement(false) val y: String? = null,
                @XmlElement(false) val mapName: String? = null,
                @XmlElement(false) val value: String,
                @XmlElement(false) val place: String? = null,
                @XmlElement(false) val nameWithPlace: String,
                @XmlElement(false) val distanceTime: String,
                @XmlElement(false) val isTransferStop: String,
                @XmlElement(false) val vm: String,
                @XmlElement(false) val gid: String,
                @XmlValue val name: String? = null
            )
        }

    }

    @Serializable
    @XmlSerialName("itdRequest", "", "")
    data class ItdDepartureMonitorResult(
        @XmlElement(false) override val version: String,
        @XmlElement(false) override val now: String,
        @XmlElement(false) override val sessionID: String,
        @XmlElement(false) override val serverID: String,
        @XmlElement(true) val itdDepartureMonitorRequest: ItdDepartureMonitorRequest,
    ) : ItdRequest {
        @Serializable
        @XmlSerialName("itdDepartureMonitorRequest")
        data class ItdDepartureMonitorRequest(
            @XmlElement(false) val requestID: String,

            @XmlElement(true) val itdOdv: ItdOdv,
//            @XmlElement(true) val itdDateTime: ItdDateTime,
//            @XmlElement(true) val itdDMDateTime: ItdDMDateTime,
//            @XmlElement(true) val itdDateRange: ItdDateRange,
//            @XmlElement(true) val itdTripOptions: ItdTripOptions,
            @XmlElement(true) val itdServingLines: ItdServingLines? = null,
            @XmlElement(true) val itdDepartureList: ItdDepartureList? = null
        ) {

            @Serializable
            @XmlSerialName("itdDepartureList")
            data class ItdDepartureList(
                val itdDeparture: List<ItdDeparture> = emptyList()
            )

            @Serializable
            @XmlSerialName("itdDeparture")
            data class ItdDeparture(
                @XmlElement(false) val stopID: String,
                @XmlElement(false) val x: String? = null,
                @XmlElement(false) val y: String? = null,
                @XmlElement(false) val mapName: String? = null,
//                @XmlElement(false) val area: Int,
//                @XmlElement(false) val platform: String,
//                @XmlElement(false) val gid: String,
//                @XmlElement(false) val pointGid: String,
                @XmlElement(false) val platformName: String? = null,
                @XmlElement(false) val plannedPlatformName: String? = null,
                @XmlElement(false) val stopName: String? = null,
//                @XmlElement(false) val nameWO: String,
                @XmlElement(false) val pointType: String? = null,
//                @XmlElement(false) val countdown: Int,
//                @XmlElement(false) val realtimeTripStatus: String? = null,
                @XmlElement(true) val itdDateTime: ItdDateTime,
                @XmlElement(true) val itdDateTimeBaseTimetable: ItdDateTimeBaseTimetable? = null, //ignore
                @XmlElement(true) val itdRTDateTime: ItdRTDateTime? = null,
                @XmlElement(true) val itdServingLine: ItdServingLines.ItdServingLine,
//                @XmlElement(true) val itdInfoLinkList: ItdInfoLinkList,
            ) {


                @Serializable
                @XmlSerialName("itdDateTimeBaseTimetable")
                data class ItdDateTimeBaseTimetable(
                    @XmlElement(true) val itdDate: ItdDate,
                    @XmlElement(true) val itdTime: ItdTime
                )

                @Serializable
                @XmlSerialName("itdRTDateTime")
                data class ItdRTDateTime(
                    @XmlElement(true) val itdDate: ItdDate,
                    @XmlElement(true) val itdTime: ItdTime
                )
            }

            @Serializable
            @XmlSerialName("itdServingLines")
            data class ItdServingLines(
                @XmlElement(true) val itdServingLine: List<ItdServingLine> = emptyList()
            ) {
                @Serializable
                @XmlSerialName("itdServingLine")
                data class ItdServingLine(
                    @XmlElement(false) val trainInfo: String? = null,
                    @XmlElement(false) val selected: Int? = null,
                    @XmlElement(false) val number: String? = null, //USED
                    @XmlElement(false) val symbol: String? = null, //USED
                    @XmlElement(false) val motType: String, //USED
                    @XmlElement(false) val direction: String? = null, //USED
                    @XmlElement(false) val directionFrom: String? = null,
                    @XmlElement(false) val trainType: String? = null, //USED
                    @XmlElement(false) val trainNum: String? = null, //USED
                    @XmlElement(false) val trainName: String? = null, //USED
                    @XmlElement(false) val type: String? = null, //USED
                    @XmlElement(false) val name: String? = null, //USED
                    @XmlElement(false) val delay: String? = null, //USED
                    @XmlElement(false) val destID: String? = null, //USED
                    @XmlElement(false) val stateless: String? = null, //USED
                    @XmlElement(false) val lineDisplay: String? = null,
                    @XmlElement(false) val index: String? = null,
                    @XmlElement(false) val assignedStop: String? = null,
                    @XmlElement(false) val assignedStopID: String? = null,
                    @XmlElement(false) val realtime: String? = null,
//                    @XmlElement(true) val itdNoTrain: ItdNoTrain? = null,
                    @XmlElement(true) val itdNoTrain: String? = null,
                    @XmlElement(true) val motDivaParams: MotDivaParams,
//                    @XmlElement(true) val itdOperator: ItdOperator,
//                    @XmlElement(true) val itdRouteDescText: String? = null,
//                    @XmlElement(true) val genAttrList: GenAttrList? = null
                ) {
                    @Serializable
                    @XmlSerialName("itdNoTrain")
                    data class ItdNoTrain(
                        @XmlElement(false) val name: String
                    )

                    @Serializable
                    @XmlSerialName("itdOperator")
                    data class ItdOperator(
                        @XmlElement(true) val code: String,
                        @XmlElement(true) val name: String,
                        @XmlElement(true) val publicCode: String
                    )
                }
            }
        }
    }

    @Serializable
    @XmlSerialName("motDivaParams")
    data class MotDivaParams(
        @XmlElement(false) val line: String,
        @XmlElement(false) val project: String? = null,
        @XmlElement(false) val direction: String,
        @XmlElement(false) val supplement: String? = null,
        @XmlElement(false) val network: String,
        @XmlElement(false) val gid: String? = null
    )

    private suspend fun nearbyStationsRequest(stationId: String, maxLocations: Int): NearbyLocationsResult {
        val url = URLBuilder(departureMonitorEndpoint)
        appendCommonRequestParams(url, "XML")
        url.parameters.append("type_dm", "stop")
        url.parameters.append("name_dm",
            UrlEncoderUtil.encode(normalizeStationId(stationId) ?: ""))
        url.parameters.append("itOptionsActive", "1")
        url.parameters.append("ptOptionsActive", "1")
        if (useProxFootSearch) url.parameters.append("useProxFootSearch", "1")
        url.parameters.append("mergeDep", "1")
        url.parameters.append("useAllStops", "1")
        url.parameters.append("mode", "direct")

        val rsp = httpClient.config {
            install(ContentNegotiation) {
                xml()
            }
        }.get(url.build())

        println("nearbyStationsRequest: ${url.buildString()}")

        try {
            val data = xml.decodeFromString(ItdDepartureMonitorResult.serializer(), rsp.bodyAsText())

            val header = data.parseHeader()

            data.itdDepartureMonitorRequest.itdOdv.let { odv ->
                require(odv.usage == "dm") { "expecting <itdOdv usage=\"dm\" />"}

                val place = odv.itdOdvPlace.takeIf { it.state == "identified" }?.odvPlaceElem?.place ?: "???"

                var ownStation: Location? = null
                val stations: MutableList<Location> = mutableListOf()

                val odvStations = odv.itdOdvName.odvNameElem
                    .mapNotNull { it.toLocation(type = odv.type, defaultPlace = place) }
                    .filter { it.type == Location.Type.STATION }

                when(odv.itdOdvName.state) {
                    "identified" -> { ownStation = odvStations.first() }
                    "list" -> { stations.addAll(odvStations) }
                    "notidentified" -> return NearbyLocationsResult(
                        header,
                        NearbyLocationsResult.Status.INVALID_ID
                    )
                    else -> {}
                }

                if(ownStation != null && !stations.contains(ownStation))
                    stations.add(ownStation)

                if(maxLocations == 0 || maxLocations >= stations.size)
                    return NearbyLocationsResult(header, NearbyLocationsResult.Status.OK, stations)

                return NearbyLocationsResult(
                    header,
                    NearbyLocationsResult.Status.OK,
                    stations.subList(0, maxLocations)
                )
            }
        } catch(e: Exception) {
            e.printStackTrace()
            throw RuntimeException("cannot parse xml: ${rsp.bodyAsText()}", e)
        }
    }

    protected open fun parseLine(
        _id: String?, network: String?, mot: String?,
        symbol: String?, name: String?, longName: String?,
        trainType: String?, trainNum: String?, trainName: String?
    ): Line {
        val id = _id ?: ""
        if (mot == null) {
            if (trainName != null) {
                val str: String = name ?: ""
                if (trainName == "S-Bahn") return Line(id, network, Product.SUBURBAN_TRAIN, str)
                if (trainName == "U-Bahn") return Line(id, network, Product.SUBWAY, str)
                if (trainName == "Straßenbahn") return Line(id, network, Product.TRAM, str)
                if (trainName == "Badner Bahn") return Line(id, network, Product.TRAM, str)
                if (trainName == "Stadtbus") return Line(id, network, Product.BUS, str)
                if (trainName == "Citybus") return Line(id, network, Product.BUS, str)
                if (trainName == "Regionalbus") return Line(id, network, Product.BUS, str)
                if (trainName == "ÖBB-Postbus") return Line(id, network, Product.BUS, str)
                if (trainName == "Autobus") return Line(id, network, Product.BUS, str)
                if (trainName == "Discobus") return Line(id, network, Product.BUS, str)
                if (trainName == "Nachtbus") return Line(id, network, Product.BUS, str)
                if (trainName == "Anrufsammeltaxi") return Line(id, network, Product.BUS, str)
                if (trainName == "Ersatzverkehr") return Line(id, network, Product.BUS, str)
                if (trainName == "Vienna Airport Lines") return Line(id, network, Product.BUS, str)
            }
        } else if ("0" == mot) {
            val trainNumStr: String = trainNum ?: ""

            if (("EC" == trainType || "EuroCity" == trainName || "Eurocity" == trainName)
                && trainNum != null
            ) return Line(id, network, Product.HIGH_SPEED_TRAIN, "EC$trainNum")
            if (("ECE" == trainType || "Eurocity-Express" == trainName) && trainNum != null) return Line(
                id,
                network,
                Product.HIGH_SPEED_TRAIN,
                "ECE$trainNum"
            )
            if (("EN" == trainType || "EuroNight" == trainName) && trainNum != null) return Line(
                id,
                network,
                Product.HIGH_SPEED_TRAIN,
                "EN$trainNum"
            )
            if (("IC" == trainType || "IC" == trainName || "InterCity" == trainName) && trainNum != null) return Line(
                id,
                network,
                Product.HIGH_SPEED_TRAIN,
                "IC$trainNum"
            )
            if ("IC21" == trainNum && trainName == null) return Line(
                id,
                network,
                Product.HIGH_SPEED_TRAIN,
                trainNum
            )
            if ("IC40" == trainNum && trainName == null) return Line(
                id,
                network,
                Product.HIGH_SPEED_TRAIN,
                trainNum
            )
            if (("ICE" == trainType || "ICE" == trainName || "Intercity-Express" == trainName)
                && trainNum != null
            ) return Line(id, network, Product.HIGH_SPEED_TRAIN, "ICE$trainNum")
            if (("ICN" == trainType || "InterCityNight" == trainName) && trainNum != null) return Line(
                id,
                network,
                Product.HIGH_SPEED_TRAIN,
                "ICN$trainNum"
            )
            if (("X" == trainType || "InterConnex" == trainName) && trainNum != null) return Line(
                id,
                network,
                Product.HIGH_SPEED_TRAIN,
                "X$trainNum"
            )
            if (("CNL" == trainType || "CityNightLine" == trainName) && trainNum != null) return Line(
                id,
                network,
                Product.HIGH_SPEED_TRAIN,
                "CNL$trainNum"
            )
            if (("THA" == trainType || "Thalys" == trainName) && trainNum != null) return Line(
                id,
                network,
                Product.HIGH_SPEED_TRAIN,
                "THA$trainNum"
            )
            if ("RHI" == trainType && trainNum != null) return Line(
                id,
                network,
                Product.HIGH_SPEED_TRAIN,
                "RHI$trainNum"
            )
            if (("TGV" == trainType || "TGV" == trainName) && trainNum != null) return Line(
                id,
                network,
                Product.HIGH_SPEED_TRAIN,
                "TGV$trainNum"
            )
            if ("TGD" == trainType && trainNum != null) return Line(
                id,
                network,
                Product.HIGH_SPEED_TRAIN,
                "TGD$trainNum"
            )
            if ("INZ" == trainType && trainNum != null) return Line(
                id,
                network,
                Product.HIGH_SPEED_TRAIN,
                "INZ$trainNum"
            )
            if (("RJ" == trainType || "railjet" == trainName)) return Line(
                id,
                network,
                Product.HIGH_SPEED_TRAIN,
                "RJ${trainNum ?: ""}"
            )
            if (("RJX" == trainType || "railjet xpress" == trainName)) return Line(
                id,
                network,
                Product.HIGH_SPEED_TRAIN,
                "RJX${trainNum ?: ""}"
            )
            if (("WB" == trainType || "WESTbahn" == trainName) && trainNum != null) return Line(
                id,
                network,
                Product.HIGH_SPEED_TRAIN,
                "WB$trainNum"
            )
            if (("HKX" == trainType || "Hamburg-Köln-Express" == trainName) && trainNum != null) return Line(
                id,
                network,
                Product.HIGH_SPEED_TRAIN,
                "HKX$trainNum"
            )
            if ("INT" == trainType && trainNum != null) // SVV, VAGFR
                return Line(id, network, Product.HIGH_SPEED_TRAIN, "INT$trainNum")
            if (("SC" == trainType || "SC Pendolino" == trainName) && trainNum != null) // SuperCity
                return Line(id, network, Product.HIGH_SPEED_TRAIN, "SC$trainNum")
            if ("ECB" == trainType && trainNum != null) // EC, Verona-München
                return Line(id, network, Product.HIGH_SPEED_TRAIN, "ECB$trainNum")
            if ("ES" == trainType && trainNum != null) // Eurostar Italia
                return Line(id, network, Product.HIGH_SPEED_TRAIN, "ES$trainNum")
            if (("EST" == trainType || "EUROSTAR" == trainName) && trainNum != null) return Line(
                id,
                network,
                Product.HIGH_SPEED_TRAIN,
                "EST$trainNum"
            )
            if ("EIC" == trainType && trainNum != null) // Ekspres InterCity, Polen
                return Line(id, network, Product.HIGH_SPEED_TRAIN, "EIC$trainNum")
            if ("MT" == trainType && "Schnee-Express" == trainName && trainNum != null) return Line(
                id,
                network,
                Product.HIGH_SPEED_TRAIN,
                "MT$trainNum"
            )
            if (("TLK" == trainType || "Tanie Linie Kolejowe" == trainName) && trainNum != null) return Line(
                id,
                network,
                Product.HIGH_SPEED_TRAIN,
                "TLK$trainNum"
            )
            if ("DNZ" == trainType && trainNum != null) // Nacht-Schnellzug
                return Line(id, network, Product.HIGH_SPEED_TRAIN, "DNZ$trainNum")
            if ("AVE" == trainType && trainNum != null) // klimatisierter Hochgeschwindigkeitszug
                return Line(id, network, Product.HIGH_SPEED_TRAIN, "DNZ$trainNum")
            if ("ARC" == trainType && trainNum != null) // Arco/Alvia/Avant (Renfe), Spanien
                return Line(id, network, Product.HIGH_SPEED_TRAIN, "ARC$trainNum")
            if ("HOT" == trainType && trainNum != null) // Spanien, Nacht
                return Line(id, network, Product.HIGH_SPEED_TRAIN, "HOT$trainNum")
            if ("LCM" == trainType && "Locomore" == trainName && trainNum != null) return Line(
                id,
                network,
                Product.HIGH_SPEED_TRAIN,
                "LCM$trainNum"
            )
            if ("Locomore" == longName) return Line(
                id,
                network,
                Product.HIGH_SPEED_TRAIN,
                "LOC${trainNum ?: ""}"
            )
            if ("NJ" == trainType && trainNum != null) // NightJet
                return Line(id, network, Product.HIGH_SPEED_TRAIN, "NJ$trainNum")
            if ("FLX" == trainType && "FlixTrain" == trainName && trainNum != null) return Line(
                id,
                network,
                Product.HIGH_SPEED_TRAIN,
                "FLX$trainNum"
            )

            if ("IR" == trainType || "Interregio" == trainName || "InterRegio" == trainName) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                "IR$trainNum"
            )
            if ("IR13" == trainNum && trainName == null) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                trainNum
            )
            if ("IR36" == trainNum && trainName == null) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                trainNum
            )
            if ("IR37" == trainNum && trainName == null) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                trainNum
            )
            if ("IR75" == trainNum && trainName == null) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                trainNum
            )
            if ("IRE" == trainType || "Interregio-Express" == trainName) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                "IRE$trainNum"
            )
            if (trainType == null && trainNum != null && P_LINE_IRE.matches(trainNum))
                return Line(id, network, Product.REGIONAL_TRAIN, trainNum)
            if ("RE" == trainType || "Regional-Express" == trainName) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                "RE${trainNum ?: ""}"
            )
            if ("RE" == trainNum && trainName == null) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                "RE"
            )
            if (trainType == null && trainNum != null && P_LINE_RE.matches(trainNum))
                return Line(id, network, Product.REGIONAL_TRAIN, trainNum)
            if ("RE3 / RB30" == trainNum && trainType == null && trainName == null) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                "RE3/RB30"
            )
            if ("Regionalexpress" == trainName) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                symbol
            )
            if ("R-Bahn" == trainName) return Line(id, network, Product.REGIONAL_TRAIN, symbol)
            if ("RE1 (RRX)" == trainNum) return Line(id, network, Product.REGIONAL_TRAIN, "RE1")
            if ("RE5 (RRX)" == trainNum) return Line(id, network, Product.REGIONAL_TRAIN, "RE5")
            if ("RE6 (RRX)" == trainNum) return Line(id, network, Product.REGIONAL_TRAIN, "RE6")
            if ("RE11 (RRX)" == trainNum) return Line(id, network, Product.REGIONAL_TRAIN, "RE11")
            if ("RB-Bahn" == trainName) return Line(id, network, Product.REGIONAL_TRAIN, symbol)
            if (trainType == null && "RB67/71" == trainNum) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                trainNum
            )
            if (trainType == null && "RB65/68" == trainNum) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                trainNum
            )
            if ("RE-Bahn" == trainName) return Line(id, network, Product.REGIONAL_TRAIN, symbol)
            if ("REX" == trainType) // RegionalExpress, Österreich
                return Line(id, network, Product.REGIONAL_TRAIN, "REX$trainNum")
            if (("RB" == trainType || "Regionalbahn" == trainName) && trainNum != null) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                "RB$trainNum"
            )
            if ("RB" == trainNum && trainName == null) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                "RB"
            )
            if (trainType == null && trainNum != null && P_LINE_RB.matches(trainNum))
                return Line(id, network, Product.REGIONAL_TRAIN, trainNum)
            if ("Abellio-Zug" == trainName) return Line(id, network, Product.REGIONAL_TRAIN, symbol)
            if ("Westfalenbahn" == trainName) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                symbol
            )
            if ("Chiemseebahn" == trainName) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                symbol
            )
            if ("R" == trainType || "Regionalzug" == trainName) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                "R$trainNum"
            )
            if (trainType == null && trainNum != null && P_LINE_R.matches(trainNum))
                return Line(id, network, Product.REGIONAL_TRAIN, trainNum)
            if ("D" == trainType || "Schnellzug" == trainName) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                "D$trainNum"
            )
            if ("E" == trainType || "Eilzug" == trainName) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                "E$trainNum"
            )
            if ("WFB" == trainType || "WestfalenBahn" == trainName) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                "WFB$trainNum"
            )
            if (("NWB" == trainType || "NordWestBahn" == trainName) && trainNum != null) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                "NWB$trainNum"
            )
            if ("WES" == trainType || "Westbahn" == trainName) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                "WES$trainNum"
            )
            if ("ERB" == trainType || "eurobahn" == trainName) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                "ERB$trainNum"
            )
            if ("CAN" == trainType || "cantus Verkehrsgesellschaft" == trainName) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                "CAN$trainNum"
            )
            if ("HEX" == trainType || "Veolia Verkehr Sachsen-Anhalt" == trainName) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                "HEX$trainNum"
            )
            if ("EB" == trainType || "Erfurter Bahn" == trainName) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                "EB$trainNum"
            )
            if ("Erfurter Bahn" == longName) return Line(id, network, Product.REGIONAL_TRAIN, "EB")
            if ("EBx" == trainType || "Erfurter Bahn Express" == trainName) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                "EBx$trainNum"
            )
            if ("Erfurter Bahn Express" == longName && symbol == null) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                "EBx"
            )
            if ("MR" == trainType && "Märkische Regiobahn" == trainName && trainNum != null) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                "MR$trainNum"
            )
            if ("MRB" == trainType || "Mitteldeutsche Regiobahn" == trainName) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                "MRB$trainNum"
            )
            if ("MRB26" == trainNum && trainType == null) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                trainNum
            )
            if ("ABR" == trainType || "ABELLIO Rail NRW GmbH" == trainName) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                "ABR$trainNum"
            )
            if ("NEB" == trainType || "NEB Niederbarnimer Eisenbahn" == trainName) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                "NEB$trainNum"
            )
            if ("OE" == trainType || "Ostdeutsche Eisenbahn GmbH" == trainName) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                "OE$trainNum"
            )
            if ("Ostdeutsche Eisenbahn GmbH" == longName && symbol == null) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                "OE"
            )
            if ("ODE" == trainType && symbol != null) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                symbol
            )
            if ("OLA" == trainType || "Ostseeland Verkehr GmbH" == trainName) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                "OLA$trainNum"
            )
            if ("UBB" == trainType || "Usedomer Bäderbahn" == trainName) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                "UBB$trainNum"
            )
            if ("EVB" == trainType || "ELBE-WESER GmbH" == trainName) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                "EVB$trainNum"
            )
            if ("RTB" == trainType || "Rurtalbahn GmbH" == trainName) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                "RTB$trainNum"
            )
            if ("STB" == trainType || "Süd-Thüringen-Bahn" == trainName) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                "STB$trainNum"
            )
            if ("HTB" == trainType || "Hellertalbahn" == trainName) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                "HTB$trainNum"
            )
            if ("VBG" == trainType || "Vogtlandbahn" == trainName) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                "VBG$trainNum"
            )
            if ("CB" == trainType || "City-Bahn Chemnitz" == trainName) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                "CB$trainNum"
            )
            if (trainType == null && ("C11" == trainNum || "C13" == trainNum || "C14" == trainNum || "C15" == trainNum)) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                trainNum
            )
            if ("CB523" == trainNum) return Line(id, network, Product.REGIONAL_TRAIN, trainNum)
            if ("VEC" == trainType || "vectus Verkehrsgesellschaft" == trainName) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                "VEC$trainNum"
            )
            if ("HzL" == trainType || "Hohenzollerische Landesbahn AG" == trainName) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                "HzL$trainNum"
            )
            if ("SBB" == trainType || "SBB GmbH" == trainName) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                "SBB$trainNum"
            )
            if ("MBB" == trainType || "Mecklenburgische Bäderbahn Molli" == trainName) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                "MBB$trainNum"
            )
            if ("OS" == trainType) // Osobní vlak
                return Line(id, network, Product.REGIONAL_TRAIN, "OS$trainNum")
            if ("SP" == trainType || "Sp" == trainType) // Spěšný vlak
                return Line(id, network, Product.REGIONAL_TRAIN, "SP$trainNum")
            if ("Dab" == trainType || "Daadetalbahn" == trainName) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                "Dab$trainNum"
            )
            if ("FEG" == trainType || "Freiberger Eisenbahngesellschaft" == trainName) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                "FEG$trainNum"
            )
            if ("ARR" == trainType || "ARRIVA" == trainName) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                "ARR$trainNum"
            )
            if ("HSB" == trainType || "Harzer Schmalspurbahn" == trainName) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                "HSB$trainNum"
            )
            if ("ALX" == trainType || "alex - Länderbahn und Vogtlandbahn GmbH" == trainName) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                "ALX$trainNum"
            )
            if ("EX" == trainType || "Fatra" == trainName) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                "EX$trainNum"
            )
            if ("ME" == trainType || "metronom" == trainName) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                "ME$trainNum"
            )
            if ("metronom" == longName) return Line(id, network, Product.REGIONAL_TRAIN, "ME")
            if ("MEr" == trainType) return Line(id, network, Product.REGIONAL_TRAIN, "MEr$trainNum")
            if ("AKN" == trainType || "AKN Eisenbahn AG" == trainName) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                "AKN$trainNum"
            )
            if ("SOE" == trainType || "Sächsisch-Oberlausitzer Eisenbahngesellschaft" == trainName) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                "SOE$trainNum"
            )
            if ("VIA" == trainType || "VIAS GmbH" == trainName) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                "VIA$trainNum"
            )
            if ("BRB" == trainType || "Bayerische Regiobahn" == trainName) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                "BRB$trainNum"
            )
            if ("BLB" == trainType || "Berchtesgadener Land Bahn" == trainName) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                "BLB$trainNum"
            )
            if ("HLB" == trainType || "Hessische Landesbahn" == trainName) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                "HLB$trainNum"
            )
            if ("NOB" == trainType || "NordOstseeBahn" == trainName) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                "NOB$trainNum"
            )
            if ("NBE" == trainType || "Nordbahn Eisenbahngesellschaft" == trainName) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                "NBE$trainNum"
            )
            if ("VEN" == trainType || "Rhenus Veniro" == trainName) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                "VEN$trainType"
            )
            if ("DPN" == trainType || "Nahreisezug" == trainName) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                "DPN$trainNum"
            )
            if ("RBG" == trainType || "Regental Bahnbetriebs GmbH" == trainName) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                "RBG$trainNum"
            )
            if ("BOB" == trainType || "Bodensee-Oberschwaben-Bahn" == trainName) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                "BOB$trainNum"
            )
            if ("VE" == trainType || "Vetter" == trainName) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                "VE$trainNum"
            )
            if ("SDG" == trainType || "SDG Sächsische Dampfeisenbahngesellschaft mbH" == trainName) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                "SDG$trainNum"
            )
            if ("PRE" == trainType || "Pressnitztalbahn" == trainName) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                "PRE$trainNum"
            )
            if ("VEB" == trainType || "Vulkan-Eifel-Bahn" == trainName) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                "VEB$trainNum"
            )
            if ("neg" == trainType || "Norddeutsche Eisenbahn Gesellschaft" == trainName) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                "neg$trainNum"
            )
            if ("AVG" == trainType || "Felsenland-Express" == trainName) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                "AVG$trainNum"
            )
            if ("P" == trainType || "BayernBahn Betriebs-GmbH" == trainName || "Brohltalbahn" == trainName || "Kasbachtalbahn" == trainName) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                "P$trainNum"
            )
            if ("SBS" == trainType || "Städtebahn Sachsen" == trainName) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                "SBS$trainNum"
            )
            if ("SES" == trainType || "Städteexpress Sachsen" == trainName) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                "SES$trainNum"
            )
            if ("SB-" == trainType) // Städtebahn Sachsen
                return Line(id, network, Product.REGIONAL_TRAIN, "SB$trainNum")
            if ("ag" == trainType) // agilis
                return Line(id, network, Product.REGIONAL_TRAIN, "ag$trainNum")
            if ("agi" == trainType || "agilis" == trainName) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                "agi$trainNum"
            )
            if ("as" == trainType || "agilis-Schnellzug" == trainName) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                "as$trainNum"
            )
            if ("TLX" == trainType || "TRILEX" == trainName) // Trilex (Vogtlandbahn)
                return Line(id, network, Product.REGIONAL_TRAIN, "TLX$trainNum")
            if ("MSB" == trainType || "Mainschleifenbahn" == trainName) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                "MSB$trainNum"
            )
            if ("BE" == trainType || "Bentheimer Eisenbahn" == trainName) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                "BE$trainNum"
            )
            if ("erx" == trainType || "erixx - Der Heidesprinter" == trainName) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                "erx$trainNum"
            )
            if (("ERX" == trainType || "Erixx" == trainName) && trainNum != null) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                "ERX$trainNum"
            )
            if ("SWE" == trainType || "Südwestdeutsche Verkehrs-AG" == trainName || "Südwestdeutsche Landesverkehrs-AG" == trainName) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                "SWE${trainNum ?: ""}"
            )
            if ("SWEG-Zug" == trainName) // Südwestdeutschen Verkehrs-Aktiengesellschaft
                return Line(id, network, Product.REGIONAL_TRAIN, "SWEG$trainNum")
            if (longName != null && longName.startsWith("SWEG-Zug")) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                "SWEG${trainNum ?: ""}"
            )
            if ("EGP Eisenbahngesellschaft Potsdam" == trainName) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                "EGP$trainNumStr"
            )
            if ("ÖBB" == trainType || "ÖBB" == trainName) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                "ÖBB$trainNum"
            )
            if ("CAT" == trainType) // City Airport Train Wien
                return Line(id, network, Product.REGIONAL_TRAIN, "CAT$trainNum")
            if ("DZ" == trainType || "Dampfzug" == trainName) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                "DZ$trainNum"
            )
            if ("CD" == trainType) // Tschechien
                return Line(id, network, Product.REGIONAL_TRAIN, "CD$trainNum")
            if ("VR" == trainType) // Polen
                return Line(id, network, Product.REGIONAL_TRAIN, symbol)
            if ("PR" == trainType) // Polen
                return Line(id, network, Product.REGIONAL_TRAIN, symbol)
            if ("KD" == trainType) // Koleje Dolnośląskie (Niederschlesische Eisenbahn)
                return Line(id, network, Product.REGIONAL_TRAIN, symbol)
            if ("Koleje Dolnoslaskie" == trainName && symbol != null) // Koleje Dolnośląskie
                return Line(id, network, Product.REGIONAL_TRAIN, symbol)
            if ("OO" == trainType || "Ordinary passenger (o.pas.)" == trainName) // GB
                return Line(id, network, Product.REGIONAL_TRAIN, "OO$trainNum")
            if ("XX" == trainType || "Express passenger    (ex.pas.)" == trainName) // GB
                return Line(id, network, Product.REGIONAL_TRAIN, "XX$trainNum")
            if ("XZ" == trainType || "Express passenger sleeper" == trainName) // GB
                return Line(id, network, Product.REGIONAL_TRAIN, "XZ$trainNum")
            if ("ATB" == trainType) // Autoschleuse Tauernbahn
                return Line(id, network, Product.REGIONAL_TRAIN, "ATB$trainNum")
            if ("ATZ" == trainType) // Autozug
                return Line(id, network, Product.REGIONAL_TRAIN, "ATZ$trainNum")
            if ("AZ" == trainType || "Auto-Zug" == trainName) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                "AZ$trainNum"
            )
            if ("AZS" == trainType && trainNum != null) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                "AZS$trainNum"
            )
            if ("DWE" == trainType || "Dessau-Wörlitzer Eisenbahn" == trainName) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                "DWE$trainNum"
            )
            if ("KTB" == trainType || "Kandertalbahn" == trainName) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                "KTB$trainNum"
            )
            if ("CBC" == trainType || "CBC" == trainName) // City-Bahn Chemnitz
                return Line(id, network, Product.REGIONAL_TRAIN, "CBC$trainNum")
            if ("Bernina Express" == trainName) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                trainNum
            )
            if ("STR" == trainType) // Harzquerbahn, Nordhausen
                return Line(id, network, Product.REGIONAL_TRAIN, "STR$trainNum")
            if ("EXT" == trainType || "Extrazug" == trainName) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                "EXT$trainNum"
            )
            if ("Heritage Railway" == trainName) // GB
                return Line(id, network, Product.REGIONAL_TRAIN, symbol)
            if ("WTB" == trainType || "Wutachtalbahn" == trainName) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                "WTB$trainNum"
            )
            if ("DB" == trainType || "DB Regio" == trainName) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                "DB$trainNum"
            )
            if ("M" == trainType && "Meridian" == trainName) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                "M$trainNum"
            )
            if ("M" == trainType && "Messezug" == trainName) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                "M$trainNum"
            )
            if ("EZ" == trainType) // ÖBB Erlebniszug
                return Line(id, network, Product.REGIONAL_TRAIN, "EZ$trainNum")
            if ("DPF" == trainType) return Line(id, network, Product.REGIONAL_TRAIN, "DPF$trainNum")
            if ("WBA" == trainType || "Waldbahn" == trainName) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                "WBA$trainNum"
            )
            if ("ÖB" == trainType && "Öchsle-Bahn-Betriebsgesellschaft mbH" == trainName && trainNum != null) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                "ÖB$trainNum"
            )
            if ("ÖBA" == trainType && trainNum != null) // Eisenbahn-Betriebsgesellschaft Ochsenhausen
                return Line(id, network, Product.REGIONAL_TRAIN, "ÖBA$trainNum")
            if (("UEF" == trainType || "Ulmer Eisenbahnfreunde" == trainName) && trainNum != null) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                "UEF$trainNum"
            )
            if (("DBG" == trainType || "Döllnitzbahn" == trainName) && trainNum != null) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                "DBG$trainNum"
            )
            if (("TL" == trainType || "TL" == trainName || "Trilex" == trainName) && trainNum != null) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                "TL$trainNum"
            )
            if (("OPB" == trainType || "oberpfalzbahn" == trainName) && trainNum != null) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                "OPB$trainNum"
            )
            if (("OPX" == trainType || "oberpfalz-express" == trainName) && trainNum != null) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                "OPX$trainNum"
            )
            if (("LEO" == trainType || "Chiemgauer Lokalbahn" == trainName) && trainNum != null) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                "LEO$trainNum"
            )
            if (("VAE" == trainType || "Voralpen-Express" == trainName) && trainNum != null) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                "VAE$trainNum"
            )
            if (("V6" == trainType || "vlexx" == trainName) && trainNum != null) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                "vlexx$trainNum"
            )
            if (("ARZ" == trainType || "Autoreisezug" == trainName) && trainNum != null) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                "ARZ$trainNum"
            )
            if ("RR" == trainType) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                "RR${trainNum}"
            )
            if (("TER" == trainType || "Train Express Regional" == trainName) && trainNum != null) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                "TER$trainNum"
            )
            if (("ENO" == trainType || "enno" == trainName) && trainNum != null) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                "ENO$trainNum"
            )
            if ("enno" == longName && symbol == null) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                "enno"
            )
            if (("PLB" == trainType || "Pinzgauer Lokalbahn" == trainName) && trainNum != null) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                "PLB$trainNum"
            )
            if (("NX" == trainType || "National Express" == trainName) && trainNum != null) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                "NX$trainNum"
            )
            if (("SE" == trainType || "ABELLIO Rail Mitteldeutschland GmbH" == trainName) && trainNum != null) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                "SE$trainNum"
            )
            if (("DNA" == trainType && trainNum != null)) // Dieselnetz Augsburg
                return Line(id, network, Product.REGIONAL_TRAIN, "DNA$trainNum")
            if ("Dieselnetz" == trainType && "Augsburg" == trainNum) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                "DNA"
            )
            if (("SAB" == trainType || "Schwäbische Alb-Bahn" == trainName) && trainNum != null) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                "SAB$trainNum"
            )
            if (symbol != null && P_LINE_MEX.matches(symbol)) // Metropolexpress
                return Line(id, network, Product.REGIONAL_TRAIN, symbol)
            if (trainType == null && trainNum != null && P_LINE_MEX.matches(trainNum)) // Metropolexpress
                return Line(id, network, Product.REGIONAL_TRAIN, trainNum)
            if (("FEX" == trainType || "Flughafen-Express" == trainName) && trainNum != null) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                "FEX$trainNum"
            )

            if (("BSB" == trainType || "Breisgau-S-Bahn Gmbh" == trainName) && trainNum != null) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                "BSB$trainNum"
            )
            if ("BSB-Zug" == trainName && trainNum != null) // Breisgau-S-Bahn
                return Line(id, network, Product.SUBURBAN_TRAIN, trainNum)
            if ("BSB-Zug" == trainName && trainNum == null) return Line(
                id,
                network,
                Product.SUBURBAN_TRAIN,
                "BSB"
            )
            if (longName != null && longName.startsWith("BSB-Zug")) return Line(
                id,
                network,
                Product.SUBURBAN_TRAIN,
                "BSB${trainNum ?: ""}"
            )
            if ("RSB" == trainType) // Regionalschnellbahn, Wien
                return Line(id, network, Product.SUBURBAN_TRAIN, "RSB$trainNum")
            if ("RS18" == trainNum && trainType == null) // Nahverkehrszug Maastricht - Valkenburg - Heerlen
                return Line(id, network, Product.SUBURBAN_TRAIN, "RS18")
            if ("RER" == trainName && symbol != null && symbol.length == 1) // Réseau Express Régional
                return Line(id, network, Product.SUBURBAN_TRAIN, symbol)
            if ("S" == trainType) return Line(id, network, Product.SUBURBAN_TRAIN, "S$trainNum")
            if ("S-Bahn" == trainName) return Line(
                id,
                network,
                Product.SUBURBAN_TRAIN,
                "S$trainNumStr"
            )
            if ("RS" == trainType && trainNum != null) // Regio S-Bahn
                return Line(id, network, Product.SUBURBAN_TRAIN, "RS$trainNum")

            if ("RT" == trainType || "RegioTram" == trainName) return Line(
                id,
                network,
                Product.TRAM,
                "RT$trainNum"
            )

            if ("Bus" == trainType && trainNum != null) return Line(
                id,
                network,
                Product.BUS,
                trainNum
            )
            if ("Bus" == longName && symbol == null) return Line(id, network, Product.BUS, longName)
            if ("SEV" == trainType || "SEV" == trainNum || "SEV" == trainName || "SEV" == symbol || "BSV" == trainType || "Ersatzverkehr" == trainName || "Schienenersatzverkehr" == trainName) return Line(
                id,
                network,
                Product.BUS,
                "SEV$trainNumStr"
            )
            if ("Bus replacement" == trainName) // GB
                return Line(id, network, Product.BUS, "BR")
            if ("BR" == trainType && trainName != null && trainName.startsWith("Bus")) // GB
                return Line(id, network, Product.BUS, "BR$trainNum")
            if ("EXB" == trainType && trainNum != null) return Line(
                id,
                network,
                Product.BUS,
                "EXB$trainNum"
            )

            if ("GB" == trainType) // Gondelbahn
                return Line(id, network, Product.CABLECAR, "GB$trainNum")
            if ("SB" == trainType) // Seilbahn
                return Line(id, network, Product.SUBURBAN_TRAIN, "SB$trainNum")

            if ("Zug" == trainName && symbol != null) return Line(id, network, Product.UNKNOWN, symbol)
            if ("Zug" == longName && symbol == null) return Line(id, network, Product.UNKNOWN, "Zug")
            if ("Zuglinie" == trainName && symbol != null) return Line(id, network, Product.UNKNOWN, symbol)
            if ("ZUG" == trainType && trainNum != null) return Line(id, network, Product.UNKNOWN, trainNum)
            if (symbol != null && P_LINE_NUMBER.matches(symbol) && trainType == null && trainName == null
            ) return Line(id, network, Product.UNKNOWN, symbol)
            if ("N" == trainType && trainName == null && symbol == null) return Line(
                id,
                network,
                Product.UNKNOWN,
                "N$trainNum"
            )
            if ("Train" == trainName) return Line(id, network, Product.UNKNOWN, null)
            if ("PPN" == trainType && "Osobowy" == trainName && trainNum != null) return Line(
                id,
                network,
                Product.UNKNOWN,
                "PPN$trainNum"
            )

            // generic
            if (trainName != null && trainType == null && trainNum == null) return Line(
                id,
                network,
                Product.UNKNOWN,
                trainName
            )
        } else if ("1" == mot) {
            if (symbol != null) return Line(id, network, Product.SUBURBAN_TRAIN, symbol)
            if (name != null && P_LINE_S.matches(name))
                return Line(
                    id,
                    network,
                    Product.SUBURBAN_TRAIN,
                    name
                )
            if ("S-Bahn" == trainName) return Line(
                id,
                network,
                Product.SUBURBAN_TRAIN,
                "S${trainNum}"
            )
            if (symbol != null && symbol == name) {
                val m = P_LINE_S_DB.matchEntire(symbol)
//                val m: java.util.regex.Matcher = P_LINE_S_DB.matcher(symbol)
                if (m != null) return Line(id, network, Product.SUBURBAN_TRAIN, m.groupValues.getOrNull(1))
            }
            if ("REX" == trainType) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                "REX${trainNum ?: ""}"
            )
            return Line(
                id,
                network,
                Product.SUBURBAN_TRAIN,
                symbol ?: name
            )
        } else if ("2" == mot) {
            return Line(id, network, Product.SUBWAY, name)
        } else if ("3" == mot || "4" == mot) {
            return Line(id, network, Product.TRAM, name)
        } else if ("5" == mot || "6" == mot || "7" == mot) {
            if ("Schienenersatzverkehr" == name) return Line(id, network, Product.BUS, "SEV")
            return Line(id, network, Product.BUS, name)
        } else if ("8" == mot) {
            return Line(id, network, Product.CABLECAR, name)
        } else if ("9" == mot) {
            return Line(id, network, Product.FERRY, name)
        } else if ("10" == mot) {
            return Line(id, network, Product.ON_DEMAND, name)
        } else if ("11" == mot) {
            return Line(id, network, Product.UNKNOWN, symbol?.takeIf { it.isNotEmpty() } ?: name)
        } else if ("12" == mot) {
            if ("Schulbus" == trainName && symbol != null) return Line(
                id,
                network,
                Product.BUS,
                symbol
            )
        } else if ("13" == mot) {
            if (("SEV" == trainName || "Ersatzverkehr" == trainName) && trainType == null) return Line(
                id,
                network,
                Product.BUS,
                "SEV"
            )
            if (trainNum != null) return Line(
                id,
                network,
                Product.REGIONAL_TRAIN,
                (trainType ?: "") + trainNum
            )
            return Line(id, network, Product.REGIONAL_TRAIN, name)
        } else if ("14" == mot || "15" == mot || "16" == mot) {
            if (trainType != null || trainNum != null) return Line(
                id,
                network,
                Product.HIGH_SPEED_TRAIN,
                (trainType ?: "") + (trainNum ?: "")
            )
            return Line(id, network, Product.HIGH_SPEED_TRAIN, name)
        } else if ("17" == mot) { // Schienenersatzverkehr
            if (trainNum == null && trainName != null && trainName.startsWith("Schienenersatz")) return Line(
                id,
                network,
                Product.BUS,
                "SEV"
            )
            return Line(id, network, Product.BUS, name)
        } else if ("18" == mot) { // Zug-Shuttle
            return Line(id, network, Product.REGIONAL_TRAIN, name)
        } else if ("19" == mot) { // Bürgerbus
            if (("Bürgerbus" == trainName || "BürgerBus" == trainName || "Kleinbus" == trainName) && symbol != null) return Line(
                id,
                network,
                Product.BUS,
                symbol
            )
            return Line(id, network, Product.BUS, name)
        }

        throw IllegalStateException(
            "cannot normalize mot='" + mot + "' symbol='" + symbol + "' name='" + name + "' long='" + longName
                    + "' trainType='" + trainType + "' trainNum='" + trainNum + "' trainName='" + trainName + "'"
        )
    }

    override suspend fun queryDepartures(
        stationId: String,
        time: Long?,
        maxDepartures: Int,
        equivs: Boolean
    ): QueryDeparturesResult {
        checkNotNull(stationId.takeIf { it.isNotEmpty() })

        return xsltDepartureMonitorRequest(stationId, time, maxDepartures, equivs)
    }

    protected fun appendDepartureMonitorRequestParameters(
        url: URLBuilder, stationId: String?,
        time: Long?, maxDepartures: Int, equivs: Boolean
    ) {
        appendCommonRequestParams(url, "XML")
        url.parameters.append("type_dm", "stop")
        normalizeStationId(stationId)?.let {
            url.parameters.append("name_dm", it)
        }
        if (time != null) appendItdDateTimeParameters(url, time)
        url.parameters.append("useRealtime", "1")
        url.parameters.append("mode", "direct")
        url.parameters.append("ptOptionsActive", "1")
        url.parameters.append("deleteAssignedStops_dm", if (equivs) "0" else "1")
        if (useProxFootSearch) url.parameters.append(
            "useProxFootSearch",
            if (equivs) "1" else "0"
        )
        url.parameters.append("mergeDep", "1") // merge departures
        if (maxDepartures > 0) url.parameters.append("limit", maxDepartures.toString())
    }

    private fun appendItdDateTimeParameters(url: URLBuilder, time: Long) {
        val t = Instant.fromEpochMilliseconds(time).toLocalDateTime(TimeZone.currentSystemDefault())

        val dateFmt = LocalDateTime.Format {
            year()
            monthNumber()
            dayOfMonth()
        }
        val timeFmt = LocalDateTime.Format {
            hour()
            minute()
        }

        url.parameters.append("itdDate", t.format(dateFmt))
        url.parameters.append("itdTime", t.format(timeFmt))
    }


    private suspend fun xsltDepartureMonitorRequest(
        stationId: String, time: Long?,
        maxDepartures: Int, equivs: Boolean
    ): QueryDeparturesResult {
        val url: URLBuilder = URLBuilder(departureMonitorEndpoint)
        appendDepartureMonitorRequestParameters(url, stationId, time, maxDepartures, equivs)

        val rsp = httpClient.config {
            install(ContentNegotiation) {
                xml()
            }
        }.get(url.build()) {
            setHttpReferer(httpReferer)
        }

        println("xsltDepMonReq: ${url.buildString()}")

        try {
            val data = xml.decodeFromString(ItdDepartureMonitorResult.serializer(), rsp.bodyAsText())

            val header = data.parseHeader()

            val stationDepartures = data.itdDepartureMonitorRequest.itdOdv.let { odv ->
                require(odv.usage == "dm") { "expecting <itdOdv usage=\"dm\" />"}

                val place = odv.itdOdvPlace.takeIf { it.state == "identified" }?.odvPlaceElem?.place ?: "???"

                var ownStation: Location? = null
                val stations: MutableList<Location> = mutableListOf()

                val nameStops = odv.itdOdvName.odvNameElem.mapNotNull {
                    it.toLocation(type = odv.type, defaultPlace = place)
                }

                val assignedStops = odv.itdOdvAssignedStops?.itdOdvAssignedStop?.map {
                    it.toLocation()
                } ?: emptyList()

                val stationStops = (nameStops + assignedStops).filter {
                    it.type == Location.Type.STATION
                }

                 return@let stationStops.distinctBy { it.id }.map {
                    StationDepartures(
                        it,
                        mutableListOf(),
                        mutableListOf()
                    )
                }.also {
                     if(odv.itdOdvName.state != "identified")
                         return QueryDeparturesResult(
                             header,
                             QueryDeparturesResult.Status.INVALID_STATION
                         )
                }
            }.toMutableList()

            data.itdDepartureMonitorRequest.itdServingLines?.let { servingLines ->
                servingLines.itdServingLine.forEach {
                    val destination: Location? = it.destinationAsLocation()
                    val line = it.lineAsLine()

                    val cancelled = it.isCancelled()

                    val lineDestination = LineDestination(line, destination)

                    val assignedStationDepartures = if(it.assignedStopID == null)
                        stationDepartures.get(0)
                    else
                        stationDepartures.find { sd -> sd.location.id == it.assignedStopID }
                            ?: StationDepartures(
                                Location(it.assignedStopID, Location.Type.STATION),
                                mutableListOf(),
                                mutableListOf()
                            ).also { stationDepartures.add(it) }
                    // The original PTE does not seem to add the created stationDeparture to
                    // the stationDepartures list. TODO: Confirm whether that is what is supposed to happen

                    if(assignedStationDepartures.lines.contains(lineDestination))
                        assignedStationDepartures.lines.add(lineDestination)
                }
            } ?: return QueryDeparturesResult(header, QueryDeparturesResult.Status.INVALID_STATION)

            data.itdDepartureMonitorRequest.itdDepartureList?.itdDeparture?.forEach { dep ->
                if(dep.itdServingLine.isCancelled()) return@forEach

                val assignedStationDepartures = stationDepartures
                    .find { it.location.id == dep.stopID }
                    ?: StationDepartures(
                        Location(
                            id = dep.stopID,
                            type = Location.Type.STATION,
                            coord = parseCoord(dep.mapName, dep.x, dep.y)
                        ),
                        mutableListOf(),
                        mutableListOf()
                    ).also { stationDepartures.add(it) }

                val position = parsePosition(dep.platformName)

                val plannedDepartureTime = dep.itdDateTime.itdDate.toLocalDate()?.let {
                    LocalDateTime(
                        it,
                        dep.itdDateTime.itdTime.toLocalTime()
                    )
                }

                val isRealtime = dep.itdServingLine.realtime == "1"

                val predictedDepartureTime = dep.itdRTDateTime?.let { rtDT ->
                    rtDT.itdDate.toLocalDate()?.let {
                        LocalDateTime(
                            it,
                            rtDT.itdTime.toLocalTime()
                        )
                    }
                } ?: plannedDepartureTime.takeIf { isRealtime }

                assignedStationDepartures.departures.add(
                    Departure(
                        plannedTime = plannedDepartureTime?.toInstant(timezone)?.toEpochMilliseconds(),
                        predictedTime = predictedDepartureTime?.toInstant(timezone)?.toEpochMilliseconds(),
                        line = dep.itdServingLine.lineAsLine(),
                        position = position,
                        destination = dep.itdServingLine.destinationAsLocation(),
                        capacity = null,
                        message = null
                    )
                )
                // The original PTE does not seem to add the created stationDeparture to
                // the stationDepartures list. TODO: Confirm whether that is what is supposed to happen
            }

            return QueryDeparturesResult(header, QueryDeparturesResult.Status.OK, stationDepartures)
        } catch(e: Exception) {
            e.printStackTrace()
            throw RuntimeException("failed to parse xml: ${rsp.bodyAsText()}", e)
        }
    }
//
//    @Throws(IOException::class)
//    protected fun queryDeparturesMobile(
//        stationId: String?, @Nullable time: java.util.Date?,
//        maxDepartures: Int, equivs: Boolean
//    ): QueryDeparturesResult {
//        val url: HttpUrl.Builder = departureMonitorEndpoint.newBuilder()
//        appendDepartureMonitorRequestParameters(url, stationId, time, maxDepartures, equivs)
//        val result: AtomicReference<QueryDeparturesResult> =
//            AtomicReference<QueryDeparturesResult>()
//
//        val callback: HttpClient.Callback = HttpClient.Callback { bodyPeek, body ->
//            try {
//                val pp: XmlPullParser = parserFactory.newPullParser()
//                pp.setInput(body.charStream())
//                val header: ResultHeader = enterEfa(pp)
//                val r: QueryDeparturesResult = QueryDeparturesResult(header)
//
//                if (XmlPullUtil.optEnter(pp, "ers")) {
//                    XmlPullUtil.enter(pp, "err")
//                    val mod: String = XmlPullUtil.valueTag(pp, "mod")
//                    val co: String = XmlPullUtil.valueTag(pp, "co")
//                    XmlPullUtil.optValueTag(pp, "u", null)
//                    XmlPullUtil.optValueTag(pp, "tx", null)
//                    if ("-2000" == co) { // STOP_INVALID
//                        result.set(
//                            QueryDeparturesResult(
//                                header,
//                                QueryDeparturesResult.Status.INVALID_STATION
//                            )
//                        )
//                    } else if ("-4050" == co) { // NO_SERVINGLINES
//                        result.set(r)
//                    } else {
//                        log.debug("EFA error: {} {}", co, mod)
//                        result.set(
//                            QueryDeparturesResult(
//                                header,
//                                QueryDeparturesResult.Status.SERVICE_DOWN
//                            )
//                        )
//                    }
//                    XmlPullUtil.exit(pp, "err")
//                    XmlPullUtil.exit(pp, "ers")
//                } else if (XmlPullUtil.optEnter(pp, "dps")) {
//                    val plannedDepartureTime: Calendar = GregorianCalendar(timeZone)
//                    val predictedDepartureTime: Calendar = GregorianCalendar(timeZone)
//
//                    while (XmlPullUtil.optEnter(pp, "dp")) {
//                        // misc
//                        /* final String stationName = */
//                        normalizeLocationName(XmlPullUtil.valueTag(pp, "n"))
//                        /* final String gid = */
//                        XmlPullUtil.optValueTag(pp, "gid", null)
//                        /* final String pgid = */
//                        XmlPullUtil.optValueTag(pp, "pgid", null)
//                        /* final boolean isRealtime = */
//                        XmlPullUtil.valueTag(pp, "realtime").equals("1")
//                        /* final String rts = */
//                        XmlPullUtil.optValueTag(pp, "rts", null)
//
//                        XmlPullUtil.optSkip(pp, "dt")
//
//                        // time
//                        parseMobileSt(pp, plannedDepartureTime, predictedDepartureTime)
//
//                        val lineDestination: LineDestination = parseMobileM(pp, true)
//
//                        XmlPullUtil.enter(pp, "r")
//                        val assignedId: String = XmlPullUtil.valueTag(pp, "id")
//                        XmlPullUtil.valueTag(pp, "a")
//                        val position: Position? =
//                            parsePosition(XmlPullUtil.optValueTag(pp, "pl", null))
//                        XmlPullUtil.skipExit(pp, "r")
//
//                        /* final Point positionCoordinate = */
//                        parseCoord(XmlPullUtil.optValueTag(pp, "c", null))
//
//                        // TODO messages
//                        var stationDepartures: StationDepartures? = findStationDepartures(
//                            r.stationDepartures,
//                            assignedId
//                        )
//                        if (stationDepartures == null) {
//                            stationDepartures = StationDepartures(
//                                Location(LocationType.STATION, assignedId),
//                                java.util.ArrayList<Departure>(maxDepartures), null
//                            )
//                            r.stationDepartures.add(stationDepartures)
//                        }
//
//                        stationDepartures.departures.add(
//                            Departure(
//                                plannedDepartureTime.getTime(),
//                                if (predictedDepartureTime.isSet(Calendar.HOUR_OF_DAY)
//                                ) predictedDepartureTime.getTime() else null,
//                                lineDestination.line,
//                                position,
//                                lineDestination.destination,
//                                null,
//                                null
//                            )
//                        )
//
//                        XmlPullUtil.skipExit(pp, "dp")
//                    }
//
//                    XmlPullUtil.skipExit(pp, "dps")
//
//                    result.set(r)
//                } else {
//                    result.set(
//                        QueryDeparturesResult(
//                            header,
//                            QueryDeparturesResult.Status.INVALID_STATION
//                        )
//                    )
//                }
//            } catch (x: XmlPullParserException) {
//                throw ParserException("cannot parse xml: $bodyPeek", x)
//            } catch (x: ParserException) {
//                throw ParserException("cannot parse xml: $bodyPeek", x)
//            }
//        }
//
//        httpClient.getInputStream(callback, url.build(), httpReferer)
//
//        return result.get()
//    }
//
//    @Throws(XmlPullParserException::class, IOException::class)
//    private fun parseMobileM(pp: XmlPullParser, tyOrCo: Boolean): LineDestination {
//        XmlPullUtil.enter(pp, "m")
//
//        val n: String = XmlPullUtil.optValueTag(pp, "n", null)
//        val productNu: String = XmlPullUtil.optValueTag(pp, "nu", null)
//        val ty: String = XmlPullUtil.valueTag(pp, "ty")
//
//        val line: Line
//        val destination: Location?
//        if ("100" == ty || "99" == ty) {
//            destination = null
//            line = Line.FOOTWAY
//        } else if ("105" == ty) {
//            destination = null
//            line = Line.TRANSFER
//        } else if ("98" == ty) {
//            destination = null
//            line = Line.SECURE_CONNECTION
//        } else if ("97" == ty) {
//            destination = null
//            line = Line.DO_NOT_CHANGE
//        } else {
//            val co: String = XmlPullUtil.valueTag(pp, "co")
//            val productType = if (tyOrCo) ty else co
//            XmlPullUtil.optValueTag(pp, "prid", null)
//            XmlPullUtil.optValueTag(pp, "trainType", null)
//            val destinationName = normalizeLocationName(XmlPullUtil.optValueTag(pp, "des", null))
//            destination = if (destinationName != null) Location(
//                LocationType.ANY,
//                null,
//                null,
//                destinationName
//            ) else null
//            XmlPullUtil.optValueTag(pp, "dy", null)
//            val de: String = XmlPullUtil.optValueTag(pp, "de", null)
//            val productName = n ?: de
//            XmlPullUtil.optValueTag(pp, "routeDesc", null)
//            XmlPullUtil.optValueTag(pp, "tco", null)
//            val lineId = parseMobileDv(pp)
//            val symbol = if (productName != null && productNu == null) productName
//            else if (productName != null && productNu.endsWith(" $productName")) productNu.substring(
//                0,
//                productNu.length - productName.length - 1
//            )
//            else productNu
//
//            val trainType: String?
//            val trainNum: String?
//            val mSymbol: java.util.regex.Matcher =
//                de.libf.ptek.AbstractEfaProvider.Companion.P_MOBILE_M_SYMBOL.matcher(symbol)
//            if (mSymbol.matches()) {
//                trainType = mSymbol.group(1)
//                trainNum = mSymbol.group(2)
//            } else {
//                trainType = null
//                trainNum = null
//            }
//
//            val network = lineId.substring(0, lineId.indexOf(':'))
//            val parsedLine: Line = parseLine(
//                lineId, network, productType, symbol, symbol, null, trainType, trainNum,
//                productName
//            )
//            line = Line(
//                parsedLine.id, parsedLine.network, parsedLine.product, parsedLine.label,
//                lineStyle(parsedLine.network, parsedLine.product, parsedLine.label)
//            )
//        }
//
//        XmlPullUtil.skipExit(pp, "m")
//
//        return LineDestination(line, destination)
//    }
//
//    @Throws(XmlPullParserException::class, IOException::class)
//    private fun parseMobileDv(pp: XmlPullParser): String {
//        XmlPullUtil.enter(pp, "dv")
//        XmlPullUtil.optValueTag(pp, "branch", null)
//        val lineIdLi: String = XmlPullUtil.valueTag(pp, "li")
//        val lineIdSu: String = XmlPullUtil.valueTag(pp, "su")
//        val lineIdPr: String = XmlPullUtil.valueTag(pp, "pr")
//        val lineIdDct: String = XmlPullUtil.valueTag(pp, "dct")
//        val lineIdNe: String = XmlPullUtil.valueTag(pp, "ne")
//        XmlPullUtil.skipExit(pp, "dv")
//
//        return "$lineIdNe:$lineIdLi:$lineIdSu:$lineIdDct:$lineIdPr"
//    }
//
//    @Throws(XmlPullParserException::class, IOException::class)
//    private fun parseMobileSt(
//        pp: XmlPullParser,
//        plannedDepartureTime: Calendar,
//        predictedDepartureTime: Calendar
//    ) {
//        XmlPullUtil.enter(pp, "st")
//
//        plannedDepartureTime.clear()
//        ParserUtils.parseIsoDate(plannedDepartureTime, XmlPullUtil.valueTag(pp, "da"))
//        ParserUtils.parseIsoTime(plannedDepartureTime, XmlPullUtil.valueTag(pp, "t"))
//
//        predictedDepartureTime.clear()
//        if (XmlPullUtil.test(pp, "rda")) {
//            ParserUtils.parseIsoDate(predictedDepartureTime, XmlPullUtil.valueTag(pp, "rda"))
//            ParserUtils.parseIsoTime(predictedDepartureTime, XmlPullUtil.valueTag(pp, "rt"))
//        }
//
//        XmlPullUtil.skipExit(pp, "st")
//    }
//
//    private fun findStationDepartures(
//        stationDepartures: List<StationDepartures>,
//        id: String
//    ): StationDepartures? {
//        for (stationDeparture in stationDepartures) if (id == stationDeparture.location.id) return stationDeparture
//
//        return null
//    }
//
//    private fun processItdPointAttributes(pp: XmlPullParser): Location {
//        val id: String = XmlPullUtil.attr(pp, "stopID")
//
//        var place = normalizeLocationName(XmlPullUtil.optAttr(pp, "locality", null))
//        if (place == null) place = normalizeLocationName(XmlPullUtil.optAttr(pp, "place", null))
//
//        var name = normalizeLocationName(XmlPullUtil.optAttr(pp, "nameWO", null))
//        if (name == null) name = normalizeLocationName(XmlPullUtil.optAttr(pp, "name", null))
//
//        val coord: Point? = processCoordAttr(pp)
//
//        return Location(LocationType.STATION, id, coord, place, name)
//    }
//
//    @Throws(XmlPullParserException::class, IOException::class)
//    private fun processItdDateTime(pp: XmlPullParser, calendar: Calendar): Boolean {
//        XmlPullUtil.enter(pp)
//        calendar.clear()
//        val success = processItdDate(pp, calendar)
//        if (success) processItdTime(pp, calendar)
//        XmlPullUtil.skipExit(pp)
//
//        return success
//    }
//
//    @Throws(XmlPullParserException::class, IOException::class)
//    private fun processItdDate(pp: XmlPullParser, calendar: Calendar): Boolean {
//        XmlPullUtil.require(pp, "itdDate")
//        val year: Int = XmlPullUtil.intAttr(pp, "year")
//        val month: Int = XmlPullUtil.intAttr(pp, "month") - 1
//        val day: Int = XmlPullUtil.intAttr(pp, "day")
//        val weekday: Int = XmlPullUtil.intAttr(pp, "weekday")
//        XmlPullUtil.next(pp)
//
//        if (weekday < 0) return false
//        if (year == 0) return false
//        if (year < 1900 || year > 2100) throw InvalidDataException("invalid year: $year")
//        if (month < 0 || month > 11) throw InvalidDataException("invalid month: $month")
//        if (day < 1 || day > 31) throw InvalidDataException("invalid day: $day")
//
//        calendar.set(Calendar.YEAR, year)
//        calendar.set(Calendar.MONTH, month)
//        calendar.set(Calendar.DAY_OF_MONTH, day)
//        return true
//    }
//
//    @Throws(XmlPullParserException::class, IOException::class)
//    private fun processItdTime(pp: XmlPullParser, calendar: Calendar) {
//        XmlPullUtil.require(pp, "itdTime")
//        calendar.set(Calendar.HOUR_OF_DAY, XmlPullUtil.intAttr(pp, "hour"))
//        calendar.set(Calendar.MINUTE, XmlPullUtil.intAttr(pp, "minute"))
//        XmlPullUtil.next(pp)
//    }
//

//
//    @Throws(XmlPullParserException::class, IOException::class)
//    private fun processItdServingLine(pp: XmlPullParser): LineDestinationAndCancelled {
//        XmlPullUtil.require(pp, "itdServingLine")
//
//        val destinationName = normalizeLocationName(XmlPullUtil.optAttr(pp, "direction", null))
//        val destinationIdStr: String = XmlPullUtil.optAttr(pp, "destID", null)
//        val destinationId = if ("-1" != destinationIdStr) destinationIdStr else null
//        val destination: Location?
//        if (destinationId != null) destination =
//            Location(LocationType.STATION, destinationId, null, destinationName)
//        else if (destinationId == null && destinationName != null) destination =
//            Location(LocationType.ANY, null, null, destinationName)
//        else destination = null
//
//        val slMotType: String = XmlPullUtil.attr(pp, "motType")
//        val slSymbol: String = XmlPullUtil.optAttr(pp, "symbol", null)
//        val slNumber: String = XmlPullUtil.optAttr(pp, "number", null)
//        val slStateless: String = XmlPullUtil.optAttr(pp, "stateless", null)
//        val slTrainType: String = XmlPullUtil.optAttr(pp, "trainType", null)
//        val slTrainName: String = XmlPullUtil.optAttr(pp, "trainName", null)
//        val slTrainNum: String = XmlPullUtil.optAttr(pp, "trainNum", null)
//
//        XmlPullUtil.enter(pp, "itdServingLine")
//        var itdTrainName: String? = null
//        var itdTrainType: String? = null
//        var itdMessage: String? = null
//        var itdDelay: String? = null
//        if (XmlPullUtil.test(pp, "itdTrain")) {
//            itdTrainName = XmlPullUtil.optAttr(pp, "name", null)
//            itdTrainType = XmlPullUtil.attr(pp, "type")
//            itdDelay = XmlPullUtil.optAttr(pp, "delay", null)
//            XmlPullUtil.requireSkip(pp, "itdTrain")
//        }
//        if (XmlPullUtil.test(pp, "itdNoTrain")) {
//            itdTrainName = XmlPullUtil.optAttr(pp, "name", null)
//            itdTrainType = XmlPullUtil.optAttr(pp, "type", null)
//            itdDelay = XmlPullUtil.optAttr(pp, "delay", null)
//            if (!pp.isEmptyElementTag()) {
//                val text: String = XmlPullUtil.valueTag(pp, "itdNoTrain")
//                if (itdTrainName != null && itdTrainName.lowercase(Locale.getDefault())
//                        .contains("ruf")
//                ) itdMessage = text
//                else if (text != null && text.lowercase(Locale.getDefault())
//                        .contains("ruf")
//                ) itdMessage = text
//            } else {
//                XmlPullUtil.next(pp)
//            }
//        }
//
//        XmlPullUtil.require(pp, "motDivaParams")
//        val divaNetwork: String = XmlPullUtil.optAttr(pp, "network", null)
//
//        XmlPullUtil.skipExit(pp, "itdServingLine")
//
//        val trainType: String = ParserUtils.firstNotEmpty(slTrainType, itdTrainType)
//        val trainName: String = ParserUtils.firstNotEmpty(slTrainName, itdTrainName)
//        val slLine: Line = parseLine(
//            slStateless, divaNetwork, slMotType, slSymbol, slNumber, slNumber, trainType,
//            slTrainNum, trainName
//        )
//
//        val line: Line = Line(
//            slLine.id, slLine.network, slLine.product, slLine.label,
//            lineStyle(slLine.network, slLine.product, slLine.label), itdMessage
//        )
//        val cancelled = "-9999" == itdDelay
//        return LineDestinationAndCancelled(line, destination, cancelled)
//    }
//
    protected fun normalizeLocationName(name: String?): String? {
        if (name.isNullOrEmpty()) return null

        return name.replace(P_STATION_NAME_WHITESPACE, " ")
    }

    protected fun appendTripRequestParameters(
        url: URLBuilder, from: Location,
        via: Location?, to: Location, time: Long, dep: Boolean,
        options: TripOptions?
    ) {
        var options: TripOptions? = options
        appendCommonRequestParams(url, "XML")

        url.parameters.append("sessionID", "0")
        url.parameters.append("requestID", "0")

        appendCommonTripRequestParams(url)

        appendLocationParams(url, from, "origin")
        appendLocationParams(url, to, "destination")
        if (via != null) appendLocationParams(url, via, "via")

        appendItdDateTimeParameters(url, time)

        url.parameters.append("itdTripDateTimeDepArr", if (dep) "dep" else "arr")

        url.parameters.append("calcNumberOfTrips", numTripsRequested.toString())

        url.parameters.append("ptOptionsActive", "1") // enable public transport options
        url.parameters.append("itOptionsActive", "1") // enable individual transport options

        if (options == null) options = TripOptions()

        if (options.optimize == NetworkProvider.Optimize.LEAST_DURATION) url.parameters.append(
            "routeType",
            "LEASTTIME"
        )
        else if (options.optimize == NetworkProvider.Optimize.LEAST_CHANGES) url.parameters.append(
            "routeType",
            "LEASTINTERCHANGE"
        )
        else if (options.optimize == NetworkProvider.Optimize.LEAST_WALKING) url.parameters.append(
            "routeType",
            "LEASTWALKING"
        )
        else if (options.optimize != null) log.log("Cannot handle " + options.optimize + ", ignoring.")

        url.parameters.append("changeSpeed", WALKSPEED_MAP[options.walkSpeed]!!)

        if (options.accessibility == NetworkProvider.Accessibility.BARRIER_FREE) {
            url.parameters.append("imparedOptionsActive", "1")
            url.parameters.append("wheelchair", "on")
            url.parameters.append("noSolidStairs", "on")
        }
        else if (options.accessibility == NetworkProvider.Accessibility.LIMITED) {
            url.parameters.append("imparedOptionsActive", "1")
            url.parameters.append("wheelchair", "on")
            url.parameters.append("lowPlatformVhcl", "on")
            url.parameters.append("noSolidStairs", "on")
        }

        options.products?.let { prods ->
            url.parameters.append("includedMeans", "checkbox")

            var hasI = false

            prods.forEach { p ->
                if (p === Product.HIGH_SPEED_TRAIN || p === Product.REGIONAL_TRAIN) {
                    url.parameters.append("inclMOT_0", "on")
                    if (p === Product.HIGH_SPEED_TRAIN) hasI = true
                }

                if (p === Product.HIGH_SPEED_TRAIN) {
                    url.parameters.append("inclMOT_14", "on")
                    url.parameters.append("inclMOT_15", "on")
                    url.parameters.append("inclMOT_16", "on")
                }

                if (p === Product.REGIONAL_TRAIN) {
                    url.parameters.append("inclMOT_13", "on")
                    url.parameters.append("inclMOT_18", "on")
                }

                if (p === Product.SUBURBAN_TRAIN)
                    url.parameters.append("inclMOT_1", "on")

                if (p === Product.SUBWAY)
                    url.parameters.append("inclMOT_2", "on")

                if (p === Product.TRAM) {
                    url.parameters.append("inclMOT_3", "on")
                    url.parameters.append("inclMOT_4", "on")
                }

                if (p === Product.BUS) {
                    url.parameters.append("inclMOT_5", "on")
                    url.parameters.append("inclMOT_6", "on")
                    url.parameters.append("inclMOT_7", "on")
                    url.parameters.append("inclMOT_17", "on")
                    url.parameters.append("inclMOT_19", "on")
                }

                if (p === Product.ON_DEMAND)
                    url.parameters.append("inclMOT_10", "on")

                if (p === Product.FERRY)
                    url.parameters.append("inclMOT_9", "on")

                if (p === Product.CABLECAR)
                    url.parameters.append("inclMOT_8", "on")
            }

            // workaround for highspeed trains: fails when you want highspeed, but not regional
            if (useLineRestriction && !hasI) url.parameters.append(
                "lineRestriction",
                "403"
            ) // means: all but ice
        }

        if (useProxFootSearch) url.parameters.append(
            "useProxFootSearch",
            "1"
        ) // walk if it makes journeys quicker

        url.parameters.append(
            "trITMOTvalue100",
            "10"
        ) // maximum time to walk to first or from last

        // stop
        if (options.flags?.contains(NetworkProvider.TripFlag.BIKE) == true)
            url.parameters.append("bikeTakeAlong", "1")

        url.parameters.append("locationServerActive", "1")
        url.parameters.append("useRealtime", "1")
        url.parameters.append(
            "nextDepsPerLeg",
            "1"
        ) // next departure in case previous was missed
    }

    private fun commandLink(sessionId: String, requestId: String): Url {
        val url = URLBuilder(tripEndpoint)
        url.parameters.append("sessionID", sessionId)
        url.parameters.append("requestID", requestId)
        url.parameters.append("calcNumberOfTrips", numTripsRequested.toString())
        appendCommonTripRequestParams(url)
        return url.build()
    }

    private fun appendCommonTripRequestParams(url: URLBuilder) {
        url.parameters.append(
            "coordListOutputFormat",
            if (useStringCoordListOutputFormat) "string" else "list"
        )
    }

    @Serializable
    @XmlSerialName("itdMessage")
    data class ItdMessage(
        @XmlElement(false) val code: Int,
        @XmlElement(false) val type: String? = null,
        @XmlValue val text: String? = null
    )



    @Serializable
    @XmlSerialName("itdRequest", "", "")
    data class ItdTripsResponse(
        @XmlElement(false) override val version: String,
        @XmlElement(false) override val now: String,
        @XmlElement(false) override val sessionID: String,
        @XmlElement(false) override val serverID: String,
        @XmlElement(true) val itdTripRequest: ItdTripRequest,
    ) : ItdRequest {

        @Serializable
        @XmlSerialName("itdTripRequest")
        data class ItdTripRequest(
            @XmlElement(false) val requestID: String,
            @XmlElement(true) val itdMessage: List<ItdMessage>? = null,
            @XmlElement(true) val itdOdv: List<ItdOdv> = emptyList(),
            @XmlElement(true) val itdTripDateTime: ItdTripDateTime,
            @XmlElement(true) val itdItinerary: ItdItinerary,
        ) {

            @Serializable
            @XmlSerialName("itdItinerary")
            data class ItdItinerary(
                @XmlElement(true) val itdRouteList: ItdRouteList
            ) {
                @Serializable
                @XmlSerialName("itdRouteList")
                data class ItdRouteList(
                    @XmlElement(true) val itdRoute: List<ItdRoute> = emptyList()
                ) {

                    @Serializable
                    @XmlSerialName("itdRoute")
                    data class ItdRoute(
                        @XmlElement(false) val routeIndex: String? = null,
                        @XmlElement(false) val routeTripIndex: String? = null,
                        @XmlElement(false) val changes: Int,
                        @XmlElement(true) val itdPartialRouteList: ItdPartialRouteList,
                        @XmlElement(true) val itdFare: ItdFare? = null
                    ) {
                        @Serializable
                        @XmlSerialName("itdFare")
                        data class ItdFare(
                            @XmlElement(true) val itdSingleTicket: ItdSingleTicket? = null,
                        ) {
                            @Serializable
                            @XmlSerialName("itdSingleTicket")
                            data class ItdSingleTicket(
                                @XmlElement(false) val net: String? = null,
                                @XmlElement(false) val currency: String? = null,
                                @XmlElement(false) val fareAdult: String? = null,
                                @XmlElement(false) val fareChild: String? = null,
                                @XmlElement(false) val unitName: String? = null,
                                @XmlElement(false) val unitsAdult: String? = null,
                                @XmlElement(false) val unitsChild: String? = null,
                                @XmlElement(false) val levelAdult: String? = null,
                                @XmlElement(false) val levelChild: String? = null,
                                @XmlElement(true) val itdGenericTicketList: ItdGenericTicketList? = null
                            ) {
                                @Serializable
                                @XmlSerialName("itdGenericTicketList")
                                data class ItdGenericTicketList(
                                    @XmlElement(true) val itdGenericTicketGroup: List<ItdGenericTicketGroup> = emptyList()
                                ) {
                                    @Serializable
                                    @XmlSerialName("itdGenericTicketGroup")
                                    data class ItdGenericTicketGroup(
                                        @XmlElement(true) val itdGenericTicket: List<ItdGenericTicket> = emptyList()
                                    ) {
                                        @Serializable
                                        @XmlSerialName("itdGenericTicket")
                                        data class ItdGenericTicket(
                                            @XmlElement(true) val ticket: String,
                                            @XmlElement(true) val value: String
                                        )
                                    }
                                }
                            }
                        }

                        @Serializable
                        @XmlSerialName("itdPartialRouteList")
                        data class ItdPartialRouteList(
                            val itdPartialRoute: List<ItdPartialRoute> = emptyList()
                        ) {
                            @Serializable
                            @XmlSerialName("itdPartialRoute")
                            data class ItdPartialRoute(
                                @XmlElement(false) val type: String,
                                @XmlElement(false) val distance: Int? = null,
                                @XmlElement(true) val itdPoint: List<ItdPoint> = emptyList(),
                                @XmlElement(true) val itdMeansOfTransport: ItdMeansOfTransport,
                                @XmlElement(true) val itdRBLControlled: ItdRBLControlled? = null,
                                @XmlElement(true) val itdInfoTextList: ItdInfoTextList? = null,
                                @XmlElement(true) val infoLink: InfoLink? = null,
                                @XmlElement(true) val itdStopSeq: ItdStopSeq? = null,
                                @XmlElement(true) val itdPathCoordinates: ItdPathCoordinates? = null,
                                @XmlElement(true) val genAttrList: GenAttrList? = null,
                                @XmlElement(true) val nextDeps: NextDeps? = null,
                            ) {

                                @Serializable
                                @XmlSerialName("nextDeps")
                                data class NextDeps(
                                    @XmlElement(true) val itdDateTime: List<ItdDateTime>
                                )

                                @Serializable
                                @XmlSerialName("itdStopSeq")
                                data class ItdStopSeq(
                                    @XmlElement(true) val itdPoint: List<ItdPoint> = emptyList()
                                )

                                @Serializable
                                @XmlSerialName("infoLink")
                                data class InfoLink(
                                    @XmlElement(true) val infoLinkText: String? = null
                                )

                                @Serializable
                                @XmlSerialName("itdInfoTextList")
                                data class ItdInfoTextList(
                                    @XmlElement(true) val infoTextListElem: List<InfoTextListElem> = emptyList()
                                ) {
                                    @Serializable
                                    @XmlSerialName("infoTextListElem")
                                    data class InfoTextListElem(
                                        @XmlValue val text: String? = null
                                    )
                                }

                                @Serializable
                                @XmlSerialName("itdRBLControlled")
                                data class ItdRBLControlled(
                                    @XmlElement(false) val delayMinutes: Int? = null,
                                    @XmlElement(false) val delayMinutesArr: Int? = null,
                                )

                                @Serializable
                                @XmlSerialName("itdMeansOfTransport")
                                data class ItdMeansOfTransport(
                                    @XmlElement(false) val productName: String? = null,
                                    @XmlElement(false) val type: Int,
                                    @XmlElement(false) val destination: String? = null,
                                    @XmlElement(false) val destID: String? = null,
                                    @XmlElement(false) val symbol: String? = null,
                                    @XmlElement(false) val motType: String? = null,
                                    @XmlElement(false) val shortname: String? = null,
                                    @XmlElement(false) val name: String,
                                    @XmlElement(false) val trainName: String? = null,
                                    @XmlElement(false) val trainType: String? = null,
                                    @XmlElement(true) val motDivaParams: MotDivaParams,
                                )


                                    @Serializable
                                @XmlSerialName("itdPoint")
                                data class ItdPoint(
                                    @XmlElement(false) val stopID: String,
                                    @XmlElement(false) val locality: String? = null,
                                    @XmlElement(false) val place: String? = null,
                                    @XmlElement(false) val nameWO: String? = null,
                                    @XmlElement(false) val name: String? = null,
                                    @XmlElement(false) val mapName: String? = null,
                                    @XmlElement(false) val x: String? = null,
                                    @XmlElement(false) val y: String? = null,
                                    @XmlElement(false) val usage: String,
                                    @XmlElement(false) val platformName: String? = null,
                                    @XmlElement(true) val itdDateTime: List<ItdDateTime>,
                                    @XmlElement(true) val itdDateTimeTarget: ItdDateTimeTarget? = null,
                                ) {
                                    @Serializable
                                    @XmlSerialName("itdDateTimeTarget")
                                    data class ItdDateTimeTarget(
                                        @XmlElement(true) val itdDate: ItdDate,
                                        @XmlElement(true) val itdTime: ItdTime
                                    )
                                }
                            }
                        }
                    }

                }
            }

            @Serializable
            @XmlSerialName("itdTripDateTime")
            data class ItdTripDateTime(
//                @XmlElement(false) val deparr: String,
//                @XmlElement(false) val ttpFrom: String,
//                @XmlElement(false) val ttpTo: String,
                @XmlElement(true) val itdDateTime: ItdDateTime,
            )
        }

    }

    override suspend fun queryTrips(
        from: Location, via: Location?, to: Location,
        date: Long, dep: Boolean, options: TripOptions?
    ): QueryTripsResult {
        val url = URLBuilder(tripEndpoint)
        appendTripRequestParameters(url, from, via, to, date, dep, options)

        val rsp = httpClient.config {
            install(ContentNegotiation) {
                xml()
            }
        }.get(url.build()) {
            setHttpReferer(httpReferer)
        }

        return parseQueryTripsResponse(
            xml.decodeFromString(ItdTripsResponse.serializer(), rsp.bodyAsText()),
            url.build()
        )
    }

    fun parseQueryTripsResponse(data: ItdTripsResponse, url: Url): QueryTripsResult {
//        try {
            val header = data.parseHeader()

            // handle no trips
            if(data.itdTripRequest.itdMessage?.any { it.code == -4000 } == true)
                return QueryTripsResult(header, QueryTripsResult.Status.NO_TRIPS)

            var ambiguousFrom: List<Location>? = null
            var ambiguousTo: List<Location>? = null
            var ambiguousVia: List<Location>? = null
            var from: Location? = null
            var to: Location? = null
            var via: Location? = null

            data.itdTripRequest.itdOdv.forEach { odv ->
                val place = odv.itdOdvPlace.takeIf { it.state == "identified" }?.odvPlaceElem?.place ?: "???"

                val locations = odv.itdOdvName.odvNameElem.mapNotNull {
                    it.toLocation(type = odv.type, defaultPlace = place)
                } + (odv.itdOdvAssignedStops?.itdOdvAssignedStop?.map {
                    it.toLocation()
                } ?: emptyList())

                when (odv.itdOdvName.state) {
                    "list" -> {
                        when(odv.usage) {
                            "origin" -> ambiguousFrom = locations.toList()
                            "via" -> ambiguousVia = locations.toList()
                            "destination" -> ambiguousTo = locations.toList()
                            else -> throw IllegalStateException("unknown usage: ${odv.usage}")
                        }
                    }
                    "identified" -> {
                        when(odv.usage) {
                            "origin" -> from = locations.firstOrNull()
                            "via" -> via = locations.firstOrNull()
                            "destination" -> to = locations.firstOrNull()
                            else -> throw IllegalStateException("unknown usage: ${odv.usage}")
                        }
                    }
                    "notidentified" -> {
                        when(odv.usage) {
                            "origin" -> return QueryTripsResult(
                                header,
                                QueryTripsResult.Status.UNKNOWN_FROM
                            )

                            "via" -> return QueryTripsResult(
                                header,
                                QueryTripsResult.Status.UNKNOWN_VIA
                            )

                            "destination" -> return QueryTripsResult(
                                header,
                                QueryTripsResult.Status.UNKNOWN_TO
                            )

                            else -> throw IllegalStateException("unknown usage: ${odv.usage}")
                        }
                    }
                }
            }

            if(ambiguousFrom != null && ambiguousTo != null && ambiguousVia != null)
                return QueryTripsResult(
                    header = header,
                    status = QueryTripsResult.Status.AMBIGUOUS,
                    ambiguousFrom = ambiguousFrom,
                    ambiguousTo = ambiguousTo,
                    ambiguousVia = ambiguousVia
                )

            require(from != null) { "from not set" }
            require(to != null) { "to not set" }

            if(data.itdTripRequest.itdTripDateTime.itdDateTime.itdDate.itdMessage?.any {
                    it.text == "invalid date"
                } == true) return QueryTripsResult(header, QueryTripsResult.Status.INVALID_DATE)

            val trips = data.itdTripRequest.itdItinerary.itdRouteList.itdRoute.mapNotNull {
                val tripId = listOfNotNull(it.routeIndex, it.routeTripIndex).joinToString("-").takeIf { useRouteIndexAsTripId }
                val legs: MutableList<Leg> = mutableListOf()
                var firstDepartureLocation: Location? = null
                var lastArrivalLocation: Location? = null
                var cancelled = false

                it.itdPartialRouteList.itdPartialRoute.forEachIndexed { index, pr ->
                    val firstStop = pr.itdPoint.first()
                    val departureLocation = firstStop.toLocation()
                    if(firstDepartureLocation == null)
                        firstDepartureLocation = departureLocation
                    val departurePosition = parsePosition(firstStop.platformName)
                    val departureTime = firstStop.itdDateTime.first().let { dt ->
                        dt.itdDate.toLocalDate()?.let {
                            LocalDateTime(it, dt.itdTime.toLocalTime())
                                .toInstant(timezone)
                                .toEpochMilliseconds()
                        }
                    }

                    val departureTargetTime = firstStop.itdDateTimeTarget?.let { dt ->
                        dt.itdDate.toLocalDate()?.let {
                            LocalDateTime(it, dt.itdTime.toLocalTime())
                                .toInstant(timezone)
                                .toEpochMilliseconds()
                        }
                    }

                    val lastStop = pr.itdPoint.last()
                    val arrivalLocation = lastStop.toLocation()
                    if(lastArrivalLocation == null)
                        lastArrivalLocation = arrivalLocation
                    val arrivalPosition = parsePosition(lastStop.platformName)
                    val arrivalTime = lastStop.itdDateTime.last().let { dt ->
                        dt.itdDate.toLocalDate()?.let {
                            LocalDateTime(it, dt.itdTime.toLocalTime())
                                .toInstant(timezone)
                                .toEpochMilliseconds()
                        }
                    }

                    val arrivalTargetTime = lastStop.itdDateTimeTarget?.let { dt ->
                        dt.itdDate.toLocalDate()?.let {
                            LocalDateTime(it, dt.itdTime.toLocalTime())
                                .toInstant(timezone)
                                .toEpochMilliseconds()
                        }
                    }

                    println(pr)

                    val motType = pr.itdMeansOfTransport.type
                    when {
                        motType <= 16 -> pr.itdMeansOfTransport.let { mot ->
                            val destinationName = mot.destination.normalize()
                            val destinationId = mot.destID

                            println(mot)

                            val destination = if(destinationId != null)
                                Location(
                                    id = destinationId,
                                    type = Location.Type.STATION,
                                    coord = null,
                                    name = destinationName
                                )
                            else if(destinationName != null)
                                Location(
                                    id = null,
                                    type = Location.Type.ANY,
                                    coord = null,
                                    name = destinationName
                                )
                            else null

                            val lineId = mot.motDivaParams.let {
                                "${it.network}:${it.line}:${it.supplement}:${it.direction}:${it.project}"
                            }

                            val line = if(mot.symbol == "AST")
                                Line(
                                    id = "?",
                                    network = mot.motDivaParams.network,
                                    product = Product.BUS,
                                    label = "AST"
                                )
                            else
                                parseLine(
                                    _id = lineId,
                                    network = mot.motDivaParams.network,
                                    mot = mot.motType,
                                    symbol = mot.symbol,
                                    name = mot.shortname,
                                    longName = mot.name,
                                    trainType = mot.trainType,
                                    trainNum = mot.shortname,
                                    trainName = mot.trainName
                                )

                            val intermediateStops = pr.itdStopSeq?.itdPoint?.map { stop ->
                                val plannedArrivalTime = stop.itdDateTime.first().let { dt ->
                                    dt.itdDate.toLocalDate()?.let {
                                        LocalDateTime(
                                            it,
                                            dt.itdTime.toLocalTime()
                                        ).toInstant(timezone)
                                    }
                                }

                                val predictedArrivalTime = pr.itdRBLControlled?.delayMinutesArr?.let { delay ->
                                    plannedArrivalTime?.plus(delay.minutes)
                                }

                                val plannedDepartureTime = stop.itdDateTime.last().let { dt ->
                                    dt.itdDate.toLocalDate()?.let {
                                        LocalDateTime(
                                            it,
                                            dt.itdTime.toLocalTime()
                                        ).toInstant(timezone)
                                    }
                                }

                                val predictedDepartureTime = pr.itdRBLControlled?.delayMinutes?.let { delay ->
                                    plannedDepartureTime?.plus(delay.minutes)
                                }

                                Stop(
                                    location = stop.toLocation(),
                                    plannedArrivalTime = plannedArrivalTime?.toEpochMilliseconds(),
                                    predictedArrivalTime = predictedArrivalTime?.toEpochMilliseconds(),
                                    plannedDepartureTime = plannedDepartureTime?.toEpochMilliseconds(),
                                    predictedDepartureTime = predictedDepartureTime?.toEpochMilliseconds(),
                                    plannedArrivalPosition = parsePosition(stop.platformName),
                                    plannedDeparturePosition = parsePosition(stop.platformName)
                                )
                            }?.dropLast(1)?.drop(1) // remove first and last, because they are not intermediate

                            val path = pr.itdPathCoordinates?.toPointList()

                            val wheelChairAccess = pr.genAttrList?.genAttrElem?.any {
                                it.name == "PlanWheelChairAccess" && it.value == "1"
                            } ?: false

                            // KVV
                            val lowFloorVehicle = pr.itdInfoTextList?.infoTextListElem?.any {
                                it.text?.lowercase()?.startsWith("niederflurwagen") ?: false
                            } ?: false

                            // Bedarfsverkehr
                            val message = pr.itdInfoTextList?.infoTextListElem?.mapNotNull {
                                it.text?.takeIf {
                                    it.lowercase().contains("ruf") || it.lowercase().contains("anmeld")
                                }
                            }?.joinToString(", ")
                                ?: pr.infoLink?.infoLinkText

                            val lineAttrs = if(wheelChairAccess || lowFloorVehicle)
                                setOf(Line.Attr.WHEEL_CHAIR_ACCESS)
                            else
                                emptySet()

                            val styledLine = line.copy(
                                style = lineStyle(
                                    network = line.network,
                                    product = line.product,
                                    label = line.label
                                ),
                                attributes = lineAttrs
                            )

                            //final Location location, final boolean departure, final Date plannedTime, final Date predictedTime,
                            //            final Position plannedPosition, final Position predictedPosition, final boolean cancelled
                            val departureStop = Stop(
                                location = departureLocation,
                                plannedDepartureTime = departureTargetTime ?: departureTime,
                                predictedDepartureTime = departureTime,
                                plannedDeparturePosition = departurePosition
                            )

                            val arrivalStop = Stop(
                                location = arrivalLocation,
                                plannedArrivalTime = arrivalTargetTime ?: arrivalTime,
                                predictedArrivalTime = arrivalTime,
                                plannedArrivalPosition = arrivalPosition
                            )

                            legs.add(
                                PublicLeg(
                                    line = styledLine,
                                    destination = destination,
                                    departureStop = departureStop,
                                    arrivalStop = arrivalStop,
                                    intermediateStops = intermediateStops,
                                    path = path ?: PathUtil.interpolatePath(
                                        departureLocation,
                                        intermediateStops,
                                        arrivalLocation
                                    ),
                                    message = message
                                )
                            )

                            cancelled = cancelled or (pr.itdRBLControlled?.let {
                                it.delayMinutes == -9999 || it.delayMinutesArr == -9999
                            } ?: false)
                        }

                        motType == 97 && pr.itdMeansOfTransport.productName?.lowercase() == "nicht umsteigen" -> {
                            // ignore
                        }

                        motType == 98 && pr.itdMeansOfTransport.productName?.lowercase() == "gesicherter anschluss" -> {
                            // ignore
                        }

                        (motType == 99 && pr.itdMeansOfTransport.productName?.lowercase() == "fussweg") ||
                                (motType == 100 && pr.itdMeansOfTransport.productName?.lowercase().let { it == "fussweg" || it == null }) -> {
                            val path = pr.itdPathCoordinates?.toPointList()?.toMutableList()
                            legs.lastOrNull().let { lastLeg ->
                                if(lastLeg is IndividualLeg && lastLeg.type == IndividualLeg.Type.WALK) {
                                    val lastIndiviual = legs.removeLast() as IndividualLeg

                                    path?.addAll(0, lastIndiviual.path)

                                    legs.add(
                                        lastIndiviual.copy(
                                            arrival = arrivalLocation,
                                            arrivalTime = arrivalTime ?: 0,
                                            path = path ?: PathUtil.interpolatePath(
                                                lastIndiviual.departure,
                                                null,
                                                arrivalLocation
                                            ),
                                            distance = lastIndiviual.distance + (pr.distance ?: 0)
                                        )
                                    )
                                } else {
                                    legs.add(
                                        IndividualLeg(
                                            type = IndividualLeg.Type.WALK,
                                            departure = departureLocation,
                                            departureTime = departureTime ?: 0,
                                            arrival = arrivalLocation,
                                            arrivalTime = arrivalTime ?: 0,
                                            path = path ?: PathUtil.interpolatePath(
                                                departureLocation,
                                                null,
                                                arrivalLocation
                                            ),
                                            distance = pr.distance ?: 0
                                        )
                                    )
                                }
                            }
                        }

                        (motType == 105 && pr.itdMeansOfTransport.productName?.lowercase() == "taxi") -> {
                            val path = pr.itdPathCoordinates?.toPointList()?.toMutableList()
                            legs.lastOrNull().let { lastLeg ->
                                if(lastLeg is IndividualLeg && lastLeg.type == IndividualLeg.Type.CAR) {
                                    val lastIndiviual = legs.removeLast() as IndividualLeg

                                    path?.addAll(0, lastIndiviual.path)

                                    legs.add(
                                        lastIndiviual.copy(
                                            arrival = arrivalLocation,
                                            arrivalTime = arrivalTime ?: 0,
                                            path = path ?: PathUtil.interpolatePath(
                                                lastIndiviual.departure,
                                                null,
                                                arrivalLocation
                                            ),
                                            distance = lastIndiviual.distance + (pr.distance ?: 0)
                                        )
                                    )
                                } else {
                                    legs.add(
                                        IndividualLeg(
                                            type = IndividualLeg.Type.CAR,
                                            departure = departureLocation,
                                            departureTime = departureTime ?: 0,
                                            arrival = arrivalLocation,
                                            arrivalTime = arrivalTime ?: 0,
                                            path = path ?: PathUtil.interpolatePath(
                                                departureLocation,
                                                null,
                                                arrivalLocation
                                            ),
                                            distance = pr.distance ?: 0
                                        )
                                    )
                                }
                            }
                        }

                        else -> throw IllegalStateException("Invalid MOT found:\n" +
                                "itdPartialRoute.type: ${pr.type}\n" +
                                "itdMeansOfTransport.type: $motType\n" +
                                "itdMeansOfTransport.productName: ${pr.itdMeansOfTransport.productName}");
                    }
                }

                require(firstDepartureLocation != null) { "first departure location not set" }
                require(lastArrivalLocation != null) { "last arrival location not set" }

                val fares = it.itdFare?.itdSingleTicket?.let {
                    listOfNotNull(
                        it.net?.let { net -> it.currency?.let { currency -> it.fareAdult?.let { fareAdult ->
                            Fare(
                                name = net.uppercase(),
                                type = Fare.Type.ADULT,
                                currency = currency,
                                fare = fareAdult.toFloat(),
                                unitName = it.unitName,
                                units = it.unitsAdult
                            )
                        }}},
                        it.net?.let { net -> it.currency?.let { currency -> it.fareChild?.let { fareChild ->
                            Fare(
                                name = net.uppercase(),
                                type = Fare.Type.CHILD,
                                currency = currency,
                                fare = fareChild.toFloat(),
                                unitName = it.unitName,
                                units = it.unitsChild
                            )
                        }}}

                    ) }?.takeIf { it.isNotEmpty() }

                Trip(
                    id = tripId,
                    from = firstDepartureLocation!!,
                    to = lastArrivalLocation!!,
                    legs = legs.toList().ensureSeparatingIndividualLegs(),
                    fares = fares,
                    capacity = null,
                    changes = it.changes
                ).takeIf { !cancelled }
            }

            return QueryTripsResult(
                header = header,
                queryUri = url.toString(),
                from = from!!,
                via = via,
                to = to!!,
                context = Context(
                    commandLink(data.sessionID, data.itdTripRequest.requestID).toString()
                ),
                trips = trips
            )
//        } catch(e: Exception) {
//            e.printStackTrace()
//            throw RuntimeException("failed to parse xml: ${rsp.bodyAsText()}", e)
//        }
    }
//
//    @Throws(IOException::class)
//    protected fun queryTripsMobile(
//        from: Location, @Nullable via: Location?, to: Location,
//        date: java.util.Date, dep: Boolean, @Nullable options: TripOptions?
//    ): QueryTripsResult {
//        val url: HttpUrl.Builder = tripEndpoint.newBuilder()
//        appendTripRequestParameters(url, from, via, to, date, dep, options)
//        val result: AtomicReference<QueryTripsResult> = AtomicReference<QueryTripsResult>()
//
//        val callback: HttpClient.Callback = HttpClient.Callback { bodyPeek, body ->
//            try {
//                result.set(queryTripsMobile(url.build(), from, via, to, body.charStream()))
//            } catch (x: XmlPullParserException) {
//                throw ParserException("cannot parse xml: $bodyPeek", x)
//            } catch (x: ParserException) {
//                throw ParserException("cannot parse xml: $bodyPeek", x)
//            } catch (x: java.lang.RuntimeException) {
//                throw java.lang.RuntimeException("uncategorized problem while processing $url", x)
//            }
//        }
//
//        httpClient.getInputStream(callback, url.build(), httpRefererTrip)
//
//        return result.get()
//    }
//

    override suspend fun queryMoreTrips(context: QueryTripsContext, later: Boolean): QueryTripsResult {
        val context = context as Context
        val url = URLBuilder(context.context!!)
        appendCommonRequestParams(url, "XML")
        url.parameters.append("command", if (later) "tripNext" else "tripPrev")

        val rsp = httpClient.config {
            install(ContentNegotiation) {
                xml()
            }
        }.get(url.build()) {
            setHttpReferer(httpReferer)
        }

        return parseQueryTripsResponse(
            xml.decodeFromString(ItdTripsResponse.serializer(), rsp.bodyAsText()),
            url.build()
        )
    }
//
//    @Throws(IOException::class)
//    protected fun queryMoreTripsMobile(
//        contextObj: QueryTripsContext,
//        later: Boolean
//    ): QueryTripsResult {
//        val context = contextObj as Context
//        val commandUrl: HttpUrl = HttpUrl.parse(context.context)
//        val url: HttpUrl.Builder = commandUrl.newBuilder()
//        appendCommonRequestParams(url, "XML")
//        url.parameters.append("command", if (later) "tripNext" else "tripPrev")
//        val result: AtomicReference<QueryTripsResult> = AtomicReference<QueryTripsResult>()
//
//        val callback: HttpClient.Callback = HttpClient.Callback { bodyPeek, body ->
//            try {
//                result.set(queryTripsMobile(url.build(), null, null, null, body.charStream()))
//            } catch (x: XmlPullParserException) {
//                throw ParserException("cannot parse xml: $bodyPeek", x)
//            } catch (x: ParserException) {
//                throw ParserException("cannot parse xml: $bodyPeek", x)
//            } catch (x: java.lang.RuntimeException) {
//                throw java.lang.RuntimeException("uncategorized problem while processing $url", x)
//            }
//        }
//
//        httpClient.getInputStream(callback, url.build(), httpRefererTrip)
//
//        return result.get()
//    }
//

//    private fun queryTrips(url: HttpUrl, reader: java.io.Reader): QueryTripsResult {
//        val pp: XmlPullParser = parserFactory.newPullParser()
//        pp.setInput(reader)
//        val header: ResultHeader = enterItdRequest(pp)
//        val context: Any = header.context
//
//        XmlPullUtil.require(pp, "itdTripRequest")
//        val requestId: String = XmlPullUtil.attr(pp, "requestID")
//        XmlPullUtil.enter(pp, "itdTripRequest")
//
//        while (XmlPullUtil.test(pp, "itdMessage")) {
//            val code: Int = XmlPullUtil.intAttr(pp, "code")
//            if (code == -4000) // no trips
//                return QueryTripsResult(header, QueryTripsResult.Status.NO_TRIPS)
//            XmlPullUtil.next(pp)
//        }
//        XmlPullUtil.optSkip(pp, "itdPrintConfiguration")
//        XmlPullUtil.optSkip(pp, "itdAddress")
//
//        var ambiguousFrom: List<Location>? = null
//        var ambiguousTo: List<Location>? = null
//        var ambiguousVia: List<Location>? = null
//        var from: Location? = null
//        var via: Location? = null
//        var to: Location? = null
//
//        while (XmlPullUtil.test(pp, "itdOdv")) {
//            val usage: String = XmlPullUtil.attr(pp, "usage")
//
//            val locations: MutableList<Location> = java.util.ArrayList<Location>()
//            val nameState = processItdOdv(
//                pp,
//                usage,
//                ProcessItdOdvCallback { nameState1: String?, location: Location, matchQuality: Int ->
//                    locations.add(location)
//                })
//
//            if ("list" == nameState) {
//                if ("origin" == usage) ambiguousFrom = locations
//                else if ("via" == usage) ambiguousVia = locations
//                else if ("destination" == usage) ambiguousTo = locations
//                else throw java.lang.IllegalStateException("unknown usage: $usage")
//            } else if ("identified" == nameState) {
//                if ("origin" == usage) from = locations[0]
//                else if ("via" == usage) via = locations[0]
//                else if ("destination" == usage) to = locations[0]
//                else throw java.lang.IllegalStateException("unknown usage: $usage")
//            } else if ("notidentified" == nameState) {
//                return if ("origin" == usage) QueryTripsResult(
//                    header,
//                    QueryTripsResult.Status.UNKNOWN_FROM
//                )
//                else if ("via" == usage) QueryTripsResult(
//                    header,
//                    QueryTripsResult.Status.UNKNOWN_VIA
//                )
//                else if ("destination" == usage) QueryTripsResult(
//                    header,
//                    QueryTripsResult.Status.UNKNOWN_TO
//                )
//                else throw java.lang.IllegalStateException("unknown usage: $usage")
//            }
//        }
//
//        if (ambiguousFrom != null || ambiguousTo != null || ambiguousVia != null) return QueryTripsResult(
//            header,
//            ambiguousFrom,
//            ambiguousVia,
//            ambiguousTo
//        )
//
//        XmlPullUtil.optSkip(pp, "itdAddOdvSeq")
//        XmlPullUtil.enter(pp, "itdTripDateTime")
//        XmlPullUtil.enter(pp, "itdDateTime")
//        XmlPullUtil.require(pp, "itdDate")
//        if (XmlPullUtil.optEnter(pp, "itdDate")) {
//            if (XmlPullUtil.test(pp, "itdMessage")) {
//                val message: String = XmlPullUtil.nextText(pp, null, "itdMessage")
//
//                if ("invalid date" == message) return QueryTripsResult(
//                    header,
//                    QueryTripsResult.Status.INVALID_DATE
//                )
//                else throw java.lang.IllegalStateException("unknown message: $message")
//            }
//            XmlPullUtil.skipExit(pp, "itdDate")
//        }
//        XmlPullUtil.skipExit(pp, "itdDateTime")
//        XmlPullUtil.skipExit(pp, "itdTripDateTime")
//
//        XmlPullUtil.requireSkip(pp, "itdTripOptions")
//        XmlPullUtil.optSkipMultiple(pp, "omcTaxi")
//
//        val trips: MutableList<Trip> = java.util.ArrayList<Trip>()
//        val idJoiner: Joiner = Joiner.on('-').skipNulls()
//
//        XmlPullUtil.require(pp, "itdItinerary")
//        if (XmlPullUtil.optEnter(pp, "itdItinerary")) {
//            XmlPullUtil.optSkip(pp, "itdLegTTs")
//
//            if (XmlPullUtil.optEnter(pp, "itdRouteList")) {
//                val calendar: Calendar = GregorianCalendar(timeZone)
//
//                while (XmlPullUtil.test(pp, "itdRoute")) {
//                    val tripId: String?
//                    if (useRouteIndexAsTripId) {
//                        val routeIndex: String = XmlPullUtil.optAttr(pp, "routeIndex", null)
//                        val routeTripIndex: String = XmlPullUtil.optAttr(pp, "routeTripIndex", null)
//                        tripId = Strings.emptyToNull(idJoiner.join(routeIndex, routeTripIndex))
//                    } else {
//                        tripId = null
//                    }
//                    val numChanges: Int = XmlPullUtil.intAttr(pp, "changes")
//                    XmlPullUtil.enter(pp, "itdRoute")
//
//                    XmlPullUtil.optSkipMultiple(pp, "itdDateTime")
//                    XmlPullUtil.optSkip(pp, "itdMapItemList")
//
//                    XmlPullUtil.enter(pp, "itdPartialRouteList")
//                    val legs: MutableList<Leg> = LinkedList<Leg>()
//                    var firstDepartureLocation: Location? = null
//                    var lastArrivalLocation: Location? = null
//
//                    var cancelled = false
//
//                    while (XmlPullUtil.test(pp, "itdPartialRoute")) {
//                        val itdPartialRouteType: String = XmlPullUtil.attr(pp, "type")
//                        val distance: Int = XmlPullUtil.optIntAttr(pp, "distance", 0)
//                        XmlPullUtil.enter(pp, "itdPartialRoute")
//
//                        XmlPullUtil.test(pp, "itdPoint")
//                        if ("departure" != XmlPullUtil.attr(
//                                pp,
//                                "usage"
//                            )
//                        ) throw java.lang.IllegalStateException()
//                        val departureLocation: Location = processItdPointAttributes(pp)
//                        if (firstDepartureLocation == null) firstDepartureLocation =
//                            departureLocation
//                        val departurePosition: Position? =
//                            parsePosition(XmlPullUtil.optAttr(pp, "platformName", null))
//                        XmlPullUtil.enter(pp, "itdPoint")
//                        XmlPullUtil.optSkip(pp, "itdMapItemList")
//                        XmlPullUtil.require(pp, "itdDateTime")
//                        processItdDateTime(pp, calendar)
//                        val departureTime: java.util.Date = calendar.getTime()
//                        val departureTargetTime: java.util.Date?
//                        if (XmlPullUtil.test(pp, "itdDateTimeTarget")) {
//                            processItdDateTime(pp, calendar)
//                            departureTargetTime = calendar.getTime()
//                        } else {
//                            departureTargetTime = null
//                        }
//                        XmlPullUtil.skipExit(pp, "itdPoint")
//
//                        XmlPullUtil.test(pp, "itdPoint")
//                        if ("arrival" != XmlPullUtil.attr(
//                                pp,
//                                "usage"
//                            )
//                        ) throw java.lang.IllegalStateException()
//                        val arrivalLocation: Location = processItdPointAttributes(pp)
//                        lastArrivalLocation = arrivalLocation
//                        val arrivalPosition: Position? =
//                            parsePosition(XmlPullUtil.optAttr(pp, "platformName", null))
//                        XmlPullUtil.enter(pp, "itdPoint")
//                        XmlPullUtil.optSkip(pp, "itdMapItemList")
//                        XmlPullUtil.require(pp, "itdDateTime")
//                        processItdDateTime(pp, calendar)
//                        val arrivalTime: java.util.Date = calendar.getTime()
//                        val arrivalTargetTime: java.util.Date?
//                        if (XmlPullUtil.test(pp, "itdDateTimeTarget")) {
//                            processItdDateTime(pp, calendar)
//                            arrivalTargetTime = calendar.getTime()
//                        } else {
//                            arrivalTargetTime = null
//                        }
//                        XmlPullUtil.skipExit(pp, "itdPoint")
//
//                        XmlPullUtil.test(pp, "itdMeansOfTransport")
//
//                        val itdMeansOfTransportProductName: String =
//                            XmlPullUtil.optAttr(pp, "productName", null)
//                        val itdMeansOfTransportType: Int = XmlPullUtil.intAttr(pp, "type")
//
//                        if (itdMeansOfTransportType <= 16) {
//                            cancelled = cancelled or processPublicLeg(
//                                pp,
//                                legs,
//                                calendar,
//                                departureTime,
//                                departureTargetTime,
//                                departureLocation,
//                                departurePosition,
//                                arrivalTime,
//                                arrivalTargetTime,
//                                arrivalLocation,
//                                arrivalPosition
//                            )
//                        } else if (itdMeansOfTransportType == 97 && "nicht umsteigen" == itdMeansOfTransportProductName) {
//                            // ignore
//                            XmlPullUtil.enter(pp, "itdMeansOfTransport")
//                            XmlPullUtil.skipExit(pp, "itdMeansOfTransport")
//                        } else if (itdMeansOfTransportType == 98 && "gesicherter Anschluss" == itdMeansOfTransportProductName) {
//                            // ignore
//                            XmlPullUtil.enter(pp, "itdMeansOfTransport")
//                            XmlPullUtil.skipExit(pp, "itdMeansOfTransport")
//                        } else if (itdMeansOfTransportType == 99 && "Fussweg" == itdMeansOfTransportProductName) {
//                            processIndividualLeg(
//                                pp, legs, Trip.Individual.Type.WALK, distance, departureTime,
//                                departureLocation, arrivalTime, arrivalLocation
//                            )
//                        } else if (itdMeansOfTransportType == 100 && (itdMeansOfTransportProductName == null || "Fussweg" == itdMeansOfTransportProductName)) {
//                            processIndividualLeg(
//                                pp, legs, Trip.Individual.Type.WALK, distance, departureTime,
//                                departureLocation, arrivalTime, arrivalLocation
//                            )
//                        } else if (itdMeansOfTransportType == 105 && "Taxi" == itdMeansOfTransportProductName) {
//                            processIndividualLeg(
//                                pp, legs, Trip.Individual.Type.CAR, distance, departureTime,
//                                departureLocation, arrivalTime, arrivalLocation
//                            )
//                        } else {
//                            throw java.lang.IllegalStateException(
//                                MoreObjects.toStringHelper("")
//                                    .add("itdPartialRoute.type", itdPartialRouteType)
//                                    .add("itdMeansOfTransport.type", itdMeansOfTransportType)
//                                    .add(
//                                        "itdMeansOfTransport.productName",
//                                        itdMeansOfTransportProductName
//                                    ).toString()
//                            )
//                        }
//
//                        XmlPullUtil.skipExit(pp, "itdPartialRoute")
//                    }
//
//                    XmlPullUtil.skipExit(pp, "itdPartialRouteList")
//
//                    val fares: MutableList<Fare?> = java.util.ArrayList<Fare>(2)
//                    if (XmlPullUtil.optEnter(pp, "itdFare")) {
//                        if (XmlPullUtil.test(pp, "itdSingleTicket")) {
//                            val net: String = XmlPullUtil.optAttr(pp, "net", null)
//                            if (net != null) {
//                                val currencyStr: String =
//                                    Strings.emptyToNull(XmlPullUtil.optAttr(pp, "currency", null))
//                                if (currencyStr != null) {
//                                    val currency: Currency = parseCurrency(currencyStr)
//                                    val fareAdult: String =
//                                        XmlPullUtil.optAttr(pp, "fareAdult", null)
//                                    val fareChild: String =
//                                        XmlPullUtil.optAttr(pp, "fareChild", null)
//                                    val unitName: String = XmlPullUtil.optAttr(pp, "unitName", null)
//                                    val unitsAdult: String =
//                                        XmlPullUtil.optAttr(pp, "unitsAdult", null)
//                                    val unitsChild: String =
//                                        XmlPullUtil.optAttr(pp, "unitsChild", null)
//                                    val levelAdult: String =
//                                        XmlPullUtil.optAttr(pp, "levelAdult", null)
//                                    val levelChild: String =
//                                        XmlPullUtil.optAttr(pp, "levelChild", null)
//                                    if (fareAdult != null) fares.add(
//                                        Fare(
//                                            net.uppercase(Locale.getDefault()),
//                                            Type.ADULT,
//                                            currency,
//                                            fareAdult.toFloat() * fareCorrectionFactor,
//                                            if (levelAdult != null) null else unitName,
//                                            levelAdult ?: unitsAdult
//                                        )
//                                    )
//                                    if (fareChild != null) fares.add(
//                                        Fare(
//                                            net.uppercase(Locale.getDefault()),
//                                            Type.CHILD,
//                                            currency,
//                                            fareChild.toFloat() * fareCorrectionFactor,
//                                            if (levelChild != null) null else unitName,
//                                            levelChild ?: unitsChild
//                                        )
//                                    )
//
//                                    if (XmlPullUtil.optEnter(pp, "itdSingleTicket")) {
//                                        if (XmlPullUtil.optEnter(pp, "itdGenericTicketList")) {
//                                            while (XmlPullUtil.test(pp, "itdGenericTicketGroup")) {
//                                                val fare: Fare? = processItdGenericTicketGroup(
//                                                    pp, net.uppercase(Locale.getDefault()),
//                                                    currency
//                                                )
//                                                if (fare != null) fares.add(fare)
//                                            }
//                                            XmlPullUtil.skipExit(pp, "itdGenericTicketList")
//                                        }
//                                        XmlPullUtil.skipExit(pp, "itdSingleTicket")
//                                    }
//                                }
//                            }
//                        }
//                        XmlPullUtil.skipExit(pp, "itdFare")
//                    }
//
//                    XmlPullUtil.skipExit(pp, "itdRoute")
//
//                    val trip: Trip = Trip(
//                        tripId, firstDepartureLocation, lastArrivalLocation, legs,
//                        if (fares.isEmpty()) null else fares, null, numChanges
//                    )
//
//                    if (!cancelled) trips.add(trip)
//                }
//
//                XmlPullUtil.skipExit(pp, "itdRouteList")
//            }
//            XmlPullUtil.skipExit(pp, "itdItinerary")
//        }
//
//        return QueryTripsResult(
//            header, url.toString(), from, via, to,
//            Context(commandLink(context as String, requestId).toString()), trips
//        )
//    }
//
//    @Throws(XmlPullParserException::class, IOException::class)
//    private fun processIndividualLeg(
//        pp: XmlPullParser,
//        legs: MutableList<Leg>,
//        individualType: Trip.Individual.Type,
//        distance: Int,
//        departureTime: java.util.Date,
//        departureLocation: Location,
//        arrivalTime: java.util.Date,
//        arrivalLocation: Location
//    ) {
//        XmlPullUtil.enter(pp, "itdMeansOfTransport")
//        XmlPullUtil.skipExit(pp, "itdMeansOfTransport")
//
//        XmlPullUtil.optSkip(pp, "itdStopSeq")
//        XmlPullUtil.optSkip(pp, "itdFootPathInfo")
//
//        var path: MutableList<Point?>? = null
//        if (XmlPullUtil.test(pp, "itdPathCoordinates")) path = processItdPathCoordinates(pp)
//
//        val lastLeg: Leg? = if (legs.size > 0) legs[legs.size - 1] else null
//        if ((lastLeg != null && lastLeg is Trip.Individual) && (lastLeg as Trip.Individual).type === individualType) {
//            val lastIndividual: Trip.Individual = legs.removeAt(legs.size - 1) as Trip.Individual
//            if (path != null && lastIndividual.path != null) path.addAll(0, lastIndividual.path)
//            legs.add(
//                Individual(
//                    individualType, lastIndividual.departure, lastIndividual.departureTime,
//                    arrivalLocation, arrivalTime, path, distance
//                )
//            )
//        } else {
//            legs.add(
//                Individual(
//                    individualType, departureLocation, departureTime, arrivalLocation, arrivalTime,
//                    path, distance
//                )
//            )
//        }
//    }
//
//    @Throws(XmlPullParserException::class, IOException::class)
//    private fun processPublicLeg(
//        pp: XmlPullParser,
//        legs: MutableList<Leg>,
//        calendar: Calendar,
//        departureTime: java.util.Date?,
//        departureTargetTime: java.util.Date?,
//        departureLocation: Location,
//        departurePosition: Position?,
//        arrivalTime: java.util.Date?,
//        arrivalTargetTime: java.util.Date?,
//        arrivalLocation: Location,
//        arrivalPosition: Position?
//    ): Boolean {
//        val destinationName = normalizeLocationName(XmlPullUtil.optAttr(pp, "destination", null))
//        val destinationId: String = XmlPullUtil.optAttr(pp, "destID", null)
//        val destination: Location?
//        if (destinationId != null) destination =
//            Location(LocationType.STATION, destinationId, null, destinationName)
//        else if (destinationId == null && destinationName != null) destination =
//            Location(LocationType.ANY, null, null, destinationName)
//        else destination = null
//
//        val motSymbol: String = XmlPullUtil.optAttr(pp, "symbol", null)
//        val motType: String = XmlPullUtil.optAttr(pp, "motType", null)
//        val motShortName: String = XmlPullUtil.optAttr(pp, "shortname", null)
//        val motName: String = XmlPullUtil.attr(pp, "name")
//        val motTrainName: String = XmlPullUtil.optAttr(pp, "trainName", null)
//        val motTrainType: String = XmlPullUtil.optAttr(pp, "trainType", null)
//
//        XmlPullUtil.enter(pp, "itdMeansOfTransport")
//        XmlPullUtil.require(pp, "motDivaParams")
//        val divaNetwork: String = XmlPullUtil.attr(pp, "network")
//        val divaLine: String = XmlPullUtil.attr(pp, "line")
//        val divaSupplement: String = XmlPullUtil.optAttr(pp, "supplement", "")
//        val divaDirection: String = XmlPullUtil.attr(pp, "direction")
//        val divaProject: String = XmlPullUtil.optAttr(pp, "project", "")
//        val lineId =
//            (divaNetwork + ':' + divaLine + ':' + divaSupplement + ':' + divaDirection + ':'
//                    + divaProject)
//        XmlPullUtil.skipExit(pp, "itdMeansOfTransport")
//
//        val line: Line
//        if ("AST" == motSymbol) line = Line(null, divaNetwork, Product.BUS, "AST")
//        else line = parseLine(
//            lineId,
//            divaNetwork,
//            motType,
//            motSymbol,
//            motShortName,
//            motName,
//            motTrainType,
//            motShortName,
//            motTrainName
//        )
//
//        val departureDelay: Int?
//        val arrivalDelay: Int?
//        val cancelled: Boolean
//        if (XmlPullUtil.test(pp, "itdRBLControlled")) {
//            departureDelay = XmlPullUtil.optIntAttr(pp, "delayMinutes", 0)
//            arrivalDelay = XmlPullUtil.optIntAttr(pp, "delayMinutesArr", 0)
//            cancelled = departureDelay == -9999 || arrivalDelay == -9999
//
//            XmlPullUtil.next(pp)
//        } else {
//            departureDelay = null
//            arrivalDelay = null
//            cancelled = false
//        }
//
//        var lowFloorVehicle = false
//        var message: String? = null
//        if (XmlPullUtil.optEnter(pp, "itdInfoTextList")) {
//            while (XmlPullUtil.test(pp, "infoTextListElem")) {
//                val text: String = XmlPullUtil.valueTag(pp, "infoTextListElem")
//                if (text != null) {
//                    val lcText = text.lowercase(Locale.getDefault())
//                    if (lcText.startsWith("niederflurwagen")) // KVV
//                        lowFloorVehicle = true
//                    else if (lcText.contains("ruf") || lcText.contains("anmeld")) // Bedarfsverkehr
//                        message = text
//                }
//            }
//            XmlPullUtil.skipExit(pp, "itdInfoTextList")
//        }
//
//        XmlPullUtil.optSkip(pp, "itdFootPathInfo")
//
//        while (XmlPullUtil.optEnter(pp, "infoLink")) {
//            XmlPullUtil.optSkip(pp, "paramList")
//            val infoLinkText: String = XmlPullUtil.valueTag(pp, "infoLinkText")
//            if (message == null) message = infoLinkText
//            XmlPullUtil.skipExit(pp, "infoLink")
//        }
//
//        var intermediateStops: MutableList<Stop>? = null
//        if (XmlPullUtil.optEnter(pp, "itdStopSeq")) {
//            intermediateStops = LinkedList<Stop>()
//            while (XmlPullUtil.test(pp, "itdPoint")) {
//                val stopLocation: Location = processItdPointAttributes(pp)
//
//                val stopPosition: Position? =
//                    parsePosition(XmlPullUtil.optAttr(pp, "platformName", null))
//
//                XmlPullUtil.enter(pp, "itdPoint")
//                XmlPullUtil.optSkip(pp, "genAttrList")
//                XmlPullUtil.optSkip(pp, "sPAs")
//                XmlPullUtil.require(pp, "itdDateTime")
//
//                val plannedStopArrivalTime: java.util.Date?
//                val predictedStopArrivalTime: java.util.Date?
//                if (processItdDateTime(pp, calendar)) {
//                    plannedStopArrivalTime = calendar.getTime()
//                    if (arrivalDelay != null) {
//                        calendar.add(Calendar.MINUTE, arrivalDelay)
//                        predictedStopArrivalTime = calendar.getTime()
//                    } else {
//                        predictedStopArrivalTime = null
//                    }
//                } else {
//                    plannedStopArrivalTime = null
//                    predictedStopArrivalTime = null
//                }
//
//                val plannedStopDepartureTime: java.util.Date?
//                val predictedStopDepartureTime: java.util.Date?
//                if (XmlPullUtil.test(pp, "itdDateTime") && processItdDateTime(pp, calendar)) {
//                    plannedStopDepartureTime = calendar.getTime()
//                    if (departureDelay != null) {
//                        calendar.add(Calendar.MINUTE, departureDelay)
//                        predictedStopDepartureTime = calendar.getTime()
//                    } else {
//                        predictedStopDepartureTime = null
//                    }
//                } else {
//                    plannedStopDepartureTime = null
//                    predictedStopDepartureTime = null
//                }
//
//                val stop: Stop = Stop(
//                    stopLocation, plannedStopArrivalTime, predictedStopArrivalTime, stopPosition,
//                    null, plannedStopDepartureTime, predictedStopDepartureTime, stopPosition, null
//                )
//
//                intermediateStops!!.add(stop)
//
//                XmlPullUtil.skipExit(pp, "itdPoint")
//            }
//            XmlPullUtil.skipExit(pp, "itdStopSeq")
//
//            // remove first and last, because they are not intermediate
//            val size = intermediateStops!!.size
//            if (size >= 2) {
//                val lastLocation: Location = intermediateStops[size - 1].location
//                if (!lastLocation.equals(arrivalLocation)) throw java.lang.IllegalStateException(
//                    lastLocation.toString() + " vs " + arrivalLocation
//                )
//                intermediateStops.removeAt(size - 1)
//
//                val firstLocation: Location = intermediateStops[0].location
//                if (!firstLocation.equals(departureLocation)) throw java.lang.IllegalStateException(
//                    firstLocation.toString() + " vs " + departureLocation
//                )
//                intermediateStops.removeAt(0)
//            }
//        }
//
//        var path: List<Point?>? = null
//        if (XmlPullUtil.test(pp, "itdPathCoordinates")) path = processItdPathCoordinates(pp)
//
//        XmlPullUtil.optSkip(pp, "itdITPathDescription")
//        XmlPullUtil.optSkip(pp, "itdInterchangePathCoordinates")
//
//        var wheelChairAccess = false
//        if (XmlPullUtil.optEnter(pp, "genAttrList")) {
//            while (XmlPullUtil.optEnter(pp, "genAttrElem")) {
//                val name: String = XmlPullUtil.valueTag(pp, "name")
//                val value: String = XmlPullUtil.valueTag(pp, "value")
//                XmlPullUtil.skipExit(pp, "genAttrElem")
//
//                // System.out.println("genAttrElem: name='" + name + "' value='" + value + "'");
//                if ("PlanWheelChairAccess" == name && "1" == value) wheelChairAccess = true
//            }
//            XmlPullUtil.skipExit(pp, "genAttrList")
//        }
//
//        if (XmlPullUtil.optEnter(pp, "nextDeps")) {
//            while (XmlPullUtil.test(pp, "itdDateTime")) {
//                processItdDateTime(pp, calendar)
//                /* final Date nextDepartureTime = */
//                calendar.getTime()
//            }
//            XmlPullUtil.skipExit(pp, "nextDeps")
//        }
//
//        val lineAttrs: MutableSet<Line.Attr> = java.util.HashSet<Line.Attr>()
//        if (wheelChairAccess || lowFloorVehicle) lineAttrs.add(Line.Attr.WHEEL_CHAIR_ACCESS)
//        val styledLine: Line = Line(
//            line.id, line.network, line.product, line.label,
//            lineStyle(line.network, line.product, line.label), lineAttrs
//        )
//
//        val departure: Stop = Stop(
//            departureLocation, true,
//            if (departureTargetTime != null) departureTargetTime else departureTime,
//            if (departureTime != null) departureTime else null, departurePosition, null
//        )
//        val arrival: Stop = Stop(
//            arrivalLocation,
//            false,
//            if (arrivalTargetTime != null) arrivalTargetTime else arrivalTime,
//            if (arrivalTime != null) arrivalTime else null,
//            arrivalPosition,
//            null
//        )
//
//        legs.add(
//            Public(
//                styledLine,
//                destination,
//                departure,
//                arrival,
//                intermediateStops,
//                path,
//                message
//            )
//        )
//
//        return cancelled
//    }
//
//    @Throws(XmlPullParserException::class, IOException::class)
//    private fun queryTripsMobile(
//        url: HttpUrl, from: Location?, @Nullable via: Location?,
//        to: Location?, reader: java.io.Reader
//    ): QueryTripsResult {
//        val pp: XmlPullParser = parserFactory.newPullParser()
//        pp.setInput(reader)
//        val header: ResultHeader = enterEfa(pp)
//        XmlPullUtil.optSkip(pp, "msgs")
//
//        val plannedTimeCal: Calendar = GregorianCalendar(timeZone)
//        val predictedTimeCal: Calendar = GregorianCalendar(timeZone)
//
//        val trips: MutableList<Trip> = java.util.ArrayList<Trip>()
//
//        if (XmlPullUtil.optEnter(pp, "ts")) {
//            while (XmlPullUtil.optEnter(pp, "tp")) {
//                XmlPullUtil.optSkip(pp, "attrs")
//
//                XmlPullUtil.valueTag(pp, "d") // duration
//                val numChanges: Int = XmlPullUtil.valueTag(pp, "ic").toInt()
//                XmlPullUtil.valueTag(pp, "de")
//                XmlPullUtil.optValueTag(pp, "optval", null)
//                XmlPullUtil.optValueTag(pp, "alt", null)
//                val tripId: String = XmlPullUtil.optValueTag(pp, "gix", null)
//
//                XmlPullUtil.enter(pp, "ls")
//
//                val legs: MutableList<Leg> = LinkedList<Leg>()
//                var firstDepartureLocation: Location? = null
//                var lastArrivalLocation: Location? = null
//
//                while (XmlPullUtil.test(pp, "l")) {
//                    XmlPullUtil.enter(pp, "l")
//                    XmlPullUtil.optSkip(pp, "rtStatus")
//
//                    XmlPullUtil.enter(pp, "ps")
//
//                    var departure: Stop? = null
//                    var arrival: Stop? = null
//
//                    while (XmlPullUtil.optEnter(pp, "p")) {
//                        val name: String = XmlPullUtil.valueTag(pp, "n")
//                        val usage: String = XmlPullUtil.valueTag(pp, "u")
//                        XmlPullUtil.optValueTag(pp, "de", null)
//                        XmlPullUtil.optValueTag(pp, "gid", null)
//                        XmlPullUtil.optValueTag(pp, "pgid", null)
//                        XmlPullUtil.optValueTag(pp, "rtStatus", null)
//                        XmlPullUtil.requireSkip(pp, "dt")
//
//                        parseMobileSt(pp, plannedTimeCal, predictedTimeCal)
//
//                        XmlPullUtil.optSkip(pp, "lis") // links
//
//                        XmlPullUtil.enter(pp, "r")
//                        val id: String = XmlPullUtil.valueTag(pp, "id")
//                        XmlPullUtil.optValueTag(pp, "a", null)
//                        val position: Position? =
//                            parsePosition(XmlPullUtil.optValueTag(pp, "pl", null))
//                        val place = normalizeLocationName(XmlPullUtil.optValueTag(pp, "pc", null))
//                        val coord: Point? = parseCoord(XmlPullUtil.optValueTag(pp, "c", null))
//                        XmlPullUtil.skipExit(pp, "r")
//
//                        val location: Location
//                        if (id == "99999997" || id == "99999998") location =
//                            Location(LocationType.ADDRESS, null, coord, place, name)
//                        else location = Location(LocationType.STATION, id, coord, place, name)
//
//                        XmlPullUtil.skipExit(pp, "p")
//
//                        val plannedTime: java.util.Date? =
//                            if (plannedTimeCal.isSet(Calendar.HOUR_OF_DAY)) plannedTimeCal.getTime()
//                            else null
//                        val predictedTime: java.util.Date? =
//                            if (predictedTimeCal.isSet(Calendar.HOUR_OF_DAY)
//                            ) predictedTimeCal.getTime() else null
//
//                        if ("departure" == usage) {
//                            departure =
//                                Stop(location, true, plannedTime, predictedTime, position, null)
//                            if (firstDepartureLocation == null) firstDepartureLocation = location
//                        } else if ("arrival" == usage) {
//                            arrival =
//                                Stop(location, false, plannedTime, predictedTime, position, null)
//                            lastArrivalLocation = location
//                        } else {
//                            throw java.lang.IllegalStateException("unknown usage: $usage")
//                        }
//                    }
//
//                    checkState(departure != null)
//                    checkState(arrival != null)
//
//                    XmlPullUtil.skipExit(pp, "ps")
//
//                    val isRealtime: Boolean = XmlPullUtil.valueTag(pp, "realtime").equals("1")
//
//                    val lineDestination: LineDestination = parseMobileM(pp, false)
//                    val path: List<Point?>? =
//                        if (XmlPullUtil.test(pp, "pt")) processCoordinateStrings(pp, "pt")
//                        else null
//
//                    val intermediateStops: MutableList<Stop>?
//                    XmlPullUtil.require(pp, "pss")
//                    if (XmlPullUtil.optEnter(pp, "pss")) {
//                        intermediateStops = LinkedList<Stop>()
//
//                        while (XmlPullUtil.test(pp, "s")) {
//                            plannedTimeCal.clear()
//                            predictedTimeCal.clear()
//
//                            val s: String = XmlPullUtil.valueTag(pp, "s")
//                            val intermediateParts =
//                                s.split(";".toRegex()).dropLastWhile { it.isEmpty() }
//                                    .toTypedArray()
//                            val id = intermediateParts[0]
//                            if (id != departure.location.id && id != arrival.location.id) {
//                                val name = normalizeLocationName(intermediateParts[1])
//
//                                if (!(intermediateParts[2].startsWith("000") && intermediateParts[3].startsWith(
//                                        "000"
//                                    ))
//                                ) {
//                                    ParserUtils.parseIsoDate(plannedTimeCal, intermediateParts[2])
//                                    ParserUtils.parseIsoTime(plannedTimeCal, intermediateParts[3])
//
//                                    if (isRealtime) {
//                                        ParserUtils.parseIsoDate(
//                                            predictedTimeCal,
//                                            intermediateParts[2]
//                                        )
//                                        ParserUtils.parseIsoTime(
//                                            predictedTimeCal,
//                                            intermediateParts[3]
//                                        )
//
//                                        if (intermediateParts.size > 5 && intermediateParts[5].length > 0) {
//                                            val delay = intermediateParts[5].toInt()
//                                            predictedTimeCal.add(Calendar.MINUTE, delay)
//                                        }
//                                    }
//                                }
//                                val coordPart = intermediateParts[4]
//
//                                val coords: Point?
//                                if ("::" != coordPart) {
//                                    val coordParts = coordPart.split(":".toRegex())
//                                        .dropLastWhile { it.isEmpty() }
//                                        .toTypedArray()
//                                    val mapName = coordParts[2]
//                                    if (COORD_FORMAT == mapName) {
//                                        if (coordParts.size < 2) throw java.lang.RuntimeException("cannot parse coordinate: $coordPart")
//                                        val lat = coordParts[1].toDouble()
//                                        val lon = coordParts[0].toDouble()
//                                        coords = Point.fromDouble(lat, lon)
//                                    } else {
//                                        coords = null
//                                    }
//                                } else {
//                                    coords = null
//                                }
//                                val location: Location =
//                                    Location(LocationType.STATION, id, coords, null, name)
//
//                                val plannedTime: java.util.Date? =
//                                    if (plannedTimeCal.isSet(Calendar.HOUR_OF_DAY)
//                                    ) plannedTimeCal.getTime() else null
//                                val predictedTime: java.util.Date? =
//                                    if (predictedTimeCal.isSet(Calendar.HOUR_OF_DAY)
//                                    ) predictedTimeCal.getTime() else null
//                                val stop: Stop =
//                                    Stop(location, false, plannedTime, predictedTime, null, null)
//
//                                intermediateStops!!.add(stop)
//                            }
//                        }
//
//                        XmlPullUtil.skipExit(pp, "pss")
//                    } else {
//                        intermediateStops = null
//                    }
//
//                    XmlPullUtil.optSkip(pp, "interchange")
//
//                    XmlPullUtil.requireSkip(pp, "ns")
//
//                    // TODO messages
//                    XmlPullUtil.skipExit(pp, "l")
//
//                    if (lineDestination.line === Line.FOOTWAY) {
//                        legs.add(
//                            Individual(
//                                Trip.Individual.Type.WALK,
//                                departure.location,
//                                departure.getDepartureTime(),
//                                arrival.location,
//                                arrival.getArrivalTime(),
//                                path,
//                                0
//                            )
//                        )
//                    } else if (lineDestination.line === Line.TRANSFER) {
//                        legs.add(
//                            Individual(
//                                Trip.Individual.Type.TRANSFER,
//                                departure.location,
//                                departure.getDepartureTime(),
//                                arrival.location,
//                                arrival.getArrivalTime(),
//                                path,
//                                0
//                            )
//                        )
//                    } else if (lineDestination.line === Line.SECURE_CONNECTION
//                        || lineDestination.line === Line.DO_NOT_CHANGE
//                    ) {
//                        // ignore
//                    } else {
//                        legs.add(
//                            Public(
//                                lineDestination.line,
//                                lineDestination.destination,
//                                departure,
//                                arrival,
//                                intermediateStops,
//                                path,
//                                null
//                            )
//                        )
//                    }
//                }
//
//                XmlPullUtil.skipExit(pp, "ls")
//
//                XmlPullUtil.optSkip(pp, "seqroutes")
//
//                val fares: List<Fare>?
//                if (XmlPullUtil.optEnter(pp, "tcs")) {
//                    fares = java.util.ArrayList<Fare>(2)
//                    XmlPullUtil.optSkipMultiple(pp, "tc") // TODO fares
//                    XmlPullUtil.skipExit(pp, "tcs")
//                } else {
//                    fares = null
//                }
//
//                val trip: Trip = Trip(
//                    tripId, firstDepartureLocation, lastArrivalLocation, legs, fares, null,
//                    numChanges
//                )
//                trips.add(trip)
//
//                XmlPullUtil.skipExit(pp, "tp")
//            }
//
//            XmlPullUtil.skipExit(pp, "ts")
//        }
//
//        if (trips.size > 0) {
//            val context = header.context as Array<String>
//            return QueryTripsResult(
//                header, url.toString(), from, via, to,
//                Context(commandLink(context[0], context[1]).toString()), trips
//            )
//        } else {
//            return QueryTripsResult(header, QueryTripsResult.Status.NO_TRIPS)
//        }
//    }
//
//    @Throws(XmlPullParserException::class, IOException::class)
//    private fun processItdPathCoordinates(pp: XmlPullParser): MutableList<Point?>? {
//        XmlPullUtil.enter(pp, "itdPathCoordinates")
//        val path: MutableList<Point?>?
//
//        val ellipsoid: String = XmlPullUtil.valueTag(pp, "coordEllipsoid")
//        if ("WGS84" == ellipsoid) {
//            val type: String = XmlPullUtil.valueTag(pp, "coordType")
//            if ("GEO_DECIMAL" != type) throw java.lang.IllegalStateException("unknown type: $type")
//            path = if (XmlPullUtil.test(pp, "itdCoordinateString")) {
//                processCoordinateStrings(pp, "itdCoordinateString")
//            } else if (XmlPullUtil.test(pp, "itdCoordinateBaseElemList")) {
//                processCoordinateBaseElems(pp)
//            } else {
//                throw java.lang.IllegalStateException(pp.getPositionDescription())
//            }
//        } else {
//            path = null
//        }
//
//        XmlPullUtil.skipExit(pp, "itdPathCoordinates")
//        return path
//    }
//
//    @Nullable
//    @Throws(XmlPullParserException::class, IOException::class)
//    private fun processCoordinateStrings(pp: XmlPullParser, tag: String): MutableList<Point?>? {
//        val path: MutableList<Point?> = LinkedList<Point>()
//
//        val value: String = XmlPullUtil.optValueTag(pp, tag, null)
//        if (value != null) {
//            for (coordStr in value.split(" +".toRegex()).dropLastWhile { it.isEmpty() }
//                .toTypedArray()) path.add(parseCoord(coordStr))
//            return path
//        } else {
//            return null
//        }
//    }
//
//    @Throws(XmlPullParserException::class, IOException::class)
//    private fun processCoordinateBaseElems(pp: XmlPullParser): MutableList<Point?> {
//        val path: MutableList<Point?> = LinkedList<Point>()
//
//        XmlPullUtil.enter(pp, "itdCoordinateBaseElemList")
//
//        while (XmlPullUtil.optEnter(pp, "itdCoordinateBaseElem")) {
//            val x: Double = XmlPullUtil.valueTag(pp, "x").toDouble()
//            val y: Double = XmlPullUtil.valueTag(pp, "y").toDouble()
//            path.add(Point.fromDouble(y, x))
//
//            XmlPullUtil.skipExit(pp, "itdCoordinateBaseElem")
//        }
//
//        XmlPullUtil.skipExit(pp, "itdCoordinateBaseElemList")
//
//        return path
//    }
//
//    private fun parseCoord(coordStr: String?): Point? {
//        if (coordStr == null) return null
//
//        val parts = coordStr.split(",".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
//        if (parts.size < 2) throw java.lang.RuntimeException("cannot parse coordinate: $coordStr")
//        val lat = parts[1].toDouble()
//        val lon = parts[0].toDouble()
//        return Point.fromDouble(lat, lon)
//    }
//
//    private fun processCoordAttr(pp: XmlPullParser): Point? {
//        val mapName: String = XmlPullUtil.optAttr(pp, "mapName", null)
//        val x: Double = XmlPullUtil.optFloatAttr(pp, "x", 0)
//        val y: Double = XmlPullUtil.optFloatAttr(pp, "y", 0)
//
//        if (mapName == null || (x == 0.0 && y == 0.0)) return null
//
//        if (COORD_FORMAT != mapName) return null
//
//        return Point.fromDouble(y, x)
//    }
//
//    private fun majorMeansToProduct(majorMeans: Int): Product? {
//        when (majorMeans) {
//            1 -> return Product.SUBWAY
//            2 -> return Product.SUBURBAN_TRAIN
//            3 -> return Product.BUS
//            4 -> return Product.TRAM
//            else -> {
//                log.info("unknown STOP_MAJOR_MEANS value: {}", majorMeans)
//                return null
//            }
//        }
//    }
//
//    @Throws(XmlPullParserException::class, IOException::class)
//    private fun processItdGenericTicketGroup(
//        pp: XmlPullParser,
//        net: String,
//        currency: Currency
//    ): Fare? {
//        XmlPullUtil.enter(pp, "itdGenericTicketGroup")
//
//        var type: Type? = null
//        var fare = 0f
//
//        while (XmlPullUtil.optEnter(pp, "itdGenericTicket")) {
//            val key: String = XmlPullUtil.valueTag(pp, "ticket")
//            val value: String = XmlPullUtil.valueTag(pp, "value")
//
//            if (key == "FOR_RIDER") {
//                val typeStr = value.split(" ".toRegex()).dropLastWhile { it.isEmpty() }
//                    .toTypedArray()[0].uppercase(Locale.getDefault())
//                type = if (typeStr == "REGULAR") Type.ADULT
//                else Type.valueOf(typeStr)
//            } else if (key == "PRICE") {
//                fare = value.toFloat() * fareCorrectionFactor
//            }
//
//            XmlPullUtil.skipExit(pp, "itdGenericTicket")
//        }
//
//        XmlPullUtil.skipExit(pp, "itdGenericTicketGroup")
//
//        return if (type != null) Fare(net, type, currency, fare, null, null)
//        else null
//    }
//
//    private fun parseCurrency(currencyStr: String): Currency {
//        if (currencyStr == "US$") return Currency.getInstance("USD")
//        if (currencyStr == "Dirham") return Currency.getInstance("AED")
//        return ParserUtils.getCurrency(currencyStr)
//    }
//
//    override fun parsePosition(position: String?): Position? {
//        if (position == null) return null
//
//        if (position.startsWith("Ri.") || position.startsWith("Richtung ")) return null
//
//        val m: java.util.regex.Matcher = P_POSITION.matcher(position)
//        if (m.matches()) return super.parsePosition(m.group(1))
//
//        return super.parsePosition(position)
//    }
//
    private fun appendLocationParams(
        url: URLBuilder,
        location: Location,
        paramSuffix: String
    ) {
        if (location.type === Location.Type.STATION && location.hasId) {
            url.parameters.append("type_$paramSuffix", "stop")
            url.parameters.append(
                "name_$paramSuffix",
                UrlEncoderUtil.encode(normalizeStationId(location.id) ?: "")
            )
        } else if (location.type === Location.Type.POI && location.hasId) {
            url.parameters.append("type_$paramSuffix", "poi")
            url.parameters.append(
                "name_$paramSuffix",
                UrlEncoderUtil.encode(location.id ?: "")
            )
        } else if (location.type === Location.Type.ADDRESS && location.hasId) {
            url.parameters.append("type_$paramSuffix", "address")
            url.parameters.append(
                "name_$paramSuffix",
                UrlEncoderUtil.encode(location.id ?: "")
            )
        } else if ((location.type === Location.Type.ADDRESS || location.type === Location.Type.COORD)
            && location.hasCoords
        ) {
            url.parameters.append("type_$paramSuffix", "coord")
            url.parameters.append(
                "name_$paramSuffix",
                UrlEncoderUtil.encode(
                    location.coord?.let {
                        it.lat.formatTo7DecimalPlaces() +
                                ":${it.lon.formatTo7DecimalPlaces()}" +
                                ":${COORD_FORMAT}"
                    } ?: ""
                )
            )
        } else if (location.name != null) {
            url.parameters.append("type_$paramSuffix", "any")
            url.parameters.append(
                "name_$paramSuffix",
                UrlEncoderUtil.encode(location.name)
            )
        } else {
            throw IllegalArgumentException("cannot append location: $location")
        }
    }

//    init {
//        try {
//            parserFactory = XmlPullParserFactory.newInstance(
//                java.lang.System.getProperty(XmlPullParserFactory.PROPERTY_NAME),
//                null
//            )
//        } catch (x: XmlPullParserException) {
//            throw java.lang.RuntimeException(x)
//        }
//    }
//
//    @Throws(XmlPullParserException::class, IOException::class)
//    private fun enterItdRequest(pp: XmlPullParser): ResultHeader {
//        if (pp.getEventType() !== XmlPullParser.START_DOCUMENT) throw ParserException("start of document expected")
//
//        pp.next()
//
//        if (pp.getEventType() === XmlPullParser.DOCDECL) pp.next()
//
//        if (pp.getEventType() === XmlPullParser.END_DOCUMENT) throw ParserException("empty document")
//
//        XmlPullUtil.require(pp, "itdRequest")
//
//        val serverVersion: String = XmlPullUtil.attr(pp, "version")
//        val now: String = XmlPullUtil.optAttr(pp, "now", null)
//        val sessionId: String = XmlPullUtil.attr(pp, "sessionID")
//        val serverId: String = XmlPullUtil.attr(pp, "serverID")
//
//        val serverTime: Long
//        if (now != null) {
//            val calendar: Calendar = GregorianCalendar(timeZone)
//            ParserUtils.parseIsoDate(calendar, now.substring(0, 10))
//            ParserUtils.parseEuropeanTime(calendar, now.substring(11))
//            serverTime = calendar.getTimeInMillis()
//        } else {
//            serverTime = 0
//        }
//
//        val header: ResultHeader = ResultHeader(
//            network, SERVER_PRODUCT, serverVersion, serverId, serverTime,
//            sessionId
//        )
//
//        XmlPullUtil.enter(pp, "itdRequest")
//
//        XmlPullUtil.optSkip(pp, "itdMessageList")
//        XmlPullUtil.optSkip(pp, "clientHeaderLines")
//        XmlPullUtil.optSkip(pp, "itdVersionInfo")
//        XmlPullUtil.optSkip(pp, "itdLayoutParams")
//        XmlPullUtil.optSkip(pp, "itdInfoLinkList")
//        XmlPullUtil.optSkip(pp, "serverMetaInfo")
//
//        return header
//    }
//
//    @Throws(XmlPullParserException::class, IOException::class)
//    private fun enterEfa(pp: XmlPullParser): ResultHeader {
//        if (pp.getEventType() !== XmlPullParser.START_DOCUMENT) throw ParserException("start of document expected")
//
//        pp.next()
//
//        if (pp.getEventType() === XmlPullParser.END_DOCUMENT) throw ParserException("empty document")
//
//        XmlPullUtil.enter(pp, "efa")
//
//        if (XmlPullUtil.test(pp, "error")) {
//            val message: String = XmlPullUtil.valueTag(pp, "error")
//            throw java.lang.RuntimeException(message)
//        } else {
//            val now: String = XmlPullUtil.valueTag(pp, "now")
//            val serverTime: Calendar = GregorianCalendar(timeZone)
//            ParserUtils.parseIsoDate(serverTime, now.substring(0, 10))
//            ParserUtils.parseEuropeanTime(serverTime, now.substring(11))
//
//            val params = processPas(pp)
//            val requestId = params["requestID"]
//            val sessionId = params["sessionID"]
//            val serverId = params["serverID"]
//
//            val header: ResultHeader = ResultHeader(
//                network, SERVER_PRODUCT, null, serverId,
//                serverTime.getTimeInMillis(), arrayOf<String?>(sessionId, requestId)
//            )
//
//            return header
//        }
//    }
//
//    @Throws(XmlPullParserException::class, IOException::class)
//    private fun processPas(pp: XmlPullParser): Map<String, String> {
//        val params: MutableMap<String, String> = java.util.HashMap<String, String>()
//
//        XmlPullUtil.enter(pp, "pas")
//
//        while (XmlPullUtil.optEnter(pp, "pa")) {
//            val name: String = XmlPullUtil.valueTag(pp, "n")
//            val value: String = XmlPullUtil.valueTag(pp, "v")
//            params[name] = value
//            XmlPullUtil.skipExit(pp, "pa")
//        }
//
//        XmlPullUtil.skipExit(pp, "pas")
//
//        return params
//    }

    companion object {
        protected const val DEFAULT_DEPARTURE_MONITOR_ENDPOINT: String = "XSLT_DM_REQUEST"
        protected const val DEFAULT_TRIP_ENDPOINT: String = "XSLT_TRIP_REQUEST2"
        protected const val DEFAULT_STOPFINDER_ENDPOINT: String = "XML_STOPFINDER_REQUEST"
        protected const val DEFAULT_COORD_ENDPOINT: String = "XML_COORD_REQUEST"

        protected const val SERVER_PRODUCT: String = "efa"
        protected const val COORD_FORMAT: String = "WGS84[DD.ddddd]"
        protected const val COORD_FORMAT_TAIL: Int = 7

//        private val log: Logger = LoggerFactory.getLogger(AbstractEfaProvider::class.java)
        private val log: AbstractLogger = PrintlnLogger()

        private val P_LINE_RE = Regex("RE ?\\d+[ab]?")
        private val P_LINE_RB = Regex("RB ?\\d+[abc]?")
        private val P_LINE_R = Regex("R ?\\d+")
        private val P_LINE_IRE = Regex("IRE\\d+[ab]?")
        private val P_LINE_MEX = Regex("ME?X ?\\d+[abc]?")
        private val P_LINE_S = Regex("S ?\\d+")
        private val P_LINE_S_DB = Regex("(S\\d+) \\((?:DB Regio AG)\\)")
        private val P_LINE_NUMBER = Regex("\\d+")

        private val P_MOBILE_M_SYMBOL = Regex("([^\\s]*)\\s+([^\\s]*)")

        val P_STATION_NAME_WHITESPACE = Regex("\\s+")

        private val P_POSITION = Regex(
            "(?:Gleis|Gl\\.|Bahnsteig|Bstg\\.|Bussteig|Busstg\\.|Steig|Hp\\.|Stop|Pos\\.|Zone|Platform|Stand|Bay|Stance)?\\s*(.+)",
            RegexOption.IGNORE_CASE
        )

        private val WALKSPEED_MAP: Map<WalkSpeed, String> = mapOf(
            WalkSpeed.SLOW to "slow",
            WalkSpeed.NORMAL to "normal",
            WalkSpeed.FAST to "fast"
        )

        val defaultXml = XML {
            defaultPolicy {
                ignoreUnknownChildren()
            }
        }
    }

    private fun ItdRequest.parseHeader(): ResultHeader {
        return ResultHeader(
            network = network,
            serverProduct = SERVER_PRODUCT,
            serverVersion = this.version,
            serverName = this.serverID,
            serverTime = LocalDateTime.parse(this.now).toInstant(timezone).toEpochMilliseconds(),
            context = this.sessionID
        )
    }

    private fun Pas?.parseHeader(now: String): ResultHeader {
        val now = LocalDateTime.parse(now)

        val requestId = this?.pa?.find { it.n == "requestID" }?.v ?: "unknown"
        val sessionId = this?.pa?.find { it.n == "sessionID" }?.v ?: "unknown"
        val serverId = this?.pa?.find { it.n == "serverID" }?.v ?: "unknown"

        return ResultHeader(
            network = network,
            serverProduct = SERVER_PRODUCT,
            serverVersion = null,
            serverName = serverId,
            serverTime = now.toInstant(timezone).toEpochMilliseconds(),
            context = arrayOf(sessionId, requestId)
        )
    }

    private fun ItdOdv.ItdOdvName.OdvNameElem.toLocation(
        type: String,
        defaultPlace: String
    ): Location? {
        var lType = type

        if(type == "any") lType = this.anyType ?: type

        val coord = parseCoord(mapName, x, y)

        when(lType) {
            "stop" -> {
                require(id == null || stateless?.startsWith(id) == true) { "id mismatch: '${stateless}' vs '$id'" }
                return Location(
                    id = id ?: stateless,
                    type = Location.Type.STATION,
                    coord = coord,
                    place = locality ?: defaultPlace,
                    name = objectName ?: name
                )
            }

            "poi" -> {
                return Location(
                    id = stateless,
                    type = Location.Type.POI,
                    coord = coord,
                    place = locality ?: defaultPlace,
                    name = objectName ?: name
                )
            }

            "loc" -> {
                return if(locality != null)
                    Location(
                        id = stateless,
                        type = Location.Type.ADDRESS,
                        coord = coord,
                        place = null,
                        name = locality
                    )
                else if(name != null)
                    Location(
                        id = stateless,
                        type = Location.Type.ADDRESS,
                        coord = coord,
                        place = null,
                        name = name
                    )
                else if(coord != null)
                    Location(
                        id = stateless,
                        type = Location.Type.COORD,
                        coord = coord,
                        place = null,
                        name = null
                    )
                else throw IllegalArgumentException("not enough data for type/anyType: $lType")
            }

            "address", "singlehouse" -> {
                return Location(
                    id = stateless,
                    type = Location.Type.ADDRESS,
                    coord = coord,
                    place = locality ?: defaultPlace,
                    name = objectName + (if(buildingNumber != null) " $buildingNumber" else "")
                )
            }

            "street", "crossing" -> {
                return Location(
                    id = stateless,
                    type = Location.Type.ADDRESS,
                    coord = coord,
                    place = locality ?: defaultPlace,
                    name = objectName ?: name
                )
            }

            "postcode" -> {
                return Location(
                    id = stateless,
                    type = Location.Type.ADDRESS,
                    coord = coord,
                    place = locality ?: defaultPlace,
                    name = postCode
                )
            }

            "buildingname" -> {
                return Location(
                    id = stateless,
                    type = Location.Type.ADDRESS,
                    coord = coord,
                    place = locality ?: defaultPlace,
                    name = buildingName ?: streetName
                )
            }

            "coord" -> {
                return Location(
                    id = stateless,
                    type = Location.Type.ADDRESS,
                    coord = coord,
                    place = defaultPlace,
                    name = name
                )
            }

            "unknown" -> return null

            else -> throw IllegalArgumentException("unknown type/anyType: $lType")
        }

    }

    fun parseCoord(mapName: String?, x: String?, y: String?): Point? {
        if(mapName == null || mapName != COORD_FORMAT) return null
        if(x.isNullOrEmpty() || y.isNullOrEmpty()) return null
        return Point.fromDouble(y.toDouble(), x.toDouble())
    }

    private fun AbstractEfaProvider.ItdPathCoordinates.toPointList(): List<Point>? {
        if(this.coordEllipsoid == "WGS84") {
            if(this.coordType != "GEO_DECIMAL") throw IllegalStateException("unknown coord type: ${this.coordType}")
            if(this.itdCoordinateString != null) {
                return this.itdCoordinateString
                    .split(" +".toRegex())
                    .dropLastWhile { it.isEmpty() }
                    .mapNotNull { it.parseCoord() }
            } else if(this.itdCoordinateBaseElemList != null) {
                return this.itdCoordinateBaseElemList.itdCoordinateBaseElem.map {
                    Point.fromDouble(it.x.toDouble(), it.y.toDouble())
                }
            } else {
                println("Failed to parse coordinates to point list: ${this}")
                return null
            }
        }

        return null
    }


    private fun ItdOdv.ItdOdvAssignedStops.ItdOdvAssignedStop.toLocation() : Location {
            return Location(
                id = stopID,
                type = Location.Type.STATION,
                coord = parseCoord(mapName, x, y),
                place = place.normalize().takeIf { name.normalize() != null },
                name = name.normalize()
            )
    }

    private fun ItdDepartureMonitorResult.ItdDepartureMonitorRequest.ItdServingLines.ItdServingLine.lineAsLine(): Line {
        val message = itdNoTrain?.takeIf { txt ->
            (name != null && name?.lowercase()?.contains("ruf") == true)
                    || (txt.lowercase().contains("ruf"))
        }

        val trainType = trainType?.takeIf { it.isNotEmpty() } ?: type
        val trainName = trainName?.takeIf { it.isNotEmpty() } ?: name
        val slLine: Line = parseLine(
            _id = stateless,
            network = motDivaParams.network,
            mot = motType,
            symbol = symbol,
            name = number,
            longName = number,
            trainType = trainType,
            trainNum = trainNum,
            trainName = trainName
        )

        return Line(
            id = slLine.id,
            network = slLine.network,
            product = slLine.product,
            label = slLine.label,
            style = lineStyle(slLine.network, slLine.product, slLine.label),
            message = message
        )
    }

    fun ItdDepartureMonitorResult.ItdDepartureMonitorRequest.ItdServingLines.ItdServingLine.destinationAsLocation(): Location? {
        val destinationId = destID.takeIf { it != "-1" }
        return if(destinationId != null)
            Location(
                id = destinationId,
                type = Location.Type.STATION,
                coord = null,
                name = direction
            )
        else if(direction != null) {
            Location(
                id = null,
                type = Location.Type.ANY,
                coord = null,
                name = direction
            )
        } else null
    }

    private fun ItdDepartureMonitorResult.ItdDepartureMonitorRequest.ItdServingLines.ItdServingLine.isCancelled(): Boolean {
        return delay == "-9999"
    }

    fun ItdPoint.toLocation(): Location {
        val place = locality.normalize() ?: place.normalize()
        val name = nameWO.normalize() ?: name.normalize()
        return Location(
            id = stopID,
            type = Location.Type.STATION,
            coord = parseCoord(mapName, x, y),
            place = place,
            name = name,
        )
    }
}

private fun List<Leg>.ensureSeparatingIndividualLegs(): List<Leg> {
    return this.flatMapIndexed { index, leg ->
        val nextLeg = this.getOrNull(index + 1)
        if(leg is PublicLeg && nextLeg is PublicLeg) {
            val walkDist = leg.arrival.distanceToInMeters(nextLeg.departure)?.toInt() ?: 0
            val walkDurationMs = (walkDist / 0.00125).roundToLong()

            listOf(leg, IndividualLeg(
                type = IndividualLeg.Type.WALK,
                departure = leg.arrival,
                departureTime = leg.arrivalTime,
                arrival = nextLeg.departure,
                arrivalTime = leg.arrivalTime + walkDurationMs,
                distance = walkDist,
                path = PathUtil.interpolatePath(
                    leg.arrival,
                    null,
                    nextLeg.arrival
                )
            ))
        } else {
            listOf(leg)
        }
    }
}

private fun Location.distanceToInMeters(other: Location): Double? {
    if(!this.hasCoords || !other.hasCoords) return null

    val earthRad = 6371000.0 // Earth's radius in meters

    // Convert degrees to radians
    val lat1Rad = this.coord!!.lat.toRadians()
    val lon1Rad = this.coord.lon.toRadians()
    val lat2Rad = other.coord!!.lat.toRadians()
    val lon2Rad = other.coord.lon.toRadians()

    // Haversine formula
    val dlat = lat2Rad - lat1Rad
    val dlon = lon2Rad - lon1Rad
    val a = sin(dlat/2).pow(2) + cos(lat1Rad) * cos(lat2Rad) * sin(dlon/2).pow(2)
    val c = 2 * atan2(sqrt(a), sqrt(1-a))

    return earthRad * c // Returns distance in meters
}

private fun Double.toRadians(): Double = this * (PI / 180)

private fun HttpClient.installDefaultXml(): HttpClient {
    return this.config {
        install(ContentNegotiation) {
            xml(format = XML {
                defaultPolicy {
                    ignoreUnknownChildren()
                }

                serializersModuleOf(AbstractEfaProvider.ItdTripsResponse::class, AbstractEfaProvider.ItdTripsResponse.serializer())
            })
        }
    }
}

private fun String?.parseCoord(): Point? {
    if (this == null) return null

        val parts = this.split(",".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
        if (parts.size < 2) throw RuntimeException("cannot parse coordinate: $this")
        val lat = parts[1].toDouble()
        val lon = parts[0].toDouble()
        return Point.fromDouble(lat, lon)
}

fun String?.normalize(): String? {
    if(this.isNullOrEmpty()) return null

    return this.replace(P_STATION_NAME_WHITESPACE, " ")
}

fun List<JsonMessage>.parseToStatus(): SuggestLocationsResult.Status? {
    return if(this.any { it.name == "code" && it.value != "-8010" && it.value != "-8011" })
        SuggestLocationsResult.Status.SERVICE_DOWN
    else null
}