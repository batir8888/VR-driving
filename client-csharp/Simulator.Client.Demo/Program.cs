using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net.Http;
using System.Text;
using System.Text.Json;

namespace Simulator.Client.Demo
{
    /// <summary>
    /// Демонстрация клиентских алгоритмов с реальным Java-сервером по HTTP (без Unity и VR).
    ///   generate [data] [outDir]        — записать траектории в samples/trajectories (входные данные)
    ///   replay [dir] [server] [flaky]   — воспроизвести траектории: A1 → A2 → A3 → сервер (A4–A6), сверить результат
    ///   compare [server] [b] [a]        — запросить сравнение попыток (A7)
    ///   history [server]                — прочитать список попыток с сервера (после перезапуска сервера)
    /// </summary>
    public static class Program
    {
        private static readonly HttpClient Http = new HttpClient { Timeout = TimeSpan.FromSeconds(5) };

        public static int Main(string[] args)
        {
            Console.OutputEncoding = Encoding.UTF8;
            string cmd = args.Length > 0 ? args[0] : "replay";
            switch (cmd)
            {
                case "generate": return Generate(Arg(args, 1, "data"), Arg(args, 2, Path.Combine("samples", "trajectories")));
                case "replay": return Replay(Arg(args, 1, Path.Combine("samples", "trajectories")), Arg(args, 2, "http://127.0.0.1:8080"), Arg(args, 3, "") == "flaky");
                case "compare": return CompareCmd(Arg(args, 1, "http://127.0.0.1:8080"), Arg(args, 2, ""), Arg(args, 3, ""));
                case "history": return History(Arg(args, 1, "http://127.0.0.1:8080"));
                default:
                    Console.WriteLine("Команды: generate | replay | compare | history");
                    return 2;
            }
        }

        private static string Arg(string[] a, int i, string dflt)
        {
            return a.Length > i ? a[i] : dflt;
        }

        // ================================================================= generate

        private static int Generate(string data, string outDir)
        {
            var scenarios = new Dictionary<string, Scenario>();
            foreach (Trajectories.Case c in Trajectories.All())
            {
                if (!scenarios.ContainsKey(c.Exercise))
                {
                    using (JsonDocument doc = JsonDocument.Parse(File.ReadAllText(Path.Combine(data, "exercises", c.Exercise, "scenario-1.json"))))
                    {
                        scenarios[c.Exercise] = ScenarioLoader.FromJson(doc.RootElement);
                    }
                }
                Trajectories.Write(c, scenarios[c.Exercise], outDir);
            }
            Console.WriteLine("Записано траекторий: " + Trajectories.All().Count + " в " + outDir);
            return 0;
        }

        // ================================================================= replay

        private sealed class Trajectory
        {
            public string Case, ExerciseId, LearnerId, ExpectedStatus;
            public List<string> Expected = new List<string>();
            public List<Frame> Frames = new List<Frame>();
        }

        private static Trajectory LoadTrajectory(string file)
        {
            using (JsonDocument doc = JsonDocument.Parse(File.ReadAllText(file)))
            {
                JsonElement r = doc.RootElement;
                var t = new Trajectory
                {
                    Case = r.GetProperty("case").GetString(),
                    ExerciseId = r.GetProperty("exercise_id").GetString(),
                    LearnerId = r.GetProperty("learner_id").GetString(),
                    ExpectedStatus = r.GetProperty("expected_status").GetString()
                };
                foreach (JsonElement e in r.GetProperty("expected_violations").EnumerateArray())
                {
                    t.Expected.Add(e.GetString());
                }
                foreach (JsonElement fe in r.GetProperty("frames").EnumerateArray())
                {
                    var f = new Frame
                    {
                        TMs = fe.GetProperty("t_ms").GetInt64(),
                        X = fe.GetProperty("x").GetDouble(),
                        Z = fe.GetProperty("z").GetDouble(),
                        HeadingDeg = fe.GetProperty("heading_deg").GetDouble(),
                        SpeedMps = fe.GetProperty("speed_mps").GetDouble(),
                        Gear = fe.GetProperty("gear").GetString(),
                        Signal = fe.GetProperty("signal").ValueKind == JsonValueKind.Null ? null : fe.GetProperty("signal").GetString(),
                        FinishReason = fe.GetProperty("finish_reason").ValueKind == JsonValueKind.Null ? null : fe.GetProperty("finish_reason").GetString()
                    };
                    foreach (JsonElement ce in fe.GetProperty("contacts").EnumerateArray())
                    {
                        f.Contacts.Add(new Contact(ce.GetProperty("object_id").GetString(), ce.GetProperty("object_kind").GetString()));
                    }
                    t.Frames.Add(f);
                }
                return t;
            }
        }

