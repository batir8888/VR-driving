using System.Collections.Generic;
using System.Text.Json;

namespace Simulator.Client.Demo
{
    /// <summary>
    /// Разбор JSON сценария (формат файлов data/exercises/*/scenario-N.json и ответа сервера на создание попытки)
    /// в структуру Scenario. System.Text.Json есть в .NET; в проекте Unity этот слой заменяется на Newtonsoft.Json
    /// или JsonUtility, библиотека Simulator.Client от формата не зависит.
    /// </summary>
    public static class ScenarioLoader
    {
        public static Scenario FromJson(JsonElement j)
        {
            var s = new Scenario
            {
                ExerciseId = j.GetProperty("exercise_id").GetString(),
                ScenarioVersion = j.GetProperty("scenario_version").GetInt32(),
                TaskText = j.GetProperty("task_text").GetString(),
                StartPose = new Pose
                {
                    X = j.GetProperty("start_pose").GetProperty("x").GetDouble(),
                    Z = j.GetProperty("start_pose").GetProperty("z").GetDouble(),
                    HeadingDeg = j.GetProperty("start_pose").GetProperty("heading_deg").GetDouble()
                },
                TravelDirection = j.GetProperty("travel_direction").GetString(),
                SpeedLimitMps = j.GetProperty("speed_limit_mps").GetDouble(),
                LengthM = j.GetProperty("vehicle_size").GetProperty("length_m").GetDouble(),
                WidthM = j.GetProperty("vehicle_size").GetProperty("width_m").GetDouble(),
                AreaBounds = Polygon(j.GetProperty("area_bounds")),
                TargetZone = Polygon(j.GetProperty("target_zone")),
                StopSpeedMps = j.GetProperty("tolerances").GetProperty("stop_speed_mps").GetDouble(),
                SpeedOverMps = j.GetProperty("tolerances").GetProperty("speed_over_mps").GetDouble(),
                HeadingTolDeg = j.GetProperty("tolerances").GetProperty("heading_deg").GetDouble(),
                SamplePeriodMs = j.GetProperty("sample_period_ms").GetInt32()
            };
            JsonElement th = j.GetProperty("target_heading_deg");
            if (th.ValueKind == JsonValueKind.Number)
            {
                s.HasTargetHeading = true;
                s.TargetHeadingDeg = th.GetDouble();
            }
            JsonElement ps = j.GetProperty("permitted_signals");
            if (ps.ValueKind == JsonValueKind.Array)
            {
                var list = new List<string>();
                foreach (JsonElement e in ps.EnumerateArray())
                {
                    list.Add(e.GetString());
                }
                s.PermittedSignals = list.ToArray();
            }
            foreach (JsonElement o in j.GetProperty("road_objects").EnumerateArray())
            {
                s.RoadObjects.Add(RoadObjectFrom(o));
            }
            return s;
        }

        private static RoadObject RoadObjectFrom(JsonElement o)
        {
            var r = new RoadObject { ObjectId = o.GetProperty("object_id").GetString(), Kind = o.GetProperty("kind").GetString() };
            JsonElement g = o.GetProperty("geometry");
            if (g.TryGetProperty("polygon", out JsonElement poly))
            {
                r.Polygon = Polygon(poly);
            }
            if (g.TryGetProperty("line", out JsonElement line))
            {
                r.Line = Polygon(line);
                r.Direction = new[] { g.GetProperty("direction")[0].GetDouble(), g.GetProperty("direction")[1].GetDouble() };
            }
            if (g.TryGetProperty("position", out JsonElement pos))
            {
                r.Position = new[] { pos[0].GetDouble(), pos[1].GetDouble() };
            }
            JsonElement st = o.GetProperty("initial_state");
            if (st.TryGetProperty("program", out JsonElement prog))
            {
                var phases = new List<SignalPhase>();
                foreach (JsonElement p in prog.EnumerateArray())
                {
                    phases.Add(new SignalPhase { Signal = p.GetProperty("signal").GetString(), DurationMs = p.GetProperty("duration_ms").GetInt64() });
                }
                r.Program = phases.ToArray();
                r.PhaseIndex = st.GetProperty("phase_index").GetInt32();
                r.PhaseOffsetMs = st.GetProperty("phase_offset_ms").GetInt64();
            }
            return r;
        }

        private static double[][] Polygon(JsonElement arr)
        {
            var pts = new List<double[]>();
            foreach (JsonElement p in arr.EnumerateArray())
            {
                pts.Add(new[] { p[0].GetDouble(), p[1].GetDouble() });
            }
            return pts.ToArray();
        }
    }
}
