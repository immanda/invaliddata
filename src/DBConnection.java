import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * DBConnection.java  — REVISED
 * DA-ICTS Database Management Division
 * Invalid Data Profiling — Protocol v1.8 (Corrected)
 *
 * Lightweight config loader for the standalone Invalid Data Profiling project.
 * No dependency on Deduplication classes.
 *
 * Reads credentials from config.properties on the classpath root.
 * The file is cached after the first load so it is read only once per JVM run.
 */
public class DBConnection {

    private static Properties cachedProps = null;

    private DBConnection() { /* static utility — not instantiated */ }

    /**
     * Returns Properties loaded from config.properties.
     * Result is cached; the file is read only once.
     *
     * @throws IOException if config.properties is not found on the classpath
     */
    public static Properties loadProperties() throws IOException {
        if (cachedProps != null) return cachedProps;

        Properties props = new Properties();
        try (InputStream input = DBConnection.class
                .getClassLoader().getResourceAsStream("config.properties")) {
            if (input == null) {
                throw new IOException(
                    "config.properties not found on the classpath. "
                    + "Run the program from the InvalidDataProfiler_Revised folder "
                    + "and ensure '.' is included in the -cp argument.");
            }
            props.load(input);
        }
        cachedProps = props;
        return cachedProps;
    }

    /**
     * Builds a JDBC URL from a base URL, appending required parameters
     * that are not already present.
     *
     * @param base        Raw JDBC URL from config.properties
     * @param cursorFetch If true, appends useCursorFetch=true (streaming reads)
     */
    public static String buildUrl(String base, boolean cursorFetch) {
        if (!base.contains("allowPublicKeyRetrieval=true")) {
            base += "&allowPublicKeyRetrieval=true";
        }
        if (cursorFetch && !base.contains("useCursorFetch=true")) {
            base += "&useCursorFetch=true";
        }
        return base;
    }
}
