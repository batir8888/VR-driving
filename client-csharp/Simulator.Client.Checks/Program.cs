using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text.Json;
using Simulator.Client.Demo;

namespace Simulator.Client.Checks
{
    /// <summary>
    /// Проверки клиентских алгоритмов A1 (блоки 7–10), A2 и A3 на подготовленных данных, без Unity и без сети.
    /// Ожидаемые результаты заданы в случаях. Таблица планируемых случаев — UP-004, таблица 4.13.
    /// Запуск из каталога code: dotnet run --project client-csharp/Simulator.Client.Checks -- data
    /// </summary>
    public static class Program
    {
        private static int total, failed;

        public static int Main(string[] args)
        {
            Console.OutputEncoding = System.Text.Encoding.UTF8;
            string data = args.Length > 0 ? args[0] : "data";
            Scenario stop = Load(data, "stop-zone");
            Scenario inter = Load(data, "intersection");
            Scenario park = Load(data, "reverse-parking");

            CheckA1(stop, inter, park);
            CheckA2(stop, inter);
            CheckA3();

            Console.WriteLine();
            Console.WriteLine("Клиентские проверки: выполнено " + total + ", ошибок " + failed);
            return failed == 0 ? 0 : 1;
        }

        // ================================================================= A1

        private static void CheckA1(Scenario stop, Scenario inter, Scenario park)
        {
            Console.WriteLine("--- A1. Восстановление исходных условий (клиент, блоки 7–10) ---");
            foreach (Scenario s in new[] { stop, inter, park })
            {
                SceneState first = Restore.Apply(s);
                string fp = Restore.Fingerprint(first);
                first.VehiclePose.X = 12.5; first.VehiclePose.Z = 33; first.VehicleSpeedMps = 7; first.Gear = "REVERSE";   // «попытка» изменила состояние
                SceneState second = Restore.Apply(s);
                Check("A1 " + s.ExerciseId + ": после попытки повторный запуск возвращает то же исходное состояние",
                    Restore.Fingerprint(second) == fp
                    && second.VehicleSpeedMps == 0 && second.Gear == "FORWARD"
                    && second.VehiclePose.X == s.StartPose.X && second.VehiclePose.Z == s.StartPose.Z, "");
                Check("A1 " + s.ExerciseId + ": каждый дорожный объект сценария установлен (" + s.RoadObjects.Count + ")",
                    second.Objects.Count == s.RoadObjects.Count, "");
            }
            string text = Restore.Describe(park, "Парковка задним ходом");
            Check("A1 описание содержит название, задание, исходное положение, направление и ограничение скорости",
                text.Contains("Парковка задним ходом") && text.Contains("Задание:") && text.Contains("Исходное положение: x = 0.0 м, z = 3.0 м")
                && text.Contains("задним ходом") && text.Contains("Ограничение скорости: 3.0 м/с"), text.Replace('\n', ' '));
            Check("A1 светофор: GREEN в начале, YELLOW на 8.5 с, RED на 12 с, GREEN на 31 с, цикл повторяется",
                Restore.SignalAt(inter, 0) == "GREEN" && Restore.SignalAt(inter, 8500) == "YELLOW"
                && Restore.SignalAt(inter, 12000) == "RED" && Restore.SignalAt(inter, 31000) == "GREEN"
                && Restore.SignalAt(inter, 90000) == "GREEN" && Restore.SignalAt(inter, 98500) == "YELLOW", "");
        }

        // ================================================================= A2

