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

import de.libf.ptek.AbstractHafasClientInterfaceProvider.StationBoardResObj.OperatorObj
import de.libf.ptek.AbstractHafasClientInterfaceProvider.StationBoardResObj.ProductObj
import de.libf.ptek.AbstractHafasClientInterfaceProvider.StopObj.PlatformObj
import de.libf.ptek.dto.Departure
import de.libf.ptek.dto.Fare
import de.libf.ptek.dto.IndividualLeg
import de.libf.ptek.dto.Leg
import de.libf.ptek.dto.Line
import de.libf.ptek.dto.Location
import de.libf.ptek.dto.NearbyLocationsResult
import de.libf.ptek.dto.Point
import de.libf.ptek.dto.Position
import de.libf.ptek.dto.Product
import de.libf.ptek.dto.PublicLeg
import de.libf.ptek.dto.QueryDeparturesResult
import de.libf.ptek.dto.QueryTripsResult
import de.libf.ptek.dto.ResultHeader
import de.libf.ptek.dto.StationDepartures
import de.libf.ptek.dto.Stop
import de.libf.ptek.dto.Style
import de.libf.ptek.dto.SuggestLocationsResult
import de.libf.ptek.dto.SuggestedLocation
import de.libf.ptek.dto.Trip
import de.libf.ptek.dto.TripOptions
import de.libf.ptek.exception.ParserException
import de.libf.ptek.util.AbstractLogger
import de.libf.ptek.util.PolylineFormat
import de.libf.ptek.util.PrintlnLogger
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.appendPathSegments
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.charsets.Charsets
import io.ktor.utils.io.core.toByteArray
import io.ktor.utils.io.errors.IOException
import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.ClassDiscriminatorMode
import kotlinx.serialization.json.Json
import org.kotlincrypto.hash.md.MD5
import kotlin.coroutines.cancellation.CancellationException
import kotlin.enums.enumEntries

/**
 * This is an implementation of the HCI (HAFAS Client Interface).
 *
 * @author Andreas Schildbach, Fabian Schillig
 */
