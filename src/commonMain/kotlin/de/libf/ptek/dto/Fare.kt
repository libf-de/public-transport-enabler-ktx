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
data class Fare(
    val name: String,
    val type: Type,
    val currency: String,
    val fare: Float,
    val unitName: String?,
    val units: String?
) {
    enum class Type { ADULT, CHILD, YOUTH, STUDENT, MILITARY, SENIOR, DISABLED, BIKE }
}