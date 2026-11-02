# Формат сообщений клиент ↔ сервер

Обмен — HTTP и JSON в кодировке UTF-8. Названия полей совпадают с атрибутами логической модели данных (`UP-003`). Время `t_ms` — миллисекунды от начала попытки; числа записываются с десятичной точкой независимо от языковых настроек.

## Создание попытки (алгоритм A1, серверная часть)

`POST /attempts`

```json
{"learner_id": "L-001", "exercise_id": "stop-zone", "scenario_version": 1, "rules_version": 1}
```

`scenario_version` и `rules_version` необязательны: без них берутся версии с наибольшим номером.

| Код | Ответ |
|---|---|
| 201 | `{"ok": true, "code": 201, "attempt_id": "a-0001", "title": "…", "scenario": { условия сценария }}` |
| 404 | неизвестный обучающийся или упражнение |
| 422 | версия сценария или правил не найдена либо сценарий неполон |

Содержимое `scenario` — файл `data/exercises/<упражнение>/scenario-<N>.json`: `start_pose`, `travel_direction`, `speed_limit_mps`, `vehicle_size`, `area_bounds`, `target_zone`, `target_heading_deg`, `permitted_signals`, `tolerances`, `sample_period_ms`, `road_objects`, `task_text`.

## Сообщения попытки (алгоритмы A2–A4)

`POST /attempts/{attempt_id}/messages`

### Группа событий

```json
{"kind": "BATCH", "batch_number": 1, "event_count": 2, "events": [
  {"event_no": 1, "t_ms": 0,   "type": "POSITION_SAMPLE",
   "context": {"x": 0.0, "z": 0.0, "heading_deg": 0.0, "speed_mps": 0.0, "gear": "FORWARD"}},
  {"event_no": 2, "t_ms": 150, "type": "CONTACT_STARTED",
   "context": {"object_id": "cone-1", "object_kind": "OBSTACLE", "speed_mps": 2.0}}
]}
```

- `batch_number` — номер группы внутри попытки с 1 без пропусков; `event_no` — сквозной номер события в попытке с 1 без пропусков.
- Контекст по типам события:

| `type` | поля `context` |
|---|---|
| `POSITION_SAMPLE` | `x`, `z`, `heading_deg`, `speed_mps`, `gear` (`FORWARD`, `REVERSE`) |
| `CONTACT_STARTED` | `object_id`, `object_kind` (`OBSTACLE`, `BORDER`), `speed_mps` |
| `STOP_LINE_CROSSED` | `signal` (`GREEN`, `YELLOW`, `RED`) |
| `BOUNDARY_CROSSED` | `direction` (`ENTER`, `EXIT`) |

### Завершение попытки

```json
{"kind": "FINISH", "duration_ms": 8500, "finish_reason": "COMPLETED", "declared_batch_count": 5}
```

`finish_reason` — `COMPLETED` или `ABORTED`; `declared_batch_count` — номер последней отправленной группы.

### Ответы

| Код | Тело | Смысл для клиента |
|---|---|---|
| 200 | `{"status": "ACCEPTED", …}` | принято; для `FINISH` и для групп, пришедших после него, в теле есть `result` |
| 200 | `{"status": "DUPLICATE", …}` | уже принято ранее, запись не создана; повторять не нужно |
| 400 | `{"status": "BAD_FORMAT", …}` | постоянная ошибка формата; сообщение клиент переносит в список отклонённых |
| 404 | `{"status": "NOT_FOUND", …}` | попытка неизвестна; повтор не поможет |
| 5xx, нет ответа | — | временная ошибка: сообщение остаётся в очереди, повтор с растущей паузой |

## Результат и история

| Запрос | Ответ |
|---|---|
| `GET /attempts/{id}/result` | `{"attempt_id", "exercise_title", "duration_ms", "status", "finality", "missing_batches", "violations": [{"violation_no", "type", "t_ms", "reason", "source_event_no"}]}`; 404, если результата ещё нет |
| `GET /attempts` | список описаний попыток (история; читается с диска после перезапуска сервера) |
| `GET /compare?b={id}[&a={id}]` | сравнение (A7): `comparable: true` с разностями либо `comparable: false` с причиной `FIRST_ATTEMPT`, `VERSIONS_DIFFER` или `PRELIMINARY` |

`status` — `ACHIEVED` или `NOT_ACHIEVED`; `finality` — `FINAL` или `PRELIMINARY`. Результат по неполному журналу всегда `PRELIMINARY` с перечнем `missing_batches`.

## Хранение на сервере

```
<store>/attempts/<attempt_id>/attempt.json   описание попытки
<store>/attempts/<attempt_id>/log.json       группы и события журнала
<store>/attempts/<attempt_id>/result.json    результат (заменяется при пересчёте)
```
