using System;
using System.Collections.Generic;

namespace Simulator.Client
{
    /// <summary>
    /// A3. Доставка очереди сообщений с повтором (общий компонент обмена данными).
    /// Схема: UP-004/UP-004_A3_схема.png, шаги — таблица 4.6.
    /// В Unity процедура выполняется в фоновой задаче; здесь канал и ожидание заданы делегатами,
    /// поэтому поведение проверяется без сети и без реального времени.
    /// </summary>
    public static class Delivery
    {
        /// <param name="queue">Очередь сообщений (RegState.Outbox): первое сообщение — следующее к отправке.</param>
        /// <param name="wait">Ожидание перед повтором, мс (блок 6).</param>
        /// <param name="maxConsecutiveFailures">0 — повторять без ограничения; N больше 0 — вернуть Suspended после N неудач подряд (очередь сохраняется).</param>
        public static PumpResult Pump(List<Message> queue, DeliveryState d, string attemptId, Channel channel,
                                      Action<long> wait, int maxConsecutiveFailures)
        {
            int failures = 0;
            while (true)
            {
                // блок 1: очередь пуста?
                if (queue.Count == 0)
                {
                    // блок 2: сообщение о завершении уже доставлено?
                    return d.FinishDelivered ? PumpResult.Done : PumpResult.WaitForMessages;   // блок 3: ждать новое сообщение
                }
                // блок 4: взять первое сообщение, не удаляя из очереди
                Message m = queue[0];
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
                            queue.RemoveAt(0);                                      // блок 11: удалить; delay := delay_0
                            d.DelayMs = d.Delay0Ms;
                            if (m.Kind == "FINISH")
                            {
                                d.FinishDelivered = true;
                            }
                            failures = 0;
                            break;
                        }
                        if (r.Code >= 400 && r.Code < 500)                          // блок 9: код 4xx?
                        {
                            queue.RemoveAt(0);                                      // блок 10: в список отклонённых, уведомить интерфейс
                            d.Rejected.Add(new RejectedMessage { Message = m, Code = r.Code });
                            failures = 0;
                            break;
                        }
                    }
                    // блок 6: ждать delay; delay := min(2·delay, delay_max)
                    failures++;
                    d.Failures++;
                    if (maxConsecutiveFailures > 0 && failures >= maxConsecutiveFailures)
                    {
                        return PumpResult.Suspended;
                    }
                    d.Waits.Add(d.DelayMs);
                    d.Retries++;
                    wait(d.DelayMs);
                    d.DelayMs = Math.Min(2 * d.DelayMs, d.DelayMaxMs);
                }
            }
        }
    }
}
