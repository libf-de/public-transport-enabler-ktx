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

package de.libf.ptek.live;

import static org.hamcrest.CoreMatchers.hasItem;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import java.util.Date;

import org.junit.Test;

import de.libf.ptek.VmobilProvider;
import de.libf.ptek.dto.Location;
import de.libf.ptek.dto.LocationType;
import de.libf.ptek.dto.NearbyLocationsResult;
import de.libf.ptek.dto.Point;
import de.libf.ptek.dto.QueryDeparturesResult;
import de.libf.ptek.dto.QueryTripsResult;
import de.libf.ptek.dto.SuggestLocationsResult;

/**
 * @author Andreas Schildbach
 */
public class VmobilProviderLiveTest extends AbstractProviderLiveTest {
    public VmobilProviderLiveTest() {
        super(new VmobilProvider(secretProperty("vmobil.api_authorization")));
    }

    @Test
    public void nearbyStationsByCoordinate() throws Exception {
        final NearbyLocationsResult result = queryNearbyStations(Location.coord(48207355, 16370602));
        print(result);
    }

    @Test
    public void nearbyStationsByCoordinateSalzburg() throws Exception {
        final NearbyLocationsResult result = queryNearbyStations(Location.coord(47809195, 13054919));
        print(result);
    }

    @Test
    public void queryDepartures() throws Exception {
        final QueryDeparturesResult result = queryDepartures("480082200", 0, false);
        print(result);
        assertEquals(QueryDeparturesResult.Status.OK, result.status);
    }

    @Test
    public void queryDeparturesSalzburg() throws Exception {
        final QueryDeparturesResult result = queryDepartures("455000200", false);
        print(result);
    }

    @Test
    public void queryDeparturesInvalidStation() throws Exception {
        final QueryDeparturesResult result = queryDepartures("999999", 0, false);
        assertEquals(QueryDeparturesResult.Status.INVALID_STATION, result.status);
    }

    @Test
    public void suggestLocations() throws Exception {
        final SuggestLocationsResult result = suggestLocations("Feldkirch");
        print(result);
    }

    @Test
    public void suggestLocationsAddress() throws Exception {
        final SuggestLocationsResult result = suggestLocations("Mutterstraße 4, 6800 Feldkirch");
        print(result);
    }

    @Test
    public void suggestLocationsEncoding() throws Exception {
        final SuggestLocationsResult result = suggestLocations("Wien Schönbrunn");
        assertEquals("Wien Schönbrunn", result.getLocations().get(0).name);
        print(result);
    }

    @Test
    public void suggestLocationsCoverageFeldkirch() throws Exception {
        final SuggestLocationsResult result = suggestLocations("Feldkirch Bahnhof");
        print(result);
        assertThat(result.getLocations(), hasItem(new Location(LocationType.STATION, "480081700")));
    }

    @Test
    public void suggestLocationsCoverageStrasswalchen() throws Exception {
        final SuggestLocationsResult result = suggestLocations("Straßwalchen West");
        print(result);
        assertThat(result.getLocations(), hasItem(new Location(LocationType.STATION, "455648300")));
    }

    @Test
    public void suggestLocationsCoverageSchwarzach() throws Exception {
        final SuggestLocationsResult result = suggestLocations("Schwarzach Abtsdorf");
        print(result);
        assertThat(result.getLocations(), hasItem(new Location(LocationType.STATION, "455661400")));
    }

    @Test
    public void suggestLocationsCoverageTrimmelkam() throws Exception {
        final SuggestLocationsResult result = suggestLocations("Trimmelkam");
        print(result);
        assertThat(result.getLocations(), hasItem(new Location(LocationType.STATION, "900018413")));
    }

    @Test
    public void shortTripFeldkirch() throws Exception {
        final Location from = new Location(LocationType.STATION, "480082200", null, "Feldkirch Katzenturm");
        final Location to = new Location(LocationType.STATION, "480081700", null, "Feldkirch Bahnhof");
        final QueryTripsResult result = queryTrips(from, null, to, new Date(), true, null);
        print(result);
        final QueryTripsResult laterResult = queryMoreTrips(result.context, true);
        print(laterResult);
        final QueryTripsResult laterResult2 = queryMoreTrips(laterResult.context, true);
        print(laterResult2);
        final QueryTripsResult earlierResult = queryMoreTrips(result.context, false);
        print(earlierResult);
    }

    @Test
    public void shortTripWien() throws Exception {
        final Location from = new Location(LocationType.STATION, "490132000", null, "Wien Stephansplatz");
        final Location to = new Location(LocationType.STATION, "490024500", null, "Wien Stubentor");
        final QueryTripsResult result = queryTrips(from, null, to, new Date(), true, null);
        print(result);
        final QueryTripsResult laterResult = queryMoreTrips(result.context, true);
        print(laterResult);
        final QueryTripsResult laterResult2 = queryMoreTrips(laterResult.context, true);
        print(laterResult2);
        final QueryTripsResult earlierResult = queryMoreTrips(result.context, false);
        print(earlierResult);
    }

    @Test
    public void shortTripSalzburg() throws Exception {
        final Location from = new Location(LocationType.STATION, "455000900", Point.from1E6(47808976, 13056409),
                "Salzburg", "Vogelweiderstraße");
        final Location to = new Location(LocationType.STATION, "455084400", Point.from1E6(47811556, 13050278),
                "Salzburg", "Merianstraße");
        final QueryTripsResult result = queryTrips(from, null, to, new Date(), true, null);
        print(result);
        assertEquals(QueryTripsResult.Status.OK, result.status);
        assertTrue(result.trips.size() > 0);
        final QueryTripsResult laterResult = queryMoreTrips(result.context, true);
        print(laterResult);
        final QueryTripsResult later2Result = queryMoreTrips(laterResult.context, true);
        print(later2Result);
        final QueryTripsResult earlierResult = queryMoreTrips(later2Result.context, false);
        print(earlierResult);
    }

    @Test
    public void tripAddressToStation() throws Exception {
        final Location from = new Location(LocationType.ADDRESS,
                "A=2@O=6800 Feldkirch, Kapfweg 6@X=9585539@Y=47239257@U=103@L=980092305@B=1@p=1437727591@",
                "6800 Feldkirch", "Kapfweg 6");
        final Location to = new Location(LocationType.STATION, "480081700", null, "Feldkirch Bahnhof");
        final QueryTripsResult result = queryTrips(from, null, to, new Date(), true, null);
        print(result);
    }

    @Test
    public void tripCoordinateToStation() throws Exception {
        final Location from = Location.coord(47238096, 9585581);
        final Location to = new Location(LocationType.STATION, "480081700", null, "Feldkirch Bahnhof");
        final QueryTripsResult result = queryTrips(from, null, to, new Date(), true, null);
        print(result);
    }
}
