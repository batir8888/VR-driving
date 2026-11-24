package checks;

import sim.Compare;
import sim.Completeness;
import sim.Evaluate;
import sim.HttpApi;
import sim.Json;
import sim.Prepare;
import sim.Receive;
import sim.Storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Проверки серверных алгоритмов A1, A4, A5, A6 (с A6.1), A7 на подготовленных данных, без VR-сцены и HTTP.
 * Ожидаемые результаты заданы в самих случаях (по определению случая), а не получены из прежнего запуска.
 * Таблица планируемых случаев — UP-004, таблица 4.13. Запуск: см. code/README.md.
 */
public final class ServerChecks {
    private static int total = 0;
    private static int failed = 0;
    private static int clock = 0;
    private static final Path OUT = Paths.get("out", "checks");
    private static final Path SAMPLES = Paths.get("samples", "journals");

    private ServerChecks() {}

    public static void main(String[] args) throws IOException {
        Path data = Paths.get("data");
        deleteTree(OUT);
        Path dataMain = copyData(data, OUT.resolve("data-main"));
        Path dataV = copyData(data, OUT.resolve("data-variants"));
        addVariants(dataV, false);
        Path dataBad = copyData(data, OUT.resolve("data-incomplete"));
        addVariants(dataBad, true);

        checkA1(dataMain, dataV, dataBad);
        checkA5();
        checkA6(dataMain);
        checkA4(dataMain);
        checkA5A6(dataMain);
        checkA7(dataV);
        checkRestart(dataMain);
        checkHttp(dataMain);

        System.out.println();
        System.out.println("Серверные проверки: выполнено " + total + ", ошибок " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ================================================================= A1

    private static void checkA1(Path dataMain, Path dataV, Path dataBad) {
        System.out.println("--- A1. Подготовка попытки (сервер, блоки 1–6) ---");
        Path store = fresh("a1");
        Map<String, Object> r = Prepare.createAttempt(dataMain, store, "L-001", "stop-zone", null, null, now());
        check("A1 версии не заданы: создана попытка с версиями 1/1",
            Boolean.TRUE.equals(r.get("ok")) && attemptField(store, "a-0001", "scenario_version").equals(1L)
                && attemptField(store, "a-0001", "state").equals("IN_PROGRESS"), Json.write(r.get("attempt_id")));
        check("A1 клиенту переданы условия сценария (start_pose, task_text)",
            Json.map(r.get("scenario")).get("start_pose") != null && Json.map(r.get("scenario")).get("task_text") != null, "");

        Map<String, Object> rv = Prepare.createAttempt(dataV, store, "L-001", "stop-zone", null, null, now());
        check("A1 версии не заданы: берутся наибольшие (сценарий 2, правила 2)",
            attemptField(store, str(rv.get("attempt_id")), "scenario_version").equals(2L)
                && attemptField(store, str(rv.get("attempt_id")), "rules_version").equals(2L), "");
        Map<String, Object> rv1 = Prepare.createAttempt(dataV, store, "L-001", "stop-zone", 1L, 1L, now());
        check("A1 версии заданы явно: берутся указанные (1/1)",
            attemptField(store, str(rv1.get("attempt_id")), "scenario_version").equals(1L), "");

        int before = Storage.attemptIds(store).size();
        Map<String, Object> bad1 = Prepare.createAttempt(dataMain, store, "L-001", "no-such", null, null, now());
        Map<String, Object> bad2 = Prepare.createAttempt(dataMain, store, "L-999", "stop-zone", null, null, now());
        Map<String, Object> bad3 = Prepare.createAttempt(dataBad, store, "L-001", "stop-zone", null, null, now());
        Map<String, Object> bad4 = Prepare.createAttempt(dataMain, store, "L-001", "stop-zone", 7L, null, now());
        check("A1 неизвестное упражнение: отказ 404", Json.integer(bad1.get("code")) == 404L, str(bad1.get("message")));
        check("A1 неизвестный обучающийся: отказ 404", Json.integer(bad2.get("code")) == 404L, str(bad2.get("message")));
        check("A1 неполный сценарий (нет target_zone): отказ 422", Json.integer(bad3.get("code")) == 422L, str(bad3.get("message")));
        check("A1 несуществующая версия сценария: отказ 422", Json.integer(bad4.get("code")) == 422L, str(bad4.get("message")));
        check("A1 при отказах попытки не создаются", Storage.attemptIds(store).size() == before, "");
    }

    // ================================================================= A5

    private static void checkA5() {
        System.out.println("--- A5. Проверка полноты журнала ---");
        // полный журнал: 2 группы, 5 событий
        Completeness.Report full = Completeness.check(attemptMap("FINISHED", 2L), logMap(
            new long[][] {{1, 3}, {2, 2}}, new long[][] {{1, 1}, {1, 2}, {1, 3}, {2, 4}, {2, 5}}));
        check("A5 полный журнал: complete, пропусков нет", full.complete && full.missingBatches.isEmpty() && full.maxEventNo == 5, "");
        // пропущена группа 2
        Completeness.Report lost = Completeness.check(attemptMap("FINISHED", 3L), logMap(
            new long[][] {{1, 2}, {3, 2}}, new long[][] {{1, 1}, {1, 2}, {3, 5}, {3, 6}}));
        check("A5 пропущена группа 2: не complete, missing_batches = [2]",
            !lost.complete && lost.missingBatches.equals(Arrays.asList(2L)), String.valueOf(lost.missingBatches));
        check("A5 события после пропуска остаются в slot по номеру (5, 6), ячейки 3–4 пусты",
            lost.slot[5] != null && lost.slot[6] != null && lost.slot[3] == null && lost.slot[4] == null, "");
        // неверное число событий в группе
        Completeness.Report cut = Completeness.check(attemptMap("FINISHED", 2L), logMap(
            new long[][] {{1, 3}, {2, 2}}, new long[][] {{1, 1}, {1, 2}, {2, 4}, {2, 5}}));
        check("A5 группа 1 объявила 3 события, пришло 2: missing_batches = [1]",
            !cut.complete && cut.missingBatches.equals(Arrays.asList(1L)), String.valueOf(cut.missingBatches));
        // пропуск в нумерации событий при полных группах
        Completeness.Report gap = Completeness.check(attemptMap("FINISHED", 1L), logMap(
            new long[][] {{1, 3}}, new long[][] {{1, 1}, {1, 2}, {1, 4}}));
        check("A5 пропуск в нумерации (1, 2, 4): не complete при пустом missing_batches",
            !gap.complete && gap.missingBatches.isEmpty() && gap.eventCount == 3 && gap.maxEventNo == 4, "");
        // повтор номера события
        Completeness.Report dup = Completeness.check(attemptMap("FINISHED", 1L), logMap(
            new long[][] {{1, 2}}, new long[][] {{1, 1}, {1, 1}}));
        check("A5 повтор номера события: не complete", !dup.complete, "");
        // номер группы вне объявленного диапазона
        Completeness.Report range = Completeness.check(attemptMap("FINISHED", 1L), logMap(
            new long[][] {{1, 1}, {4, 1}}, new long[][] {{1, 1}, {4, 2}}));
        check("A5 группа 4 вне объявленного диапазона 1..1: не complete", !range.complete, "");
        // попытка не завершена
        Completeness.Report open = Completeness.check(attemptMap("IN_PROGRESS", null), logMap(new long[][] {{1, 1}}, new long[][] {{1, 1}}));
        check("A5 попытка не завершена: оценка невозможна", !open.finished, "");
    }

    // ================================================================= A6

    /** Случай оценки: упражнение, журнал, причина завершения, ожидаемый перечень ошибок (по порядку). */
    private static final class Case {
        String name, exercise, finish;
        List<Map<String, Object>> events = new ArrayList<>();
        String[] expected;
        long duration;
    }

    private static void checkA6(Path data) {
        System.out.println("--- A6 (с A6.1). Оценка по правилам: по каждому правилу каталога правильный случай и ошибка ---");
        List<Case> cases = a6Cases();
        for (Case c : cases) {
            Path store = fresh("a6-" + c.name);
            String id = runCase(data, store, c, 4, false);
            Map<String, Object> result = result(store, id);
            String got = types(result);
            String want = String.join(",", c.expected);
            boolean statusOk = c.expected.length == 0
                ? "ACHIEVED".equals(result.get("status")) : "NOT_ACHIEVED".equals(result.get("status"));
            check("A6 " + c.exercise + "/" + c.name + ": ошибки [" + want + "], статус " + result.get("status") + ", " + result.get("finality"),
                got.equals(want) && statusOk && "FINAL".equals(result.get("finality")), "получено [" + got + "]");
            // reason и время у каждой ошибки
            boolean explained = true;
            for (Object o : Json.list(result.get("violations"))) {
                Map<String, Object> v = Json.map(o);
                explained &= v.get("reason") != null && !v.get("reason").toString().isEmpty() && v.get("t_ms") != null;
            }
            check("A6 " + c.exercise + "/" + c.name + ": у каждой ошибки есть тип, время и причина", explained, "");
            // воспроизводимость и независимость от порядка поступления групп
            Path store2 = fresh("a6r-" + c.name);
            String id2 = runCase(data, store2, c, 4, true);
            boolean same = fileText(store, id, "result.json").equals(fileText(store2, id2, "result.json").replace(id2, id));
            check("A6 " + c.exercise + "/" + c.name + ": обратный порядок поступления групп даёт тот же result.json", same, "");
            writeSample(c);
        }
        // типовая нагрузка на одну попытку: причины в результате
        Path s = fresh("a6-reasons");
        Case red = cases.stream().filter(c -> c.name.equals("red_signal")).findFirst().get();
        Map<String, Object> res = result(s, runCase(data, s, red, 4, false));
        String reason = str(Json.map(Json.list(res.get("violations")).get(0)).get("reason"));
        check("A6 причина красного сигнала: «" + reason + "»", reason.equals("стоп-линия пересечена при сигнале RED"), "");
    }

    private static List<Case> a6Cases() {
        List<Case> list = new ArrayList<>();
        // ---- остановка в заданной зоне (stop-zone): зона z 40..46, x ±1.75
        list.add(stop("stop_ok", "COMPLETED", c -> {
            pos(c, 0, 0, 0, 0, 0, "FORWARD"); pos(c, 3000, 0, 20, 0, 8.0, "FORWARD");
            pos(c, 6000, 0, 42, 0, 4.0, "FORWARD"); pos(c, 8000, 0, 43, 0, 0.0, "FORWARD");
        }, 8000));
        list.add(stop("stopped_outside_zone", "COMPLETED", c -> {
            pos(c, 0, 0, 0, 0, 0, "FORWARD"); pos(c, 3000, 0, 20, 0, 8.0, "FORWARD"); pos(c, 6000, 0, 30, 0, 0.0, "FORWARD");
        }, 6000, "TARGET_ZONE_MISSED"));
        list.add(stop("partly_outside_zone", "COMPLETED", c -> {
            pos(c, 0, 0, 0, 0, 0, "FORWARD"); pos(c, 6000, 0, 44.5, 0, 0.0, "FORWARD");   // нос за пределом зоны: 44.5+2.25 > 46
        }, 6000, "TARGET_ZONE_MISSED"));
        list.add(stop("not_stopped_in_zone", "COMPLETED", c -> {
            pos(c, 0, 0, 0, 0, 0, "FORWARD"); pos(c, 6000, 0, 43, 0, 3.0, "FORWARD");
        }, 6000, "NOT_STOPPED"));
        list.add(stop("speed_at_stop_threshold", "COMPLETED", c -> {
            pos(c, 0, 0, 0, 0, 0, "FORWARD"); pos(c, 6000, 0, 43, 0, 0.3, "FORWARD");   // 0.3 = допуск: остановка
        }, 6000));
        list.add(stop("one_long_contact", "COMPLETED", c -> {
            pos(c, 0, 0, 0, 0, 0, "FORWARD"); contact(c, 2000, "cone-1", "OBSTACLE", 2.0);
            pos(c, 2200, 0, 21, 0, 2.0, "FORWARD"); pos(c, 2400, 0, 21.4, 0, 2.0, "FORWARD");   // контакт продолжается, событий нет
            pos(c, 8000, 0, 43, 0, 0.0, "FORWARD");
        }, 8000, "COLLISION"));
        list.add(stop("two_contacts", "COMPLETED", c -> {
            pos(c, 0, 0, 0, 0, 0, "FORWARD"); contact(c, 2000, "cone-1", "OBSTACLE", 2.0);
            contact(c, 4000, "cone-2", "OBSTACLE", 1.5); pos(c, 8000, 0, 43, 0, 0.0, "FORWARD");
        }, 8000, "COLLISION", "COLLISION"));
        list.add(stop("left_area_and_returned", "COMPLETED", c -> {
            pos(c, 0, 0, 0, 0, 0, "FORWARD"); boundary(c, 2000, "EXIT"); boundary(c, 3000, "ENTER");
            pos(c, 8000, 0, 43, 0, 0.0, "FORWARD");
        }, 8000, "LEFT_AREA"));
        list.add(stop("aborted_while_moving", "ABORTED", c -> {
            pos(c, 0, 0, 0, 0, 0, "FORWARD"); pos(c, 4000, 0, 20, 0, 5.0, "FORWARD");
        }, 4000, "NOT_STOPPED", "TARGET_ZONE_MISSED", "ATTEMPT_ABORTED"));
        list.add(stop("no_position_record", "COMPLETED", c -> {
            contact(c, 1000, "cone-1", "OBSTACLE", 1.0);
        }, 5000, "COLLISION", "TARGET_ZONE_MISSED"));
        // ---- перекрёсток (intersection): конечная зона z 60..70, x ±3
        list.add(inter("green_ok", "COMPLETED", c -> {
            pos(c, 0, 0, 0, 0, 0, "FORWARD"); pos(c, 3000, 0, 20, 0, 8.0, "FORWARD");
            stopLine(c, 4500, "GREEN"); pos(c, 6000, 0, 40, 0, 8.0, "FORWARD"); pos(c, 9000, 0, 65, 0, 5.0, "FORWARD");
        }, 9000));
        list.add(inter("red_signal", "COMPLETED", c -> {
            pos(c, 0, 0, 0, 0, 0, "FORWARD"); pos(c, 14000, 0, 20, 0, 8.0, "FORWARD");
            stopLine(c, 15500, "RED"); pos(c, 20000, 0, 65, 0, 5.0, "FORWARD");
        }, 20000, "STOP_LINE_PROHIBITED"));
        list.add(inter("yellow_signal", "COMPLETED", c -> {
            pos(c, 0, 0, 0, 0, 0, "FORWARD"); stopLine(c, 8500, "YELLOW"); pos(c, 12000, 0, 65, 0, 5.0, "FORWARD");
        }, 12000, "STOP_LINE_PROHIBITED"));
        list.add(inter("one_continuous_speeding", "COMPLETED", c -> {
            pos(c, 0, 0, 0, 0, 0, "FORWARD");
            for (int i = 1; i <= 5; i++) { pos(c, 1000 * i, 0, 10 * i, 0, 9.5, "FORWARD"); }   // 5 записей подряд выше предела
            pos(c, 9000, 0, 65, 0, 5.0, "FORWARD");
        }, 9000, "SPEED_LIMIT_EXCEEDED"));
        list.add(inter("two_speeding_episodes", "COMPLETED", c -> {
            pos(c, 0, 0, 0, 0, 0, "FORWARD");
            pos(c, 1000, 0, 10, 0, 9.5, "FORWARD"); pos(c, 2000, 0, 20, 0, 9.5, "FORWARD");
            pos(c, 3000, 0, 28, 0, 8.0, "FORWARD");
            pos(c, 4000, 0, 40, 0, 9.0, "FORWARD");
            pos(c, 9000, 0, 65, 0, 5.0, "FORWARD");
        }, 9000, "SPEED_LIMIT_EXCEEDED", "SPEED_LIMIT_EXCEEDED"));
        list.add(inter("speed_at_limit_plus_tolerance", "COMPLETED", c -> {
            pos(c, 0, 0, 0, 0, 0, "FORWARD"); pos(c, 2000, 0, 20, 0, 8.8, "FORWARD"); pos(c, 9000, 0, 65, 0, 5.0, "FORWARD");   // 8.3 + 0.5 — не превышение
        }, 9000));
        list.add(inter("obstacle_contact", "COMPLETED", c -> {
            pos(c, 0, 0, 0, 0, 0, "FORWARD"); contact(c, 5000, "obstacle-1", "OBSTACLE", 6.0);
            pos(c, 9000, 0, 65, 0, 5.0, "FORWARD");
        }, 9000, "COLLISION"));
        list.add(inter("did_not_reach_end_zone", "COMPLETED", c -> {
            pos(c, 0, 0, 0, 0, 0, "FORWARD"); pos(c, 9000, 0, 50, 0, 5.0, "FORWARD");
        }, 9000, "TARGET_ZONE_MISSED"));
        list.add(inter("left_area", "COMPLETED", c -> {
            pos(c, 0, 0, 0, 0, 0, "FORWARD"); boundary(c, 3000, "EXIT"); pos(c, 9000, 0, 65, 0, 5.0, "FORWARD");
        }, 9000, "LEFT_AREA"));
        // ---- парковка задним ходом (reverse-parking): место z -10.5..-4.5, x ±1.5, ориентация 0°±10°
        list.add(park("parked_ok", "COMPLETED", c -> {
            pos(c, 0, 0, 3, 0, 0, "REVERSE"); pos(c, 3000, 0, -2, 0, 1.5, "REVERSE"); pos(c, 6000, 0, -7.5, 0, 0.0, "REVERSE");
        }, 6000));
        list.add(park("heading_12_degrees", "COMPLETED", c -> {
            pos(c, 0, 0, 3, 0, 0, "REVERSE"); pos(c, 6000, 0, -7.5, 12, 0.0, "REVERSE");
        }, 6000, "WRONG_ORIENTATION"));
        list.add(park("heading_at_tolerance", "COMPLETED", c -> {
            pos(c, 0, 0, 3, 0, 0, "REVERSE"); pos(c, 6000, 0, -7.5, 10, 0.0, "REVERSE");   // ровно 10° — допуск
        }, 6000));
        list.add(park("forward_entry", "COMPLETED", c -> {
            pos(c, 0, 0, 3, 0, 0, "FORWARD"); pos(c, 3000, 0, 0, 180, 1.5, "FORWARD"); pos(c, 6000, 0, -7.5, 180, 0.0, "FORWARD");
        }, 6000, "WRONG_ORIENTATION", "WRONG_ENTRY_DIRECTION"));
        list.add(park("border_contact", "COMPLETED", c -> {
            pos(c, 0, 0, 3, 0, 0, "REVERSE"); contact(c, 4000, "border-left", "BORDER", 0.8);
            pos(c, 6000, 0, -7.5, 0, 0.0, "REVERSE");
        }, 6000, "BORDER_CONTACT"));
        list.add(park("neighbour_car_contact", "COMPLETED", c -> {
            pos(c, 0, 0, 3, 0, 0, "REVERSE"); contact(c, 4000, "car-right", "OBSTACLE", 0.8);
            pos(c, 6000, 0, -7.5, 0, 0.0, "REVERSE");
        }, 6000, "COLLISION"));
        list.add(park("not_stopped", "COMPLETED", c -> {
            pos(c, 0, 0, 3, 0, 0, "REVERSE"); pos(c, 6000, 0, -7.5, 0, 1.0, "REVERSE");
        }, 6000, "NOT_STOPPED"));
        list.add(park("stopped_outside_place", "COMPLETED", c -> {
            pos(c, 0, 0, 3, 0, 0, "REVERSE"); pos(c, 6000, 0, -2, 0, 0.0, "REVERSE");
        }, 6000, "TARGET_ZONE_MISSED"));
        list.add(park("reentered_zone_changes_entry_gear", "COMPLETED", c -> {
            // первый въезд задним ходом, затем выезд из зоны и повторный въезд передним ходом: решает последний въезд
            pos(c, 0, 0, 3, 0, 0, "REVERSE"); pos(c, 2000, 0, -6, 0, 1.0, "REVERSE");
            pos(c, 3000, 0, -2, 0, 1.0, "FORWARD"); pos(c, 4000, 0, -6, 0, 1.0, "FORWARD");
            pos(c, 6000, 0, -7.5, 0, 0.0, "FORWARD");
        }, 6000, "WRONG_ENTRY_DIRECTION"));
        return list;
    }

    // ================================================================= A4

    private static void checkA4(Path data) {
        System.out.println("--- A4. Приём сообщений и запись в журнал ---");
        Path store = fresh("a4");
        String id = create(data, store, "L-001", "stop-zone");
        List<Map<String, Object>> ev = new ArrayList<>();
        pos(ev, 0, 0, 0, 0, 0, "FORWARD"); pos(ev, 200, 0, 1, 0, 1, "FORWARD");
        Map<String, Object> b1 = batchMsg(1, ev);
        Map<String, Object> r1 = Receive.receive(data, store, id, b1);
        check("A4 новая группа: 200 ACCEPTED, 2 события в журнале",
            r1.get("status").equals("ACCEPTED") && logEvents(store, id) == 2, "");
        String logBefore = fileText(store, id, "log.json");
        Map<String, Object> r2 = Receive.receive(data, store, id, b1);
        check("A4 та же группа повторно: 200 DUPLICATE, журнал не изменился",
            r2.get("status").equals("DUPLICATE") && logBefore.equals(fileText(store, id, "log.json")), "");
        check("A4 неизвестная попытка: 404", Json.integer(Receive.receive(data, store, "a-9999", b1).get("code")) == 404L, "");
        Map<String, Object> wrongCount = batchMsg(2, ev);
        wrongCount.put("event_count", 5L);
        check("A4 неверное число событий в группе: 400, запись не создана",
            Json.integer(Receive.receive(data, store, id, wrongCount).get("code")) == 400L && logEvents(store, id) == 2, "");
        Map<String, Object> badType = batchMsg(2, ev);
        Json.map(Json.list(badType.get("events")).get(0)).put("type", "TELEPORT");
        check("A4 недопустимый тип события: 400", Json.integer(Receive.receive(data, store, id, badType).get("code")) == 400L, "");
        Map<String, Object> badNo = batchMsg(0, ev);
        check("A4 batch_number = 0: 400", Json.integer(Receive.receive(data, store, id, badNo).get("code")) == 400L, "");
        Map<String, Object> badCtx = batchMsg(2, ev);
        Json.map(Json.map(Json.list(badCtx.get("events")).get(0)).get("context")).put("gear", "SIDEWAYS");
        check("A4 недопустимое значение контекста (gear): 400", Json.integer(Receive.receive(data, store, id, badCtx).get("code")) == 400L, "");

        check("A4 до завершения оценки нет: result.json отсутствует", !Files.exists(Storage.attemptDir(store, id).resolve("result.json")), "");
        Map<String, Object> fin = finishMsg(1, 6000, "COMPLETED");
        Map<String, Object> rf = Receive.receive(data, store, id, fin);
        check("A4 FINISH: попытка переведена в FINISHED, выполнена оценка (результат окончательный)",
            "FINISHED".equals(attemptField(store, id, "state")) && rf.get("result") != null
                && "FINAL".equals(Json.map(rf.get("result")).get("finality")), "");
        String resBefore = fileText(store, id, "result.json");
        Map<String, Object> rf2 = Receive.receive(data, store, id, fin);
        check("A4 повторный FINISH: DUPLICATE, результат не изменился",
            rf2.get("status").equals("DUPLICATE") && resBefore.equals(fileText(store, id, "result.json")), "");

        // сбой оценки после записи FINISHED: повторный FINISH выполняет оценку заново
        try {
            Files.delete(Storage.attemptDir(store, id).resolve("result.json"));
        } catch (java.io.IOException ex) {
            throw new RuntimeException(ex);
        }
        Map<String, Object> rf3 = Receive.receive(data, store, id, fin);
        check("A4 FINISHED без результата (сбой оценки): повторный FINISH оценивает заново, результат тот же",
            rf3.get("status").equals("ACCEPTED") && resBefore.equals(fileText(store, id, "result.json")), String.valueOf(rf3.get("status")));

        // недопустимые значения FINISH и номеров: 400, попытка не переходит в FINISHED
        Path storeBad = fresh("a4bad");
        String idBad = create(data, storeBad, "L-001", "stop-zone");
        Map<String, Object> negDeclared = finishMsg(-5, 1000, "COMPLETED");
        Map<String, Object> hugeDeclared = finishMsg(Receive.MAX_BATCH_NUMBER + 1, 1000, "COMPLETED");
        Map<String, Object> negDuration = finishMsg(0, -77, "COMPLETED");
        Map<String, Object> badReason = finishMsg(0, 1000, "BANANA");
        check("A4 FINISH с declared_batch_count = −5 или выше предела: 400",
            Json.integer(Receive.receive(data, storeBad, idBad, negDeclared).get("code")) == 400L
                && Json.integer(Receive.receive(data, storeBad, idBad, hugeDeclared).get("code")) == 400L, "");
        check("A4 FINISH с отрицательной длительностью или неизвестной причиной: 400",
            Json.integer(Receive.receive(data, storeBad, idBad, negDuration).get("code")) == 400L
                && Json.integer(Receive.receive(data, storeBad, idBad, badReason).get("code")) == 400L, "");
        check("A4 после отклонённых FINISH попытка не завершена, результата нет",
            !"FINISHED".equals(attemptField(storeBad, idBad, "state"))
                && !Files.exists(Storage.attemptDir(storeBad, idBad).resolve("result.json")), "");
        List<Map<String, Object>> bigNo = new ArrayList<>();
        pos(bigNo, 0, 0, 0, 0, 0, "FORWARD");
        bigNo.get(0).put("event_no", 2147483647L);
        check("A4 event_no выше предела: 400, запись не создана",
            Json.integer(Receive.receive(data, storeBad, idBad, batchMsg(1, bigNo)).get("code")) == 400L
                && !Files.exists(Storage.attemptDir(storeBad, idBad).resolve("log.json")), "");
        Map<String, Object> okFin = Receive.receive(data, storeBad, idBad, finishMsg(0, 1000, "COMPLETED"));
        check("A4 после отклонений допустимый FINISH принимается и даёт результат",
            okFin.get("status").equals("ACCEPTED") && okFin.get("result") != null, "");

        // поздняя группа после FINISH: пересчёт (результат заменяется)
        Path store2 = fresh("a4late");
        String id2 = create(data, store2, "L-001", "stop-zone");
        List<Map<String, Object>> g1 = new ArrayList<>(), g2 = new ArrayList<>();
        pos(g1, 0, 0, 0, 0, 0, "FORWARD");
        contact(g2, 2000, "cone-1", "OBSTACLE", 2.0);   // event_no = 1 в своём списке; перенумеруем ниже
        g2.get(0).put("event_no", 2L);
        List<Map<String, Object>> g3 = new ArrayList<>();
        pos(g3, 8000, 0, 43, 0, 0.0, "FORWARD");
        g3.get(0).put("event_no", 3L);
        Receive.receive(data, store2, id2, batchMsg(1, g1));
        Receive.receive(data, store2, id2, batchMsg(3, g3));
        Receive.receive(data, store2, id2, finishMsg(3, 8000, "COMPLETED"));
        Map<String, Object> early = result(store2, id2);
        check("A4 FINISH при недостающей группе 2: результат PRELIMINARY, missing_batches = [2]",
            "PRELIMINARY".equals(early.get("finality")) && Json.list(early.get("missing_batches")).equals(Arrays.asList(2L)), "");
        Receive.receive(data, store2, id2, batchMsg(2, g2));
        Map<String, Object> later = result(store2, id2);
        check("A4 поздняя группа после FINISH: автоматическая повторная оценка, результат FINAL с ошибкой COLLISION",
            "FINAL".equals(later.get("finality")) && types(later).equals("COLLISION"), types(later));
    }

    // ================================================================= A5 + A6

    private static void checkA5A6(Path data) {
        System.out.println("--- A5 + A6. Неполный журнал: средняя группа потеряна ---");
        Path store = fresh("a5a6");
        String id = create(data, store, "L-001", "stop-zone");
        List<Map<String, Object>> g1 = new ArrayList<>(), g2 = new ArrayList<>(), g3 = new ArrayList<>();
        pos(g1, 0, 0, 0, 0, 0, "FORWARD");                                   // событие 1 (группа 1)
        pos(g2, 3000, 0, 20, 0, 8.0, "FORWARD"); g2.get(0).put("event_no", 2L);      // группа 2 — потеряна
        contact(g3, 5000, "cone-2", "OBSTACLE", 3.0); g3.get(0).put("event_no", 3L);  // группа 3: контакт
        pos(g3, 8000, 0, 43, 0, 0.0, "FORWARD"); g3.get(1).put("event_no", 4L);        // группа 3: последняя запись положения
        Receive.receive(data, store, id, batchMsg(1, g1));
        Receive.receive(data, store, id, batchMsg(3, g3));
        Receive.receive(data, store, id, finishMsg(3, 8000, "COMPLETED"));
        Map<String, Object> r = result(store, id);
        check("A5+A6 результат PRELIMINARY, missing_batches = [2]",
            "PRELIMINARY".equals(r.get("finality")) && Json.list(r.get("missing_batches")).equals(Arrays.asList(2L)), "");
        check("A5+A6 ошибка из поздней группы (COLLISION) присутствует",
            types(r).equals("COLLISION"), types(r));
        check("A5+A6 конечное состояние проверено по настоящей последней записи (зона достигнута, TARGET_ZONE_MISSED нет)",
            !types(r).contains("TARGET_ZONE_MISSED"), "");
        check("A5+A6 статус NOT_ACHIEVED при предварительном результате",
            "NOT_ACHIEVED".equals(r.get("status")), "");
        check("A5+A6 ссылка на событие-основание: source_event_no = 3",
            Json.map(Json.list(r.get("violations")).get(0)).get("source_event_no").equals(3L), "");
    }

    // ================================================================= A7

    private static void checkA7(Path dataV) {
        System.out.println("--- A7. Сравнение попыток при совпадении версий ---");
        Path store = fresh("a7");
        Case ok = new Case(); ok.exercise = "stop-zone"; ok.finish = "COMPLETED"; ok.duration = 8000; ok.name = "a7";
        pos(ok.events, 0, 0, 0, 0, 0, "FORWARD"); pos(ok.events, 8000, 0, 43, 0, 0.0, "FORWARD");
        Case bad = new Case(); bad.exercise = "stop-zone"; bad.finish = "COMPLETED"; bad.duration = 9000; bad.name = "a7";
        pos(bad.events, 0, 0, 0, 0, 0, "FORWARD"); contact(bad.events, 2000, "cone-1", "OBSTACLE", 2.0);
        pos(bad.events, 9000, 0, 43, 0, 0.0, "FORWARD");

        String first = runWith(dataV, store, "L-001", "stop-zone", 1L, 1L, bad, false);
        Map<String, Object> m1 = Compare.compare(dataV, store, first, null);
        check("A7 первая попытка: «сравнивать не с чем»", "FIRST_ATTEMPT".equals(m1.get("reason")) && Boolean.FALSE.equals(m1.get("comparable")), str(m1.get("message")));

        String second = runWith(dataV, store, "L-001", "stop-zone", 1L, 1L, ok, false);
        Map<String, Object> m2 = Compare.compare(dataV, store, second, null);
        check("A7 две попытки с одинаковыми версиями: сравнение допустимо, разность COLLISION = −1, длительность −1000 мс",
            Boolean.TRUE.equals(m2.get("comparable")) && Json.map(m2.get("violations_diff")).get("COLLISION").equals(-1L)
                && m2.get("duration_diff_ms").equals(-1000L), "");
        check("A7 сравнение: статусы A и B (NOT_ACHIEVED → ACHIEVED)",
            "NOT_ACHIEVED".equals(m2.get("status_a")) && "ACHIEVED".equals(m2.get("status_b")), "");

        String third = runWith(dataV, store, "L-001", "stop-zone", 2L, 1L, ok, false);
        Map<String, Object> m3 = Compare.compare(dataV, store, third, null);
        check("A7 изменена версия сценария: версии различаются, показаны номера и change_note",
            "VERSIONS_DIFFER".equals(m3.get("reason")) && str(m3.get("message")).contains("Целевая зона сдвинута"), str(m3.get("message")));

        String fourth = runWith(dataV, store, "L-001", "stop-zone", 1L, 2L, ok, false);
        Map<String, Object> m4 = Compare.compare(dataV, store, fourth, null);
        check("A7 изменены версии правил: версии различаются, показан change_note правил",
            "VERSIONS_DIFFER".equals(m4.get("reason")) && str(m4.get("message")).contains("LEFT_AREA"), str(m4.get("message")));

        Map<String, Object> m5 = Compare.compare(dataV, store, fourth, first);
        check("A7 пользователь выбрал две попытки с разными версиями: сравнение отклонено", "VERSIONS_DIFFER".equals(m5.get("reason")), "");
        Map<String, Object> m6 = Compare.compare(dataV, store, second, first);
        check("A7 пользователь выбрал две попытки с одинаковыми версиями: сравнение допустимо", Boolean.TRUE.equals(m6.get("comparable")), "");

        // предварительный результат
        String fifth = runWith(dataV, store, "L-001", "stop-zone", 1L, 1L, ok, true);   // группа потеряна
        Map<String, Object> m7 = Compare.compare(dataV, store, fifth, null);
        check("A7 предварительный результат у выбранной попытки: сравнение не выполняется, показаны недостающие группы",
            "PRELIMINARY".equals(m7.get("reason")) && !Json.list(m7.get("missing_batches")).isEmpty(), "");
        // другой обучающийся не считается «предыдущей попыткой»
        String other = runWith(dataV, store, "L-002", "stop-zone", 1L, 1L, ok, false);
        Map<String, Object> m8 = Compare.compare(dataV, store, other, null);
        check("A7 попытки другого обучающегося не используются: для L-002 это первая попытка", "FIRST_ATTEMPT".equals(m8.get("reason")), "");
    }

    // ================================================================= перезапуск

    private static void checkRestart(Path data) {
        System.out.println("--- Доступность истории после перезапуска сервера ---");
        Path store = fresh("restart");
        Case c = new Case(); c.exercise = "stop-zone"; c.finish = "COMPLETED"; c.duration = 8000; c.name = "r";
        pos(c.events, 0, 0, 0, 0, 0, "FORWARD"); contact(c.events, 3000, "cone-1", "OBSTACLE", 2.0); pos(c.events, 8000, 0, 43, 0, 0.0, "FORWARD");
        String id = runCase(data, store, c, 4, false);
        String before = fileText(store, id, "result.json");
        // «перезапуск»: все данные читаются заново только с диска, состояния в памяти нет
        Path copy = OUT.resolve("restart-copy");
        deleteTree(copy);
        copyTree(store, copy);
        check("История попыток читается из каталога хранилища: найдено попыток 1",
            Storage.attemptIds(copy).size() == 1, "");
        Map<String, Object> resultAgain = Json.map(Storage.read(Storage.attemptDir(copy, id).resolve("result.json")));
        check("Сохранённый результат открывается после перезапуска без изменений", Json.write(resultAgain).concat("\n").equals(before), "");
        Map<String, Object> late = Receive.receive(data, copy, id, finishMsg(1, 8000, "COMPLETED"));
        check("Повторное завершение после перезапуска не создаёт записей (DUPLICATE)", late.get("status").equals("DUPLICATE"), "");
    }

    // ================================================================= построение данных

    private static Case stop(String name, String finish, java.util.function.Consumer<List<Map<String, Object>>> fill, long dur, String... expected) {
        return make("stop-zone", name, finish, fill, dur, expected);
    }

    private static Case inter(String name, String finish, java.util.function.Consumer<List<Map<String, Object>>> fill, long dur, String... expected) {
        return make("intersection", name, finish, fill, dur, expected);
    }

    private static Case park(String name, String finish, java.util.function.Consumer<List<Map<String, Object>>> fill, long dur, String... expected) {
        return make("reverse-parking", name, finish, fill, dur, expected);
    }

    private static Case make(String exercise, String name, String finish,
                             java.util.function.Consumer<List<Map<String, Object>>> fill, long dur, String[] expected) {
        Case c = new Case();
        c.exercise = exercise; c.name = name; c.finish = finish; c.duration = dur; c.expected = expected;
        fill.accept(c.events);
        return c;
    }

    private static void pos(List<Map<String, Object>> list, long t, double x, double z, double h, double v, String gear) {
        list.add(Json.obj("event_no", (long) list.size() + 1, "t_ms", t, "type", "POSITION_SAMPLE",
            "context", Json.obj("x", x, "z", z, "heading_deg", h, "speed_mps", v, "gear", gear)));
    }

    private static void contact(List<Map<String, Object>> list, long t, String object, String kind, double v) {
        list.add(Json.obj("event_no", (long) list.size() + 1, "t_ms", t, "type", "CONTACT_STARTED",
            "context", Json.obj("object_id", object, "object_kind", kind, "speed_mps", v)));
    }

    private static void stopLine(List<Map<String, Object>> list, long t, String signal) {
        list.add(Json.obj("event_no", (long) list.size() + 1, "t_ms", t, "type", "STOP_LINE_CROSSED",
            "context", Json.obj("signal", signal)));
    }

    private static void boundary(List<Map<String, Object>> list, long t, String direction) {
        list.add(Json.obj("event_no", (long) list.size() + 1, "t_ms", t, "type", "BOUNDARY_CROSSED",
            "context", Json.obj("direction", direction)));
    }

    private static Map<String, Object> batchMsg(long number, List<Map<String, Object>> events) {
        List<Object> copy = new ArrayList<>();
        for (Map<String, Object> e : events) {
            copy.add(Json.map(Json.parse(Json.write(e))));   // глубокая копия: проверки меняют сообщения
        }
        return Json.obj("kind", "BATCH", "batch_number", number, "event_count", (long) copy.size(), "events", copy);
    }

    private static Map<String, Object> finishMsg(long declared, long duration, String reason) {
        return Json.obj("kind", "FINISH", "duration_ms", duration, "finish_reason", reason, "declared_batch_count", declared);
    }

    /** Отправляет случай серверу группами по size событий (reverse — в обратном порядке, FINISH последним). */
    private static String runCase(Path data, Path store, Case c, int size, boolean reverse) {
        return runWith(data, store, "L-001", c.exercise, null, null, c, reverse ? "reverse" : null, size);
    }

    private static String runWith(Path data, Path store, String learner, String exercise, Long sv, Long rv, Case c, boolean dropMiddle) {
        return runWith(data, store, learner, exercise, sv, rv, c, dropMiddle ? "drop" : null, 1);
    }

    private static String runWith(Path data, Path store, String learner, String exercise, Long sv, Long rv, Case c, String mode, int size) {
        Map<String, Object> r = Prepare.createAttempt(data, store, learner, exercise, sv, rv, now());
        String id = str(r.get("attempt_id"));
        List<Map<String, Object>> batches = new ArrayList<>();
        for (int i = 0; i < c.events.size(); i += size) {
            batches.add(batchMsg(batches.size() + 1, c.events.subList(i, Math.min(i + size, c.events.size()))));
        }
        List<Map<String, Object>> send = new ArrayList<>(batches);
        if ("reverse".equals(mode)) {
            Collections.reverse(send);
        } else if ("drop".equals(mode) && send.size() > 1) {
            send.remove(0);   // потеряна первая группа
        }
        for (Map<String, Object> m : send) {
            Receive.receive(data, store, id, m);
        }
        Receive.receive(data, store, id, finishMsg(batches.size(), c.duration, c.finish));
        return id;
    }

    private static String create(Path data, Path store, String learner, String exercise) {
        return str(Prepare.createAttempt(data, store, learner, exercise, null, null, now()).get("attempt_id"));
    }

    private static Map<String, Object> attemptMap(String state, Long declared) {
        return Json.obj("attempt_id", "t", "state", state, "declared_batch_count", declared);
    }

    private static Map<String, Object> logMap(long[][] batches, long[][] events) {
        List<Object> bl = new ArrayList<>(), el = new ArrayList<>();
        for (long[] b : batches) {
            bl.add(Json.obj("batch_number", b[0], "event_count", b[1]));
        }
        for (long[] e : events) {
            el.add(Json.obj("event_no", e[1], "batch_number", e[0], "t_ms", 0L, "type", "POSITION_SAMPLE", "context", Json.obj()));
        }
        return Json.obj("attempt_id", "t", "batches", bl, "events", el);
    }

    // ================================================================= HTTP: 400 и 500

    /** Замечание 3 рецензии: 400 — только ошибка входного запроса; сбой хранилища — 500, и сообщение можно повторить. */
    private static void checkHttp(Path data) throws IOException {
        System.out.println("--- HTTP. Разделение ошибок запроса (400) и сервера (500) ---");
        Path store = fresh("http");
        String id = create(data, store, "L-001", "stop-zone");
        List<Map<String, Object>> ev = new ArrayList<>();
        boundary(ev, 1000, "EXIT");
        String batch = Json.write(batchMsg(1, ev));
        com.sun.net.httpserver.HttpServer server = HttpApi.start(0, data, store);
        java.io.PrintStream err = System.err;
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            String messages = base + "/attempts/" + id + "/messages";
            check("HTTP тело не является JSON: 400", post(messages, "{не json") == 400, "");
            check("HTTP тело — массив вместо объекта: 400", post(messages, "[]") == 400, "");
            check("HTTP learner_id не строка: 400", post(base + "/attempts", "{\"learner_id\":5,\"exercise_id\":\"stop-zone\"}") == 400, "");
            check("HTTP группа с batch_number = 0: 400", post(messages,
                Json.write(Json.obj("kind", "BATCH", "batch_number", 0L, "event_count", 0L, "events", new ArrayList<Object>()))) == 400, "");

            Path attemptFile = Storage.attemptDir(store, id).resolve("attempt.json");
            byte[] saved = Files.readAllBytes(attemptFile);
            Files.write(attemptFile, "{повреждено".getBytes(StandardCharsets.UTF_8));
            System.setErr(new java.io.PrintStream(java.io.OutputStream.nullOutputStream()));   // ожидаемая трассировка сбоя
            int broken = post(messages, batch);
            System.setErr(err);
            check("HTTP корректная группа при повреждённом attempt.json: 500, а не 400", broken == 500, "код " + broken);
            check("HTTP после ответа 500 журнал не изменён", !Files.exists(Storage.attemptDir(store, id).resolve("log.json")), "");
            Files.write(attemptFile, saved);
            int again = post(messages, batch);
            check("HTTP повтор той же группы после восстановления данных: 200, группа записана",
                again == 200 && Files.exists(Storage.attemptDir(store, id).resolve("log.json")), "код " + again);
        } finally {
            System.setErr(err);
            server.stop(0);
        }
    }

    private static int post(String url, String body) throws IOException {
        try {
            java.net.http.HttpResponse<String> r = java.net.http.HttpClient.newHttpClient().send(
                java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
                java.net.http.HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return r.statusCode();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
    }

    // ================================================================= вспомогательное

    private static void check(String name, boolean ok, String detail) {
        total++;
        if (!ok) {
            failed++;
        }
        System.out.println((ok ? "[OK]   " : "[FAIL] ") + name + (ok || detail.isEmpty() ? "" : "  <-- " + detail));
    }

    private static String now() {
        return String.format(java.util.Locale.ROOT, "2026-11-02T10:00:%02d", ++clock);
    }

    private static String str(Object o) {
        return Json.str(o);
    }

    private static Path fresh(String name) {
        Path p = OUT.resolve("store-" + name);
        deleteTree(p);
        return p;
    }

    private static Object attemptField(Path store, String id, String field) {
        return Json.map(Storage.read(Storage.attemptDir(store, id).resolve("attempt.json"))).get(field);
    }

    private static Map<String, Object> result(Path store, String id) {
        return Json.map(Storage.read(Storage.attemptDir(store, id).resolve("result.json")));
    }

    private static String fileText(Path store, String id, String name) {
        try {
            return new String(Files.readAllBytes(Storage.attemptDir(store, id).resolve(name)), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static int logEvents(Path store, String id) {
        return Json.list(Json.map(Storage.read(Storage.attemptDir(store, id).resolve("log.json"))).get("events")).size();
    }

    private static String types(Map<String, Object> result) {
        List<String> t = new ArrayList<>();
        for (Object o : Json.list(result.get("violations"))) {
            t.add(str(Json.map(o).get("type")));
        }
        return String.join(",", t);
    }

    /** Журнал случая сохраняется как пример входных данных сервера (samples/journals). */
    private static void writeSample(Case c) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("case", c.exercise + "/" + c.name);
        doc.put("exercise_id", c.exercise);
        doc.put("finish_reason", c.finish);
        doc.put("duration_ms", c.duration);
        doc.put("expected_violations", new ArrayList<Object>(Arrays.asList((Object[]) c.expected)));
        doc.put("events", new ArrayList<Object>(c.events));
        Storage.writeAtomic(SAMPLES.resolve(c.exercise + "__" + c.name + ".json"), doc);
    }

    private static Path copyData(Path from, Path to) {
        copyTree(from, to);
        return to;
    }

    /** Варианты данных для проверок A1 и A7: вторая версия сценария и правил остановки; либо неполный сценарий. */
    private static void addVariants(Path dataDir, boolean incomplete) {
        Path ex = dataDir.resolve("exercises").resolve("stop-zone");
        Map<String, Object> sc = Json.map(Storage.read(ex.resolve("scenario-1.json")));
        sc.put("scenario_version", 2L);
        sc.put("target_zone", Json.parse("[[-1.75, 41.0], [1.75, 41.0], [1.75, 45.0], [-1.75, 45.0]]"));
        sc.put("change_note", "Целевая зона сдвинута и сужена: теперь 41–45 м от старта.");
        if (incomplete) {
            sc.put("target_zone", null);
            Storage.writeAtomic(ex.resolve("scenario-2.json"), sc);
            return;
        }
        Storage.writeAtomic(ex.resolve("scenario-2.json"), sc);
        Map<String, Object> rules = Json.map(Storage.read(ex.resolve("rules-1.json")));
        rules.put("rules_version", 2L);
        List<Object> enabled = Json.list(rules.get("enabled_rules"));
        enabled.remove("LEFT_AREA");
        rules.put("change_note", "Правило LEFT_AREA исключено: выезд за границы участка не учитывается.");
        Storage.writeAtomic(ex.resolve("rules-2.json"), rules);
    }

    private static void copyTree(Path from, Path to) {
        try (Stream<Path> s = Files.walk(from)) {
            s.forEach(p -> {
                try {
                    Path target = to.resolve(from.relativize(p).toString());
                    if (Files.isDirectory(p)) {
                        Files.createDirectories(target);
                    } else {
                        Files.createDirectories(target.getParent());
                        Files.copy(p, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            });
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void deleteTree(Path p) {
        if (!Files.exists(p)) {
            return;
        }
        try (Stream<Path> s = Files.walk(p)) {
            s.sorted(Comparator.reverseOrder()).forEach(x -> x.toFile().delete());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
