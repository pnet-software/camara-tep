package gr.pnet.camara.qualityondemand.session;

import java.util.List;

import gr.pnet.camara.qualityondemand.generated.status.model.PortsSpec;
import gr.pnet.camara.qualityondemand.generated.status.model.PortsSpecRangesInner;

/**
 * Ports of one end of a session's flow: single ports and ranges, either possibly empty. No ports at all means any.
 */
public record Ports(List<Integer> ports, List<Range> ranges) {

    public record Range(int from, int to) {
    }

    public Ports {
        ports = ports == null ? List.of() : List.copyOf(ports);
        ranges = ranges == null ? List.of() : List.copyOf(ranges);
    }

    static final Ports ANY = new Ports(List.of(), List.of());

    public static Ports from(PortsSpec spec) {
        if (spec == null) {
            return ANY;
        }
        List<Range> ranges = spec.getRanges() == null ? List.of()
                : spec.getRanges().stream().map(r -> new Range(r.getFrom(), r.getTo())).toList();
        return new Ports(spec.getPorts(), ranges);
    }

    public boolean any() {
        return ports.isEmpty() && ranges.isEmpty();
    }

    // Null for any ports, as the CAMARA field is then left out
    public PortsSpec toSpec() {
        if (any()) {
            return null;
        }
        PortsSpec spec = new PortsSpec();
        if (!ports.isEmpty()) {
            spec.ports(ports);
        }
        if (!ranges.isEmpty()) {
            spec.ranges(ranges.stream().map(r -> new PortsSpecRangesInner(r.from(), r.to())).toList());
        }
        return spec;
    }
}