        private static void CheckA2(Scenario stop, Scenario inter)
        {
            Console.WriteLine("--- A2. Регистрация событий и формирование групп ---");
            // продолжительный контакт — одно событие
            RegState st = NewState(stop);
            for (int t = 0; t <= 3000; t += 100)
            {
                bool touching = (t >= 1000 && t <= 1900) || t >= 2500;
                Registrar.ProcessFrame(st, stop, Fr(t, 0, t / 100.0, 0, 2.0, "FORWARD", null, null,
                    touching ? new[] { new Contact("cone-1", "OBSTACLE") } : new Contact[0]));
            }
            var events = AllEvents(st);
            Check("A2 контакт длится 10 кадров: одно CONTACT_STARTED; после разрыва контакта новый контакт — второе событие",
                Count(events, "CONTACT_STARTED") == 2, "найдено " + Count(events, "CONTACT_STARTED"));
            EventRecord c1 = events.First(e => e.Type == "CONTACT_STARTED");
            Check("A2 CONTACT_STARTED содержит объект, вид и скорость в момент начала контакта",
                c1.ObjectId == "cone-1" && c1.ObjectKind == "OBSTACLE" && c1.SpeedMps == 2.0 && c1.TMs == 1000, "");

            // два объекта одновременно
            st = NewState(stop);
            Registrar.ProcessFrame(st, stop, Fr(0, 0, 0, 0, 1, "FORWARD", null, null, new Contact[0]));
            Registrar.ProcessFrame(st, stop, Fr(100, 0, 0, 0, 1, "FORWARD", null, null,
                new[] { new Contact("cone-1", "OBSTACLE"), new Contact("cone-2", "OBSTACLE") }));
            Check("A2 два разных объекта в одном кадре: два события", Count(AllEvents(st), "CONTACT_STARTED") == 2, "");

            // стоп-линия: сигнал в момент пересечения
            foreach (string signal in new[] { "GREEN", "RED" })
            {
                st = NewState(inter);
                Registrar.ProcessFrame(st, inter, Fr(0, 0, 29.0, 0, 5, "FORWARD", signal, null, new Contact[0]));
                Registrar.ProcessFrame(st, inter, Fr(100, 0, 29.5, 0, 5, "FORWARD", signal, null, new Contact[0]));
                Registrar.ProcessFrame(st, inter, Fr(200, 0, 30.2, 0, 5, "FORWARD", signal, null, new Contact[0]));
                Registrar.ProcessFrame(st, inter, Fr(300, 0, 31.0, 0, 5, "FORWARD", signal, null, new Contact[0]));
                List<EventRecord> crossings = AllEvents(st).Where(e => e.Type == "STOP_LINE_CROSSED").ToList();
                Check("A2 пересечение стоп-линии при сигнале " + signal + ": одно событие, сигнал записан, время 200 мс",
                    crossings.Count == 1 && crossings[0].Signal == signal && crossings[0].TMs == 200, "событий " + crossings.Count);
            }
            st = NewState(inter);
            Registrar.ProcessFrame(st, inter, Fr(0, 0, 31.0, 0, 3, "REVERSE", "GREEN", null, new Contact[0]));
            Registrar.ProcessFrame(st, inter, Fr(100, 0, 29.0, 0, 3, "REVERSE", "GREEN", null, new Contact[0]));
            Check("A2 движение назад через стоп-линию (против хода) не регистрируется",
                Count(AllEvents(st), "STOP_LINE_CROSSED") == 0, "");
            st = NewState(inter);
            Registrar.ProcessFrame(st, inter, Fr(0, 5.0, 29.0, 0, 5, "FORWARD", "GREEN", null, new Contact[0]));
            Registrar.ProcessFrame(st, inter, Fr(100, 5.0, 31.0, 0, 5, "FORWARD", "GREEN", null, new Contact[0]));
            Check("A2 переход за линию вне её протяжённости (x = 5 при линии −3,5…3,5) не регистрируется",
                Count(AllEvents(st), "STOP_LINE_CROSSED") == 0, "");

            // границы участка
            st = NewState(inter);
            double[] xs = { 0, 5.5, 6.5, 6.8, 5.0, 0 };
            for (int i = 0; i < xs.Length; i++)
            {
                Registrar.ProcessFrame(st, inter, Fr(i * 100, xs[i], 10, 0, 2, "FORWARD", null, null, new Contact[0]));
            }
            List<EventRecord> bc = AllEvents(st).Where(e => e.Type == "BOUNDARY_CROSSED").ToList();
            Check("A2 выезд за границу и возврат: EXIT затем ENTER, по одному событию",
                bc.Count == 2 && bc[0].Direction == "EXIT" && bc[1].Direction == "ENTER", "событий " + bc.Count);

            // период записи
            st = NewState(stop);
            for (int t = 0; t <= 2000; t += 100)
            {
                Registrar.ProcessFrame(st, stop, Fr(t, 0, t / 100.0, 0, 2.0, "FORWARD", null, null, new Contact[0]));
            }
            List<EventRecord> samples = AllEvents(st).Where(e => e.Type == "POSITION_SAMPLE").ToList();
            Check("A2 кадры каждые 100 мс при периоде 200 мс: записи в моменты 0, 200, …, 2000 (11 записей)",
                samples.Count == 11 && samples[1].TMs == 200 && samples.Last().TMs == 2000, "записей " + samples.Count);

            // завершение: последняя запись, группа, сообщение о завершении
            st = NewState(stop);
            st.BatchMaxEvents = 3;
            for (int t = 0; t <= 3000; t += 100)
            {
                bool last = t == 3000;
                Registrar.ProcessFrame(st, stop, Fr(t, 0, 40 + t / 1000.0, 0, last ? 0 : 1.0, "FORWARD", null, last ? "COMPLETED" : null,
                    t == 1000 ? new[] { new Contact("cone-1", "OBSTACLE") } : new Contact[0]));
            }
            List<Message> msgs = st.Outbox;
            Message fin = msgs.Last();
            List<Message> batches = msgs.Where(m => m.Kind == "BATCH").ToList();
            Check("A2 завершение: последним в очереди стоит FINISH с duration_ms = 3000 и finish_reason = COMPLETED",
                fin.Kind == "FINISH" && fin.DurationMs == 3000 && fin.FinishReason == "COMPLETED", "");
            Check("A2 declared_batch_count = номеру последней группы (" + batches.Count + ")",
                fin.DeclaredBatchCount == batches.Count && batches.Last().BatchNumber == batches.Count, "");
            Check("A2 номера групп идут подряд с 1, event_count равен числу событий",
                batches.Select(b => b.BatchNumber).SequenceEqual(Enumerable.Range(1, batches.Count))
                && batches.All(b => b.EventCount == b.Events.Count), "");
            List<EventRecord> all = batches.SelectMany(b => b.Events).ToList();
            Check("A2 события нумеруются сквозь группы подряд с 1 (" + all.Count + " событий)",
                all.Select(e => e.EventNo).SequenceEqual(Enumerable.Range(1, all.Count).Select(i => (long)i)), "");
            EventRecord lastEvent = all.Last();
            Check("A2 последнее событие журнала — POSITION_SAMPLE в момент завершения (3000 мс, скорость 0)",
                lastEvent.Type == "POSITION_SAMPLE" && lastEvent.TMs == 3000 && lastEvent.SpeedMps == 0, "");
            // закрытие по числу событий: в каждом кадре ровно одно событие (запись положения), порог 3
            st = NewState(stop);
            st.BatchMaxEvents = 3; st.BatchMaxIntervalMs = 1000000;
            for (int t = 0; t < 2000; t += 200)
            {
                Registrar.ProcessFrame(st, stop, Fr(t, 0, 0, 0, 0, "FORWARD", null, null, new Contact[0]));
            }
            Check("A2 закрытие группы по числу событий: 10 кадров при пороге 3 дают три группы по 3 события и одно событие в буфере",
                st.Outbox.Count == 3 && st.Outbox.All(m => m.EventCount == 3) && st.Buffer.Count == 1, "групп " + st.Outbox.Count);

            // закрытие по интервалу
            st = NewState(stop);
            st.BatchMaxEvents = 1000; st.BatchMaxIntervalMs = 1000;
            for (int t = 0; t <= 2500; t += 100)
            {
                Registrar.ProcessFrame(st, stop, Fr(t, 0, 0, 0, 0, "FORWARD", null, null, new Contact[0]));
            }
            Check("A2 закрытие группы по интервалу 1000 мс: за 2,5 с закрыто 2 группы, остальное — в буфере",
                st.Outbox.Count == 2 && st.Buffer.Count > 0, "групп " + st.Outbox.Count);
        }

