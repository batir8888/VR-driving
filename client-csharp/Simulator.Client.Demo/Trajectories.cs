using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Text;

namespace Simulator.Client.Demo
{
    /// <summary>
    /// Генератор записанных траекторий — входных данных клиентских алгоритмов A2 и A3 вместо VR-сцены.
    /// Это детерминированная кусочно-линейная кинематика по опорным точкам (не физика WheelCollider):
    /// положение, курс и скорость интерполируются между точками с шагом кадра 100 мс.
    /// Ожидаемый перечень ошибок каждого случая задан здесь, по определению случая, независимо от сервера.
    /// </summary>
    public static class Trajectories
    {
        public const int FrameMs = 100;

        public sealed class Key
        {
            public long T;
            public double X, Z, H, V;
            public string Gear;
            public Key(long t, double x, double z, double h, double v, string gear) { T = t; X = x; Z = z; H = h; V = v; Gear = gear; }
        }

        public sealed class Touch
        {
            public long From, To;
            public string ObjectId, Kind;
            public Touch(long from, long to, string id, string kind) { From = from; To = to; ObjectId = id; Kind = kind; }
        }

        public sealed class Case
        {
            public string Exercise, Name, FinishReason = "COMPLETED";
            public string[] Expected;
            public List<Key> Keys = new List<Key>();
            public List<Touch> Touches = new List<Touch>();
        }

