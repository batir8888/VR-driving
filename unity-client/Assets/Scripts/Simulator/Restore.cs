using System;
using System.Collections.Generic;
using System.Globalization;
using System.Text;

namespace Simulator.Client
{
    /// <summary>
    /// A1. Подготовка попытки с одинаковыми исходными условиями — клиентская часть (блоки 7–10).
    /// Серверная часть (блоки 1–6) — server-java/src/sim/Prepare.java.
    /// Схема: UP-004/UP-004_A1_схема.png, шаги — таблица 4.4. Модуль упражнений клиента.
    /// </summary>
    public static class Restore
    {
        /// <summary>Блоки 7–9: автомобиль в start_pose со скоростью 0 и направлением FORWARD; объекты в initial_state.</summary>
        public static SceneState Apply(Scenario s)
        {
            var state = new SceneState();
            // блок 7: установить автомобиль в start_pose: скорость 0, направление FORWARD
            state.VehiclePose = new Pose { X = s.StartPose.X, Z = s.StartPose.Z, HeadingDeg = s.StartPose.HeadingDeg };
            state.VehicleSpeedMps = 0;
            state.Gear = "FORWARD";
            // блоки 8–9: для каждого объекта из road_objects установить объект в initial_state
            foreach (RoadObject o in s.RoadObjects)
            {
                var placed = new PlacedObject { ObjectId = o.ObjectId, Kind = o.Kind };
                if (o.Kind == "TRAFFIC_SIGNAL")
                {
                    placed.SignalPhaseIndex = o.PhaseIndex;
                    placed.SignalPhaseElapsedMs = o.PhaseOffsetMs;
                }
                state.Objects.Add(placed);
            }
            return state;
        }

        /// <summary>Блок 10: текст описания для модуля интерфейса — название, задание, исходное положение, направление, ограничение скорости.</summary>
        public static string Describe(Scenario s, string title)
        {
            CultureInfo c = CultureInfo.InvariantCulture;
            var sb = new StringBuilder();
            sb.Append("Упражнение: ").Append(title).Append('\n');
            sb.Append("Задание: ").Append(s.TaskText).Append('\n');
            sb.Append("Исходное положение: x = ").Append(s.StartPose.X.ToString("0.0", c))
              .Append(" м, z = ").Append(s.StartPose.Z.ToString("0.0", c))
              .Append(" м, курс ").Append(s.StartPose.HeadingDeg.ToString("0", c)).Append("°\n");
            sb.Append("Направление движения: ").Append(s.TravelDirection == "REVERSE" ? "задним ходом" : "вперёд").Append('\n');
            sb.Append("Ограничение скорости: ").Append(s.SpeedLimitMps.ToString("0.0", c)).Append(" м/с (")
              .Append((s.SpeedLimitMps * 3.6).ToString("0", c)).Append(" км/ч)");
            return sb.ToString();
        }

        /// <summary>Отпечаток состояния сцены: два запуска из исходных условий должны давать одинаковый отпечаток.</summary>
        public static string Fingerprint(SceneState st)
        {
            CultureInfo c = CultureInfo.InvariantCulture;
            var sb = new StringBuilder();
            sb.Append(st.VehiclePose.X.ToString("R", c)).Append('|').Append(st.VehiclePose.Z.ToString("R", c)).Append('|')
              .Append(st.VehiclePose.HeadingDeg.ToString("R", c)).Append('|').Append(st.VehicleSpeedMps.ToString("R", c))
              .Append('|').Append(st.Gear);
            foreach (PlacedObject o in st.Objects)
            {
                sb.Append(';').Append(o.ObjectId).Append(':').Append(o.Kind).Append(':')
                  .Append(o.SignalPhaseIndex).Append(':').Append(o.SignalPhaseElapsedMs);
            }
            return sb.ToString();
        }

        /// <summary>Сигнал светофора в момент t_ms от начала попытки по программе объекта (циклически).</summary>
        public static string SignalAt(Scenario s, long tMs)
        {
            foreach (RoadObject o in s.RoadObjects)
            {
                if (o.Kind != "TRAFFIC_SIGNAL" || o.Program == null || o.Program.Length == 0)
                {
                    continue;
                }
                long total = 0;
                foreach (SignalPhase p in o.Program)
                {
                    total += p.DurationMs;
                }
                long pos = 0;
                for (int i = 0; i < o.PhaseIndex; i++)
                {
                    pos += o.Program[i].DurationMs;
                }
                pos = (pos + o.PhaseOffsetMs + tMs) % total;
                foreach (SignalPhase p in o.Program)
                {
                    if (pos < p.DurationMs)
                    {
                        return p.Signal;
                    }
                    pos -= p.DurationMs;
                }
            }
            return null;
        }
    }
}
