package sim;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Правило возвращает пояснение ошибки либо null, если условие соблюдено. */
abstract class AssessmentRule {
    final String eventType;
    final String code;

    AssessmentRule(String eventType, String code) {
        this.eventType = eventType;
        this.code = code;
    }

    abstract String reason(AttemptFacts facts, Map<String, Object> context);

    // Порядок каталога задаёт порядок ошибок, выявленных по одному событию.
    static final List<AssessmentRule> CATALOG = java.util.Collections.unmodifiableList(Arrays.asList(
        new ObstacleContact(), new BorderContact(), new LeftArea(), new StopSignal(), new SpeedEpisode(),
        new FullStop(), new TargetZone(), new ParkingHeading(), new EntryDirection(), new Aborted()));

    static final class ObstacleContact extends AssessmentRule {
        ObstacleContact() {
            super("CONTACT_STARTED", "COLLISION");
        }

        @Override
        String reason(AttemptFacts facts, Map<String, Object> context) {
            if ("OBSTACLE".equals(context.get("object_kind"))) {
                return String.format(Locale.ROOT, "контакт с препятствием на скорости %.1f м/с: объект %s",
                    Json.num(context.get("speed_mps")), context.get("object_id"));
            }
            return null;
        }
    }

    static final class BorderContact extends AssessmentRule {
        BorderContact() {
            super("CONTACT_STARTED", "BORDER_CONTACT");
        }

        @Override
        String reason(AttemptFacts facts, Map<String, Object> context) {
            if ("BORDER".equals(context.get("object_kind"))) {
                return "контакт с границей " + context.get("object_id");
            }
            return null;
        }
    }

    static final class LeftArea extends AssessmentRule {
        LeftArea() {
            super("BOUNDARY_CROSSED", "LEFT_AREA");
        }

        @Override
        String reason(AttemptFacts facts, Map<String, Object> context) {
            return "EXIT".equals(context.get("direction")) ? "выезд за границы участка" : null;
        }
    }

    static final class StopSignal extends AssessmentRule {
        StopSignal() {
            super("STOP_LINE_CROSSED", "STOP_LINE_PROHIBITED");
        }

        @Override
        String reason(AttemptFacts facts, Map<String, Object> context) {
            if (!facts.permittedSignals.contains(context.get("signal"))) {
                return "стоп-линия пересечена при сигнале " + context.get("signal");
            }
            return null;
        }
    }

    static final class SpeedEpisode extends AssessmentRule {
        SpeedEpisode() {
            super("POSITION_SAMPLE", "SPEED_LIMIT_EXCEEDED");
        }

        @Override
        String reason(AttemptFacts facts, Map<String, Object> context) {
            double v = Json.num(context.get("speed_mps"));
            if (facts.exceedsLimit(v) && !facts.speeding) {
                return String.format(Locale.ROOT, "скорость %.1f м/с при пределе %.1f м/с", v, facts.speedLimit);
            }
            return null;
        }
    }

    static final class FullStop extends AssessmentRule {
        FullStop() {
            super("END", "NOT_STOPPED");
        }

        @Override
        String reason(AttemptFacts facts, Map<String, Object> context) {
            Map<String, Object> last = facts.lastPosition;
            if (last != null && Json.num(last.get("speed_mps")) > facts.stopSpeed) {
                return String.format(Locale.ROOT, "в конце попытки скорость %.1f м/с: полной остановки нет",
                    Json.num(last.get("speed_mps")));
            }
            return null;
        }
    }

    static final class TargetZone extends AssessmentRule {
        TargetZone() {
            super("END", "TARGET_ZONE_MISSED");
        }

        @Override
        String reason(AttemptFacts facts, Map<String, Object> context) {
            Map<String, Object> last = facts.lastPosition;
            if (last == null) {
                return "автомобиль вне целевой области";
            }
            double x = Json.num(last.get("x")), z = Json.num(last.get("z"));
            boolean inside;
            if ("SIGNALIZED_INTERSECTION".equals(facts.kind)) {      // для перекрёстка — центр автомобиля
                inside = Geometry.contains(facts.targetZone, x, z);
            } else {                                               // остановка, парковка — весь контур
                inside = Geometry.allInside(facts.targetZone, Geometry.vehicleCorners(
                    x, z, Json.num(last.get("heading_deg")), facts.length, facts.width));
            }
            return inside ? null : "автомобиль вне целевой области";
        }
    }

    static final class ParkingHeading extends AssessmentRule {
        ParkingHeading() {
            super("END", "WRONG_ORIENTATION");
        }

        @Override
        String reason(AttemptFacts facts, Map<String, Object> context) {
            Map<String, Object> last = facts.lastPosition;
            if (last == null || facts.targetHeading == null) {
                return null;
            }
            double delta = Geometry.headingDelta(Json.num(last.get("heading_deg")),
                facts.targetHeading);
            if (delta > facts.headingTol) {
                return String.format(Locale.ROOT, "ориентация отличается от заданной на %.1f градусов", delta);
            }
            return null;
        }
    }

    static final class EntryDirection extends AssessmentRule {
        EntryDirection() {
            super("END", "WRONG_ENTRY_DIRECTION");
        }

        @Override
        String reason(AttemptFacts facts, Map<String, Object> context) {
            if (facts.entryGear != null && !"REVERSE".equals(facts.entryGear)) {
                return "заезд в парковочное место выполнен не задним ходом";
            }
            return null;
        }
    }

    static final class Aborted extends AssessmentRule {
        Aborted() {
            super("END", "ATTEMPT_ABORTED");
        }

        @Override
        String reason(AttemptFacts facts, Map<String, Object> context) {
            return "ABORTED".equals(facts.attempt.get("finish_reason")) ? "попытка прервана обучающимся" : null;
        }
    }
}
