using System.Collections.Generic;
using System.Threading;

namespace Simulator.Client
{
    // Данные клиента. Это структуры без поведения: алгоритмы A1–A3 — процедуры в классах Restore, Registrar, Delivery.
    // Названия полей соответствуют сущностям логической модели данных (UP-003) и формату сообщений (docs/message-format.md).

    public sealed class Pose
    {
        public double X, Z, HeadingDeg;
    }

    public sealed class SignalPhase
    {
        public string Signal;
        public long DurationMs;
    }

    /// <summary>Дорожный объект сценария (ScenarioVersion.road_objects).</summary>
    public sealed class RoadObject
    {
        public string ObjectId;
        public string Kind;                 // OBSTACLE, BORDER, STOP_LINE, TRAFFIC_SIGNAL
        public double[][] Polygon;          // OBSTACLE, BORDER
        public double[][] Line;             // STOP_LINE: две точки
        public double[] Direction;          // STOP_LINE: направление пересечения (единичный вектор x, z)
        public double[] Position;           // TRAFFIC_SIGNAL
        public SignalPhase[] Program;       // TRAFFIC_SIGNAL: программа сигналов
        public int PhaseIndex;              // TRAFFIC_SIGNAL: начальная фаза
        public long PhaseOffsetMs;          // TRAFFIC_SIGNAL: смещение внутри начальной фазы
    }

    /// <summary>Условия задания (ScenarioVersion).</summary>
    public sealed class Scenario
    {
        public string ExerciseId;
        public int ScenarioVersion;
        public string TaskText;
        public Pose StartPose;
        public string TravelDirection;      // FORWARD или REVERSE
        public double SpeedLimitMps;
        public double LengthM, WidthM;
        public double[][] AreaBounds;
        public double[][] TargetZone;
        public double TargetHeadingDeg;
        public bool HasTargetHeading;
        public string[] PermittedSignals;
        public double StopSpeedMps, SpeedOverMps, HeadingTolDeg;
        public int SamplePeriodMs;
        public List<RoadObject> RoadObjects = new List<RoadObject>();
    }

    /// <summary>Состояние сцены после восстановления исходных условий (результат A1, блоки 7–9).</summary>
    public sealed class SceneState
    {
        public Pose VehiclePose;
        public double VehicleSpeedMps;
        public string Gear;
        public List<PlacedObject> Objects = new List<PlacedObject>();
    }

    public sealed class PlacedObject
    {
        public string ObjectId, Kind;
        public int SignalPhaseIndex;        // только для светофора
        public long SignalPhaseElapsedMs;
    }

    /// <summary>Данные одного кадра (вход A2).</summary>
    public sealed class Frame
    {
        public long TMs;
        public double X, Z, HeadingDeg, SpeedMps;
        public string Gear;
        public List<Contact> Contacts = new List<Contact>();
        public string Signal;               // сигнал светофора в этом кадре
        public string FinishReason;         // null — попытка продолжается; COMPLETED или ABORTED — кадр завершает попытку
    }

    public sealed class Contact
    {
        public string ObjectId, ObjectKind;
        public Contact() { }
        public Contact(string id, string kind) { ObjectId = id; ObjectKind = kind; }
    }

    /// <summary>Событие журнала (Event). Поля контекста заполняются в зависимости от типа.</summary>
    public sealed class EventRecord
    {
        public long EventNo, TMs;
        public string Type;                 // POSITION_SAMPLE, CONTACT_STARTED, STOP_LINE_CROSSED, BOUNDARY_CROSSED
        public double X, Z, HeadingDeg, SpeedMps;
        public string Gear, ObjectId, ObjectKind, Signal, Direction;
    }

    /// <summary>Сообщение в очереди отправки: группа событий (BATCH) или завершение попытки (FINISH).</summary>
    public sealed class Message
    {
        public string Kind;
        // BATCH
        public int BatchNumber, EventCount;
        public List<EventRecord> Events;
        // FINISH
        public long DurationMs;
        public string FinishReason;
        public int DeclaredBatchCount;
    }

    /// <summary>Состояние регистрации (вне модели данных, A2).</summary>
    public sealed class RegState
    {
        public long LastSampleT = long.MinValue / 2;
        public bool PrevInsideInit;
        public bool PrevInside = true;
        public HashSet<string> PrevContacts = new HashSet<string>();
        public Dictionary<string, double> PrevSide = new Dictionary<string, double>();
        public List<EventRecord> Buffer = new List<EventRecord>();
        public long BatchOpenedAtT = -1;
        public long NextEventNo = 1;
        public int NextBatchNumber = 1;
        public bool FinishQueued;
        public int BatchMaxEvents = 20;
        public long BatchMaxIntervalMs = 2000;
        /// <summary>Очередь отправки: её заполняет A2 в потоке кадров, а опустошает A3 в фоновом потоке; обращения — под lock (Outbox).</summary>
        public List<Message> Outbox = new List<Message>();
    }

    public sealed class ChannelResponse
    {
        public int Code;
        public string Body;
        public ChannelResponse(int code, string body) { Code = code; Body = body; }
    }

    /// <summary>Канал связи: отправляет тело JSON на путь и возвращает ответ; null — нет ответа (сетевая ошибка, тайм-аут).</summary>
    public delegate ChannelResponse Channel(string path, string jsonBody);

    public sealed class RejectedMessage
    {
        public Message Message;
        public int Code;
    }

    /// <summary>Состояние доставки (вне модели данных, A3).</summary>
    public sealed class DeliveryState
    {
        public long Delay0Ms = 1000;
        public long DelayMaxMs = 8000;
        public long DelayMs = 1000;
        public bool FinishDelivered;
        public List<RejectedMessage> Rejected = new List<RejectedMessage>();
        public List<long> Waits = new List<long>();     // журнал пауз перед повторами (для проверки роста до предела)
        public int Requests;
        public int Retries;
        public int Failures;                            // отправки без подтверждения: нет ответа, 5xx
        /// <summary>Просьба остановить доставку (выход из приложения); прерывает паузу фонового потока. Очередь сохраняется.</summary>
        public readonly ManualResetEventSlim Stop = new ManualResetEventSlim(false);
    }

    public enum PumpResult
    {
        Done,               // очередь пуста, завершение доставлено (блок 2, «да»)
        WaitForMessages,    // очередь пуста, ждём новые сообщения (блок 3)
        Suspended           // доставка остановлена по просьбе (DeliveryState.Stop); очередь сохранена
    }
}
