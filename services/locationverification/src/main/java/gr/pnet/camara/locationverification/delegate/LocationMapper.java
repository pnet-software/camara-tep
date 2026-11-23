package gr.pnet.camara.locationverification.delegate;

import java.util.Optional;

import gr.pnet.camara.locationverification.generated.status.model.VerificationResult;
import gr.pnet.tepclient.model.TepGeoPoint;
import gr.pnet.tepclient.model.TepSubscriberLocation;

/**
 * Maps the TEP cell a device is in onto a CAMARA location verification. The cell's coverage circle is the
 * network's estimate of where the device is, and is compared with the circle the consumer asked about.
 */
final class LocationMapper {

    // Mean Earth radius (IUGG), in metres
    static final double EARTH_RADIUS = 6_371_008.8;

    private LocationMapper() {
    }

    /** A circle on the Earth's surface, its radius in metres */
    record GeoCircle(double latitude, double longitude, double radius) {
    }

    /** {@code matchRate} only with {@code PARTIAL} */
    record Verification(VerificationResult result, Integer matchRate) {
    }

    // Empty when TEP gives no usable centre or radius for the cell: it gives neither for a cell not registered with it
    static Optional<GeoCircle> coverage(TepSubscriberLocation location) {
        TepGeoPoint center = location.getCenter();
        Double radius = location.getRadius();
        if (center == null || radius == null || !Double.isFinite(radius) || radius < 0) {
            return Optional.empty();
        }
        Double latitude = center.getLat();
        Double longitude = center.getLon();
        if (latitude == null || longitude == null || !(Math.abs(latitude) <= 90) || !(Math.abs(longitude) <= 180)) {
            return Optional.empty();
        }
        return Optional.of(new GeoCircle(latitude, longitude, radius));
    }

    // TRUE when the cell lies within the area, FALSE when they don't meet. Otherwise PARTIAL, including a cell
    // larger than the area, with the share of the cell inside it, (area ∩ cell) / cell, as the spec's matchRate.
    static Verification verify(GeoCircle cell, GeoCircle area) {
        double d = distance(cell, area);
        if (d + cell.radius() <= area.radius()) {
            return new Verification(VerificationResult.TRUE, null);
        }
        if (d >= cell.radius() + area.radius()) {
            return new Verification(VerificationResult.FALSE, null);
        }
        double share = overlap(cell.radius(), area.radius(), d) / (Math.PI * cell.radius() * cell.radius());
        // The spec allows 1 to 99: a sliver of overlap is still PARTIAL, and so is a near-complete one
        int matchRate = (int) Math.max(1, Math.min(99, Math.round(share * 100)));
        return new Verification(VerificationResult.PARTIAL, matchRate);
    }

    // Great-circle distance between the centres, in metres (haversine)
    static double distance(GeoCircle a, GeoCircle b) {
        double dLat = Math.toRadians(b.latitude() - a.latitude());
        double dLon = Math.toRadians(b.longitude() - a.longitude());
        double h = Math.pow(Math.sin(dLat / 2), 2) + Math.cos(Math.toRadians(a.latitude()))
                * Math.cos(Math.toRadians(b.latitude())) * Math.pow(Math.sin(dLon / 2), 2);
        return 2 * EARTH_RADIUS * Math.asin(Math.min(1, Math.sqrt(h)));
    }

    // Area shared by two circles whose centres are d apart, with d < r1 + r2. Treats them as flat, which is
    // close enough at cell sizes.
    private static double overlap(double r1, double r2, double d) {
        if (d <= Math.abs(r1 - r2)) {
            double r = Math.min(r1, r2);
            return Math.PI * r * r;
        }
        double a1 = r1 * r1 * Math.acos(cosine((d * d + r1 * r1 - r2 * r2) / (2 * d * r1)));
        double a2 = r2 * r2 * Math.acos(cosine((d * d + r2 * r2 - r1 * r1) / (2 * d * r2)));
        double kite = 0.5 * Math.sqrt(Math.max(0, (-d + r1 + r2) * (d + r1 - r2) * (d - r1 + r2) * (d + r1 + r2)));
        return a1 + a2 - kite;
    }

    // Keeps rounding errors from pushing a cosine outside acos's domain
    private static double cosine(double value) {
        return Math.max(-1, Math.min(1, value));
    }
}
