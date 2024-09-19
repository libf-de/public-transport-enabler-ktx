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

import de.libf.ptek.dto.Line
import de.libf.ptek.dto.Location
import de.libf.ptek.dto.Product
import io.ktor.client.HttpClient
import io.ktor.http.Url
import nl.adaptivity.xmlutil.serialization.XML
import kotlin.jvm.JvmOverloads

/**
 * @author Andreas Schildbach
 */
class VvoProvider @JvmOverloads constructor(apiBase: Url = API_BASE, httpClient: HttpClient = defaultHttpClient, xml: XML = defaultXml) :
    AbstractEfaProvider(NetworkId.VVO, apiBase, null, null, STOP_FINDER_ENDPOINT, COORD_ENDPOINT, httpClient) {
    init {
//        setRequestUrlEncoding(Charsets.UTF_8)
//        setSessionCookieName("VVO-EFA")
    }

    override fun parseLine(
        id: String?, network: String?, mot: String?,
        symbol: String?, name: String?, longName: String?,
        trainType: String?, trainNum: String?, trainName: String?
    ): Line {

        fun generateId(): String {
            return network.hashCode().times(31)
                .plus(mot.hashCode()).times(31)
                .plus(symbol.hashCode()).times(31)
                .plus(name.hashCode()).times(31)
                .plus(longName.hashCode()).times(31)
                .plus(trainType.hashCode()).times(31)
                .plus(trainNum.hashCode()).times(31)
                .plus(trainName.hashCode()).times(31).toString()
        }

        if ("0" == mot) {
            if ("Twoje Linie Kolejowe" == trainName && symbol != null) return Line(
                id ?: generateId(),
                network,
                Product.HIGH_SPEED_TRAIN,
                "TLK$symbol"
            )

            if ("Regionalbahn" == trainName && trainNum == null) return Line(
                id ?: generateId(),
                network,
                Product.REGIONAL_TRAIN,
                null
            )
            if ("Ostdeutsche Eisenbahn GmbH" == longName) return Line(
                id ?: generateId(),
                network,
                Product.REGIONAL_TRAIN,
                "OE"
            )
            if ("Meridian" == longName) return Line(id ?: generateId(), network, Product.REGIONAL_TRAIN, "M")
            if ("trilex" == longName) return Line(id ?: generateId(), network, Product.REGIONAL_TRAIN, "TLX")
            if ("Trilex" == trainName && trainNum == null) return Line(
                id ?: generateId(),
                network,
                Product.REGIONAL_TRAIN,
                "TLX"
            )
            if ("U28" == symbol || "U 28" == symbol) // Nationalparkbahn
                return Line(id ?: generateId(), network, Product.REGIONAL_TRAIN, "U28")
            if ("SB 71" == symbol) // Städtebahn Sachsen
                return Line(id ?: generateId(), network, Product.REGIONAL_TRAIN, "SB71")
            if ("RB 71" == symbol) return Line(id ?: generateId(), network, Product.REGIONAL_TRAIN, "RB71")

            if (symbol != null && P_C_LINE.matches(symbol)) return Line(
                id ?: generateId(),
                network,
                Product.TRAM,
                symbol
            )

            if ("Fernbus" == trainName && trainNum == null) return Line(
                id ?: generateId(),
                network,
                Product.BUS,
                trainName
            )
        }

        return super.parseLine(
            id,
            network,
            mot,
            symbol,
            name,
            longName,
            trainType,
            trainNum,
            trainName
        )
    }

    override suspend fun getStationMapUrl(station: Location): String? {
        return null
    }

    companion object {
        private val API_BASE: Url = Url("https://efa.vvo-online.de/std3/")
        private const val STOP_FINDER_ENDPOINT = "XSLT_STOPFINDER_REQUEST"
        private const val COORD_ENDPOINT = "XSLT_COORD_REQUEST"

        private val P_C_LINE = Regex("C\\d{1,2}")
    }
}
