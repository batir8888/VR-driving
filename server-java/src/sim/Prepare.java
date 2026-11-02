package sim;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * A1. Подготовка попытки с одинаковыми исходными условиями — серверная часть (блоки 1–6).
 * Клиентская часть (блоки 7–10) — client-csharp/Restore.cs.
 * Схема: UP-004/UP-004_A1_схема.png, шаги — таблица 4.4.
 */
public final class Prepare {
    private Prepare() {}

    /** Обязательные поля сценария (блок 4). */
    static final String[] REQUIRED_SCENARIO_FIELDS = {
        "task_text", "start_pose", "travel_direction", "speed_limit_mps", "vehicle_size", "area_bounds",
        "target_zone", "tolerances", "sample_period_ms", "road_objects"
    };

    /**
     * @param scenarioVersion номер версии сценария или null (блок 3: взять наибольший)
     * @param rulesVersion    номер версии правил или null
     * @param startedAt       момент создания (передаётся вызывающим, чтобы проверки были воспроизводимы)
     * @return ok=true: attempt_id, scenario, title, travel_direction...; ok=false: code и message (отказ)
     */
    public static Map<String, Object> createAttempt(Path dataDir, Path store, String learnerId, String exerciseId,
                                                    Long scenarioVersion, Long rulesVersion, String startedAt) {
        // блок 1: ввод learner_id, exercise_id, [scenario_version], [rules_version] — параметры метода

        // блок 2: обучающийся и упражнение найдены?
        Path exDir = dataDir.resolve("exercises").resolve(String.valueOf(exerciseId));
        Map<String, Object> learner = findLearner(dataDir, learnerId);
        if (learner == null || !Files.isRegularFile(exDir.resolve("exercise.json"))) {
            return refusal(404, "неизвестный обучающийся или упражнение");
        }
        Map<String, Object> exercise = Json.map(Storage.read(exDir.resolve("exercise.json")));

        // блок 3: выбрать версии — указанные либо с наибольшим номером
        long sv = scenarioVersion != null ? scenarioVersion : maxVersion(Storage.versions(exDir, "scenario"));
        long rv = rulesVersion != null ? rulesVersion : maxVersion(Storage.versions(exDir, "rules"));

        // блок 4: версии найдены, условия сценария полны?
        Path scenarioFile = exDir.resolve("scenario-" + sv + ".json");
        Path rulesFile = exDir.resolve("rules-" + rv + ".json");
        if (!Files.isRegularFile(scenarioFile) || !Files.isRegularFile(rulesFile)) {
            return refusal(422, "сценарий недоступен или неполон");
        }
        Map<String, Object> scenario = Json.map(Storage.read(scenarioFile));
        for (String field : REQUIRED_SCENARIO_FIELDS) {
            if (scenario.get(field) == null) {
                return refusal(422, "сценарий недоступен или неполон");
            }
        }

        // блок 5: создать попытку (версии копируются и далее не меняются), запись атомарная
        String attemptId = nextAttemptId(store);
        Map<String, Object> attempt = Json.obj(
            "attempt_id", attemptId,
            "learner_id", learnerId,
            "exercise_id", exerciseId,
            "scenario_version", sv,
            "rules_version", rv,
            "started_at", startedAt,
            "state", "IN_PROGRESS",
            "duration_ms", null,
            "finish_reason", null,
            "declared_batch_count", null);
        Storage.writeAtomic(Storage.attemptDir(store, attemptId).resolve("attempt.json"), attempt);

        // блок 6: передать клиенту attempt_id и условия сценария
        return Json.obj(
            "ok", Boolean.TRUE,
            "code", 201L,
            "attempt_id", attemptId,
            "title", exercise.get("title"),
            "scenario", scenario);
    }

    private static Map<String, Object> findLearner(Path dataDir, String learnerId) {
        Path f = dataDir.resolve("learners.json");
        if (learnerId == null || !Files.isRegularFile(f)) {
            return null;
        }
        List<Object> all = Json.list(Storage.read(f));
        for (Object o : all) {
            Map<String, Object> l = Json.map(o);
            if (learnerId.equals(l.get("learner_id"))) {
                return l;
            }
        }
        return null;
    }

    private static long maxVersion(List<Long> versions) {
        return versions.isEmpty() ? -1 : versions.get(versions.size() - 1);
    }

    private static Map<String, Object> refusal(long code, String message) {
        return Json.obj("ok", Boolean.FALSE, "code", code, "message", message);
    }

    /** a-0001, a-0002, ... — детерминированные идентификаторы для воспроизводимых проверок. */
    private static String nextAttemptId(Path store) {
        int max = 0;
        for (String id : Storage.attemptIds(store)) {
            if (id.startsWith("a-")) {
                try {
                    max = Math.max(max, Integer.parseInt(id.substring(2)));
                } catch (NumberFormatException ignored) {
                    // чужой каталог
                }
            }
        }
        return String.format(java.util.Locale.ROOT, "a-%04d", max + 1);
    }
}