        private static int Replay(string dir, string server, bool flaky)
        {
            string[] files = Directory.GetFiles(dir, "*.json").OrderBy(f => f, StringComparer.Ordinal).ToArray();
            Console.WriteLine("Воспроизведение траекторий: " + files.Length + ", сервер " + server + (flaky ? ", канал с отказами" : ""));
            int ok = 0, bad = 0;
            var described = new HashSet<string>();
            foreach (string file in files)
            {
                Trajectory tr = LoadTrajectory(file);
                Console.WriteLine();
                Console.WriteLine("=== " + tr.Case + " ===");

                // A1, сервер: создать попытку
                HttpResponseMessage created = Http.PostAsync(server + "/attempts", Json("{\"learner_id\":\"" + tr.LearnerId + "\",\"exercise_id\":\"" + tr.ExerciseId + "\"}")).Result;
                using (JsonDocument cd = JsonDocument.Parse(created.Content.ReadAsStringAsync().Result))
                {
                    if ((int)created.StatusCode != 201)
                    {
                        Console.WriteLine("[FAIL] попытка не создана: " + (int)created.StatusCode + " " + cd.RootElement);
                        bad++;
                        continue;
                    }
                    string attemptId = cd.RootElement.GetProperty("attempt_id").GetString();
                    string title = cd.RootElement.GetProperty("title").GetString();
                    Scenario sc = ScenarioLoader.FromJson(cd.RootElement.GetProperty("scenario"));

                    // A1, клиент: восстановить исходные условия и показать описание
                    SceneState scene = Restore.Apply(sc);
                    if (described.Add(tr.ExerciseId))
                    {
                        Console.WriteLine(Restore.Describe(sc, title));
                    }
                    Console.WriteLine("Попытка " + attemptId + "; сценарий v" + sc.ScenarioVersion + "; состояние сцены: " + Restore.Fingerprint(scene));

                    RunAttempt(tr, sc, attemptId, server, flaky);
                    bool match = Verify(tr, attemptId, server);
                    if (match) { ok++; } else { bad++; }
                }
            }
            Console.WriteLine();
            Console.WriteLine("Итог воспроизведения: траекторий " + files.Length + ", совпало с ожиданием " + ok + ", расхождений " + bad);
            return bad == 0 ? 0 : 1;
        }

        /// <summary>A2 на каждом кадре; A3 в фоновом потоке. Паузы перед повторами выдерживаются в масштабе 1:100, чтобы демонстрация шла быстро; в журнал попадают исходные значения.</summary>
        private static void RunAttempt(Trajectory tr, Scenario sc, string attemptId, string server, bool flaky)
        {
            var reg = new RegState();
            var del = new DeliveryState();
            int requestNo = 0;
            int lostAcks = 0, outageFailures = 0, serverErrors = 0;

            Channel channel = (path, body) =>
            {
                requestNo++;
                if (flaky)
                {
                    if (requestNo >= 3 && requestNo <= 6)       // обрыв связи: четыре отправки подряд без ответа
                    {
                        outageFailures++;
                        return null;
                    }
                    if (requestNo == 7)                         // сервер временно отвечает 503
                    {
                        serverErrors++;
                        return new ChannelResponse(503, "{}");
                    }
                    if (requestNo == 9)                         // сообщение дошло, ответ потерян: клиент отправит его повторно
                    {
                        try { Http.PostAsync(server + path, Json(body)).Wait(); } catch (Exception) { }
                        lostAcks++;
                        return null;
                    }
                }
                try
                {
                    HttpResponseMessage r = Http.PostAsync(server + path, Json(body)).Result;
                    return new ChannelResponse((int)r.StatusCode, r.Content.ReadAsStringAsync().Result);
                }
                catch (Exception)
                {
                    return null;
                }
            };

            System.Threading.Thread worker = Delivery.StartWorker(reg.Outbox, del, attemptId, channel,
                ms => System.Threading.Thread.Sleep((int)(ms / 100)));                  // A3: фоновый поток
            foreach (Frame f in tr.Frames)
            {
                Registrar.ProcessFrame(reg, sc, f);                                     // A2: кадр обработан без ожидания сети
            }
            worker.Join();                                                              // после завершения — до подтверждения
            string end = del.FinishDelivered && reg.Outbox.Count == 0 ? "Done"
                : del.FinishRejected ? "завершение отклонено: " + del.FinishRejectReason : "не завершена";
            Console.WriteLine("Кадров " + tr.Frames.Count + "; групп " + (reg.NextBatchNumber - 1) + "; событий " + (reg.NextEventNo - 1)
                + "; запросов " + del.Requests + "; неудачных отправок " + del.Failures
                + "; доставка: " + end + "; отклонено " + del.Rejected.Count
                + (flaky ? "; обрывов " + outageFailures + ", ответов 503 " + serverErrors + ", потерянных подтверждений " + lostAcks
                    + "; паузы, мс: " + string.Join(",", del.Waits) : ""));
        }

