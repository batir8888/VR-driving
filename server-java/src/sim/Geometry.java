package sim;

import java.util.List;

/**
 * Геометрия на плоскости (x, z). Курс heading_deg отсчитывается от оси z по часовой стрелке к оси x
 * (как поворот вокруг вертикальной оси в Unity): направление «вперёд» = (sin h, cos h).
 */
public final class Geometry {
    private static final double EPS = 1e-9;

    private Geometry() {}

    /** Четыре угла прямоугольника автомобиля (UP-004, раздел 3: «контур автомобиля»). */
    public static double[][] vehicleCorners(double x, double z, double headingDeg, double length, double width) {
        double h = Math.toRadians(headingDeg);
        double fx = Math.sin(h), fz = Math.cos(h);   // вперёд
        double rx = Math.cos(h), rz = -Math.sin(h);  // вправо
        double hl = length / 2.0, hw = width / 2.0;
        return new double[][] {
            {x + fx * hl + rx * hw, z + fz * hl + rz * hw},
            {x + fx * hl - rx * hw, z + fz * hl - rz * hw},
            {x - fx * hl - rx * hw, z - fz * hl - rz * hw},
            {x - fx * hl + rx * hw, z - fz * hl + rz * hw}
        };
    }

    public static double[][] polygon(Object json) {
        List<Object> pts = Json.list(json);
        double[][] p = new double[pts.size()][2];
        for (int i = 0; i < p.length; i++) {
            List<Object> xy = Json.list(pts.get(i));
            p[i][0] = Json.num(xy.get(0));
            p[i][1] = Json.num(xy.get(1));
        }
        return p;
    }

    /** Точка внутри многоугольника или на его границе. */
    public static boolean contains(double[][] poly, double x, double z) {
        int n = poly.length;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            if (onSegment(poly[j], poly[i], x, z)) {
                return true;
            }
        }
        boolean inside = false;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            double xi = poly[i][0], zi = poly[i][1], xj = poly[j][0], zj = poly[j][1];
            if ((zi > z) != (zj > z) && x < (xj - xi) * (z - zi) / (zj - zi) + xi) {
                inside = !inside;
            }
        }
        return inside;
    }

    private static boolean onSegment(double[] a, double[] b, double x, double z) {
        double cross = (b[0] - a[0]) * (z - a[1]) - (b[1] - a[1]) * (x - a[0]);
        if (Math.abs(cross) > EPS) {
            return false;
        }
        return x >= Math.min(a[0], b[0]) - EPS && x <= Math.max(a[0], b[0]) + EPS
            && z >= Math.min(a[1], b[1]) - EPS && z <= Math.max(a[1], b[1]) + EPS;
    }

    /** Все углы контура лежат в многоугольнике («габариты внутри зоны»). */
    public static boolean allInside(double[][] poly, double[][] corners) {
        for (double[] c : corners) {
            if (!contains(poly, c[0], c[1])) {
                return false;
            }
        }
        return true;
    }

    /** Модуль разности курсов в градусах, 0..180. */
    public static double headingDelta(double a, double b) {
        double d = Math.abs(a - b) % 360.0;
        return d > 180.0 ? 360.0 - d : d;
    }
}
