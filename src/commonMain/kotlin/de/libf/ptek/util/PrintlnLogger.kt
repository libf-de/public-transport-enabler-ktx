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

package de.libf.ptek.util

class PrintlnLogger : AbstractLogger {
    override fun log(tag: String, message: String) {
        println("[I] $tag: $message")
    }

    override fun warn(tag: String, message: String) {
        println("[W] $tag: $message")
    }

    override fun error(tag: String, message: String) {
        println("[E] $tag: $message")
    }
}