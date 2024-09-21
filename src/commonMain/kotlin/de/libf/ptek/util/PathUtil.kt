package de.libf.ptek.util

import de.libf.ptek.dto.Location
import de.libf.ptek.dto.Point
import de.libf.ptek.dto.Stop

class PathUtil {
    companion object {
        fun interpolatePath(departure: Location, intermediateStops: List<Stop>?, arrival: Location): List<Point> {
            val path = mutableListOf<Point>()

            if (departure.hasLocation) {
                path.add(Point.fromDouble(departure.latAsDouble, departure.lonAsDouble))
            }

            if (intermediateStops != null) {
                intermediateStops.filter {
                    it.location.hasLocation
                }.forEach {
                    path.add(Point.fromDouble(it.location.latAsDouble, it.location.lonAsDouble))
                }
            }

            if (arrival.hasLocation) {
                path.add(Point.fromDouble(arrival.latAsDouble, arrival.lonAsDouble))
            }

            return path.toList()
        }
    }
}