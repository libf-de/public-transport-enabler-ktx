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
import de.libf.ptek.dto.Position
import de.libf.ptek.dto.Product
import de.libf.ptek.dto.StationDepartures
import de.libf.ptek.util.AbstractLogger
import de.libf.ptek.util.PrintlnLogger
import io.ktor.client.HttpClient

/**
 * @author Andreas Schildbach
 */
abstract class AbstractHafasProvider protected constructor(
    network: NetworkId,
    httpClient: HttpClient,
    private val productsMap: List<Product?>,
    logger: AbstractLogger = PrintlnLogger()
) : AbstractNetworkProvider(network, httpClient, logger) {
    private val CAPABILITIES = listOf(
        NetworkProvider.Capability.SUGGEST_LOCATIONS,
        NetworkProvider.Capability.NEARBY_LOCATIONS,
        NetworkProvider.Capability.DEPARTURES,
        NetworkProvider.Capability.TRIPS,
        NetworkProvider.Capability.TRIPS_VIA
    )

    // this should be overridden by networks not providing one of the default capabilities
    override fun hasCapability(capability: NetworkProvider.Capability?): Boolean {
        return CAPABILITIES.contains(capability)
    }

    protected fun productsString(products: Set<Product?>): CharSequence {
        val productsStr = StringBuilder(productsMap.size)
        for (i in productsMap.indices) {
            if (productsMap[i] != null && products.contains(productsMap[i])) productsStr.append('1')
            else productsStr.append('0')
        }
        return productsStr
    }

    protected fun allProductsString(): CharSequence {
        val productsStr = StringBuilder(productsMap.size)
        for (i in productsMap.indices) productsStr.append('1')
        return productsStr
    }

    protected fun allProductsInt(): Int {
        return (1 shl productsMap.size) - 1
    }

    protected fun intToProduct(productInt: Int): Product? {
        val allProductsInt = allProductsInt()
        if(productInt > allProductsInt) throw IllegalArgumentException("value $productInt cannot be greater than $allProductsInt")

        var value = productInt
        var product: Product? = null
        for (i in productsMap.indices.reversed()) {
            val v = 1 shl i
            if (value >= v) {
                val p: Product? = productsMap[i]
                product = if ((product === Product.ON_DEMAND && p === Product.BUS)
                    || (product === Product.BUS && p === Product.ON_DEMAND)
                ) Product.ON_DEMAND
                else if (product != null && p !== product) throw IllegalArgumentException(
                    "ambiguous value: $productInt"
                )
                else p
                value -= v
            }
        }
        if(value != 0) throw RuntimeException("failed to check state - value is not 0")
        return product
    }

    protected fun intToProducts(value: Int): Set<Product> {
        var value = value
        val allProductsInt = allProductsInt()
        if(value > allProductsInt) throw IllegalArgumentException("value $value cannot be greater than $allProductsInt")

        val products = mutableSetOf<Product>()
        for (i in productsMap.indices.reversed()) {
            val v = 1 shl i
            if (value >= v) {
                productsMap[i]?.let { products.add(it) }
                value -= v
            }
        }
        if(value != 0) throw RuntimeException("failed to check state - value is not 0")
        return products
    }

    protected open fun splitStationName(name: String): Array<String?> {
        return arrayOf(null, name)
    }

    protected open fun splitPOI(poi: String): Array<String?> {
        return arrayOf(null, poi)
    }

    protected open fun splitAddress(address: String): Array<String?> {
        return arrayOf(null, address)
    }

    protected fun normalizePosition(position: String?): Position? {
        if (position == null) return null

        val matchResult = P_POSITION_PLATFORM.find(position)
        return if (matchResult != null) {
            parsePosition(matchResult.groupValues[1])
        } else {
            parsePosition(position)
        }
    }

    protected fun findStationDepartures(
        stationDepartures: List<StationDepartures>,
        location: Location?
    ): StationDepartures? {
        for (stationDeparture in stationDepartures) if (stationDeparture.location.equals(location)) return stationDeparture

        return null
    }

    companion object {
        const val SERVER_PRODUCT: String = "hafas"
        const val DEFAULT_MAX_DEPARTURES: Int = 100

        const val DEFAULT_MAX_LOCATIONS: Int = 50
        const val DEFAULT_MAX_DISTANCE: Int = 20000

        val P_SPLIT_NAME_FIRST_COMMA: Regex =
            Regex("([^,]*), (.*)")
        val P_SPLIT_NAME_LAST_COMMA: Regex =
            Regex("(.*), ([^,]*)")
        val P_SPLIT_NAME_NEXT_TO_LAST_COMMA: Regex =
            Regex("(.*), ([^,]*, [^,]*)")
        val P_SPLIT_NAME_PAREN: Regex =
            Regex("(.*) \\((.{3,}?)\\)")

        private val P_POSITION_PLATFORM = Regex(
            "Gleis\\s*(.*)\\s*",
            RegexOption.IGNORE_CASE
        )
    }
}
