// com/example/beautiful_barometer/util/Units.java
package com.example.beautiful_barometer.util;

import android.content.SharedPreferences;
import java.util.Locale;
public class Units {
    public enum System { METRIC, IMPERIAL, MMHG }

    private static final double HPA_TO_INHG = 0.0295299830714;
    private static final double HPA_TO_MMHG = 0.750061683;  // 1 hPa ≈ 0.75006 mmHg
    private static final double M_TO_FT     = 3.28084;

    public static System getSystem(SharedPreferences prefs) {
        String v = prefs.getString("pref_units", "metric");
        if ("imperial".equals(v)) return System.IMPERIAL;
        if ("mmhg".equals(v))     return System.MMHG;
        return System.METRIC;
    }

    /** Давление храним в hPa, форматируем под выбранную систему. */
    public static String formatPressure(double hPa, System sys) {
        if (Double.isNaN(hPa)) return "--";
        switch (sys) {
            case IMPERIAL: {
                double inHg = hPa * HPA_TO_INHG;
                return String.format(Locale.getDefault(), "%.2f inHg", inHg);
            }
            case MMHG: {
                double mmHg = hPa * HPA_TO_MMHG;
                return String.format(Locale.getDefault(), "%.2f mmHg", mmHg);
            }
            case METRIC:
            default:
                return String.format(Locale.getDefault(), "%.2f hPa", hPa);
        }
    }

    /** Короткий формат без единиц — для рисок на шкале. */
    public static String formatPressureTick(double hPa, System sys) {
        if (Double.isNaN(hPa)) return "--";
        switch (sys) {
            case IMPERIAL:
                return String.format("%.2f", hPa * HPA_TO_INHG);
            case MMHG:
                return String.format("%.0f", hPa * HPA_TO_MMHG);
            case METRIC:
            default:
                return String.format("%.0f", hPa);
        }
    }


    public static String formatPressureDelta(double hPaDelta, System sys) {
        if (Double.isNaN(hPaDelta)) return "—";
        switch (sys) {
            case IMPERIAL:
                return String.format(Locale.getDefault(), "%+.2f inHg", hPaDelta * HPA_TO_INHG);
            case MMHG:
                return String.format(Locale.getDefault(), "%+.1f mmHg", hPaDelta * HPA_TO_MMHG);
            case METRIC:
            default:
                return String.format(Locale.getDefault(), "%+.1f hPa", hPaDelta);
        }
    }

    public static String formatAltitude(double meters, System sys) {
        if (sys == System.IMPERIAL) {
            return String.format("%+.0f ft", meters * M_TO_FT);
        } else {
            return String.format("%+.0f м", meters);
        }
    }

    // h ≈ 44330 * (1 - (P/P0)^(1/5.255)), P0 = 1013.25 hPa
    public static double altitudeFromPressure(double hPa) {
        double p0 = 1013.25;
        return 44330.0 * (1.0 - Math.pow(hPa / p0, 0.1902949571836346));
    }
}
