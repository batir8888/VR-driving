package sim;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Один экземпляр обслуживает одну оценку журнала, включая повторный расчёт. */
final class ScoringSession {
    private final AttemptFacts facts;
    private final Map<String, List<AssessmentRule>> rulesByEvent = new HashMap<>();

    ScoringSession(AttemptFacts facts, List<Object> enabled) {
        this.facts = facts;
        for (AssessmentRule rule : AssessmentRule.CATALOG) {
            if (enabled.contains(rule.code)) {
                rulesByEvent.computeIfAbsent(rule.eventType, key -> new ArrayList<>()).add(rule);
            }
        }
    }

    Map<String, Object> score(Completeness.Report report) {
        List<Map<String, Object>> violations = new ArrayList<>();
        for (int no = 1; no < report.slot.length; no++) {
            Map<String, Object> event = report.slot[no];
            if (event != null) {
                apply(event, violations);
            }
        }
        apply(Json.obj("type", "END", "t_ms", facts.attempt.get("duration_ms")), violations);
        // блок 6: violation_no по порядку добавления
        for (int i = 0; i < violations.size(); i++) {
            Map<String, Object> v = violations.get(i);
            Map<String, Object> numbered = Json.obj("violation_no", (long) (i + 1));
            numbered.putAll(v);
            violations.set(i, numbered);
        }

        // блоки 7–9: статус
        String status = violations.isEmpty() ? "ACHIEVED" : "NOT_ACHIEVED";
        // блоки 10–12: окончательность
        String finality = report.complete ? "FINAL" : "PRELIMINARY";
        List<Object> missing = new ArrayList<>();
        if (!report.complete) {
            missing.addAll(report.missingBatches);
        }
        return Json.obj(
            "attempt_id", facts.attempt.get("attempt_id"),
            "status", status,
            "finality", finality,
            "missing_batches", missing,
            "violations", new ArrayList<Object>(violations));
    }

    private void apply(Map<String, Object> event, List<Map<String, Object>> violations) {
        String type = Json.str(event.get("type"));
        Map<String, Object> context = "END".equals(type) ? null : Json.map(event.get("context"));
        for (AssessmentRule rule : rulesByEvent.getOrDefault(type, Collections.emptyList())) {
            String reason = rule.reason(facts, context);
            if (reason != null) {
                violations.add(Json.obj("type", rule.code, "t_ms", Json.integer(event.get("t_ms")),
                    "reason", reason, "source_event_no", event.get("event_no")));
            }
        }
        if ("POSITION_SAMPLE".equals(type)) {
            facts.observePosition(context);
        }
    }
}
