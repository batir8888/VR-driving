package sim;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A5. Проверка полноты журнала (компонент оценки). Схема: UP-004_A5_схема.png, шаги — таблица 4.8.
 */
public final class Completeness {
    private Completeness() {}

    /** Результат проверки (блок 16). Структура данных без поведения. */
    public static final class Report {
        public boolean finished;
        public boolean complete;
        public final List<Long> missingBatches = new ArrayList<>();
        /** slot[event_no] — событие или null; slot[0] не используется. */
        public Map<String, Object>[] slot;
        public int eventCount;   // K
        public int maxEventNo;   // M
    }

    @SuppressWarnings("unchecked")
    public static Report check(Map<String, Object> attempt, Map<String, Object> log) {
        Report r = new Report();
        // блок 1: попытка, группы и события загружены (параметры метода)

        // блок 2: state = FINISHED?
        if (!"FINISHED".equals(attempt.get("state"))) {
            r.finished = false;
            return r;
        }
        r.finished = true;

        // блок 3: подготовка массивов
        int n = (int) Json.integer(attempt.get("declared_batch_count"));
        Long[] expected = new Long[n + 1];
        long[] counted = new long[n + 1];
        boolean ok = true;
        List<Object> events = Json.list(log.get("events"));
        int k = events.size();
        int m = 0;
        for (Object o : events) {
            m = (int) Math.max(m, Json.integer(Json.map(o).get("event_no")));
        }
        Map<String, Object>[] slot = (Map<String, Object>[]) new Map[m + 1];

        // блоки 4–7: цикл по группам журнала
        for (Object o : Json.list(log.get("batches"))) {
            Map<String, Object> b = Json.map(o);
            long number = Json.integer(b.get("batch_number"));
            if (number >= 1 && number <= n) {                 // блок 5
                expected[(int) number] = Json.integer(b.get("event_count"));   // блок 6
            } else {
                ok = false;                                   // блок 7
            }
        }

        // блоки 8–11: цикл по событиям журнала
        for (Object o : events) {
            Map<String, Object> e = Json.map(o);
            long no = Json.integer(e.get("event_no"));
            if (no >= 1 && slot[(int) no] == null) {          // блок 9
                slot[(int) no] = e;                           // блок 10
                long bn = Json.integer(e.get("batch_number"));
                if (bn >= 1 && bn <= n) {
                    counted[(int) bn]++;
                }
            } else {
                ok = false;                                   // блок 11
            }
        }

        // блоки 12–14: для i от 1 до N
        for (int i = 1; i <= n; i++) {
            if (!(expected[i] != null && counted[i] == expected[i])) {   // блок 13
                r.missingBatches.add((long) i);                          // блок 14
            }
        }

        // блок 15: complete := ok, missing_batches пуст и K = M
        r.complete = ok && r.missingBatches.isEmpty() && k == m;
        r.slot = slot;
        r.eventCount = k;
        r.maxEventNo = m;
        return r;       // блок 16
    }
}