        public static List<Case> All()
        {
            var list = new List<Case>();
            // ---- остановка в заданной зоне
            list.Add(Make("stop-zone", "stop_ok", new Key(0, 0, 0, 0, 0, "FORWARD"), new Key(3000, 0, 20, 0, 8, "FORWARD"),
                new Key(6000, 0, 38, 0, 6, "FORWARD"), new Key(8500, 0, 43, 0, 0, "FORWARD")));
            list.Add(Make("stop-zone", "stop_short_of_zone", new Key(0, 0, 0, 0, 0, "FORWARD"), new Key(3000, 0, 20, 0, 8, "FORWARD"),
                new Key(6000, 0, 30, 0, 0, "FORWARD"), "TARGET_ZONE_MISSED"));
            Case cones = Make("stop-zone", "stop_with_cone_contacts", new Key(0, 0, 0, 0, 0, "FORWARD"), new Key(3000, 0, 20, 0, 8, "FORWARD"),
                new Key(6000, 0, 38, 0, 6, "FORWARD"), new Key(8500, 0, 43, 0, 0, "FORWARD"), "COLLISION", "COLLISION");
            cones.Touches.Add(new Touch(3000, 4200, "cone-1", "OBSTACLE"));   // один продолжительный контакт: 12 кадров
            cones.Touches.Add(new Touch(5800, 6000, "cone-2", "OBSTACLE"));
            list.Add(cones);
            list.Add(Make("stop-zone", "stop_left_area", new Key(0, 0, 0, 0, 0, "FORWARD"), new Key(2000, 0, 12, 0, 6, "FORWARD"),
                new Key(3000, 4.6, 18, 0, 6, "FORWARD"), new Key(4000, 0, 24, 0, 6, "FORWARD"), new Key(6000, 0, 38, 0, 5, "FORWARD"),
                new Key(8500, 0, 43, 0, 0, "FORWARD"), "LEFT_AREA"));
            Case aborted = Make("stop-zone", "stop_aborted", new Key(0, 0, 0, 0, 0, "FORWARD"), new Key(3000, 0, 20, 0, 5, "FORWARD"),
                "NOT_STOPPED", "TARGET_ZONE_MISSED", "ATTEMPT_ABORTED");
            aborted.FinishReason = "ABORTED";
            list.Add(aborted);
            // ---- проезд регулируемого перекрёстка (программа сигналов: GREEN 0–8 с, YELLOW 8–10 с, RED 10–30 с)
            list.Add(Make("intersection", "green_pass", new Key(0, 0, 0, 0, 0, "FORWARD"), new Key(3000, 0, 15, 0, 8, "FORWARD"),
                new Key(5500, 0, 35, 0, 8, "FORWARD"), new Key(9000, 0, 65, 0, 5, "FORWARD")));
            list.Add(Make("intersection", "red_pass", new Key(0, 0, 0, 0, 0, "FORWARD"), new Key(13000, 0, 0, 0, 0, "FORWARD"),
                new Key(16000, 0, 22, 0, 8, "FORWARD"), new Key(18000, 0, 38, 0, 8, "FORWARD"), new Key(22000, 0, 65, 0, 5, "FORWARD"),
                "STOP_LINE_PROHIBITED"));
            list.Add(Make("intersection", "yellow_pass", new Key(0, 0, 0, 0, 2, "FORWARD"), new Key(9000, 0, 30, 0, 5, "FORWARD"),
                new Key(15000, 0, 65, 0, 5, "FORWARD"), "STOP_LINE_PROHIBITED"));
            list.Add(Make("intersection", "speeding_on_green", new Key(0, 0, 0, 0, 0, "FORWARD"), new Key(1500, 0, 8, 0, 10, "FORWARD"),
                new Key(4000, 0, 40, 0, 10, "FORWARD"), new Key(5500, 0, 52, 0, 6, "FORWARD"), new Key(8000, 0, 65, 0, 5, "FORWARD"),
                "SPEED_LIMIT_EXCEEDED"));
            Case obstacle = Make("intersection", "obstacle_contact", new Key(0, 0, 0, 0, 0, "FORWARD"), new Key(3000, 0, 15, 0, 8, "FORWARD"),
                new Key(5500, 0, 35, 0, 8, "FORWARD"), new Key(9000, 0, 65, 0, 5, "FORWARD"), "COLLISION");
            obstacle.Touches.Add(new Touch(6500, 7000, "obstacle-1", "OBSTACLE"));
            list.Add(obstacle);
            // ---- парковка задним ходом
            list.Add(Make("reverse-parking", "parked_correctly", new Key(0, 0, 3, 0, 0, "REVERSE"), new Key(3000, 0, -1, 0, 1.5, "REVERSE"),
                new Key(6000, 0, -7.5, 0, 0, "REVERSE")));
            list.Add(Make("reverse-parking", "parked_with_heading_12", new Key(0, 0, 3, 0, 0, "REVERSE"), new Key(3000, 0, -1, 6, 1.5, "REVERSE"),
                new Key(6000, 0, -7.5, 12, 0, "REVERSE"), "WRONG_ORIENTATION"));
            list.Add(Make("reverse-parking", "entered_forward", new Key(0, 0, 3, 0, 0, "FORWARD"), new Key(3000, 0, 0, 180, 1.5, "FORWARD"),
                new Key(6000, 0, -7.5, 180, 0, "FORWARD"), "WRONG_ORIENTATION", "WRONG_ENTRY_DIRECTION"));
            Case border = Make("reverse-parking", "border_touched", new Key(0, 0, 3, 0, 0, "REVERSE"), new Key(3000, 0, -1, 0, 1.5, "REVERSE"),
                new Key(6000, 0, -7.5, 0, 0, "REVERSE"), "BORDER_CONTACT");
            border.Touches.Add(new Touch(4000, 4600, "border-left", "BORDER"));
            list.Add(border);
            list.Add(Make("reverse-parking", "not_stopped_in_place", new Key(0, 0, 3, 0, 0, "REVERSE"), new Key(3000, 0, -1, 0, 1.5, "REVERSE"),
                new Key(6000, 0, -7.5, 0, 1.0, "REVERSE"), "NOT_STOPPED"));
            return list;
        }

        /// <summary>Аргументы — опорные точки (Key), затем ожидаемые коды ошибок (string).</summary>
        private static Case Make(string exercise, string name, params object[] parts)
        {
            var c = new Case { Exercise = exercise, Name = name };
            var expected = new List<string>();
            foreach (object p in parts)
            {
                if (p is Key k) { c.Keys.Add(k); } else { expected.Add((string)p); }
            }
            c.Expected = expected.ToArray();
            return c;
        }

