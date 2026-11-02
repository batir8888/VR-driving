package sim;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A4. Приём сообщения сервером и запись в журнал (компоненты приёма и хранения).
 * Схема: UP-004_A4_схема.png, шаги — таблица 4.7. Формат сообщений — docs/message-format.md.
 */
public final class Receive {
    private Receive() {}

    static final Set<String> EVENT_TYPES = new HashSet<>(Arrays.asList(
        "POSITION_SAMPLE", "CONTACT_STARTED", "STOP_LINE_CROSSED", "BOUNDARY_CROSSED"));

    /**
     * @return code (200/400/404), status (ACCEPTED/DUPLICATE) и message; при попытке оценки — result
     */
    public static Map<String, Object> receive(Path dataDir, Path store, String attemptId, Map<String, Object> msg) {
        // блок 1: принять сообщение: attempt_id, вид, данные группы или завершения
        // блок 2: попытка с таким attempt_id существует?
        Path dir = Storage.attemptDir(store, String.valueOf(attemptId));
        Path attemptFile = dir.resolve("attempt.json");
        if (!Files.isRegularFile(attemptFile)) {
            return reply(404, "NOT_FOUND", "попытка не найдена");
        }
        Map<String, Object> attempt = Json.map(Storage.read(attemptFile));
        String kind = String.valueOf(msg.get("kind"));

        // блок 3: вид сообщения — BATCH?
        if (!"BATCH".equals(kind)) {
            if (!"FINISH".equals(kind)) {
                return reply(400, "BAD_FORMAT", "неизвестный вид сообщения");
            }
            // блок 4: попытка уже FINISHED?
            if ("FINISHED".equals(attempt.get("state"))) {
                return reply(200, "DUPLICATE", "повтор: запись не создана");
            }
            // блок 6: записать state = FINISHED, duration_ms, finish_reason, declared_batch_count
            try {
                attempt.put("duration_ms", Json.integer(msg.get("duration_ms")));
                attempt.put("finish_reason", Json.str(msg.get("finish_reason")));
                attempt.put("declared_batch_count", Json.integer(msg.get("declared_batch_count")));
            } catch (IllegalArgumentException ex) {
                return reply(400, "BAD_FORMAT", "неверный формат сообщения о завершении");
            }
            attempt.put("state", "FINISHED");
            Storage.writeAtomic(attemptFile, attempt);
            return evaluateAndReply(dataDir, store, attemptId);   // блок 10
        }

        // блок 5: формат верен?
        String problem = validateBatch(msg);
        if (problem != null) {
            return reply(400, "BAD_FORMAT", problem);
        }
        long number = Json.integer(msg.get("batch_number"));
        List<Object> events = Json.list(msg.get("events"));
        Path logFile = dir.resolve("log.json");
        Map<String, Object> log = Files.isRegularFile(logFile)
            ? Json.map(Storage.read(logFile))
            : Json.obj("attempt_id", attemptId, "batches", new ArrayList<Object>(), "events", new ArrayList<Object>());

        // блок 7: группа с таким batch_number уже есть в журнале?
        for (Object b : Json.list(log.get("batches"))) {
            if (Json.integer(Json.map(b).get("batch_number")) == number) {
                return reply(200, "DUPLICATE", "повтор: запись не создана");
            }
        }
        // блок 8: дописать группу и её события в журнал (атомарно)
        Json.list(log.get("batches")).add(Json.obj("batch_number", number, "event_count", (long) events.size()));
        for (Object o : events) {
            Map<String, Object> e = Json.map(o);
            Json.list(log.get("events")).add(Json.obj(
                "event_no", e.get("event_no"),
                "batch_number", number,
                "t_ms", e.get("t_ms"),
                "type", e.get("type"),
                "context", e.get("context")));
        }
        Storage.writeAtomic(logFile, log);

        // блок 9: state = FINISHED?
        if ("FINISHED".equals(attempt.get("state"))) {
            return evaluateAndReply(dataDir, store, attemptId);   // блок 10: повторная оценка
        }
        return reply(200, "ACCEPTED", "принято");
    }

    /** Блок 10: подпрограмма «Оценить попытку» — A5 и A6. */
    private static Map<String, Object> evaluateAndReply(Path dataDir, Path store, String attemptId) {
        Map<String, Object> result = Evaluate.evaluateAttempt(dataDir, store, attemptId);
        Map<String, Object> r = reply(200, "ACCEPTED", "принято");
        r.put("result", result);
        return r;
    }

    private static Map<String, Object> reply(long code, String status, String message) {
        return Json.obj("code", code, "status", status, "message", message);
    }

    /** Блок 5: batch_number ≥ 1, event_count = числу событий, типы и контекст допустимы; один проход по списку. */
    static String validateBatch(Map<String, Object> msg) {
        try {
            if (Json.integer(msg.get("batch_number")) < 1) {
                return "batch_number должен быть не меньше 1";
            }
            List<Object> events = Json.list(msg.get("events"));
            if (events.isEmpty() || Json.integer(msg.get("event_count")) != events.size()) {
                return "event_count не совпадает с числом событий или группа пуста";
            }
            for (Object o : events) {
                Map<String, Object> e = Json.map(o);
                if (Json.integer(e.get("event_no")) < 1 || Json.integer(e.get("t_ms")) < 0) {
                    return "event_no и t_ms: недопустимые значения";
                }
                String type = Json.str(e.get("type"));
                if (!EVENT_TYPES.contains(type)) {
                    return "недопустимый тип события " + type;
                }
                String bad = validateContext(type, Json.map(e.get("context")));
                if (bad != null) {
                    return bad;
                }
            }
            return null;
        } catch (IllegalArgumentException ex) {
            return "неверный формат группы: " + ex.getMessage();
        }
    }

    private static String validateContext(String type, Map<String, Object> c) {
        switch (type) {
            case "POSITION_SAMPLE":
                Json.num(c.get("x")); Json.num(c.get("z")); Json.num(c.get("heading_deg")); Json.num(c.get("speed_mps"));
                return oneOf(c.get("gear"), "gear", "FORWARD", "REVERSE");
            case "CONTACT_STARTED":
                Json.str(c.get("object_id")); Json.num(c.get("speed_mps"));
                return oneOf(c.get("object_kind"), "object_kind", "OBSTACLE", "BORDER");
            case "STOP_LINE_CROSSED":
                return oneOf(c.get("signal"), "signal", "GREEN", "YELLOW", "RED");
            default: // BOUNDARY_CROSSED
                return oneOf(c.get("direction"), "direction", "ENTER", "EXIT");
        }
    }

    private static String oneOf(Object value, String field, String... allowed) {
        return Arrays.asList(allowed).contains(value) ? null : "недопустимое значение поля " + field;
    }
}
