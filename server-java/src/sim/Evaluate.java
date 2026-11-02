package sim;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A6. Оценка событий по правилам и формирование результата; A6.1 — применение правил к событию.
 * Схемы: UP-004_A6_схема.png, UP-004_A6_1_схема.png; шаги — таблицы 4.9 и 4.10; правила — таблица 4.2.
 * Оценка использует только журнал, сценарий и версию правил, поэтому воспроизводима.
 */
public final class Evaluate {
    private Evaluate() {}

    /** Коды правил в порядке каталога (порядок проверки на конец попытки). */
    static final String[] END_RULES = {
        "NOT_STOPPED", "TARGET_ZONE_MISSED", "WRONG_ORIENTATION", "WRONG_ENTRY_DIRECTION", "ATTEMPT_ABORTED"
    };

    /** Состояние, переносимое от события к событию (блок 2). */
    static final class State {
        boolean speeding = false;
        boolean inZonePrev = false;
        String entryGear = null;
        Map<String, Object> lastSample = null;
    }

    /** Данные, нужные подпрограмме A6.1 (сценарий, правила, упражнение, попытка). */
    static final class Context {
        Map<String, Object> scenario;
        List<Object> enabled;
        String kind;
        Map<String, Object> attempt;
        double[][] targetZone;
        double length, width;
        double speedLimit, speedOver, stopSpeed, headingTol;
        List<Object> permittedSignals;
    }

    // ------------------------------------------------------------------ A4 → A5 → A6

    /** Вызывается компонентом приёма (A4, блок 10) после завершения попытки. */
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

