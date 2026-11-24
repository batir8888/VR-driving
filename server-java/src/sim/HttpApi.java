package sim;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Тонкая HTTP-обёртка над процедурами A1, A4–A7 (встроенный com.sun.net.httpserver, без Spring).
 * Spring Boot заменит её на следующем этапе практики; логика остаётся в процедурах пакета sim.
 *
 * Запуск: java -cp out/server sim.HttpApi --port 8080 --data data --store out/store
 */
public final class HttpApi {
    private static final Object LOCK = new Object();

    private HttpApi() {}

    public static void main(String[] args) throws IOException {
        int port = 8080;
        Path data = Paths.get("data");
        Path store = Paths.get("out", "store");
        for (int i = 0; i + 1 < args.length; i += 2) {
            switch (args[i]) {
                case "--port": port = Integer.parseInt(args[i + 1]); break;
                case "--data": data = Paths.get(args[i + 1]); break;
                case "--store": store = Paths.get(args[i + 1]); break;
                default: throw new IllegalArgumentException("Неизвестный параметр " + args[i]);
            }
        }
        start(port, data, store);
        System.out.println("Сервер запущен: http://localhost:" + port + "/ (данные " + data + ", хранилище " + store + ")");
    }

    public static HttpServer start(int port, Path data, Path store) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", ex -> handle(ex, data, store));
        server.start();
        return server;
    }

    private static void handle(HttpExchange ex, Path data, Path store) throws IOException {
        int code;
        Object body;
        try {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            String[] parts = path.split("/");   // "", "attempts", "{id}", "messages"
            synchronized (LOCK) {
                if (method.equals("POST") && path.equals("/attempts")) {
                    Map<String, Object> req = requestBody(ex);
                    String learnerId;
                    String exerciseId;
                    Long scenarioVersion;
                    Long rulesVersion;
                    try {
                        learnerId = asString(req.get("learner_id"));
                        exerciseId = asString(req.get("exercise_id"));
                        scenarioVersion = req.get("scenario_version") == null ? null : Json.integer(req.get("scenario_version"));
                        rulesVersion = req.get("rules_version") == null ? null : Json.integer(req.get("rules_version"));
                    } catch (IllegalArgumentException | ClassCastException | NullPointerException e) {
                        throw new BadRequest(e);
                    }
                    Map<String, Object> r = Prepare.createAttempt(data, store, learnerId, exerciseId,
                        scenarioVersion, rulesVersion, Instant.now().toString());
                    code = (int) Json.integer(r.get("code"));
                    body = r;
                } else if (method.equals("POST") && parts.length == 4 && parts[1].equals("attempts") && parts[3].equals("messages")) {
                    Map<String, Object> msg = requestBody(ex);
                    Map<String, Object> r = Receive.receive(data, store, parts[2], msg);
                    code = (int) Json.integer(r.get("code"));
                    body = r;
                } else if (method.equals("GET") && parts.length == 4 && parts[1].equals("attempts") && parts[3].equals("result")) {
                    Path dir = Storage.attemptDir(store, parts[2]);
                    if (!Files.isRegularFile(dir.resolve("result.json"))) {
                        code = 404;
                        body = Json.obj("message", "результат отсутствует");
                    } else {
                        Map<String, Object> attempt = Json.map(Storage.read(dir.resolve("attempt.json")));
                        Map<String, Object> exercise = Json.map(Storage.read(data.resolve("exercises")
                            .resolve(Json.str(attempt.get("exercise_id"))).resolve("exercise.json")));
                        code = 200;
                        body = Evaluate.view(Json.map(Storage.read(dir.resolve("result.json"))), exercise, attempt);
                    }
                } else if (method.equals("GET") && path.equals("/attempts")) {
                    List<Object> list = new ArrayList<>();
                    for (String id : Storage.attemptIds(store)) {
                        list.add(Storage.read(Storage.attemptDir(store, id).resolve("attempt.json")));
                    }
                    code = 200;
                    body = list;
                } else if (method.equals("GET") && path.equals("/compare")) {
                    Map<String, String> q = query(ex.getRequestURI().getRawQuery());
                    Map<String, Object> r = Compare.compare(data, store, q.get("b"), q.get("a"));
                    code = (int) Json.integer(r.get("code"));
                    body = r;
                } else {
                    code = 404;
                    body = Json.obj("message", "маршрут не найден");
                }
            }
        } catch (BadRequest e) {
            code = 400;   // ошибка во входном запросе: клиент не должен повторять это сообщение
            body = Json.obj("status", "BAD_FORMAT", "message", "неверный формат запроса: " + e.getMessage());
        } catch (RuntimeException e) {
            // сбой чтения хранилища или расчёта: запрос корректен, клиент оставит сообщение в очереди и повторит
            code = 500;
            body = Json.obj("status", "SERVER_ERROR", "message", "внутренняя ошибка сервера");
            System.err.println("[HttpApi] " + ex.getRequestMethod() + " " + ex.getRequestURI() + ": внутренняя ошибка");
            e.printStackTrace();
        }
        byte[] bytes = Json.write(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    /** Ошибка разбора или проверки входного запроса — единственный источник ответа 400 в обработчике. */
    static final class BadRequest extends RuntimeException {
        BadRequest(Throwable cause) {
            super(cause.getMessage(), cause);
        }
    }

    /** Разбирает тело запроса как JSON-объект; любая ошибка разбора относится к запросу (400). */
    private static Map<String, Object> requestBody(HttpExchange ex) throws IOException {
        String text = readBody(ex);
        try {
            return Json.map(Json.parse(text));
        } catch (IllegalArgumentException | ClassCastException | NullPointerException e) {
            throw new BadRequest(e);
        }
    }

    private static String asString(Object o) {
        return o == null ? null : Json.str(o);
    }

    private static String readBody(HttpExchange ex) throws IOException {
        return new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> q = new java.util.HashMap<>();
        if (raw != null) {
            for (String kv : raw.split("&")) {
                String[] p = kv.split("=", 2);
                q.put(URLDecoder.decode(p[0], StandardCharsets.UTF_8),
                    p.length > 1 ? URLDecoder.decode(p[1], StandardCharsets.UTF_8) : "");
            }
        }
        return q;
    }
}
