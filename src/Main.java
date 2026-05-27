/**
 * Main.java  — REVISED
 * DA-ICTS Database Management Division
 * Invalid Data Profiling — Protocol v1.8 (Corrected)
 *
 * Standalone entry point. Runs the Invalid Data Profiling process
 * independently of Deduplication. Connects to the RSBSA source DB,
 * scans all active non-duplicate records for data quality issues, and
 * writes results to surname_issues in results_db with corrected use_case_ids.
 *
 * Usage (Windows):
 *   javac -cp ".;mysql-connector-j-*.jar" src\*.java -d out
 *   java  -cp "out;mysql-connector-j-*.jar;." Main
 *
 * Usage (Linux / Mac):
 *   javac -cp ".:mysql-connector-j-*.jar" src/*.java -d out
 *   java  -cp "out:mysql-connector-j-*.jar:." Main
 *
 * config.properties must be present in the working directory.
 */
public class Main {

    public static void main(String[] args) {
        System.out.println("=================================================================");
        System.out.println("  DA-ICTS DATABASE MANAGEMENT DIVISION");
        System.out.println("  Invalid Data Profiling  —  Protocol v1.8  (REVISED)");
        System.out.println("=================================================================");
        System.out.println();

        boolean success = false;
        try {
            DataProfiler.runProfiling();
            success = true;
        } catch (Exception e) {
            System.err.println("❌ Invalid Data Profiling FAILED: " + e.getMessage());
            e.printStackTrace();
        }

        System.out.println();
        System.out.println("=================================================================");
        System.out.println("  Invalid Data Profiling: " + (success ? "✅ COMPLETED" : "❌ FAILED"));
        System.out.println("=================================================================");

        if (!success) System.exit(1);
    }
}
