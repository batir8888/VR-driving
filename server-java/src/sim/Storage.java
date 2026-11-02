package sim;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

/**
 * Компонент хранения (прототип): файлы JSON в каталоге попытки.
 * Запись атомарная: сначала временный файл, затем переименование (A1 блок 5, A4 блок 8, A6 блок 13).
 */
public final class Storage {
    private Storage() {}

    public static Object read(Path file) {
        try {
            return Json.parse(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось прочитать " + file + ": " + e.getMessage(), e);
        }
    }

    public static void writeAtomic(Path file, Object value) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(tmp, (Json.write(value) + "\n").getBytes(StandardCharsets.UTF_8));
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось записать " + file + ": " + e.getMessage(), e);
        }
    }

    public static Path attemptDir(Path store, String attemptId) {
        return store.resolve("attempts").resolve(attemptId);
    }

    /** Идентификаторы попыток в порядке возрастания. */
    public static List<String> attemptIds(Path store) {
        Path dir = store.resolve("attempts");
        List<String> ids = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return ids;
        }
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(Files::isDirectory).forEach(p -> ids.add(p.getFileName().toString()));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        Collections.sort(ids);
        return ids;
    }

    /** Номера версий файлов вида prefix-N.json в каталоге упражнения. */
    public static List<Long> versions(Path exerciseDir, String prefix) {
        List<Long> result = new ArrayList<>();
        if (!Files.isDirectory(exerciseDir)) {
            return result;
        }
        try (Stream<Path> s = Files.list(exerciseDir)) {
            s.forEach(p -> {
                String name = p.getFileName().toString();
                if (name.startsWith(prefix + "-") && name.endsWith(".json")) {
                    String n = name.substring(prefix.length() + 1, name.length() - 5);
                    try {
                        result.add(Long.valueOf(n));
                    } catch (NumberFormatException ignored) {
                        // посторонний файл
                    }
                }
            });
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        Collections.sort(result);
        return result;
    }
}
