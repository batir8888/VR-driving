package sim;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;

/**
 * A6. Оценка событий по правилам и формирование результата; A6.1 — применение правил к событию.
 * Схемы: UP-004_A6_схема.png, UP-004_A6_1_схема.png; шаги — таблицы 4.9 и 4.10; правила — таблица 4.2.
 * Оценка использует только журнал, сценарий и версию правил, поэтому воспроизводима.
 */
public final class Evaluate {
    private Evaluate() {}

    // ------------------------------------------------------------------ A4 → A5 → A6

    /** Вызывается компонентом приёма (A4, блок 12) после завершения попытки. */
    public static Map<String, Object> evaluateAttempt(Path dataDir, Path store, String attemptId) {
        Path dir = Storage.attemptDir(store, attemptId);
        Map<String, Object> attempt = Json.map(Storage.read(dir.resolve("attempt.json")));
        Path logFile = dir.resolve("log.json");
        Map<String, Object> log = Files.isRegularFile(logFile)
            ? Json.map(Storage.read(logFile))
            : Json.obj("attempt_id", attemptId, "batches", new ArrayList<Object>(), "events", new ArrayList<Object>());

        Completeness.Report report = Completeness.check(attempt, log);        // A5
        if (!report.finished) {
            return null;                                                       // попытка не завершена
        }
        String exerciseId = Json.str(attempt.get("exercise_id"));
        Path exDir = dataDir.resolve("exercises").resolve(exerciseId);
        Map<String, Object> exercise = Json.map(Storage.read(exDir.resolve("exercise.json")));
        Map<String, Object> scenario = Json.map(Storage.read(exDir.resolve("scenario-" + attempt.get("scenario_version") + ".json")));
        Map<String, Object> rules = Json.map(Storage.read(exDir.resolve("rules-" + attempt.get("rules_version") + ".json")));

        Map<String, Object> result = score(attempt, exercise, scenario, rules, report);     // A6
        Storage.writeAtomic(dir.resolve("result.json"), result);                            // блок 13
        return view(result, exercise, attempt);                                             // блок 14
    }

    /** Представление результата для клиента: добавляет название упражнения и длительность (блок 14). */
    public static Map<String, Object> view(Map<String, Object> result, Map<String, Object> exercise, Map<String, Object> attempt) {
        Map<String, Object> v = Json.obj(
            "attempt_id", result.get("attempt_id"),
            "exercise_title", exercise.get("title"),
            "duration_ms", attempt.get("duration_ms"),
            "status", result.get("status"),
            "finality", result.get("finality"),
            "missing_batches", result.get("missing_batches"),
            "violations", result.get("violations"));
        return v;
    }

    // ------------------------------------------------------------------ A6

    public static Map<String, Object> score(Map<String, Object> attempt, Map<String, Object> exercise,
                                            Map<String, Object> scenario, Map<String, Object> rules,
                                            Completeness.Report report) {
        ScoringSession session = new ScoringSession(new AttemptFacts(attempt, exercise, scenario),
            Json.list(rules.get("enabled_rules")));
        return session.score(report);
    }
}