        /// <summary>Кадры траектории с шагом 100 мс; последний кадр завершает попытку.</summary>
        public static List<Frame> Frames(Case c, Scenario sc)
        {
            var frames = new List<Frame>();
            long end = c.Keys[c.Keys.Count - 1].T;
            int seg = 0;
            for (long t = 0; t <= end; t += FrameMs)
            {
                while (seg + 2 < c.Keys.Count && t > c.Keys[seg + 1].T)
                {
                    seg++;
                }
                Key a = c.Keys[seg], b = c.Keys[Math.Min(seg + 1, c.Keys.Count - 1)];
                double u = b.T == a.T ? 1.0 : Math.Min(1.0, Math.Max(0.0, (double)(t - a.T) / (b.T - a.T)));
                var f = new Frame
                {
                    TMs = t,
                    X = Round(a.X + (b.X - a.X) * u),
                    Z = Round(a.Z + (b.Z - a.Z) * u),
                    HeadingDeg = Round(a.H + (b.H - a.H) * u),
                    SpeedMps = Round(a.V + (b.V - a.V) * u),
                    Gear = u >= 1.0 ? b.Gear : a.Gear,
                    Signal = Restore.SignalAt(sc, t)
                };
                foreach (Touch touch in c.Touches)
                {
                    if (t >= touch.From && t <= touch.To)
                    {
                        f.Contacts.Add(new Contact(touch.ObjectId, touch.Kind));
                    }
                }
                if (t == end)
                {
                    f.FinishReason = c.FinishReason;
                }
                frames.Add(f);
            }
            return frames;
        }

        private static double Round(double v)
        {
            return Math.Round(v, 3);
        }

        // ------------------------------------------------------------ файлы

        public static void Write(Case c, Scenario sc, string dir)
        {
            CultureInfo ci = CultureInfo.InvariantCulture;
            var sb = new StringBuilder();
            sb.Append("{\n  \"case\": ").Append(MessageJson.Str(c.Exercise + "/" + c.Name)).Append(",\n");
            sb.Append("  \"exercise_id\": ").Append(MessageJson.Str(c.Exercise)).Append(",\n");
            sb.Append("  \"learner_id\": \"L-001\",\n");
            sb.Append("  \"expected_status\": ").Append(MessageJson.Str(c.Expected.Length == 0 ? "ACHIEVED" : "NOT_ACHIEVED")).Append(",\n");
            sb.Append("  \"expected_violations\": [");
            for (int i = 0; i < c.Expected.Length; i++)
            {
                sb.Append(i > 0 ? ", " : "").Append(MessageJson.Str(c.Expected[i]));
            }
            sb.Append("],\n  \"frames\": [\n");
            List<Frame> frames = Frames(c, sc);
            for (int i = 0; i < frames.Count; i++)
            {
                Frame f = frames[i];
                sb.Append("    {\"t_ms\": ").Append(f.TMs)
                  .Append(", \"x\": ").Append(f.X.ToString("0.###", ci)).Append(", \"z\": ").Append(f.Z.ToString("0.###", ci))
                  .Append(", \"heading_deg\": ").Append(f.HeadingDeg.ToString("0.###", ci))
                  .Append(", \"speed_mps\": ").Append(f.SpeedMps.ToString("0.###", ci))
                  .Append(", \"gear\": ").Append(MessageJson.Str(f.Gear))
                  .Append(", \"signal\": ").Append(MessageJson.Str(f.Signal))
                  .Append(", \"contacts\": [");
                for (int k = 0; k < f.Contacts.Count; k++)
                {
                    sb.Append(k > 0 ? ", " : "").Append("{\"object_id\": ").Append(MessageJson.Str(f.Contacts[k].ObjectId))
                      .Append(", \"object_kind\": ").Append(MessageJson.Str(f.Contacts[k].ObjectKind)).Append('}');
                }
                sb.Append("], \"finish_reason\": ").Append(MessageJson.Str(f.FinishReason)).Append('}')
                  .Append(i + 1 < frames.Count ? ",\n" : "\n");
            }
            sb.Append("  ]\n}\n");
            Directory.CreateDirectory(dir);
            File.WriteAllText(Path.Combine(dir, c.Exercise + "__" + c.Name + ".json"), sb.ToString(), new UTF8Encoding(false));
        }
    }
}
