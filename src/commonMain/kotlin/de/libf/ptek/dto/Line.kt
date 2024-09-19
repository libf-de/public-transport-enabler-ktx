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
data class Line(
    val id: String,
    val network: String? = null,
    val product: Product = Product.UNKNOWN,
    val label: String? = null,
    val name: String? = null,
    val style: Style? = null,
    val attributes: Set<Attr>? = null,
    val message: String? = null,
    val altName: String? = null
) : Comparable<Line> {
    @Serializable
    enum class Attr { CIRCLE_CLOCKWISE, CIRCLE_ANTICLOCKWISE, SERVICE_REPLACEMENT, LINE_AIRPORT, WHEEL_CHAIR_ACCESS, BICYCLE_CARRIAGE }

    companion object {
        val FOOTWAY = Line(id = "FOOTWAY", product = Product.FOOTWAY)
        val TRANSFER = Line(id = "TRANSFER", product = Product.TRANSFER)
        val SECURE_CONNECTION = Line(id = "SECURE_CONNECTION", product = Product.SECURE_CONNECTION)
        val DO_NOT_CHANGE = Line(id = "DO_NOT_CHANGE", product = Product.DO_NOT_CHANGE)
        val INVALID = Line(id = "INVALID", product = Product.UNKNOWN)

        val DEFAULT_LINE_COLOR = Style.RED
    }

    val productCode: Char
        get() = product.c

    fun hasAttr(attr: Attr): Boolean {
        return attributes?.contains(attr) ?: false
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Line) return false

        if(!other.network.equals(this.network)) return false
        if(other.product != this.product) return false
        if(!other.label.equals(this.label)) return false

        return true
    }

    override fun hashCode(): Int {
        return network.hashCode()
            .times(31).plus(product.hashCode())
            .times(31).plus(label.hashCode())
    }

    private fun compareNullableStrings(a: String?, b: String?): Int {
        return when {
            a == b -> 0
            a == null -> 1
            b == null -> -1
            else -> a.compareTo(b)
        }
    }

    private fun compareNullableEnums(a: Product?, b: Product?): Int {
        return when {
            a == b -> 0
            a == null -> 1
            b == null -> -1
            else -> a.ordinal.compareTo(b.ordinal)
        }
    }


    override fun compareTo(other: Line): Int {
        val networkComparison = compareNullableStrings(this.network, other.network)
        if (networkComparison != 0) return networkComparison

        // Compare product
        val productComparison = compareNullableEnums(this.product, other.product)
        if (productComparison != 0) return productComparison

        // Compare label
        return compareNullableStrings(this.label, other.label)
    }
}