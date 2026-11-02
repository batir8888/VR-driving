using System;
using System.Collections.Generic;

namespace Simulator.Client
{
    /// <summary>
    /// A2. Регистрация событий и формирование групп (модуль регистрации клиента).
    /// Схема: UP-004/UP-004_A2_схема.png, шаги — таблица 4.5. Клиент фиксирует наблюдаемые факты;
    /// нарушения определяет сервер. Отправку выполняет A3 в фоновом потоке, поэтому обработка кадра не ждёт сети.
    /// Для стоп-линии берётся передний край автомобиля (центр плюс половина длины по курсу), для границы участка — центр.
    /// </summary>
    public static class Registrar
    {
        /// <summary>Вызывается один раз на каждый кадр (блок 1: данные кадра).</summary>
        public static void ProcessFrame(RegState st, Scenario sc, Frame f)
        {
            if (st.BatchOpenedAtT < 0)
            {
                st.BatchOpenedAtT = f.TMs;
            }
            // блок 2: кадр не завершающий и t_ms − last_sample_t ≥ sample_period_ms?
            // Завершающий кадр записывает положение в блоке 14, но контакты, стоп-линия и граница проверяются на нём как обычно.
            bool finishing = f.FinishReason != null;
            if (!finishing && f.TMs - st.LastSampleT >= sc.SamplePeriodMs)
            {
                AddSample(st, f);                                           // блок 3
            }
            // блоки 4–6: для каждого контакта из текущих
            var current = new HashSet<string>();
            foreach (Contact c in f.Contacts)
            {
                current.Add(c.ObjectId);
                if (!st.PrevContacts.Contains(c.ObjectId))                  // блок 5: объекта нет в prev_contacts?
                {
                    Add(st, new EventRecord                                 // блок 6: CONTACT_STARTED
                    {
                        TMs = f.TMs, Type = "CONTACT_STARTED", ObjectId = c.ObjectId, ObjectKind = c.ObjectKind, SpeedMps = f.SpeedMps
                    });
                }
            }
            st.PrevContacts = current;                                      // блок 7
            // блоки 8–10: для каждой стоп-линии из road_objects
            foreach (RoadObject o in sc.RoadObjects)
            {
                if (o.Kind != "STOP_LINE")
                {
                    continue;
                }
                bool withinSpan;
                double heading = f.HeadingDeg * Math.PI / 180.0;
                double frontX = f.X + Math.Sin(heading) * sc.LengthM / 2.0;
                double frontZ = f.Z + Math.Cos(heading) * sc.LengthM / 2.0;
                double side = SideOfLine(o, frontX, frontZ, out withinSpan);
                double prev;
                bool had = st.PrevSide.TryGetValue(o.ObjectId, out prev);
                st.PrevSide[o.ObjectId] = side;
                if (had && prev < 0 && side >= 0 && withinSpan)             // блок 9: передний край перешёл линию по ходу?
                {
                    Add(st, new EventRecord { TMs = f.TMs, Type = "STOP_LINE_CROSSED", Signal = f.Signal });   // блок 10
                }
            }
            // блоки 11–12: признак «внутри area_bounds» изменился?
            bool inside = Inside(sc.AreaBounds, f.X, f.Z);
            if (st.PrevInsideInit && inside != st.PrevInside)
            {
                Add(st, new EventRecord { TMs = f.TMs, Type = "BOUNDARY_CROSSED", Direction = inside ? "ENTER" : "EXIT" });
            }
            st.PrevInside = inside;
            st.PrevInsideInit = true;
            // блок 13: попытка завершается?
            if (finishing)
            {
                // блок 14: последняя POSITION_SAMPLE, закрыть группу, поставить в очередь сообщение о завершении
                AddSample(st, f);
                CloseBatch(st, f.TMs);
                if (!st.FinishQueued)
                {
                    lock (st.Outbox)
                    {
                        st.Outbox.Add(new Message
                        {
                            Kind = "FINISH",
                            DurationMs = f.TMs,
                            FinishReason = f.FinishReason,
                            DeclaredBatchCount = st.NextBatchNumber - 1
                        });
                    }
                    st.FinishQueued = true;
                }
                return;
            }
            // блок 15: в буфере ≥ batch_max_events или прошло ≥ batch_max_interval_ms?
            if (st.Buffer.Count >= st.BatchMaxEvents || f.TMs - st.BatchOpenedAtT >= st.BatchMaxIntervalMs)
            {
                CloseBatch(st, f.TMs);                                      // блок 16
            }
        }