        // ================================================================= A3

        /// <summary>Канал со сценарием ответов: код, или -1 — нет ответа. Запоминает полученные тела.</summary>
        private sealed class Script
        {
            public readonly Queue<int> Answers;
            public readonly List<string> Received = new List<string>();
            public int Default = 200;
            public Script(params int[] answers) { Answers = new Queue<int>(answers); }
            public ChannelResponse Send(string path, string body)
            {
                Received.Add(body);
                int code = Answers.Count > 0 ? Answers.Dequeue() : Default;
                return code < 0 ? null : new ChannelResponse(code, "{}");
            }
        }

        private static void CheckA3()
        {
            Console.WriteLine("--- A3. Доставка очереди с повтором ---");
            List<Message> Queue() => new List<Message>
            {
                Batch(1, 2), Batch(2, 2), Batch(3, 1),
                new Message { Kind = "FINISH", DurationMs = 5000, FinishReason = "COMPLETED", DeclaredBatchCount = 3 }
            };

            // успех
            var q = Queue(); var d = new DeliveryState(); var script = new Script();
            PumpResult r = Delivery.Pump(q, d, "a-0001", script.Send, ms => { }, 0);
            Check("A3 все ответы 200: очередь пуста, завершение доставлено, результат Done, 4 запроса, повторов нет",
                r == PumpResult.Done && q.Count == 0 && d.FinishDelivered && d.Requests == 4 && d.Retries == 0, r.ToString());
            Check("A3 порядок отправки совпадает с порядком очереди (группы 1, 2, 3, затем FINISH)",
                script.Received.Count == 4 && script.Received[0].Contains("\"batch_number\":1") && script.Received[1].Contains("\"batch_number\":2")
                && script.Received[2].Contains("\"batch_number\":3") && script.Received[3].Contains("FINISH"), "");

            // временные ошибки: нет ответа, 503; рост паузы до предела
            q = Queue(); d = new DeliveryState(); script = new Script(-1, -1, -1, 503, -1, -1);
            var waits = new List<long>();
            r = Delivery.Pump(q, d, "a-0001", script.Send, waits.Add, 0);
            Check("A3 6 неудач подряд (нет ответа, 503): паузы 1000, 2000, 4000, 8000, 8000, 8000 мс — рост ограничен пределом",
                waits.SequenceEqual(new long[] { 1000, 2000, 4000, 8000, 8000, 8000 }), string.Join(",", waits));
            Check("A3 после успеха пауза возвращается к начальной (delay_0 = 1000 мс)", d.DelayMs == 1000, "");
            Check("A3 при повторах первая группа отправлялась снова (7 отправок группы 1), следующая не опережала её",
                script.Received.Take(7).All(b => b.Contains("\"batch_number\":1")) && script.Received[7].Contains("\"batch_number\":2"),
                "первые 7 тел: " + script.Received.Take(7).Count(b => b.Contains("\"batch_number\":1")));
            Check("A3 после восстановления связи очередь передана целиком: Done, потерь нет",
                r == PumpResult.Done && q.Count == 0 && d.Rejected.Count == 0, r.ToString());

            // 4xx
            q = Queue(); d = new DeliveryState(); script = new Script(200, 400);
            r = Delivery.Pump(q, d, "a-0001", script.Send, ms => { }, 0);
            Check("A3 ответ 400 на группу 2: сообщение перенесено в список отклонённых, очередь не заблокирована",
                d.Rejected.Count == 1 && d.Rejected[0].Message.BatchNumber == 2 && d.Rejected[0].Code == 400 && r == PumpResult.Done && q.Count == 0, "");
            Check("A3 отклонённое сообщение не отправлялось повторно (всего 4 запроса)", d.Requests == 4 && d.Retries == 0, "запросов " + d.Requests);

            // 404
            q = Queue(); d = new DeliveryState(); script = new Script(404);
            Delivery.Pump(q, d, "a-9999", script.Send, ms => { }, 0);
            Check("A3 ответ 404 (неизвестная попытка): повтор не выполняется, сообщение в списке отклонённых", d.Rejected.Count == 1 && d.Retries == 0, "");

            // связь недоступна: очередь сохраняется
            q = Queue(); d = new DeliveryState(); script = new Script(); script.Default = -1;
            r = Delivery.Pump(q, d, "a-0001", script.Send, ms => { }, 2);
            Check("A3 сервер недоступен: Suspended, очередь сохранена целиком (4 сообщения), первым остаётся группа 1",
                r == PumpResult.Suspended && q.Count == 4 && q[0].BatchNumber == 1, r + ", в очереди " + q.Count);
            script.Default = 200;
            r = Delivery.Pump(q, d, "a-0001", script.Send, ms => { }, 2);
            Check("A3 после восстановления связи очередь передаётся с группы 1 и завершается Done",
                r == PumpResult.Done && q.Count == 0 && d.FinishDelivered, r.ToString());

            // ожидание сообщений
            q = new List<Message>(); d = new DeliveryState();
            r = Delivery.Pump(q, d, "a-0001", new Script().Send, ms => { }, 0);
            Check("A3 очередь пуста, завершение не доставлено: ожидание новых сообщений (WaitForMessages)", r == PumpResult.WaitForMessages, r.ToString());

            // формат JSON
            Message b = Batch(7, 2);
            using (JsonDocument doc = JsonDocument.Parse(MessageJson.Write(b)))
            {
                JsonElement root = doc.RootElement;
                Check("A3 тело BATCH — допустимый JSON с batch_number, event_count и событиями с контекстом",
                    root.GetProperty("kind").GetString() == "BATCH" && root.GetProperty("batch_number").GetInt32() == 7
                    && root.GetProperty("events").GetArrayLength() == 2
                    && root.GetProperty("events")[0].GetProperty("context").GetProperty("gear").GetString() == "FORWARD", "");
            }
            using (JsonDocument doc = JsonDocument.Parse(MessageJson.Write(Queue().Last())))
            {
                Check("A3 тело FINISH — допустимый JSON с duration_ms, finish_reason, declared_batch_count",
                    doc.RootElement.GetProperty("declared_batch_count").GetInt32() == 3
                    && doc.RootElement.GetProperty("finish_reason").GetString() == "COMPLETED", "");
            }
        }

