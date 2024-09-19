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
import de.libf.ptek.dto.Position
import de.libf.ptek.dto.Product
import de.libf.ptek.dto.Style
import de.libf.ptek.dto.QueryTripsResult
import de.libf.ptek.dto.SuggestLocationsResult
import de.libf.ptek.dto.TripOptions
import de.libf.ptek.util.AbstractLogger
import de.libf.ptek.util.PrintlnLogger
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.utils.io.errors.IOException
import kotlinx.datetime.TimeZone
import kotlin.coroutines.cancellation.CancellationException
import kotlin.enums.enumEntries


/**
 * @author Andreas Schildbach
 * @author Fabian Schillig
 */
abstract class AbstractNetworkProvider(
    protected val network: NetworkId,
    protected val httpClient: HttpClient,
    protected val logger: AbstractLogger = PrintlnLogger()
) : NetworkProvider {

    var timeZone: TimeZone = TimeZone.of("CET")

    protected var numTripsRequested: Int = 6
    private var styles: Map<String, Style>? = null

    @Deprecated("Construct with HttpClient yourself")
    fun setUserAgent(userAgent: Any): AbstractNetworkProvider {
        throw UnsupportedOperationException("set Useragent using httpClient")
        //return this
    }

    @Deprecated("Construct with HttpClient yourself")
    fun setProxy(proxy: Any): AbstractNetworkProvider {
        throw UnsupportedOperationException("set Proxy using httpClient")
        //return this
    }

    @Deprecated("Construct with HttpClient yourself")
    fun setTrustAllCertificates(trustAllCertificates: Any): AbstractNetworkProvider {
        throw UnsupportedOperationException("set trustAllCertificates using httpClient")
        //return this
    }

    @Deprecated("Construct with HttpClient yourself")
    protected fun setRequestUrlEncoding(requestUrlEncoding: Any): AbstractNetworkProvider {
        throw UnsupportedOperationException("set requestUrlEncoding using httpClient")
        //return this
    }

    override fun id(): NetworkId? {
        return network
    }

    override fun hasCapabilities(vararg capabilities: NetworkProvider.Capability): Boolean {
        for (capability in capabilities) if (!hasCapability(capability)) return false

        return true
    }

    protected abstract fun hasCapability(capability: NetworkProvider.Capability?): Boolean

    @Deprecated("")
    override suspend fun suggestLocations(constraint: CharSequence): SuggestLocationsResult {
        return suggestLocations(constraint, null, 0)
    }

    //from: Location, via: Location?, to: Location, date: Long, dep: Boolean, products: Set<Product>?, optimize: Optimize?, walkSpeed: WalkSpeed?, accessibility: Accessibility?
    @Deprecated("")
    override suspend fun queryTrips(
        from: Location, via: Location?, to: Location, date: Long, dep: Boolean,
        products: Set<Product>?, optimize: NetworkProvider.Optimize?, walkSpeed: NetworkProvider.WalkSpeed?,
        accessibility: NetworkProvider.Accessibility?, flags: Set<NetworkProvider.TripFlag>?
    ): QueryTripsResult? {
        return queryTrips(
            from, via, to, date, dep,
            TripOptions(products, optimize, walkSpeed, accessibility, flags)
        )
    }

    override fun defaultProducts(): Set<Product> {
        return ALL_EXCEPT_HIGHSPEED
    }

    fun setNumTripsRequested(numTripsRequested: Int): AbstractNetworkProvider {
        this.numTripsRequested = numTripsRequested
        return this
    }

    fun setStyles(styles: Map<String, Style>?): AbstractNetworkProvider {
        this.styles = styles
        return this
    }

    override fun lineStyle(
        network: String?, product: Product?,
        label: String?
    ): Style {
        val styles: Map<String, Style>? = this.styles
        if (styles != null && product != null) {
            if (network != null) {
                // check for line match
                val lineStyle = styles[network + STYLES_SEP + product.c + STYLES_SEP + (label ?: "")]
                if (lineStyle != null) return lineStyle

                // check for product match
                val productStyle: Style? = styles[network + STYLES_SEP + product.c]
                if (productStyle != null) return productStyle

                // check for night bus, as that's a common special case
                if (product === Product.BUS && label != null && label.startsWith("N")) {
                    val nightStyle: Style? = styles[network + STYLES_SEP + "BN"]
                    if (nightStyle != null) return nightStyle
                }
            }

            // check for line match
            val string: String = product.c + (label ?: "")
            val lineStyle: Style? = styles[string]
            if (lineStyle != null) return lineStyle

            // check for product match
            val productStyle: Style? = styles[product.c.toString()]
            if (productStyle != null) return productStyle

            // check for night bus, as that's a common special case
            if (product === Product.BUS && label != null && label.startsWith("N")) {
                val nightStyle: Style? = styles["BN"]
                if (nightStyle != null) return nightStyle
            }
        }

        // standard colors
        return Standard.STYLES[product]!!
    }

    @Throws(IOException::class, CancellationException::class)
    override suspend fun getArea(): List<Point>? {
        return null
    }

    protected fun parsePosition(position: String?): Position? {
        if (position == null) return null

        P_NAME_SECTION.matchEntire(position)?.let { matchResult ->
            val name = matchResult.groupValues[1].toInt().toString()
            return if (matchResult.groupValues.size > 2 && matchResult.groupValues[2].isNotEmpty()) {
                Position(name, matchResult.groupValues[2].replace(Regex("\\s+"), ""))
            } else {
                Position(name)
            }
        }

        // Second pattern: P_NAME_NOSW
        P_NAME_NOSW.matchEntire(position)?.let { matchResult ->
            return Position(
                matchResult.groupValues[1].toInt().toString(),
                matchResult.groupValues[2].substring(0, 1)
            )
        }

        // Default case
        return Position(position)
    }

    companion object {
        protected val ALL_EXCEPT_HIGHSPEED: Set<Product> = enumEntries<Product>().filter {
            it != Product.HIGH_SPEED_TRAIN
        }.toSet()

        private const val STYLES_SEP = '|'

        fun normalizeStationId(stationId: String?): String? {
            if (stationId == null || stationId.length == 0) return null

            if (stationId[0] != '0') return stationId

            val normalized = StringBuilder(stationId)
            while (normalized.isNotEmpty() && normalized.get(0) == '0') normalized.deleteAt(0)

            return normalized.toString()
        }

        private val P_NAME_SECTION = Regex(
            "(\\d{1,5})\\s*([A-Z](?:\\s*-?\\s*[A-Z])?)?",
            RegexOption.IGNORE_CASE
        )

        private val P_NAME_NOSW = Regex(
            "(\\d{1,5})\\s*(Nord|Süd|Ost|West)",
            RegexOption.IGNORE_CASE
        )

        val defaultHttpClient: HttpClient
            get() = HttpClient(CIO)
    }
}