    @SuppressWarnings("unchecked")
    public static Map<String, Object> score(Map<String, Object> attempt, Map<String, Object> exercise,
                                            Map<String, Object> scenario, Map<String, Object> rules,
                                            Completeness.Report report) {
        // блок 1: ввод — параметры метода
        Context ctx = new Context();
        ctx.scenario = scenario;
        ctx.enabled = Json.list(rules.get("enabled_rules"));
        ctx.kind = Json.str(exercise.get("kind"));
        ctx.attempt = attempt;
        ctx.targetZone = Geometry.polygon(scenario.get("target_zone"));
        Map<String, Object> size = Json.map(scenario.get("vehicle_size"));
        ctx.length = Json.num(size.get("length_m"));
        ctx.width = Json.num(size.get("width_m"));
        ctx.speedLimit = Json.num(scenario.get("speed_limit_mps"));
        Map<String, Object> tol = Json.map(scenario.get("tolerances"));
        ctx.speedOver = Json.num(tol.get("speed_over_mps"));
        ctx.stopSpeed = Json.num(tol.get("stop_speed_mps"));
        ctx.headingTol = Json.num(tol.get("heading_deg"));
        ctx.permittedSignals = scenario.get("permitted_signals") == null
            ? new ArrayList<Object>() : Json.list(scenario.get("permitted_signals"));

        // блок 2: начальное состояние
        List<Map<String, Object>> violations = new ArrayList<>();
        State state = new State();

        // блок 3: для каждой непустой ячейки slot по возрастанию event_no
        for (int no = 1; no < report.slot.length; no++) {
            Map<String, Object> e = report.slot[no];
            if (e == null) {
                continue;
            }
            applyRules(ctx, state, e, violations);        // блок 4: A6.1
        }
        // блок 5: правила конца попытки (A6.1, событие END)
        Map<String, Object> end = Json.obj("type", "END", "t_ms", attempt.get("duration_ms"));
        applyRules(ctx, state, end, violations);

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
            "attempt_id", attempt.get("attempt_id"),
            "status", status,
            "finality", finality,
            "missing_batches", missing,
            "violations", new ArrayList<Object>(violations));
    }

    // ------------------------------------------------------------------ A6.1

    /** Подпрограмма A6.1: правила, подходящие событию; проверка условий; ошибки; обновление состояния. */
    static void applyRules(Context ctx, State st, Map<String, Object> e, List<Map<String, Object>> out) {
        String type = Json.str(e.get("type"));
        long t = Json.integer(e.get("t_ms"));
        Map<String, Object> c = "END".equals(type) ? null : Json.map(e.get("context"));
        Object srcNo = "END".equals(type) ? null : e.get("event_no");

        // блок 1: R := правила из enabled_rules, относящиеся к e.type, по каталогу
        String[] rules = rulesFor(type);
        // блоки 2–4: для каждого правила проверить условие и добавить ошибку
        for (String r : rules) {
            if (!ctx.enabled.contains(r)) {
                continue;
            }
            String reason = check(r, ctx, st, c);
            if (reason != null) {
                out.add(Json.obj("type", r, "t_ms", t, "reason", reason, "source_event_no", srcNo));
            }
        }
        // блок 5: обновить состояние по e (после проверки правил)
        if ("POSITION_SAMPLE".equals(type)) {
            st.lastSample = e;
            st.speeding = Json.num(c.get("speed_mps")) > ctx.speedLimit + ctx.speedOver;
            boolean center = Geometry.contains(ctx.targetZone, Json.num(c.get("x")), Json.num(c.get("z")));
            if (center && !st.inZonePrev) {
                st.entryGear = Json.str(c.get("gear"));
            }
            st.inZonePrev = center;
        }
    }

    /** Каталог правил: тип события → коды правил, источником которых он служит (таблица 4.2). */
    static String[] rulesFor(String type) {
        switch (type) {
            case "CONTACT_STARTED": return new String[] {"COLLISION", "BORDER_CONTACT"};
            case "BOUNDARY_CROSSED": return new String[] {"LEFT_AREA"};
            case "STOP_LINE_CROSSED": return new String[] {"STOP_LINE_PROHIBITED"};
            case "POSITION_SAMPLE": return new String[] {"SPEED_LIMIT_EXCEEDED"};
            case "END": return END_RULES;
            default: return new String[0];
        }
    }

    /** Условие правила; возвращает причину (reason) или null, если ошибки нет. */
    private static String check(String rule, Context ctx, State st, Map<String, Object> c) {
        switch (rule) {
            case "COLLISION":
                if ("OBSTACLE".equals(c.get("object_kind"))) {
                    return String.format(Locale.ROOT, "контакт с препятствием на скорости %.1f м/с: объект %s",
                        Json.num(c.get("speed_mps")), c.get("object_id"));
                }
                return null;
            case "BORDER_CONTACT":
                if ("BORDER".equals(c.get("object_kind"))) {
                    return "контакт с границей " + c.get("object_id");
                }
                return null;
            case "LEFT_AREA":
                return "EXIT".equals(c.get("direction")) ? "выезд за границы участка" : null;
            case "STOP_LINE_PROHIBITED":
                if (!ctx.permittedSignals.contains(c.get("signal"))) {
                    return "стоп-линия пересечена при сигнале " + c.get("signal");
                }
                return null;
            case "SPEED_LIMIT_EXCEEDED": {
                double v = Json.num(c.get("speed_mps"));
                if (v > ctx.speedLimit + ctx.speedOver && !st.speeding) {
                    return String.format(Locale.ROOT, "скорость %.1f м/с при пределе %.1f м/с", v, ctx.speedLimit);
                }
                return null;
            }
            default:
                return checkEnd(rule, ctx, st);
        }
    }

    /** Правила конца попытки (событие END). */
    private static String checkEnd(String rule, Context ctx, State st) {
        Map<String, Object> last = st.lastSample == null ? null : Json.map(st.lastSample.get("context"));
        switch (rule) {
            case "NOT_STOPPED":
                if (last != null && Json.num(last.get("speed_mps")) > ctx.stopSpeed) {
                    return String.format(Locale.ROOT, "в конце попытки скорость %.1f м/с: полной остановки нет",
                        Json.num(last.get("speed_mps")));
                }
                return null;
            case "TARGET_ZONE_MISSED": {
                if (last == null) {
                    return "автомобиль вне целевой области";
                }
                double x = Json.num(last.get("x")), z = Json.num(last.get("z"));
                boolean inside;
                if ("SIGNALIZED_INTERSECTION".equals(ctx.kind)) {      // для перекрёстка — центр автомобиля
                    inside = Geometry.contains(ctx.targetZone, x, z);
                } else {                                               // остановка, парковка — весь контур
                    inside = Geometry.allInside(ctx.targetZone, Geometry.vehicleCorners(
                        x, z, Json.num(last.get("heading_deg")), ctx.length, ctx.width));
                }
                return inside ? null : "автомобиль вне целевой области";
            }
            case "WRONG_ORIENTATION": {
                if (last == null || ctx.scenario.get("target_heading_deg") == null) {
                    return null;
                }
                double delta = Geometry.headingDelta(Json.num(last.get("heading_deg")),
                    Json.num(ctx.scenario.get("target_heading_deg")));
                if (delta > ctx.headingTol) {
                    return String.format(Locale.ROOT, "ориентация отличается от заданной на %.1f градусов", delta);
                }
                return null;
            }
            case "WRONG_ENTRY_DIRECTION":
                if (st.entryGear != null && !"REVERSE".equals(st.entryGear)) {
                    return "заезд в парковочное место выполнен не задним ходом";
                }
                return null;
            case "ATTEMPT_ABORTED":
                return "ABORTED".equals(ctx.attempt.get("finish_reason")) ? "попытка прервана обучающимся" : null;
            default:
                return null;
        }
    }
}