        private static bool Verify(Trajectory tr, string attemptId, string server)
        {
            HttpResponseMessage r = Http.GetAsync(server + "/attempts/" + attemptId + "/result").Result;
            string text = r.Content.ReadAsStringAsync().Result;
            if ((int)r.StatusCode != 200)
            {
                Console.WriteLine("[FAIL] результат не получен: " + (int)r.StatusCode + " " + text);
                return false;
            }
            using (JsonDocument doc = JsonDocument.Parse(text))
            {
                JsonElement res = doc.RootElement;
                var got = new List<string>();
                Console.WriteLine("Результат: " + res.GetProperty("exercise_title").GetString() + ", длительность "
                    + res.GetProperty("duration_ms").GetInt64() + " мс, статус " + res.GetProperty("status").GetString()
                    + ", " + res.GetProperty("finality").GetString());
                foreach (JsonElement v in res.GetProperty("violations").EnumerateArray())
                {
                    got.Add(v.GetProperty("type").GetString());
                    Console.WriteLine("  ошибка " + v.GetProperty("violation_no").GetInt32() + ": " + v.GetProperty("type").GetString()
                        + ", t = " + v.GetProperty("t_ms").GetInt64() + " мс, " + v.GetProperty("reason").GetString()
                        + (v.GetProperty("source_event_no").ValueKind == JsonValueKind.Null ? " (по конечному состоянию)" : " (событие № " + v.GetProperty("source_event_no").GetInt64() + ")"));
                }
                bool same = got.SequenceEqual(tr.Expected)
                    && res.GetProperty("status").GetString() == tr.ExpectedStatus
                    && res.GetProperty("finality").GetString() == "FINAL";
                Console.WriteLine((same ? "[OK]   " : "[FAIL] ") + "ожидалось [" + string.Join(", ", tr.Expected) + "], получено [" + string.Join(", ", got) + "]");
                return same;
            }
        }

        // ================================================================= compare / history

        private static int CompareCmd(string server, string b, string a)
        {
            string url = server + "/compare?b=" + Uri.EscapeDataString(b) + (a.Length > 0 ? "&a=" + Uri.EscapeDataString(a) : "");
            HttpResponseMessage r = Http.GetAsync(url).Result;
            Console.WriteLine((int)r.StatusCode + " " + r.Content.ReadAsStringAsync().Result);
            return (int)r.StatusCode == 200 ? 0 : 1;
        }

        private static int History(string server)
        {
            string text = Http.GetStringAsync(server + "/attempts").Result;
            using (JsonDocument doc = JsonDocument.Parse(text))
            {
                int n = doc.RootElement.GetArrayLength();
                Console.WriteLine("История на сервере: попыток " + n);
                foreach (JsonElement a in doc.RootElement.EnumerateArray())
                {
                    Console.WriteLine("  " + a.GetProperty("attempt_id").GetString() + " " + a.GetProperty("exercise_id").GetString()
                        + " " + a.GetProperty("state").GetString());
                }
                return n > 0 ? 0 : 1;
            }
        }

        private static StringContent Json(string body)
        {
            return new StringContent(body, new UTF8Encoding(false), "application/json");
        }
    }
}
