using System.Globalization;
using System.Text;

namespace Simulator.Client
{
    /// <summary>Запись сообщения в JSON по docs/message-format.md без внешних библиотек. Числа — в инвариантной культуре.</summary>
    public static class MessageJson
    {
        public static string Write(Message m)
        {
            var sb = new StringBuilder();
            if (m.Kind == "FINISH")
            {
                sb.Append("{\"kind\":\"FINISH\",\"duration_ms\":").Append(m.DurationMs)
                  .Append(",\"finish_reason\":").Append(Str(m.FinishReason))
                  .Append(",\"declared_batch_count\":").Append(m.DeclaredBatchCount).Append('}');
                return sb.ToString();
            }
            sb.Append("{\"kind\":\"BATCH\",\"batch_number\":").Append(m.BatchNumber)
              .Append(",\"event_count\":").Append(m.EventCount).Append(",\"events\":[");
            for (int i = 0; i < m.Events.Count; i++)
            {
                if (i > 0)
                {
                    sb.Append(',');
                }
                WriteEvent(sb, m.Events[i]);
            }
            sb.Append("]}");
            return sb.ToString();
        }

        private static void WriteEvent(StringBuilder sb, EventRecord e)
        {
            sb.Append("{\"event_no\":").Append(e.EventNo).Append(",\"t_ms\":").Append(e.TMs)
              .Append(",\"type\":").Append(Str(e.Type)).Append(",\"context\":{");
            switch (e.Type)
            {
                case "POSITION_SAMPLE":
                    sb.Append("\"x\":").Append(Num(e.X)).Append(",\"z\":").Append(Num(e.Z))
                      .Append(",\"heading_deg\":").Append(Num(e.HeadingDeg)).Append(",\"speed_mps\":").Append(Num(e.SpeedMps))
                      .Append(",\"gear\":").Append(Str(e.Gear));
                    break;
                case "CONTACT_STARTED":
                    sb.Append("\"object_id\":").Append(Str(e.ObjectId)).Append(",\"object_kind\":").Append(Str(e.ObjectKind))
                      .Append(",\"speed_mps\":").Append(Num(e.SpeedMps));
                    break;
                case "STOP_LINE_CROSSED":
                    sb.Append("\"signal\":").Append(Str(e.Signal));
                    break;
                default:    // BOUNDARY_CROSSED
                    sb.Append("\"direction\":").Append(Str(e.Direction));
                    break;
            }
            sb.Append("}}");
        }

        public static string Num(double v)
        {
            return v.ToString("0.###", CultureInfo.InvariantCulture);
        }

        public static string Str(string s)
        {
            if (s == null)
            {
                return "null";
            }
            var sb = new StringBuilder("\"");
            foreach (char c in s)
            {
                switch (c)
                {
                    case '"': sb.Append("\\\""); break;
                    case '\\': sb.Append("\\\\"); break;
                    case '\n': sb.Append("\\n"); break;
                    default: sb.Append(c); break;
                }
            }
            return sb.Append('"').ToString();
        }
    }
}
