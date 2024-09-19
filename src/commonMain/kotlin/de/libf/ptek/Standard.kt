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

import de.libf.ptek.dto.Product
import de.libf.ptek.dto.Style
import de.libf.ptek.dto.Style.Shape

/**
 * @author Andreas Schildbach
 */
object Standard {
    val COLOR_BACKGROUND_HIGH_SPEED_TRAIN: Int = Style.WHITE
    val COLOR_BACKGROUND_REGIONAL_TRAIN: Int = Style.GRAY
    val COLOR_BACKGROUND_SUBURBAN_TRAIN: Int = Style.parseColor("#006e34")
    val COLOR_BACKGROUND_SUBWAY: Int = Style.parseColor("#003090")
    val COLOR_BACKGROUND_TRAM: Int = Style.parseColor("#cc0000")
    val COLOR_BACKGROUND_BUS: Int = Style.parseColor("#993399")
    val COLOR_BACKGROUND_ON_DEMAND: Int = Style.parseColor("#00695c")
    val COLOR_BACKGROUND_FERRY: Int = Style.BLUE

    val STYLES: Map<Product?, Style> = mapOf(
        Product.HIGH_SPEED_TRAIN to Style(
            shape = Shape.RECT,
            backgroundColor = COLOR_BACKGROUND_HIGH_SPEED_TRAIN,
            foregroundColor = Style.RED,
            borderColor = Style.RED
        ),

        Product.REGIONAL_TRAIN to Style(
            shape = Shape.RECT,
            backgroundColor = COLOR_BACKGROUND_REGIONAL_TRAIN,
            foregroundColor = Style.WHITE
        ),

        Product.SUBURBAN_TRAIN to Style(
            shape = Shape.CIRCLE,
            backgroundColor = COLOR_BACKGROUND_SUBURBAN_TRAIN,
            foregroundColor = Style.WHITE
        ),

        Product.SUBWAY to Style(
            shape = Shape.RECT,
            backgroundColor = COLOR_BACKGROUND_SUBWAY,
            foregroundColor = Style.WHITE
        ),

        Product.TRAM to Style(
            shape = Shape.RECT,
            backgroundColor = COLOR_BACKGROUND_TRAM,
            foregroundColor = Style.WHITE
        ),

        Product.BUS to Style(
            backgroundColor = COLOR_BACKGROUND_BUS,
            foregroundColor = Style.WHITE
        ),

        Product.ON_DEMAND to Style(
            backgroundColor = COLOR_BACKGROUND_ON_DEMAND,
            foregroundColor = Style.WHITE
        ),

        Product.FERRY to Style(
            shape = Shape.CIRCLE,
            backgroundColor = COLOR_BACKGROUND_FERRY,
            foregroundColor = Style.WHITE
        ),

        Product.UNKNOWN to Style(
            backgroundColor = Style.DKGRAY,
            foregroundColor = Style.WHITE
        ),

        null to Style(
            backgroundColor = Style.DKGRAY,
            foregroundColor = Style.WHITE
        )

    )
}
