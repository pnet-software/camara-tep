package gr.pnet.camara.locationverification.delegate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

import gr.pnet.camara.locationverification.delegate.LocationMapper.GeoCircle;
import gr.pnet.camara.locationverification.delegate.LocationMapper.Verification;
import gr.pnet.camara.locationverification.generated.status.model.VerificationResult;
import gr.pnet.tepclient.model.TepGeoPoint;
import gr.pnet.tepclient.model.TepSubscriberLocation;

class LocationMapperTest {

    private static final double LAT = 37.9838;
    private static final double LON = 23.7275;

    @Test
    void coverageIsTheCellsCentreAndRadius() {
        assertThat(LocationMapper.coverage(location(new TepGeoPoint().lat(LAT).lon(LON), 500.0)))
                .contains(new GeoCircle(LAT, LON, 500));
    }

    // TEP leaves out the centre and radius of a cell that isn't registered with it
    @Test
    void coverageIsEmptyWithoutAUsableCentreOrRadius() {
        assertThat(LocationMapper.coverage(new TepSubscriberLocation().cellId("99950-000000210"))).isEmpty();
        assertThat(LocationMapper.coverage(location(null, 500.0))).isEmpty();
        assertThat(LocationMapper.coverage(location(new TepGeoPoint().lat(LAT).lon(LON), null))).isEmpty();
        assertThat(LocationMapper.coverage(location(new TepGeoPoint().lon(LON), 500.0))).isEmpty();
        assertThat(LocationMapper.coverage(location(new TepGeoPoint().lat(LAT), 500.0))).isEmpty();
        assertThat(LocationMapper.coverage(location(new TepGeoPoint().lat(95.0).lon(LON), 500.0))).isEmpty();
        assertThat(LocationMapper.coverage(location(new TepGeoPoint().lat(LAT).lon(190.0), 500.0))).isEmpty();
        assertThat(LocationMapper.coverage(location(new TepGeoPoint().lat(LAT).lon(LON), -1.0))).isEmpty();
        assertThat(LocationMapper.coverage(location(new TepGeoPoint().lat(LAT).lon(LON), Double.NaN))).isEmpty();
    }

    @Test
    void distanceIsAlongTheEarthsSurface() {
        assertThat(LocationMapper.distance(circle(0, 0, 0), circle(1, 0, 0))).isCloseTo(111_195, within(1.0));
        assertThat(LocationMapper.distance(circle(LAT, LON, 0), circle(LAT, LON, 0))).isZero();
    }

    @Test
    void cellWithinTheAreaIsTrue() {
        assertThat(verify(circle(LAT, LON, 500), circle(LAT, LON, 2000)))
                .isEqualTo(new Verification(VerificationResult.TRUE, null));
    }

    @Test
    void cellAwayFromTheAreaIsFalse() {
        assertThat(verify(circle(LAT, LON, 500), north(circle(LAT, LON, 2000), 3000)))
                .isEqualTo(new Verification(VerificationResult.FALSE, null));
    }

    // The cell is the network's estimate, so an area inside a larger cell is only a partial match
    @Test
    void areaWithinALargerCellIsPartial() {
        assertThat(verify(circle(LAT, LON, 1000), circle(LAT, LON, 500)))
                .isEqualTo(new Verification(VerificationResult.PARTIAL, 25));
    }

    // Two circles of radius r whose centres are r apart share 39% of their area
    @Test
    void overlappingCirclesArePartialWithTheShareOfTheCellInside() {
        assertThat(verify(circle(LAT, LON, 1000), north(circle(LAT, LON, 1000), 1000)))
                .isEqualTo(new Verification(VerificationResult.PARTIAL, 39));
    }

    @Test
    void matchRateStaysWithinOneAndNinetyNine() {
        assertThat(verify(circle(LAT, LON, 10_000), circle(LAT, LON, 1)).matchRate()).isEqualTo(1);
        assertThat(verify(circle(LAT, LON, 1000), north(circle(LAT, LON, 1000), 1)).matchRate()).isEqualTo(99);
    }

    @Test
    void cellWithNoRadiusIsAPoint() {
        assertThat(verify(circle(LAT, LON, 0), north(circle(LAT, LON, 2000), 1999)).result())
                .isEqualTo(VerificationResult.TRUE);
        assertThat(verify(circle(LAT, LON, 0), north(circle(LAT, LON, 2000), 2001)).result())
                .isEqualTo(VerificationResult.FALSE);
    }

    private static Verification verify(GeoCircle cell, GeoCircle area) {
        return LocationMapper.verify(cell, area);
    }

    private static GeoCircle circle(double latitude, double longitude, double radius) {
        return new GeoCircle(latitude, longitude, radius);
    }

    // The same circle moved the given distance north, along its meridian
    private static GeoCircle north(GeoCircle circle, double metres) {
        return new GeoCircle(circle.latitude() + Math.toDegrees(metres / LocationMapper.EARTH_RADIUS),
                circle.longitude(), circle.radius());
    }

    private static TepSubscriberLocation location(TepGeoPoint center, Double radius) {
        return new TepSubscriberLocation().cellId("99950-000000210").center(center).radius(radius);
    }
}
