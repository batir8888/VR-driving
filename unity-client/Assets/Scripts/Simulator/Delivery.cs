using System;
using System.Collections.Generic;
using System.Threading;

namespace Simulator.Client
{
    /// <summary>
    /// A3. Доставка очереди сообщений с повтором (общий компонент обмена данными).
    /// Схема: UP-004/UP-004_A3_схема.png, шаги — таблица 4.6.
    /// Выполняется в фоновом потоке (StartWorker): поток кадров только добавляет сообщения в очередь, поэтому
    /// зависший канал не задерживает кадр. Канал и ожидание заданы делегатами, поэтому поведение проверяется
    /// без сети и без реального времени. Канал вызывается не из главного потока и должен быть потокобезопасным.
    /// </summary>
    public static class Delivery
    {
        /// <param name="queue">Очередь сообщений (RegState.Outbox): первое сообщение — следующее к отправке.</param>
        /// <param name="wait">Ожидание перед повтором, мс (блок 6).</param>
        public static PumpResult Pump(List<Message> queue, DeliveryState d, string attemptId, Channel channel,
                                      Action<long> wait)
        {
            while (true)
            {
                if (d.Stop.IsSet)
                {
                    return PumpResult.Suspended;
                }
                Message m;
                lock (queue)
                {
                    // блок 1: очередь пуста?
                    if (queue.Count == 0)
                    {
                        if (d.FinishRejected)
                        {
                            return PumpResult.FinishRejected;
                        }
                        // блок 2: сообщение о завершении уже доставлено?
                        return d.FinishDelivered ? PumpResult.Done : PumpResult.WaitForMessages;   // блок 3: ждать новое сообщение
                    }
                    // блок 4: взять первое сообщение, не удаляя из очереди
                    m = queue[0];
                }
                string path = "/attempts/" + attemptId + "/messages";
                string body = MessageJson.Write(m);
                while (true)
                {
                    // блок 5: отправить HTTP POST с тайм-аутом
                    d.Requests++;
                    ChannelResponse r = channel(path, body);
                    // блок 7: ответ получен?
                    if (r != null)
                    {
                        if (r.Code >= 200 && r.Code < 300)                          // блок 8: код 2xx?
                        {
                            lock (queue) { queue.RemoveAt(0); }                     // блок 11: удалить; delay := delay_0
                            d.DelayMs = d.Delay0Ms;
                            if (m.Kind == "FINISH")
                            {
                                d.FinishDelivered = true;
                            }
                            break;
                        }
                        if (r.Code >= 400 && r.Code < 500)                          // блок 9: код 4xx?
                        {
                            lock (queue) { queue.RemoveAt(0); }                     // блок 10: в список отклонённых, уведомить интерфейс
                            d.Rejected.Add(new RejectedMessage { Message = m, Code = r.Code, Reason = r.Body });
                            if (m.Kind == "FINISH")
                            {
                                // сервер не принял завершение: ждать больше нечего, поток должен закончить работу,
                                // а вызывающий код — показать причину и не считать попытку завершённой
                                d.FinishRejected = true;
                                d.FinishRejectReason = r.Body;
                                return PumpResult.FinishRejected;
                            }
                            break;
                        }
                    }
                    // блок 6: ждать delay; delay := min(2·delay, delay_max)
                    d.Failures++;
                    d.Waits.Add(d.DelayMs);
                    d.Retries++;
                    wait(d.DelayMs);
                    d.DelayMs = Math.Min(2 * d.DelayMs, d.DelayMaxMs);
                    if (d.Stop.IsSet)
                    {
                        return PumpResult.Suspended;
                    }
                }
            }
        }

        /// <summary>Запускает Pump в фоновом потоке: блок 3 — короткое ожидание новых сообщений. Поток завершается после Done, FinishRejected или просьбы об остановке.</summary>
        /// <param name="wait">Пауза перед повтором; null — реальная пауза, которую прерывает DeliveryState.Stop.</param>
        public static Thread StartWorker(List<Message> queue, DeliveryState d, string attemptId, Channel channel, Action<long> wait)
        {
            Action<long> pause = wait ?? (ms => d.Stop.Wait(TimeSpan.FromMilliseconds(ms)));
            var worker = new Thread(() =>
            {
                while (Pump(queue, d, attemptId, channel, pause) == PumpResult.WaitForMessages)
                {
                    d.Stop.Wait(IdlePollMs);
                }
            });
            worker.IsBackground = true;
            worker.Name = "Delivery";
            worker.Start();
            return worker;
        }

        private const int IdlePollMs = 10;
    }
}
