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
enum class Product(val c: Char) {
    HIGH_SPEED_TRAIN('I'),
    REGIONAL_TRAIN('R'),
    SUBURBAN_TRAIN('S'),
    SUBWAY('U'),
    TRAM('T'),
    BUS('B'),
    FERRY('F'),
    CABLECAR('C'),
    ON_DEMAND('P'),
    FOOTWAY('W'),
    TRANSFER('T'),
    SECURE_CONNECTION('E'),
    DO_NOT_CHANGE('D'),
    UNKNOWN('?');

    companion object {
        val ALL = Product.entries.toSet()

        fun fromCode(c: Char): Product = Product.entries.find { it.c == c } ?: throw IllegalArgumentException("Unknown product code: $c")
        fun fromCodes(c: CharArray): Set<Product> = c.map { fromCode(it) }.toSet()
        fun Set<Product>.toCodes(): CharArray = this.map { it.c }.toCharArray()
    }
}