        // ================================================================= вспомогательное

        private static Message Batch(int number, int events)
        {
            var list = new List<EventRecord>();
            for (int i = 0; i < events; i++)
            {
                list.Add(new EventRecord { EventNo = i + 1, TMs = i * 200, Type = "POSITION_SAMPLE", X = 0.5, Z = i, HeadingDeg = 0, SpeedMps = 1.25, Gear = "FORWARD" });
            }
            return new Message { Kind = "BATCH", BatchNumber = number, EventCount = events, Events = list };
        }

        private static RegState NewState(Scenario sc)
        {
            return new RegState();
        }

        private static Frame Fr(long t, double x, double z, double h, double v, string gear, string signal, string finish, Contact[] contacts)
        {
            return new Frame { TMs = t, X = x, Z = z, HeadingDeg = h, SpeedMps = v, Gear = gear, Signal = signal, FinishReason = finish, Contacts = contacts.ToList() };
        }

        /// <summary>Все события: закрытые группы и буфер, по номерам.</summary>
        private static List<EventRecord> AllEvents(RegState st)
        {
            var list = new List<EventRecord>();
            foreach (Message m in st.Outbox)
            {
                if (m.Kind == "BATCH")
                {
                    list.AddRange(m.Events);
                }
            }
            list.AddRange(st.Buffer);
            return list.OrderBy(e => e.EventNo).ToList();
        }

        private static int Count(List<EventRecord> events, string type)
        {
            return events.Count(e => e.Type == type);
        }

        private static Scenario Load(string data, string exercise)
        {
            string path = Path.Combine(data, "exercises", exercise, "scenario-1.json");
            using (JsonDocument doc = JsonDocument.Parse(File.ReadAllText(path)))
            {
                return ScenarioLoader.FromJson(doc.RootElement);
            }
        }

        private static void Check(string name, bool ok, string detail)
        {
            total++;
            if (!ok)
            {
                failed++;
            }
            Console.WriteLine((ok ? "[OK]   " : "[FAIL] ") + name + (ok || detail.Length == 0 ? "" : "  <-- " + detail));
        }
    }
}
