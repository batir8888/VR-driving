package sim;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Условия упражнения и наблюдения, накопленные за один проход по журналу. */
final class AttemptFacts {
    final Map<String, Object> attempt;
    final String kind;
    final double[][] targetZone;
    final double length, width, speedLimit, speedOver, stopSpeed, headingTol;
    final Double targetHeading;
    final List<Object> permittedSignals;

    private boolean inZone;
    boolean speeding;
    String entryGear;
    Map<String, Object> lastPosition;

    AttemptFacts(Map<String, Object> attempt, Map<String, Object> exercise, Map<String, Object> scenario) {
        this.attempt = attempt;
        kind = Json.str(exercise.get("kind"));
        targetZone = Geometry.polygon(scenario.get("target_zone"));
        Map<String, Object> size = Json.map(scenario.get("vehicle_size"));
        length = Json.num(size.get("length_m"));
        width = Json.num(size.get("width_m"));
        speedLimit = Json.num(scenario.get("speed_limit_mps"));
        Map<String, Object> tolerance = Json.map(scenario.get("tolerances"));
        speedOver = Json.num(tolerance.get("speed_over_mps"));
        stopSpeed = Json.num(tolerance.get("stop_speed_mps"));
        headingTol = Json.num(tolerance.get("heading_deg"));
        targetHeading = scenario.get("target_heading_deg") == null ? null : Json.num(scenario.get("target_heading_deg"));
        permittedSignals = scenario.get("permitted_signals") == null
            ? Collections.emptyList() : Json.list(scenario.get("permitted_signals"));
    }

    boolean exceedsLimit(double speed) {
        return speed > speedLimit + speedOver;
    }

    void observePosition(Map<String, Object> position) {
        lastPosition = position;
        speeding = exceedsLimit(Json.num(position.get("speed_mps")));
        boolean inside = Geometry.contains(targetZone, Json.num(position.get("x")), Json.num(position.get("z")));
        if (inside && !inZone) {
            entryGear = Json.str(position.get("gear"));
        }
        inZone = inside;
    }
}
