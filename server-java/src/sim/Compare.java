package sim;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * A7. Сравнение попыток при совпадении версий (компонент хранения; интерфейс показывает результат).
 * Схема: UP-004_A7_схема.png, шаги — таблица 4.11.
 */
public final class Compare {
    private Compare() {}

    public static Map<String, Object> compare(Path dataDir, Path store, String attemptIdB, String attemptIdA) {
        // блок 1: принять attempt_id_B, [attempt_id_A]
        Map<String, Object> b = loadAttempt(store, attemptIdB);
        if (b == null) {
            return Json.obj("code", 404L, "comparable", Boolean.FALSE, "reason", "NOT_FOUND", "message", "попытка не найдена");
        }
        Map<String, Object> a = null;
        boolean explicit = attemptIdA != null;
        List<Map<String, Object>> others = new ArrayList<>();   // P
        if (explicit) {                                          // блок 2: attempt_id_A задан?
            a = loadAttempt(store, attemptIdA);                  // блок 3: загрузить A
            if (a == null) {
                return Json.obj("code", 404L, "comparable", Boolean.FALSE, "reason", "NOT_FOUND", "message", "попытка не найдена");
            }
            // блок 5: версии A и B совпадают?
            if (!sameVersions(a, b)) {
                return notComparable("VERSIONS_DIFFER", "версии различаются: " + versionNote(dataDir, a, b), a, b, null);
            }
        } else {
            // блок 4: P := попытки обучающегося по упражнению, созданные раньше B
            for (String id : Storage.attemptIds(store)) {
                Map<String, Object> p = loadAttempt(store, id);
                if (p != null && p.get("learner_id").equals(b.get("learner_id"))
                    && p.get("exercise_id").equals(b.get("exercise_id"))
                    && earlier(p, b)) {
                    others.add(p);
                }
            }
            Map<String, Object> best = null;
            for (Map<String, Object> p : others) {               // блок 6: для каждой попытки p из P
                // блок 7: версии p совпадают с версиями B и p позже best?
                if (sameVersions(p, b) && (best == null || earlier(best, p))) {
                    best = p;                                    // блок 8
                }
            }
            if (best == null) {                                  // блок 9: best найдена?
                if (!others.isEmpty()) {                         // блок 10: P непуст?
                    Map<String, Object> last = others.get(others.size() - 1);
                    return notComparable("VERSIONS_DIFFER", "версии изменены: " + versionNote(dataDir, last, b), last, b, null);
                }
                return notComparable("FIRST_ATTEMPT", "первая попытка, сравнивать не с чем", null, b, null);
            }
            a = best;
        }
        // блок 11: результаты A и B окончательные?
        Map<String, Object> ra = loadResult(store, Json.str(a.get("attempt_id")));
        Map<String, Object> rb = loadResult(store, Json.str(b.get("attempt_id")));
        if (ra == null || rb == null || !"FINAL".equals(ra.get("finality")) || !"FINAL".equals(rb.get("finality"))) {
            List<Object> missing = new ArrayList<>();
            if (ra != null) { missing.addAll(Json.list(ra.get("missing_batches"))); }
            if (rb != null) { missing.addAll(Json.list(rb.get("missing_batches"))); }
            Map<String, Object> r = notComparable("PRELIMINARY", "результат предварительный", a, b, null);
            r.put("missing_batches", missing);
            return r;
        }
        // блоки 12–13: подсчёт ошибок по типам для каждой стороны
        Map<String, Long> cntA = countByType(ra);
        Map<String, Long> cntB = countByType(rb);
        // блок 14: разности B − A по типам, длительности и статусу
        Map<String, Object> diff = new LinkedHashMap<>();
        for (String type : new TreeSet<String>() {{ addAll(cntA.keySet()); addAll(cntB.keySet()); }}) {
            diff.put(type, cntB.getOrDefault(type, 0L) - cntA.getOrDefault(type, 0L));
        }
        // блок 15: вернуть сравнение
        return Json.obj(
            "code", 200L,
            "comparable", Boolean.TRUE,
            "attempt_a", a.get("attempt_id"),
            "attempt_b", b.get("attempt_id"),
            "duration_diff_ms", Json.integer(b.get("duration_ms")) - Json.integer(a.get("duration_ms")),
            "status_a", ra.get("status"),
            "status_b", rb.get("status"),
            "violations_diff", diff,
            "violations_a", ra.get("violations"),
            "violations_b", rb.get("violations"));
    }

    private static Map<String, Object> notComparable(String reason, String message,
                                                    Map<String, Object> a, Map<String, Object> b, Object extra) {
        return Json.obj("code", 200L, "comparable", Boolean.FALSE, "reason", reason, "message", message);
    }

    private static boolean sameVersions(Map<String, Object> x, Map<String, Object> y) {
        return x.get("exercise_id").equals(y.get("exercise_id"))
            && x.get("scenario_version").equals(y.get("scenario_version"))
            && x.get("rules_version").equals(y.get("rules_version"));
    }

    /** x создана раньше y: по started_at, при равенстве — по идентификатору. */
    private static boolean earlier(Map<String, Object> x, Map<String, Object> y) {
        int c = Json.str(x.get("started_at")).compareTo(Json.str(y.get("started_at")));
        return c < 0 || (c == 0 && Json.str(x.get("attempt_id")).compareTo(Json.str(y.get("attempt_id"))) < 0);
    }

    /** Номера версий обеих попыток и описания отличий (change_note) для сообщения пользователю. */
    private static String versionNote(Path dataDir, Map<String, Object> a, Map<String, Object> b) {
        StringBuilder sb = new StringBuilder();
        sb.append("сценарий ").append(a.get("scenario_version")).append(" и ").append(b.get("scenario_version"))
          .append(", правила ").append(a.get("rules_version")).append(" и ").append(b.get("rules_version"));
        Path exDir = dataDir.resolve("exercises").resolve(Json.str(b.get("exercise_id")));
        appendNote(sb, exDir, "scenario", b.get("scenario_version"), a.get("scenario_version"), "сценарий");
        appendNote(sb, exDir, "rules", b.get("rules_version"), a.get("rules_version"), "правила");
        return sb.toString();
    }

    private static void appendNote(StringBuilder sb, Path exDir, String prefix, Object vb, Object va, String label) {
        if (vb.equals(va)) {
            return;
        }
        Path f = exDir.resolve(prefix + "-" + vb + ".json");
        if (Files.isRegularFile(f)) {
            Object note = Json.map(Storage.read(f)).get("change_note");
            if (note != null) {
                sb.append(". Изменение (").append(label).append(" ").append(vb).append("): ").append(note);
            }
        }
    }

    private static Map<String, Long> countByType(Map<String, Object> result) {
        Map<String, Long> cnt = new LinkedHashMap<>();
        for (Object o : Json.list(result.get("violations"))) {
            cnt.merge(Json.str(Json.map(o).get("type")), 1L, Long::sum);
        }
        return cnt;
    }

    private static Map<String, Object> loadAttempt(Path store, String id) {
        Path f = Storage.attemptDir(store, id).resolve("attempt.json");
        return Files.isRegularFile(f) ? Json.map(Storage.read(f)) : null;
    }

    private static Map<String, Object> loadResult(Path store, String id) {
        Path f = Storage.attemptDir(store, id).resolve("result.json");
        return Files.isRegularFile(f) ? Json.map(Storage.read(f)) : null;
    }
}