abstract class AbstractHafasClientInterfaceProvider(
    network: NetworkId,
    httpClient: HttpClient,
    private val apiBase: Url,
    productsMap: List<Product?>,
    logger: AbstractLogger = PrintlnLogger()
) : AbstractHafasProvider(
    network, configureHttpClient(httpClient), productsMap, logger
) {
    var apiEndpoint: String = "mgate.exe"
        private set
    var apiVersion: String? = null
        private set
    var apiExt: String? = null
        private set
    var apiAuthorization: String? = null
        private set
    var apiClient: String? = null
        private set
    var requestChecksumSalt: ByteArray? = null
        private set
    var requestMicMacSalt: ByteArray? = null
        private set

    fun getApiBase(): Url {
        return apiBase
    }

    fun setApiEndpoint(apiEndpoint: String): AbstractHafasClientInterfaceProvider {
        this.apiEndpoint = apiEndpoint
        return this
    }

    protected fun setApiVersion(apiVersion: String): AbstractHafasClientInterfaceProvider {
        if (apiVersion.compareTo(
                "1.14",
                ignoreCase = true
            ) < 0
        ) throw RuntimeException("apiVersion must be 1.14 or higher")
        this.apiVersion = apiVersion
        return this
    }

    protected fun setApiExt(apiExt: String): AbstractHafasClientInterfaceProvider {
        this.apiExt = apiExt
        return this
    }

    protected fun setApiAuthorization(apiAuthorization: String?): AbstractHafasClientInterfaceProvider {
        this.apiAuthorization = apiAuthorization
        return this
    }

    protected fun setApiClient(apiClient: String?): AbstractHafasClientInterfaceProvider {
        this.apiClient = apiClient
        return this
    }

    protected fun setRequestChecksumSalt(requestChecksumSalt: ByteArray?): AbstractHafasClientInterfaceProvider {
        this.requestChecksumSalt = requestChecksumSalt
        return this
    }

    protected fun setRequestMicMacSalt(requestMicMacSalt: ByteArray?): AbstractHafasClientInterfaceProvider {
        this.requestMicMacSalt = requestMicMacSalt
        return this
    }

    @Throws(IOException::class, CancellationException::class)
    override suspend fun queryNearbyLocations(
        types: Set<Location.Type>, location: Location,
        maxDistance: Int, maxLocations: Int
    ): NearbyLocationsResult {
        if (location.hasCoords) return jsonLocGeoPos(
            types,
            location.coord!!,
            maxDistance,
            maxLocations
        )
        else throw IllegalArgumentException("cannot handle: $location")
    }

    @Throws(IOException::class, CancellationException::class)
    override suspend fun queryDepartures(
        stationId: String, time: Long?,
        maxDepartures: Int, equivs: Boolean
    ): QueryDeparturesResult {
        return jsonStationBoard(stationId, time, maxDepartures, equivs)
    }

    @Throws(IOException::class, CancellationException::class)
    override suspend fun suggestLocations(
        constraint: CharSequence,
        types: Set<Location.Type>?, maxLocations: Int
    ): SuggestLocationsResult {
        return jsonLocMatch(constraint, types, maxLocations)
    }

    @Throws(IOException::class, CancellationException::class)
    override suspend fun queryTrips(
        from: Location, via: Location?, to: Location,
        date: Long, dep: Boolean, options: TripOptions?
    ): QueryTripsResult {
        return jsonTripSearch(
            from, via, to, date, dep, options?.products,
            options?.walkSpeed, null
        )
    }

    @Throws(IOException::class, CancellationException::class)
    override suspend fun queryMoreTrips(
        context: QueryTripsContext,
        later: Boolean
    ): QueryTripsResult {
        val jsonContext = context as JsonContext
        return jsonTripSearch(
            jsonContext.from,
            jsonContext.via,
            jsonContext.to,
            jsonContext.date,
            jsonContext.dep,
            jsonContext.products,
            jsonContext.walkSpeed,
            if (later) jsonContext.laterContext else jsonContext.earlierContext
        )
    }

    /*******************/

    @Serializable
    data class LocCrdObj(
        val x: Int,
        val y: Int
    )

    @Serializable
    data class CrdSysObj(val type: String)

    @Serializable
    data class RemObj(
        val code: String,
        val txtS: String? = null,
        val txtN: String? = null
    )

    @Serializable
    data class LocObj(
        val type: String? = null,
        val mMastLocX: Int? = null, //-1 if null
        val extId: String? = null,
        val name: String? = null,
        val pCls: Int? = null, //-1 if null
        val lid: String? = null,
        val crd: LocCrdObj? = null,
        val crdSysX: Int? = null //-1 if null
    )

    @Serializable
    data class IconObj(
        val bg: ColorObj? = null,
        val fg: ColorObj? = null,
        val shp: String? = null,
    ) {
        @Serializable
        data class ColorObj(
            val a: Int? = 255,
            val r: Int,
            val g: Int,
            val b: Int,
        ) {
            fun toColorInt(): Int {
                return ((a ?: 255) shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        val shape: Style.Shape?
            get() = when (shp) {
                "C" -> Style.Shape.CIRCLE
                "R" -> Style.Shape.RECT
                null -> null
                else -> throw IllegalStateException("cannot handle shape $shp")
            }

        val style: Style?
            get() = bg?.let { bgCol ->
                val background = bgCol.toColorInt()
                val foreground = fg?.toColorInt() ?: Style.deriveForegroundColor(background)

                return shape.let {
                    if (it != null) Style(
                        shape = it,
                        backgroundColor = background,
                        foregroundColor = foreground
                    )
                    else Style(backgroundColor = background, foregroundColor = foreground)
                }
            }
    }

    @Serializable
    data class StopObj(
        val dCncl: Boolean? = null,

        val dPltfS: PlatformObj? = null,
        val dPlatfS: String? = null,
        val dPltfR: PlatformObj? = null,
        val dPlatfR: String? = null,

        val dProdX: Int? = -1,
        val dTimeS: String? = null, //planned time
        val dTimeR: String? = null, //predicted time

        val aCncl: Boolean? = null, //arrival cancelled
        val aTimeS: String? = null, //planned arrival time
        val aTimeR: String? = null, //predicted arrival time
        val aPltfS: PlatformObj? = null,
        val aPlatfS: String? = null,
        val aPltfR: PlatformObj? = null,
        val aPlatfR: String? = null,

        val locX: Int
    ) {
        @Serializable
        data class PlatformObj(val txt: String)
    }

    @Serializable
    data class JourneyObj(
        val stbStop: StopObj,
        val date: String,
        val dirTxt: String? = null,
        val stopL: List<StopObj>? = null,
        val remL: List<RemRef>? = null,
    ) {
        @Serializable
        data class RemRef(val remX: Int)


    }

    @Serializable
    sealed interface ApiResponseType {
        val common: ApiCommonResponse
    }

    @Serializable
    sealed interface ApiCommonResponse {
        val sD: String?
        val sT: String?
    }

    @Serializable
    data class ApiResponse<T : ApiResponseType>(
        val err: String? = null,
        val errTxt: String? = null,
        val svcResL: List<SvcResObj<T>>,
        val ver: String,
    ) {
        @Serializable
        data class SvcResObj<T : ApiResponseType>(
            val meth: String,
            val err: String? = null,
            val errTxt: String? = null,
            val res: T,
        )
    }

    @Serializable
    data class LocGeoPosResObj(
        val locL: List<LocObj>? = null,
        override val common: SvcResCommonObj,
        // 0 oben, 1 unten
    ) : ApiResponseType {
        @Serializable
        data class SvcResCommonObj(
            val remL: List<RemObj>,
            val crdSysL: List<CrdSysObj>? = null,
            override val sD: String? = null, //parseIsoDate
            override val sT: String? = null, //parseJsonTime
        ) : ApiCommonResponse {

        }
    }

    @Serializable
    data class LocMatchResponse(
//        val match: List<LocObj>? = null,
        val match: SvcMatchObj? = null,
        override val common: SvcResCommonObj,
        // 0 oben, 1 unten
    ) : ApiResponseType {
        @Serializable
        data class SvcResCommonObj(
            val remL: List<RemObj>,
            val crdSysL: List<CrdSysObj>? = null,
            override val sD: String? = null, //parseIsoDate
            override val sT: String? = null, //parseJsonTime
        ) : ApiCommonResponse

        @Serializable
        data class SvcMatchObj(
            val locL: List<LocObj>? = null
        )
    }

    @Serializable
    data class OutConObj(
        val dep: StopObj,
        val arr: StopObj,
        val date: String,
        val secL: List<SectionObj>,
        val trfRes: TrfResObj? = null,
        val ovwTrfRefL: List<TrfRefObj>? = null
    ) {
        @Serializable
        data class TrfRefObj(
            val type: String,
            val fareSetX: Int,
            val fareX: Int,
            val ticketX: Int,
        )

        @Serializable
        data class TrfResObj(
            val fareSetL: List<FareSetObj>? = null
        ) {
            @Serializable
            data class FareSetObj(
                val fareL: List<FareObj>,
                val name: String? = null
            ) {
                @Serializable
                data class FareObj(
                    val name: String = "",

                    val ticketL: List<TicketObj>? = null,

                    val cur: String? = null,
                    val prc: Int? = null
                ) {
                    @Serializable
                    data class TicketObj(
                        val name: String,
                        val cur: String? = null,
                        val prc: Int? = null
                    )
                }
            }

        }

        @Serializable
        data class SectionObj(
            val type: String,
            val dep: StopObj,
            val arr: StopObj,
            val jny: SectionJourney? = null,
            val gis: GisObject? = null
        ) {

            @Serializable
            data class GisObject(val dist: Int? = null)

            @Serializable
            data class SectionJourney(
                val prodX: Int,
                val dirTxt: String? = null,
                val stopL: List<StopObj>? = null,
                val polyG: PolyGObj? = null,
                val remL: List<JourneyObj.RemRef>? = null,
                val msgL: List<JourneyObj.RemRef>? = null,

                ) {
                @Serializable
                data class PolyGObj(
                    val crdSysX: Int? = null,
                    val polyXL: List<Int>
                )

            }

        }
    }

    @Serializable
    data class TripSearchResponse(
        override val common: TripSearchCommonObj,

//        val res: TripSearchResObj? = null,
        val outConL: List<OutConObj>? = null,
        val outCtxScrF: String? = null,
        val outCtxScrB: String? = null,
    ) : ApiResponseType {
        @Serializable
        data class TripSearchResObj(
            val outConL: List<OutConObj>? = null,
            val outCtxScrF: String? = null,
            val outCtxScrB: String? = null,
        )

        @Serializable
        data class TripSearchCommonObj(
            val remL: List<RemObj>,
            val icoL: List<IconObj>,
            val opL: List<OperatorObj>,
            val prodL: List<ProductObj>,
            val crdSysL: List<CrdSysObj>? = null,
            val polyL: List<PolyObj>? = null,
            val locL: List<LocObj>? = null,
            override val sD: String? = null,
            override val sT: String? = null,
        ) : ApiCommonResponse {
            @Serializable
            data class PolyObj(
                val delta: Boolean,
                val crdEncYX: String
            )
        }
    }

    @Serializable
    data class StationBoardResObj(
        override val common: StationBoardCommonObj,
        val jnyL: List<JourneyObj>? = null,
    ) : ApiResponseType {

        @Serializable
        data class StationBoardCommonObj(
            val remL: List<RemObj>,
            val icoL: List<IconObj>,
            val opL: List<OperatorObj>,
            val prodL: List<ProductObj>,
            val crdSysL: List<CrdSysObj>? = null,
            val locL: List<LocObj>? = null,
            override val sD: String? = null,
            override val sT: String? = null,
        ) : ApiCommonResponse

        @Serializable
        data class JourneyObj(
            val stbStop: StopObj,
            val date: String,
            val dirTxt: String? = null,
            val stopL: List<StopObj>? = null,
            val remL: List<RemRef>? = null,
        ) {
            @Serializable
            data class RemRef(val remX: Int)


        }

        @Serializable
        data class OperatorObj(val name: String)

        @Serializable
        data class ProductObj(
            val name: String? = null,
            val nameS: String? = null,
            val number: String? = null,
            val icoX: Int,
            val oprX: Int? = -1,
            val cls: Int? = -1,
            val prodCtx: ProductCtxObj? = null,
            val addName: String? = null
        ) {
            @Serializable
            data class ProductCtxObj(val lineId: String? = null)
        }
    }


    @Throws(IOException::class, CancellationException::class)
    suspend fun jsonLocGeoPos(
        types: Set<Location.Type?>, coord: Point,
        maxDistance: Int, maxLocations: Int
    ): NearbyLocationsResult {
        val getStations = types.contains(Location.Type.STATION)
        val getPOIs = types.contains(Location.Type.POI)

        val request = wrapJsonApiRequest(
            request = ApiRequest.SvcReqObj(
                meth = "LocGeoPos",
                cfg = ApiRequest.SvcReqObj.SvcCfgDefault(),
                req = ApiRequest.SvcReqObj.LocGeoPosRequest(
                    ring = ApiRequest.SvcReqObj.LocGeoPosRequest.Ring(
                        cCrd = LocCrdObj(
                            x = coord.lonAs1E6,
                            y = coord.latAs1E6
                        ),
                        maxDist = maxDistance.orIfZero(DEFAULT_MAX_DISTANCE)
                    ),
                    getStops = getStations,
                    getPOIs = getPOIs,
                    maxLoc = maxLocations.orIfZero(DEFAULT_MAX_LOCATIONS),
                )
            ),
            formatted = false
        )

        val url: Url = requestUrl(request)

        val response = httpClient.post(url) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
//            header(HttpHeaders.Accept, "application/json")
//            header(HttpHeaders.ContentType, "application/json")
            setBody(request)
        }

//        try {
//            val rsp = response.bodyAsText()
//
//            val json = Json { isLenient = true }
//
//            val data = json.decodeFromString<ApiResponse<LocGeoPosResObj>>(rsp)
            val data = response.body<ApiResponse<LocGeoPosResObj>>()
            if (data.err != null && data.err != "OK") throw RuntimeException("${data.err} ${data.errTxt}")

            if (data.svcResL.size != 2) throw IllegalStateException("svcResL.size != 2")
            val header: ResultHeader = parseServerInfo(data.svcResL[0], data.ver)

            val svcRes = data.svcResL[1]
            if (svcRes.meth != "LocGeoPos") throw IllegalStateException("svcRes.meth != LocGeoPos")
            if (svcRes.err != "OK") {
                logger.warn(TAG, "Hafas error: ${svcRes.err} ${svcRes.errTxt}")
                when {
                    (svcRes.err == "FAIL" && svcRes.errTxt == "HCI Service: request failed")
                            || (svcRes.err == "CGI_READ_FAILED")
                            || (svcRes.err == "CGI_NO_SERVER")
                            || (svcRes.err == "H_UNKNOWN") ->
                        return NearbyLocationsResult(
                            header,
                            NearbyLocationsResult.Status.SERVICE_DOWN
                        )

                    else -> throw RuntimeException("${svcRes.err} ${svcRes.errTxt}")
                }
            }

            //val remarks = parseRemList(svcRes.res.common.remL)
            val crdSysList = svcRes.res!!.common.crdSysL!!
            val locations = svcRes.res!!.locL?.let {
                parseLocList(it, crdSysList).filter { types.contains(it.type) }
            } ?: emptyList()

            return NearbyLocationsResult(header, NearbyLocationsResult.Status.OK, locations)
//        } catch (e: Exception) {
//            throw ParserException("cannot parse json: '${response.bodyAsText()}' on $url  -- ${e.message}")
//        }
    }

    @Throws(IOException::class, CancellationException::class)
    protected suspend fun jsonStationBoard(
        stationId: String, time: Long?,
        maxDepartures: Int, equivs: Boolean
    ): QueryDeparturesResult {
        val canStbFltrEquiv = apiVersion!!.compareTo("1.18", ignoreCase = true) <= 0
        val raisedMaxDepartures = if (!equivs && !canStbFltrEquiv) {
            (maxDepartures * 4).also {
                logger.log(
                    TAG,
                    "stbFltrEquiv workaround in effect: querying for " +
                            "$it departures rather than $maxDepartures",
                )
            }
        } else null

        val dateTime =
            (time?.let { Instant.fromEpochMilliseconds(it) } ?: Clock.System.now()).toLocalDateTime(
                timeZone
            )
        val jsonDate = jsonDate(dateTime.date)
        val jsonTime = jsonTime(dateTime.time)
        val normalizedStationId: String = normalizeStationId(
            stationId
        )!!

        val request = wrapJsonApiRequest(
            request = ApiRequest.SvcReqObj(
                meth = "StationBoard",
                req = ApiRequest.SvcReqObj.StationBoardRequest(
                    date = jsonDate,
                    time = jsonTime,
                    stbLoc = ApiRequest.SvcReqObj.StationBoardRequest.LocObj(
                        extId = normalizedStationId
                    ),
                    stbFltrEquiv = equivs.takeIf { canStbFltrEquiv },
                    maxJny = raisedMaxDepartures ?: maxDepartures.orIfZero(DEFAULT_MAX_DEPARTURES)
                )
            ),
            formatted = false
        )

        val url: Url = requestUrl(request)

        val response = httpClient.post(url) {
//            header(HttpHeaders.Accept, "application/json")
//            header(HttpHeaders.ContentType, "application/json")
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            setBody(request)
        }

//        try {
            val data = response.body<ApiResponse<StationBoardResObj>>()
            if (data.err != null && data.err != "OK") throw RuntimeException("${data.err} ${data.errTxt}")

            if (data.svcResL.size != 2) throw IllegalStateException("svcResL.size != 2")
            val header: ResultHeader = parseServerInfo(data.svcResL[0], data.ver)

            val svcRes = data.svcResL[1]
            if (svcRes.meth != "StationBoard") throw IllegalStateException("svcRes.meth != StationBoard")

            if (svcRes.err != "OK") {
                logger.warn(TAG, "Hafas error: ${svcRes.err} ${svcRes.errTxt}")
                return when {
                    (svcRes.err == "LOCATION" && svcRes.errTxt == "HCI Service: location missing or invalid") ->
                        QueryDeparturesResult(header, QueryDeparturesResult.Status.INVALID_STATION)

                    (svcRes.err == "FAIL" && svcRes.errTxt == "HCI Service: request failed")
                            || (svcRes.err == "PROBLEMS" && svcRes.errTxt == "HCI Service: problems during service execution")
                            || (svcRes.err == "CGI_READ_FAILED")
                            || (svcRes.err == "CGI_NO_SERVER")
                            || (svcRes.err == "H_UNKNOWN") ->
                        QueryDeparturesResult(header, QueryDeparturesResult.Status.SERVICE_DOWN)

                    else -> throw RuntimeException("${svcRes.err} ${svcRes.errTxt}")
                }
            }

            val remarks = parseRemList(svcRes.res!!.common.remL)
            val styles = svcRes.res.common.icoL.map { it.style }
            val operators = svcRes.res.common.opL.map { it.name }
            val lines = parseProdList(svcRes.res.common.prodL, operators, styles)
            val crdSysList = svcRes.res.common.crdSysL!!
            val locList = svcRes.res.common.locL!!

            val result = QueryDeparturesResult(header)

            svcRes.res.jnyL?.forEach { journeyObj ->
                if (journeyObj.stbStop.dCncl == true) return@forEach

                val position = journeyObj.stbStop.dPltfS?.let { Position(it.txt) }  //TODO type
                    ?: journeyObj.stbStop.dPlatfS?.let(::normalizePosition)

                val date = LocalDate.Formats.ISO_BASIC.parse(journeyObj.date)

                val plannedTime = journeyObj.stbStop.dTimeS
                    ?.let { parseJsonTime(date, it) }
                    ?.toInstant(timeZone)
                val predictedTime = journeyObj.stbStop.dTimeR
                    ?.let { parseJsonTime(date, it) }
                    ?.toInstant(timeZone)

                val line: Line = journeyObj.stbStop.dProdX
                    ?.takeIf { it != -1 }
                    ?.let { lines.getOrNull(it) }
                    ?: return@forEach

                val location: Location = parseLoc(
                    locList,
                    journeyObj.stbStop.locX,
                    null,
                    crdSysList
                )?.takeIf { it.type == Location.Type.STATION } ?: return@forEach

                if (!equivs && location.id != stationId) return@forEach

                val jnyDirTxt: String? = journeyObj.dirTxt
                val destination: Location? = journeyObj.stopL?.lastOrNull()?.let {
                    // if last entry in stopL happens to be our destination, use it
                    val lastStopName = locList[it.locX].name
                    if (jnyDirTxt != null && jnyDirTxt == lastStopName)
                        parseLoc(locList, it.locX, null, crdSysList)
                    else null
                } ?: jnyDirTxt?.let(::splitStationName)?.let {
                    // otherwise split unidentified destination as if it was a station and use it
                    Location(
                        id = null,
                        type = Location.Type.ANY,
                        place = it[0],
                        name = it[1]
                    )
                }

                var message: String? = null
                journeyObj.remL?.map {
                    val remark = remarks[it.remX]
                    if (remark[0] == "l?") message = remark[1]
                }

                val departure = Departure(
                    plannedTime = plannedTime?.toEpochMilliseconds(),
                    predictedTime = predictedTime?.toEpochMilliseconds(),
                    line = line,
                    position = position,
                    destination = destination,
                    capacity = null,
                    message = message
                )

                val stationDepartures = findStationDepartures(result.stationDepartures, location)
                    ?: StationDepartures(location, mutableListOf(), mutableListOf()).also {
                        result.stationDepartures.add(it)
                    }

                stationDepartures.departures.add(departure)
            }

            // sort departures
            result.stationDepartures.forEach {
                it.departures.sortWith(Departure.TIME_COMPARATOR)
            }

            return result
//        } catch (x: Exception) {
//            throw ParserException("cannot parse json: '${response.bodyAsText()}' on $url, caused by ${x.message}")
//        }
    }

    @Throws(IOException::class, CancellationException::class)
    protected suspend fun jsonLocMatch(
        constraint: CharSequence,
        types: Set<Location.Type>?, maxLocations: Int
    ): SuggestLocationsResult {
        var _maxLocations = maxLocations
        if (maxLocations == 0) _maxLocations =
            DEFAULT_MAX_LOCATIONS
        val type = if (types == null || types.contains(Location.Type.ANY) || types.containsAll(
                setOf(
                    Location.Type.STATION,
                    Location.Type.ADDRESS,
                    Location.Type.POI
                )
            )
        ) "ALL"
        else types.map {
            when (it) {
                Location.Type.STATION -> "S"
                Location.Type.ADDRESS -> "A"
                Location.Type.POI -> "P"
                else -> ""
            }
        }.joinToString(separator = "")

        val request = wrapJsonApiRequest(
            request = ApiRequest.SvcReqObj(
                meth = "LocMatch",
                req = ApiRequest.SvcReqObj.LocMatchRequest(
                    input = ApiRequest.SvcReqObj.LocMatchRequest.InputData(
                        loc = LocObj(
                            name = "$constraint?",
                            type = type
                        ),
                        maxLoc = _maxLocations
                    )
                )
            ),
            formatted = false
        )

        val url: Url = requestUrl(request)

        val response = httpClient.post(url) {
//            header(HttpHeaders.Accept, "application/json")
//            header(HttpHeaders.ContentType, "application/json")
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            setBody(request)
        }

//        try {

        println(response.bodyAsText())

            val data = response.body<ApiResponse<LocMatchResponse>>()
            if (data.err != null && data.err != "OK") throw RuntimeException("${data.err} ${data.errTxt}")

            if (data.svcResL.size != 2) throw IllegalStateException("svcResL.size != 2")
            val header: ResultHeader = parseServerInfo(data.svcResL[0], data.ver)

            val svcRes = data.svcResL[1]
            if (svcRes.meth != "LocMatch") throw IllegalStateException("svcRes.meth != LocMatch")
            if (svcRes.err != "OK") {
                logger.warn(TAG, "Hafas error: ${svcRes.err} ${svcRes.errTxt}")
                when {
                    (svcRes.err == "FAIL" && svcRes.errTxt == "HCI Service: request failed")
                            || (svcRes.err == "CGI_READ_FAILED")
                            || (svcRes.err == "CGI_NO_SERVER")
                            || (svcRes.err == "H_UNKNOWN") ->
                        return SuggestLocationsResult(
                            header,
                            SuggestLocationsResult.Status.SERVICE_DOWN
                        )

                    else -> throw RuntimeException("${svcRes.err} ${svcRes.errTxt}")
                }
            }

//            val remarks = parseRemList(svcRes.res.common.remL)
            val crdSysList = svcRes.res!!.common.crdSysL!!
            val suggestedLocations = svcRes.res.match?.locL?.let {
                parseLocList(it, crdSysList)
            }?.map(::SuggestedLocation) ?: emptyList()

            //TODO weight
            return SuggestLocationsResult(header, suggestedLocations)
//        } catch (x: Exception) {
//            throw ParserException("cannot parse json: '${response.bodyAsText()}' on $url, caused by ${x.message}")
//        }
    }

    @Throws(IOException::class, CancellationException::class)
    private suspend fun jsonTripSearchIdentify(location: Location): Location? {
        if (location.hasId) return location
        if (location.hasName) {
            val result: SuggestLocationsResult =
                jsonLocMatch(
                    listOfNotNull(location.place, location.name).joinToString(" "),
                    null,
                    1
                )
            if (result.status == SuggestLocationsResult.Status.OK) {
                val locations: List<Location> = result.getLocations() ?: emptyList()
                if (locations.isNotEmpty()) return locations[0]
            }
        }
        if (location.hasCoords) {
            val result: NearbyLocationsResult =
                jsonLocGeoPos(enumEntries<Location.Type>().toSet(), location.coord!!, 0, 1)
            if (result.status == NearbyLocationsResult.Status.OK) {
                val locations: List<Location> = result.locations
                if (locations.isNotEmpty()) return locations[0]
            }
        }
        return null
    }

    @Throws(IOException::class, CancellationException::class)
    suspend fun jsonTripSearch(
        _from: Location, _via: Location?, _to: Location, time: Long,
        dep: Boolean, products: Set<Product>?, walkSpeed: NetworkProvider.WalkSpeed?,
        moreContext: String?
    ): QueryTripsResult {
        val from = jsonTripSearchIdentify(_from)
        val via: Location? = _via?.let { jsonTripSearchIdentify(it) }
        val to: Location? = jsonTripSearchIdentify(_to)
        if (from == null) return QueryTripsResult(
            ResultHeader(
                network,
                SERVER_PRODUCT
            ),
            QueryTripsResult.Status.UNKNOWN_FROM
        )
        if (_via != null) {
            if (via == null) return QueryTripsResult(
                ResultHeader(
                    network,
                    SERVER_PRODUCT
                ),
                QueryTripsResult.Status.UNKNOWN_VIA
            )
        }

        if (to == null) return QueryTripsResult(
            ResultHeader(
                network,
                SERVER_PRODUCT
            ),
            QueryTripsResult.Status.UNKNOWN_TO
        )

        val tripInstant = time.let { Instant.fromEpochMilliseconds(it) }
        val tripDateTime = tripInstant.toLocalDateTime(timeZone)
        val outDate = jsonDate(tripDateTime.date)
        val outTime = jsonTime(tripDateTime.time)
        val outFrwd: CharSequence = dep.toString()
        val jnyFltr = if (products != null) productsString(products) else null

        val meta =
            "foot_speed_" + (walkSpeed ?: NetworkProvider.WalkSpeed.NORMAL).name.lowercase()

        val request = wrapJsonApiRequest(
            request = ApiRequest.SvcReqObj(
                meth = "TripSearch",
                req = ApiRequest.SvcReqObj.TripSearchRequest(
                    ctxScr = moreContext,
                    depLocL = listOf(from.toLocObj()),
                    arrLocL = listOf(to.toLocObj()),
                    viaLocL = via?.let {
                        listOf(
                            ApiRequest.SvcReqObj.TripSearchRequest.ViaLocObj(
                                it.toLocObj()
                            )
                        )
                    },
                    outDate = outDate,
                    outTime = outTime,
                    outFrwd = outFrwd.toString(),
                    jnyFltrL = jnyFltr?.let {
                        listOf(
                            ApiRequest.SvcReqObj.TripSearchRequest.JourneyFilter(
                                value = it.toString()
                            )
                        )
                    },
                    gisFltrL = listOf(
                        ApiRequest.SvcReqObj.TripSearchRequest.GisFilter(
                            meta = meta
                        )
                    )
                )
            ),
            formatted = false
        )

        val url: Url = requestUrl(request)

        val response = httpClient.post(url) {
//            header(HttpHeaders.Accept, "application/json")
//            header(HttpHeaders.ContentType, "application/json")
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            setBody(request)
        }

//            val rsp = response.bodyAsText()
//
//            val json = Json { isLenient = true; ignoreUnknownKeys = true }
//
//            val data = json.decodeFromString<ApiResponse<TripSearchResponse>>(rsp)

        println(response.bodyAsText())

        val data = response.body<ApiResponse<TripSearchResponse>>()
        if (data.err != null && data.err != "OK") throw RuntimeException("${data.err} ${data.errTxt}")

        if (data.svcResL.size != 2) throw IllegalStateException("svcResL.size != 2")
        val header: ResultHeader = parseServerInfo(data.svcResL[0], data.ver)

        val svcRes = data.svcResL[1]
        if (svcRes.meth != "TripSearch") throw IllegalStateException("svcRes.meth != TripSearch")
        if (svcRes.err != "OK") {
            logger.warn(TAG, "Hafas error: ${svcRes.err} ${svcRes.errTxt}")

            when (svcRes.err) {
                "H890" ->  // No connections found.
                    return QueryTripsResult(
                        statusHint = "No connections found",
                        header = header, status = QueryTripsResult.Status.NO_TRIPS
                    )

                "H891" ->  // No route found (try entering an intermediate station).
                    return QueryTripsResult(
                        statusHint = "No route found (try entering an intermediate station).",
                        header = header, status = QueryTripsResult.Status.NO_TRIPS
                    )

                "H892" ->  // HAFAS Kernel: Request too complex (try entering less intermediate stations)
                    return QueryTripsResult(
                        statusHint = "Request too complex (try entering less intermediate stations)",
                        header = header, status = QueryTripsResult.Status.NO_TRIPS
                    )

                "H895" ->  // Departure/Arrival are too near.
                    return QueryTripsResult(
                        statusHint = "Departure/Arrival are too near",
                        header = header, status = QueryTripsResult.Status.TOO_CLOSE
                    )

                "H9220" -> // Nearby to the given address stations could not be found.
                    return QueryTripsResult(
                        statusHint = "Nearby to the given address stations could not be found.",
                        header = header, status = QueryTripsResult.Status.UNRESOLVABLE_ADDRESS
                    )

                "H886" ->  // HAFAS Kernel: No connections found within the requested time interval
                    return QueryTripsResult(
                        statusHint = "No connections found within the requested time interval",
                        header = header, status = QueryTripsResult.Status.NO_TRIPS
                    )

                "H887" ->  // HAFAS Kernel: Kernel computation time limit reached.
                    return QueryTripsResult(
                        statusHint = "Kernel computation time limit reached",
                        header = header, status = QueryTripsResult.Status.SERVICE_DOWN
                    )

                "H9240" -> // HAFAS Kernel: Internal error.
                    return QueryTripsResult(
                        statusHint = "Internal error",
                        header = header, status = QueryTripsResult.Status.SERVICE_DOWN
                    )

                "H9360" -> // Date outside of the timetable period.
                    return QueryTripsResult(
                        statusHint = "Date outside of the timetable period",
                        header = header, status = QueryTripsResult.Status.INVALID_DATE
                    )

                "H9380" -> // Departure/Arrival/Intermediate or equivalent stations def'd more than once.
                    return QueryTripsResult(
                        statusHint = "Departure/Arrival/Intermediate or equivalent stations def'd more than once",
                        header = header, status = QueryTripsResult.Status.TOO_CLOSE
                    )

                "PROBLEMS",
                "FAIL",
                "CGI_READ_FAILED",
                "CGI_NO_SERVER",
                "H_UNKNOWN" ->
                    return QueryTripsResult(header, QueryTripsResult.Status.SERVICE_DOWN)

                "LOCATION" ->
                    return QueryTripsResult(header, QueryTripsResult.Status.UNKNOWN_LOCATION)

                else -> throw RuntimeException("${svcRes.err}: ${svcRes.errTxt}")
            }
        }

        val res = svcRes.res
        val common = svcRes.res.common
        val locList = common.locL!!
        val crdSysList = common.crdSysL!!
        val operators = svcRes.res.common.opL.map { it.name }
        val remarks = parseRemList(common.remL)
        val styles: List<Style?> = common.icoL.map { it.style }
        val lines: List<Line> = parseProdList(common.prodL, operators, styles)
        val encodedPolylines = common.polyL?.takeIf { it.all { it.delta } }?.map { it.crdEncYX }

        val trips = res.outConL?.map { outCon ->
            val tripFrom: Location = parseLoc(
                locList, outCon.dep.locX,
                mutableSetOf(), crdSysList
            )!!
            val tripTo: Location = parseLoc(
                locList, outCon.arr.locX,
                mutableSetOf(), crdSysList
            )!!

            val baseDate = LocalDate.Formats.ISO_BASIC.parse(outCon.date)

            val legs: List<Leg> = outCon.secL.map { sec ->
                val secType: String = sec.type

                val departureStop: Stop = parseJsonStop(sec.dep, locList, crdSysList, baseDate)

                val arrivalStop: Stop = parseJsonStop(sec.arr, locList, crdSysList, baseDate)

                if (SECTION_TYPE_JOURNEY == secType || SECTION_TYPE_TELE_TAXI == secType) {
                    val jny = sec.jny!!
                    val line: Line = lines[jny.prodX]
                    val dirTxt: String? = jny.dirTxt

                    val destination: Location?
                    if (dirTxt != null) {
                        val splitDirTxt = splitStationName(dirTxt)
                        destination =
                            Location(
                                id = null,
                                type = Location.Type.ANY,
                                place = splitDirTxt[0],
                                name = splitDirTxt[1]
                            )
                    } else {
                        destination = null
                    }

                    val intermediateStops = jny.stopL?.also {
                        if (it.size < 2) throw IllegalStateException("stopList must be at least 2 stops")
                    }?.map {
                        parseJsonStop(it, locList, crdSysList, baseDate)
                    }

                    val path = jny.polyG?.let { polyG ->
                        val type = polyG.crdSysX?.takeIf { it != -1 }
                            ?.let { crdSysList.getOrNull(it)?.type }
                        if (type != null && type != "WGS84") throw IllegalStateException("unknown type: $type")

                        encodedPolylines?.let { encodedPolylines ->
                            polyG.polyXL.flatMap { PolylineFormat.decode(encodedPolylines[it]) }
                        }
                    } //TODO: There seems to be a "poly" attribute on the jny-object, also try that

                    var message: String? = null
                    jny.remL?.forEach {
                        val remark = remarks[it.remX]
                        if (remark[0] == "l?") message = remark[1]
                    } ?: jny.msgL?.forEach {
                        val remark = remarks[it.remX]
                        if (remark[0] == "l?") message = remark[1]
                    }

                    PublicLeg(
                        line = line,
                        destination = destination,
                        departureStop = departureStop,
                        arrivalStop = arrivalStop,
                        intermediateStops = intermediateStops ?: emptyList(),
                        path = path,
                        message = message
                    )
                } else if (secType.isIndividualType()) {
                    val distance: Int = sec.gis?.dist ?: 0
                    IndividualLeg(
                        secType.toIndividualType(),
                        departureStop.location,
                        departureStop.getDepartureTime()!!,
                        arrivalStop.location,
                        arrivalStop.getArrivalTime()!!,
                        null,
                        distance
                    )
                } else {
                    throw IllegalStateException("cannot handle type: $secType")
                }
            }

            val fares: List<Fare>? =
                outCon.trfRes?.let { trfRes ->
                    outCon.ovwTrfRefL?.let { ovwTrfRefList ->
                        val fareSetList = trfRes.fareSetL
                        ovwTrfRefList.flatMap { ovwTrfRef ->
                            val jsonFareSet =
                                fareSetList?.get(ovwTrfRef.fareSetX) ?: return@flatMap emptyList()
                            when (ovwTrfRef.type) {
                                "T" -> { //ticket
                                    val jsonFare = jsonFareSet.fareL[ovwTrfRef.fareX]
                                    val fareName = jsonFare.name
                                    val jsonTicket = jsonFare.ticketL?.get(ovwTrfRef.ticketX)
                                        ?: return@flatMap emptyList()
                                    val ticketName = jsonTicket.name
                                    val currencyStr = jsonTicket.cur
                                        .takeIf { it?.isNotEmpty() ?: false }
                                        ?: return@flatMap emptyList()
                                    val priceCents = jsonTicket.prc ?: return@flatMap emptyList()

                                    Fare(
                                        """
                                        ${normalizeFareName(fareName)}
                                        $ticketName
                                    """.trimIndent(),
                                        normalizeFareType(ticketName),
                                        currencyStr,
                                        priceCents / 100f,
                                        null,
                                        null
                                    ).takeIf { !hideFare(it) }?.let(::listOf) ?: emptyList()
                                }

                                "F" -> {
                                    val jsonFare = jsonFareSet.fareL[ovwTrfRef.fareX]
                                    val fareName: String = jsonFare.name
                                    val currencyStr = jsonFare.cur
                                        .takeIf { it?.isNotEmpty() ?: false }
                                        ?: return@flatMap emptyList()
                                    val priceCents = jsonFare.prc ?: return@flatMap emptyList()

                                    Fare(
                                        normalizeFareName(fareName),
                                        normalizeFareType(fareName),
                                        currencyStr,
                                        priceCents / 100f,
                                        null,
                                        null
                                    ).takeIf { !hideFare(it) }?.let(::listOf) ?: emptyList()
                                }

                                "FS" -> {
                                    val fareSetName: String =
                                        jsonFareSet.name ?: return@flatMap emptyList()

                                    jsonFareSet.fareL.mapNotNull {
                                        val fareName = it.name
                                        val currencyStr = it.cur
                                            ?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                                        val priceCents = it.prc ?: return@mapNotNull null

                                        Fare(
                                            normalizeFareName(fareSetName),
                                            normalizeFareType(fareName),
                                            currencyStr,
                                            priceCents / 100f,
                                            null,
                                            null
                                        ).takeIf { !hideFare(it) }
                                    }
                                }

                                else -> throw IllegalArgumentException("cannot handle type: ${ovwTrfRef.type}")
                            }
                        }
                    }
                }


            Trip(null, tripFrom, tripTo, legs, fares, null, null)
        }

        val context =
            JsonContext(
                from, via, to, time, dep, products, walkSpeed,
                res.outCtxScrF, res.outCtxScrB
            )
        return QueryTripsResult(header, null, from, null, to, context, trips ?: emptyList())
    }

    protected fun normalizeFareType(fareName: String): Fare.Type {
        val fareNameLc = fareName.lowercase()
        if (fareNameLc.contains("erwachsene") || fareNameLc.contains("adult")) return Fare.Type.ADULT
        if (fareNameLc.contains("kind") || fareNameLc.contains("child") || fareNameLc.contains("kids")) return Fare.Type.CHILD
        if (fareNameLc.contains("ermäßigung")) return Fare.Type.CHILD
        if (fareNameLc.contains("schüler") || fareNameLc.contains("azubi")) return Fare.Type.STUDENT
        if (fareNameLc.contains("fahrrad")) return Fare.Type.BIKE
        if (fareNameLc.contains("senior")) return Fare.Type.SENIOR
        return Fare.Type.ADULT
    }

    protected fun normalizeFareName(fareName: String): String {
        return fareName
    }

    protected fun hideFare(fare: Fare?): Boolean {
        return false
    }

    @Serializable
    data class ApiRequest(
        val auth: String?,
        val client: String?,
        val ext: String?,
        val ver: String,
        val lang: String = "eng",
        val svcReqL: List<SvcReqObj>,
        val formatted: Boolean
    ) {
        @Serializable
        data class SvcReqObj(
            val meth: String,
            val cfg: SvcCfg? = SvcCfgDefault(),
            val req: SvcRequest
        ) {
            @Serializable
            sealed interface SvcRequest

            @Serializable
            data class SvcReqLeader(
                val getServerDateTime: Boolean = true,
                val getTimeTablePeriod: Boolean = false
            ) : SvcRequest

            @Serializable
            data class LocGeoPosRequest(
                val ring: Ring,
                val getStops: Boolean,
                val getPOIs: Boolean,
                val maxLoc: Int
            ) : SvcRequest {

                @Serializable
                data class Ring(
                    val cCrd: LocCrdObj,
                    val maxDist: Int
                )
            }

            @Serializable
            data class LocMatchRequest(
                val input: InputData
            ) : SvcRequest {

                @Serializable
                data class InputData(
                    val field: String = "S",
                    val loc: LocObj,
                    val maxLoc: Int
                )
            }

            @Serializable
            data class StationBoardRequest(
                val type: String = "DEP",
                val date: String,
                val time: String,
                val stbLoc: LocObj,
                val stbFltrEquiv: Boolean?,
                val maxJny: Int
            ) : SvcRequest {

                @Serializable
                data class LocObj(
                    val type: String = "S",
                    val state: String = "F", //F/M
                    val extId: String
                )
            }

            @Serializable
            data class TripSearchRequest(
                val ctxScr: String?,
                val depLocL: List<LocObj>,
                val arrLocL: List<LocObj>,
                val viaLocL: List<ViaLocObj>?,
                val outDate: String,
                val outTime: String,
                val outFrwd: String,
                val jnyFltrL: List<JourneyFilter>? = null,
                val gisFltrL: List<GisFilter>,
                val getPolyline: Boolean = true,
                val getPasslist: Boolean = true,
                val getConGroups: Boolean = false,
                val getIST: Boolean = false,
                val getEco: Boolean = false,
                val extChgTime: Int = -1
            ) : SvcRequest {
                @Serializable
                data class JourneyFilter(
                    val value: String,
                    val mode: String = "BIT",
                    val type: String = "PROD"
                )

                @Serializable
                data class GisFilter(
                    val mode: String = "FB",
                    val profile: GisFilterProfile = GisFilterProfile(),
                    val type: String = "M",
                    val meta: String
                )

                @Serializable
                data class GisFilterProfile(
                    val type: String = "F",
                    val linDistRouting: Boolean = false,
                    val maxdist: Int = 2000
                )

                @Serializable
                data class ViaLocObj(val loc: LocObj)
            }


            @Serializable
            sealed interface SvcCfg

            @Serializable
            class SvcCfgDefault :
                SvcCfg {
                val polyEnc: String = "GPA"
            }
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun wrapJsonApiRequest(request: ApiRequest.SvcReqObj, formatted: Boolean): String {
        // Put default values in the serialized JSON string, leave out null-values and don't
        // add "type" fields for polymorphic data classes as the resulting JSON is only sent
        // to external APIs.
        val json = Json {
            encodeDefaults = true; explicitNulls = false; classDiscriminatorMode =
            ClassDiscriminatorMode.NONE
        }

        //TODO: Properly insert the AUTH and CLIENT string into the JSON object
        return json.encodeToString(
            ApiRequest(
                //auth = apiAuthorization,
                //client = apiClient!!,
                auth = apiAuthorization?.let { "%AUTH%" },
                client = apiClient?.let { "%CLIENT%" },
                ext = apiExt,
                ver = apiVersion!!,
                svcReqL = listOf(
                    ApiRequest.SvcReqObj(
                        meth = "ServerInfo",
                        cfg = null,
                        req = ApiRequest.SvcReqObj.SvcReqLeader()
                    ),
                    request
                ),
                formatted = formatted
            )
        )
            .replace("\"%AUTH%\"", apiAuthorization ?: "")
            .replace("\"%CLIENT%\"", apiClient ?: "")
    }

    @OptIn(ExperimentalStdlibApi::class)
    private fun requestUrl(body: String): Url {
        val url: URLBuilder = URLBuilder(apiBase).appendPathSegments(apiEndpoint)

        if (requestChecksumSalt != null) {
            var data = body.toByteArray(Charsets.UTF_8)
            requestChecksumSalt?.let { data += it }
            val checksum = MD5().digest(data)
            url.parameters.append("checksum", checksum.toHexString())
        }
        if (requestMicMacSalt != null) {
            val mic = MD5().digest(body.toByteArray(Charsets.UTF_8))
            url.parameters.append("mic", mic.toHexString())

            var macData = body.toByteArray(Charsets.UTF_8)
            requestMicMacSalt?.let { macData += it }
            val mac = MD5().digest(macData)
            url.parameters.append("mac", mac.toHexString())
        }
        return url.build()
    }

    private fun Location.toLocObj(): LocObj {
        if (!this.hasId) throw IllegalArgumentException("cannot handle: $this")

        return when (this.type) {
            Location.Type.STATION -> LocObj(
                type = "S",
                extId = this.id
            )

            Location.Type.ADDRESS -> LocObj(
                type = "A",
                lid = this.id
            )

            Location.Type.POI -> LocObj(
                type = "P",
                lid = this.id
            )

            else -> throw IllegalArgumentException("cannot handle: $this")
        }
    }

    private fun jsonLocation(location: Location): String {
        if (!location.hasId) throw IllegalArgumentException("cannot handle: $location")

        return Json.encodeToString(
            when (location.type) {
                Location.Type.STATION -> LocObj(
                    type = "S",
                    extId = location.id
                )

                Location.Type.ADDRESS -> LocObj(
                    type = "A",
                    lid = location.id
                )

                Location.Type.POI -> LocObj(
                    type = "P",
                    lid = location.id
                )

                else -> throw IllegalArgumentException("cannot handle: $location")
            }
        )
    }

    private fun jsonDate(time: LocalDate): String {
        return LocalDate.Format {
            year()
            monthNumber()
            dayOfMonth()
        }.format(time)
    }

    private fun jsonTime(time: LocalTime): String {
        return LocalTime.Format {
            hour()
            minute()
        }.format(time) + "00"
    }

    fun <T : ApiResponseType> parseServerInfo(
        serverInfo: ApiResponse.SvcResObj<T>,
        serverVersion: String
    ): ResultHeader {
        if (serverInfo.meth != "ServerInfo") throw RuntimeException("expected ServerInfo, got ${serverInfo.meth}")
        if (serverInfo.err != null && serverInfo.err != "OK") {
            val msg = "err=${serverInfo.err}, errTxt=\"${serverInfo.errTxt}\""
            println("ServerInfo error: $msg, ignoring")
            return ResultHeader(
                network,
                SERVER_PRODUCT, serverVersion, null, 0, null
            )
        }

        //TODO: Timezone?
        val curInstant = serverInfo.res.common.sD?.let { dat ->
            val date = LocalDate.Formats.ISO_BASIC.parse(dat)
            serverInfo.res.common.sT?.let { tim -> parseJsonTime(date, tim) }
        }?.toInstant(timeZone) ?: Clock.System.now()

        return ResultHeader(
            network,
            SERVER_PRODUCT, serverVersion, null, curInstant.toEpochMilliseconds(), null
        )
    }

    private fun parseJsonTime(
        date: LocalDate,
        str: CharSequence
    ): LocalDateTime {
        val m = P_JSON_TIME.matchEntire(str)
        if (m != null) {
            val time = LocalTime(
                hour = m.groupValues[2].toInt(),
                minute = m.groupValues[3].toInt(),
                second = m.groupValues[4].toInt()
            )

            return if (m.groupValues[1].isNotEmpty()) {
                LocalDateTime(
                    date.plus(m.groupValues[1].toInt(), DateTimeUnit.DAY),
                    time
                )
            } else {
                LocalDateTime(
                    date,
                    time
                )
            }
        }

        throw RuntimeException("cannot parse JsonTime: '$str'")
    }

    private fun parseJsonStop(
        data: StopObj, locList: List<LocObj>, crdSysList: List<CrdSysObj>,
        baseDate: LocalDate
    ): Stop {
        val location = parseLoc(locList, data.locX, mutableSetOf(), crdSysList)

        val arrivalCancelled = data.aCncl
        val plannedArrivalTime =
            data.aTimeS?.let { parseJsonTime(baseDate, it) }?.toInstant(timeZone)
        val predictedArrivalTime =
            data.aTimeR?.let { parseJsonTime(baseDate, it) }?.toInstant(timeZone)
        val plannedArrivalPosition = getPosition(data.aPltfS, data.aPlatfS)
        val predictedArrivalPosition = getPosition(data.aPltfR, data.aPlatfR)

        val departureCancelled = data.dCncl
        val plannedDepartureTime =
            data.dTimeS?.let { parseJsonTime(baseDate, it) }?.toInstant(timeZone)
        val predictedDepartureTime =
            data.dTimeR?.let { parseJsonTime(baseDate, it) }?.toInstant(timeZone)
        val plannedDeparturePosition = getPosition(data.dPltfS, data.dPlatfS)
        val predictedDeparturePosition = getPosition(data.dPltfR, data.dPlatfR)

        return Stop(
            location!!,
            plannedArrivalTime?.toEpochMilliseconds(),
            predictedArrivalTime?.toEpochMilliseconds(),
            plannedArrivalPosition,
            predictedArrivalPosition,
            arrivalCancelled ?: false,
            plannedDepartureTime?.toEpochMilliseconds(),
            predictedDepartureTime?.toEpochMilliseconds(),
            plannedDeparturePosition,
            predictedDeparturePosition,
            departureCancelled ?: false
        )
    }

    private fun parseRemList(remList: List<RemObj>): List<Array<String?>> {
        return remList.map {
            arrayOf(it.code, it.txtS ?: it.txtN)
        }
    }

    private fun parseLocList(locList: List<LocObj>, crdSysList: List<CrdSysObj>): List<Location> {
        val prevLoc = mutableSetOf<Int>()
        return locList.mapIndexedNotNull { index, locObj ->
            parseLoc(locList, index, prevLoc, crdSysList)
        }
    }


    private fun parseLoc(
        locList: List<LocObj>,
        locListIndex: Int,
        previousLocListIndexes: MutableSet<Int>?,
        crdSysList: List<CrdSysObj>
    ): Location? {
        val loc = locList[locListIndex]
        val type = loc.type ?: return null

        val locationType: Location.Type
        val id: String?
        val placeAndName: Array<String?>
        val products: Set<Product>?
        when (type) {
            "S" -> {
                val mMastLocX = loc.mMastLocX ?: -1
                if (previousLocListIndexes != null && mMastLocX != -1 && !previousLocListIndexes.contains(
                        mMastLocX
                    )
                ) {
                    previousLocListIndexes.add(locListIndex)
                    return parseLoc(locList, mMastLocX, previousLocListIndexes, crdSysList)
                }
                locationType = Location.Type.STATION
                id =
                    normalizeStationId(
                        loc.extId
                    )
                placeAndName = splitStationName(loc.name!!)
                products = loc.pCls?.takeIf { it != -1 }?.let(::intToProducts)
            }

            "P" -> {
                locationType = Location.Type.POI
                id = loc.lid
                placeAndName = splitPOI(loc.name!!)
                products = null
            }

            "A" -> {
                locationType = Location.Type.ADDRESS
                id = loc.lid
                placeAndName = splitAddress(loc.name!!)
                products = null
            }

            else -> throw RuntimeException("Unknown type $type: $loc")
        }

        val coord: Point? = loc.crd?.let { crd ->
            loc.crdSysX?.takeIf { it != -1 }?.let {
                val crdSysType = crdSysList[it].type
                if ("WGS84" != crdSysType) throw RuntimeException("unknown type: $crdSysType")
            }

            Point.from1E6(crd.y, crd.x)
        }

        return Location(id, locationType, coord, placeAndName[0], placeAndName[1], products)
    }

    fun parseProdList(
        prodList: List<ProductObj>,
        operators: List<String>,
        styles: List<Style?>
    ): List<Line> {
        return prodList.map { productObj ->
            val name = productObj.name?.takeIf { it.isNotEmpty() }
            val nameS = productObj.addName ?: productObj.nameS
            val number = productObj.number
            val style = styles[productObj.icoX]
            val operator = productObj.oprX?.takeIf { it != -1 }?.let { operators[it] }
            val id = productObj.prodCtx?.lineId ?: return@map Line.INVALID
            val product =
                productObj.cls?.takeIf { it != -1 }?.let(::intToProduct) ?: return@map Line.INVALID
            newLine(id, operator, product, name, nameS, number, style)
        }.also {
            println(it)
        }
    }

    protected fun newLine(
        id: String, operator: String?, product: Product, name: String?,
        shortName: String?, number: String?, style: Style?
    ): Line {
        val longName =
            if (name != null) name + (if (number != null && !name.endsWith(number)) " ($number)" else "")
            else if (shortName != null) shortName + (if (number != null && !shortName.endsWith(
                    number
                )
            ) " ($number)" else "")
            else number

        if (product === Product.BUS || product === Product.TRAM) {
            // For bus and tram, prefer a slightly shorter label without the product prefix
            val label =
                shortName ?: if (number != null && name != null && name.endsWith(number)) number
                else name
            return Line(id, operator, product, label, longName, lineStyle(operator, product, label))
        } else if (product == Product.REGIONAL_TRAIN) {
            return Line(
                id,
                operator,
                product,
                shortName,
                longName,
                lineStyle(operator, product, name)
            )
        } else {
            // Otherwise the longer label is fine
            return Line(id, operator, product, name, longName, lineStyle(operator, product, name))
        }
    }

    fun getPosition(obj: PlatformObj?, str: String?): Position? {
        return obj?.let { Position(it.txt) }  //TODO type
            ?: str?.let(::normalizePosition)
    }

    @Serializable
    data class JsonContext(
        val from: Location,
        val via: Location?,
        val to: Location,
        val date: Long,
        val dep: Boolean,
        val products: Set<Product>?,
        val walkSpeed: NetworkProvider.WalkSpeed?,
        val laterContext: String?,
        val earlierContext: String?
    ) : QueryTripsContext {
        override val canQueryLater: Boolean
            get() = laterContext != null

        override val canQueryEarlier: Boolean
            get() = earlierContext != null
    }

    companion object {
        const val TAG = "AbstractHafasClientInterfaceProvider"
        private const val SERVER_PRODUCT = "hci"
        private const val SECTION_TYPE_JOURNEY = "JNY"
        private const val SECTION_TYPE_WALK = "WALK"
        private const val SECTION_TYPE_TRANSFER = "TRSF"
        private const val SECTION_TYPE_TELE_TAXI = "TETA"
        private const val SECTION_TYPE_DEVI = "DEVI"
        private const val SECTION_TYPE_CHECK_IN = "CHKI"
        private const val SECTION_TYPE_CHECK_OUT = "CHKO"

        private fun String.isIndividualType(): Boolean {
            return this == SECTION_TYPE_WALK ||
                    this == SECTION_TYPE_TRANSFER ||
                    this == SECTION_TYPE_DEVI ||
                    this == SECTION_TYPE_CHECK_IN ||
                    this == SECTION_TYPE_CHECK_OUT
        }

        private fun String.toIndividualType(): IndividualLeg.Type {
            return when (this) {
                SECTION_TYPE_WALK -> IndividualLeg.Type.WALK
                SECTION_TYPE_TRANSFER,
                SECTION_TYPE_DEVI -> IndividualLeg.Type.TRANSFER

                SECTION_TYPE_CHECK_IN -> IndividualLeg.Type.CHECK_IN
                SECTION_TYPE_CHECK_OUT -> IndividualLeg.Type.CHECK_OUT
                else -> throw RuntimeException("unknown individual type: $this")
            }
        }

        open fun configureHttpClient(base: HttpClient): HttpClient {
            val mJson = Json { ignoreUnknownKeys = true }

            return base.config {
                install(ContentNegotiation) {
                    json(mJson)
                }
            }
        }

        private val P_JSON_TIME = Regex("(\\d{2})?(\\d{2})(\\d{2})(\\d{2})")

//        fun decryptSalt(encryptedSalt: String?, saltEncryptionKey: String?): ByteArray {
//            try {
//                val key: ByteArray =
//                    com.google.common.io.BaseEncoding.base16().lowerCase().decode(saltEncryptionKey)
//                com.google.common.base.Preconditions.checkState(
//                    key.size * 8 == 128,
//                    "encryption key must be 128 bits"
//                )
//                val secretKey: SecretKeySpec = SecretKeySpec(key, "AES")
//                val ivParameterSpec: IvParameterSpec = IvParameterSpec(ByteArray(16))
//                val cipher: javax.crypto.Cipher =
//                    javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
//                cipher.init(javax.crypto.Cipher.DECRYPT_MODE, secretKey, ivParameterSpec)
//                return cipher.doFinal(
//                    com.google.common.io.BaseEncoding.base64().decode(encryptedSalt)
//                )
//            } catch (x: GeneralSecurityException) {
//                // should not happen
//                throw java.lang.RuntimeException(x)
//            }
//        }
    }
}

private fun Int.orIfZero(alternative: Int): Int {
    return if (this == 0) alternative else this
}