        /// <summary>Блок 3 (и блок 14): добавить POSITION_SAMPLE; last_sample_t := t_ms.</summary>
        private static void AddSample(RegState st, Frame f)
        {
            Add(st, new EventRecord
            {
                TMs = f.TMs, Type = "POSITION_SAMPLE", X = f.X, Z = f.Z, HeadingDeg = f.HeadingDeg, SpeedMps = f.SpeedMps, Gear = f.Gear
            });
            st.LastSampleT = f.TMs;
        }

        /// <summary>Событие получает следующий event_no и попадает в буфер.</summary>
        private static void Add(RegState st, EventRecord e)
        {
            e.EventNo = st.NextEventNo++;
            st.Buffer.Add(e);
        }

        /// <summary>Блок 16: закрыть группу (batch_number, event_count), поставить в очередь отправки, очистить буфер.</summary>
        private static void CloseBatch(RegState st, long tMs)
        {
            if (st.Buffer.Count == 0)
            {
                return;     // пустая группа не отправляется (UP-003, связь EventBatch → Event: 1..*)
            }
            lock (st.Outbox)
            {
                st.Outbox.Add(new Message
                {
                    Kind = "BATCH",
                    BatchNumber = st.NextBatchNumber++,
                    EventCount = st.Buffer.Count,
                    Events = new List<EventRecord>(st.Buffer)
                });
            }
            st.Buffer.Clear();
            st.BatchOpenedAtT = tMs;
        }

        /// <summary>Расстояние от заданной точки автомобиля до линии вдоль направления пересечения; отрицательное — до линии.</summary>
        private static double SideOfLine(RoadObject o, double x, double z, out bool withinSpan)
        {
            double ax = o.Line[0][0], az = o.Line[0][1], bx = o.Line[1][0], bz = o.Line[1][1];
            double dx = o.Direction[0], dz = o.Direction[1];
            double len = Math.Sqrt((bx - ax) * (bx - ax) + (bz - az) * (bz - az));
            double t = ((x - ax) * (bx - ax) + (z - az) * (bz - az)) / len;
            withinSpan = t >= 0 && t <= len;
            return (x - ax) * dx + (z - az) * dz;
        }

        /// <summary>Точка внутри многоугольника (или на границе): чётно-нечётное правило.</summary>
        public static bool Inside(double[][] poly, double x, double z)
        {
            const double eps = 1e-9;
            int n = poly.Length;
            for (int i = 0, j = n - 1; i < n; j = i++)
            {
                double cross = (poly[i][0] - poly[j][0]) * (z - poly[j][1]) - (poly[i][1] - poly[j][1]) * (x - poly[j][0]);
                if (Math.Abs(cross) <= eps
                    && x >= Math.Min(poly[i][0], poly[j][0]) - eps && x <= Math.Max(poly[i][0], poly[j][0]) + eps
                    && z >= Math.Min(poly[i][1], poly[j][1]) - eps && z <= Math.Max(poly[i][1], poly[j][1]) + eps)
                {
                    return true;
                }
            }
            bool inside = false;
            for (int i = 0, j = n - 1; i < n; j = i++)
            {
                if ((poly[i][1] > z) != (poly[j][1] > z)
                    && x < (poly[j][0] - poly[i][0]) * (z - poly[i][1]) / (poly[j][1] - poly[i][1]) + poly[i][0])
                {
                    inside = !inside;
                }
            }
            return inside;
        }
    }
}
