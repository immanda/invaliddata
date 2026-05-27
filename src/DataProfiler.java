import java.io.IOException;
import java.sql.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Pattern;

/**
 * DataProfiler.java  — REVISED
 * DA-ICTS Database Management Division
 * Invalid Data Profiling — Protocol v1.8 (Corrected)
 *
 * ┌─────────────────────────────────────────────────────────────────────┐
 *  REVISION: Use Case ID key correction
 *
 *  Method  : getUseCaseId()
 *  Problem : External-source use case IDs were shifted +10 positions,
 *            causing mismatches between the DB and the protocol table.
 *
 *  Before  : return "2." + (base + idx + 10);   ← WRONG
 *              NFFIS → 2.20–2.29  (should be 2.10–2.19)
 *              FiSHR → 2.30–2.39  (should be 2.20–2.29)
 *              NCFRS → 2.40–2.49  (should be 2.30–2.39)
 *
 *  After   : return "2." + (base + idx);         ← CORRECT
 *              NFFIS → 2.10–2.19  ✓
 *              FiSHR → 2.20–2.29  ✓
 *              NCFRS → 2.30–2.39  ✓
 * └─────────────────────────────────────────────────────────────────────┘
 *
 * Full Use Case ID Map (Protocol v1.8):
 *   FFRS   : 1.1(A) 1.2(B) 1.3(C) 1.4(D) 1.5(F) 1.6(G) 1.7(H) 1.8(I) 1.9(J) 2.0(K)
 *   NFFIS  : 2.10(A)–2.19(K)
 *   FiSHR  : 2.20(A)–2.29(K)
 *   NCFRS  : 2.30(A)–2.39(K)
 *
 * Error codes (no Code E):
 *   A=blank  B=numeric_only  C=special_chars  D=has_suffix_in_surname
 *   F=placeholder_value  G=abnormal_value  H=null_value
 *   I=invalid_age(>100)  J=minor_age(<=10)  K=senior_age(90–99)
 *
 * DB credentials loaded from: config.properties (classpath resource)
 * Entry point: DataProfiler.runProfiling()
 */
public class DataProfiler {

    private static final int BATCH_SIZE = 5000;

    // ── Validation Rules (Protocol v1.8) ──────────────────────────────────────
    private static final Pattern NUMERIC_PATTERN       = Pattern.compile("\\d+");
    private static final Pattern SPECIAL_CHARS_PATTERN = Pattern.compile("[^A-Za-zÑñ\\s\\-']");
    private static final Set<String> PLACEHOLDERS      = new HashSet<>(Arrays.asList("N/A", "NA", "-", "NONE"));
    private static final Set<String> ABNORMAL_WORDS    = new HashSet<>(Arrays.asList("test", "xxx", "sample"));
    private static final Set<String> SUFFIXES          = new HashSet<>(Arrays.asList("JR", "SR", "JR.", "SR.", "II", "III", "IV"));

    // Issue-flag string → error code letter
    private static final Map<String, String> ISSUE_CODE_MAP = new HashMap<>();
    static {
        ISSUE_CODE_MAP.put("blank",                 "A");
        ISSUE_CODE_MAP.put("numeric_only",          "B");
        ISSUE_CODE_MAP.put("special_chars",         "C");
        ISSUE_CODE_MAP.put("has_suffix_in_surname", "D");
        ISSUE_CODE_MAP.put("placeholder_value",     "F");
        ISSUE_CODE_MAP.put("abnormal_value",        "G");
        ISSUE_CODE_MAP.put("null_value",            "H");
        ISSUE_CODE_MAP.put("invalid_age",           "I");
        ISSUE_CODE_MAP.put("minor_age",             "J");
        ISSUE_CODE_MAP.put("senior_age",            "K");
    }

    // Protocol code order — note: no 'E'
    private static final char[] CODE_ORDER = {'A', 'B', 'C', 'D', 'F', 'G', 'H', 'I', 'J', 'K'};

    // use_case_id → [ucId, source, code, description]
    // Built once at class-init for fast summary lookup.
    private static final Map<String, String[]> USE_CASE_INFO = new LinkedHashMap<>();
    static {
        buildUseCaseInfoForSource("1", 0, "ffrs");    // 1.1–1.9 + 2.0
        buildUseCaseInfoForSource("2", 10, "nffis");  // 2.10–2.19
        buildUseCaseInfoForSource("2", 20, "fishr");  // 2.20–2.29
        buildUseCaseInfoForSource("2", 30, "ncfrs");  // 2.30–2.39
    }

    private static void buildUseCaseInfoForSource(String prefix, int base, String source) {
        boolean isFfrs = "ffrs".equals(source);
        for (int i = 0; i < CODE_ORDER.length; i++) {
            char code = CODE_ORDER[i];
            String ucId;
            if (isFfrs && code == 'K') {
                ucId = "2.0";                        // FFRS senior_age is 2.0 per protocol
            } else {
                int num = isFfrs ? (base + i + 1) : (base + i);
                ucId = prefix + "." + num;
            }
            String description = ISSUE_CODE_MAP.entrySet().stream()
                    .filter(e -> e.getValue().equals(String.valueOf(code)))
                    .map(Map.Entry::getKey)
                    .findFirst().orElse("unknown");
            USE_CASE_INFO.put(ucId + "|" + source,
                    new String[]{ucId, source, String.valueOf(code), description});
        }
    }

    // Accumulated stats per use_case_id for the final summary
    private static final Map<String, Long> globalStats = new LinkedHashMap<>();

    // ── Public entry point ────────────────────────────────────────────────────

    public static void runProfiling() throws Exception {
        System.out.println("--- Starting Invalid Data Profiling (Protocol v1.8 Revised) ---");
        globalStats.clear();

        String srcUrl, tgtUrl, srcUser, srcPass, tgtUser, tgtPass;
        try {
            Properties props = DBConnection.loadProperties();
            srcUrl  = DBConnection.buildUrl(
                        props.getProperty("source.url")
                             .replace("serverTimezone=UTC",
                                      "useUnicode=true&characterEncoding=UTF-8&serverTimezone=UTC"),
                        true);
            tgtUrl  = DBConnection.buildUrl(
                        props.getProperty("target.url")
                             .replace("serverTimezone=UTC",
                                      "useUnicode=true&characterEncoding=UTF-8&serverTimezone=UTC"),
                        false);
            srcUser = props.getProperty("source.user");
            srcPass = props.getProperty("source.password");
            tgtUser = props.getProperty("target.user");
            tgtPass = props.getProperty("target.password");
        } catch (IOException e) {
            throw new RuntimeException("Failed to load config.properties: " + e.getMessage(), e);
        }

        setupTargetTable(tgtUrl, tgtUser, tgtPass);
        processData(srcUrl, srcUser, srcPass, tgtUrl, tgtUser, tgtPass);
        printFinalSummary();

        System.out.println("--- Invalid Data Profiling Finished ---");
    }

    // ── Setup ─────────────────────────────────────────────────────────────────

    private static void setupTargetTable(String tgtUrl, String tgtUser, String tgtPass)
            throws SQLException {
        try (Connection conn = DriverManager.getConnection(tgtUrl, tgtUser, tgtPass);
             Statement stmt  = conn.createStatement()) {

            stmt.executeUpdate("DROP TABLE IF EXISTS surname_issues");

            stmt.executeUpdate(
                "CREATE TABLE surname_issues ("
                + "rsbsa_no VARCHAR(255) PRIMARY KEY, "
                + "use_case_id VARCHAR(50), "
                + "original_surname VARCHAR(255), ext_name VARCHAR(255), "
                + "first_name VARCHAR(255), middle_name VARCHAR(255), sex VARCHAR(10), "
                + "birthday DATE, "
                + "region VARCHAR(255), province VARCHAR(255), "
                + "municipality VARCHAR(255), barangay VARCHAR(255), "
                + "detected_suffix VARCHAR(50), issue_flag TEXT, error_code TEXT, "
                + "date_created DATETIME, date_updated DATETIME, "
                + "encoder_fullname VARCHAR(255), data_source VARCHAR(255), "
                + "age INT, age_flag TEXT)"
            );
            System.out.println("✅ Target table 'surname_issues' recreated (Protocol v1.8 Revised schema).");
        }
    }

    // ── Processing ────────────────────────────────────────────────────────────

    private static void processData(
            String srcUrl, String srcUser, String srcPass,
            String tgtUrl, String tgtUser, String tgtPass) throws SQLException {

        String selectSql =
            "SELECT k1.rsbsa_no, k1.surname, k1.ext_name, k1.reg, k1.prv, "
            + "k1.first_name, k1.middle_name, k1.sex, k1.birthday, "
            + "k1.data_source, k1.mun, k1.brgy, "
            + "k4.date_created, k4.date_updated, k4.encoder_fullname "
            + "FROM farmers_kyc1 k1 "
            + "INNER JOIN farmers_kyc4 k4 ON k1.rsbsa_no = k4.rsbsa_no "
            + "INNER JOIN intervention k5 ON k1.rsbsa_no = k5.rsbsa_no "
            + "WHERE k4.duplicated = '0' "
            + "  AND k4.ch_occupation = 'active' "
            + "  AND k4.deceased = '0'";

        String insertSql =
            "INSERT INTO surname_issues "
            + "(rsbsa_no, use_case_id, original_surname, ext_name, first_name, middle_name, "
            + "sex, birthday, region, province, municipality, barangay, "
            + "detected_suffix, issue_flag, error_code, "
            + "date_created, date_updated, encoder_fullname, data_source, age, age_flag) "
            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";

        try (Connection srcConn = DriverManager.getConnection(srcUrl, srcUser, srcPass);
             Connection tgtConn = DriverManager.getConnection(tgtUrl, tgtUser, tgtPass);
             PreparedStatement sel = srcConn.prepareStatement(
                     selectSql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
             PreparedStatement ins = tgtConn.prepareStatement(insertSql)) {

            sel.setFetchSize(Integer.MIN_VALUE);   // Stream results
            ResultSet rs = sel.executeQuery();

            int scanned = 0, found = 0;
            while (rs.next()) {
                IssueResult issue = validateRow(rs);
                if (issue != null) {
                    addIssueToBatch(ins, issue);
                    globalStats.merge(issue.useCaseId, 1L, Long::sum);
                    found++;
                    if (found % BATCH_SIZE == 0) ins.executeBatch();
                }
                if (++scanned % 10_000 == 0) {
                    System.out.printf("  Scanned %,d rows...%n", scanned);
                }
            }
            ins.executeBatch();   // Flush remainder
            System.out.printf("✅ Done. Rows scanned: %,d | Issues found: %,d%n", scanned, found);
        }
    }

    // ── Row Validation ────────────────────────────────────────────────────────

    private static IssueResult validateRow(ResultSet rs) throws SQLException {
        List<String> issues = new ArrayList<>();
        List<String> codes  = new ArrayList<>();

        String ds      = rs.getString("data_source");
        String rsbsaNo = rs.getString("rsbsa_no");
        String surname = rs.getString("surname");
        String firstName  = rs.getString("first_name");
        String middleName = rs.getString("middle_name");
        String sex        = rs.getString("sex");
        LocalDate bday    = rs.getObject("birthday", LocalDate.class);

        IssueResult res = new IssueResult();
        res.rsbsaNo         = rsbsaNo;
        res.dataSource      = ds;
        res.originalSurname = surname;
        res.extName         = rs.getString("ext_name");
        res.firstName       = firstName;
        res.middleName      = middleName;
        res.sex             = sex;
        res.birthday        = bday;
        res.region          = rs.getString("reg");
        res.province        = rs.getString("prv");
        res.municipality    = rs.getString("mun");
        res.barangay        = rs.getString("brgy");
        res.dateCreated     = toLocalDateTime(rs.getTimestamp("date_created"));
        res.dateUpdated     = toLocalDateTime(rs.getTimestamp("date_updated"));
        res.encoderFullname = rs.getString("encoder_fullname");

        // 1. Name field checks (A, B, C, F, G, H)
        validateField(surname,    "surname",     issues, codes);
        validateField(firstName,  "first_name",  issues, codes);
        validateField(middleName, "middle_name", issues, codes);

        // 2. RSBSA number null check (H)
        if (rsbsaNo == null) { issues.add("rsbsa_no: null_value"); codes.add("H"); }

        // 3. Sex validation (A, G, H)
        validateSex(sex, issues, codes);

        // 4. Birthday / age (H, I, J, K)
        validateBirthdayAndAge(bday, issues, codes, res);

        // 5. Suffix in surname (D)
        if (surname != null && !surname.isEmpty()) {
            String[] parts = surname.trim().split("\\s+");
            if (parts.length > 1 && SUFFIXES.contains(parts[parts.length - 1].toUpperCase())) {
                res.detectedSuffix = parts[parts.length - 1].toUpperCase();
                issues.add("surname: has_suffix_in_surname");
                codes.add("D");
            }
        }

        if (issues.isEmpty()) return null;   // Clean record

        res.issueFlag = String.join(", ", issues);
        res.errorCode = String.join(", ", codes);
        res.useCaseId = getUseCaseId(ds, codes.get(0));
        return res;
    }

    private static void validateField(String val, String fieldName,
                                       List<String> issues, List<String> codes) {
        if (val == null) {
            issues.add(fieldName + ": null_value");  codes.add("H"); return;
        }
        if (val.trim().isEmpty()) {
            issues.add(fieldName + ": blank");       codes.add("A"); return;
        }
        if (NUMERIC_PATTERN.matcher(val).matches()) {
            issues.add(fieldName + ": numeric_only"); codes.add("B");
        }
        if (SPECIAL_CHARS_PATTERN.matcher(val).find()) {
            issues.add(fieldName + ": special_chars"); codes.add("C");
        }
        if (PLACEHOLDERS.contains(val.trim().toUpperCase())) {
            issues.add(fieldName + ": placeholder_value"); codes.add("F");
        }
        String lower = val.trim().toLowerCase();
        if (lower.length() <= 1 || ABNORMAL_WORDS.contains(lower)) {
            issues.add(fieldName + ": abnormal_value"); codes.add("G");
        }
    }

    private static void validateSex(String val, List<String> issues, List<String> codes) {
        if (val == null) {
            codes.add("H"); issues.add("sex: null_value");
        } else if (val.trim().isEmpty()) {
            codes.add("A"); issues.add("sex: blank");
        } else if (!val.trim().equals("1") && !val.trim().equals("2")) {
            codes.add("G"); issues.add("sex: invalid_value");
        }
    }

    private static void validateBirthdayAndAge(
            LocalDate bday, List<String> issues, List<String> codes, IssueResult res) {
        if (bday == null) {
            issues.add("birthday: null_value"); codes.add("H"); return;
        }
        int age = java.time.Period.between(bday, LocalDate.now()).getYears();
        res.age = age;
        if (age > 100) {
            issues.add("age: invalid_age"); codes.add("I"); res.ageFlag = "age: invalid_age";
        } else if (age >= 90 && age <= 99) {
            issues.add("age: senior_age");  codes.add("K"); res.ageFlag = "age: senior_age";
        } else if (age <= 10) {
            issues.add("age: minor_age");   codes.add("J"); res.ageFlag = "age: minor_age";
        }
    }

    // ── Use Case ID (CORRECTED — Protocol v1.8) ───────────────────────────────
    //
    //  CODE_ORDER index (0-based):  A=0  B=1  C=2  D=3  F=4  G=5  H=6  I=7  J=8  K=9
    //
    //  FFRS  → "1." + (idx + 1)       A=1.1 B=1.2 C=1.3 D=1.4 F=1.5
    //                                  G=1.6 H=1.7 I=1.8 J=1.9 K=2.0 (special)
    //
    //  NFFIS → "2." + (10 + idx)      A=2.10 B=2.11 C=2.12 D=2.13 F=2.14
    //                                  G=2.15 H=2.16 I=2.17 J=2.18 K=2.19
    //
    //  FiSHR → "2." + (20 + idx)      A=2.20 B=2.21 C=2.22 D=2.23 F=2.24
    //                                  G=2.25 H=2.26 I=2.27 J=2.28 K=2.29
    //
    //  NCFRS → "2." + (30 + idx)      A=2.30 B=2.31 C=2.32 D=2.33 F=2.34
    //                                  G=2.35 H=2.36 I=2.37 J=2.38 K=2.39
    //
    private static String getUseCaseId(String source, String code) {
        char c = (code == null || code.isEmpty()) ? 'A' : code.charAt(0);

        int idx = -1;
        for (int i = 0; i < CODE_ORDER.length; i++) {
            if (CODE_ORDER[i] == c) { idx = i; break; }
        }
        if (idx == -1) return "Unknown";

        if ("ffrs".equalsIgnoreCase(source)) {
            if (c == 'K') return "2.0";           // FFRS senior_age exception
            return "1." + (idx + 1);
        }

        int base;
        if      ("nffis".equalsIgnoreCase(source)) base = 10;
        else if ("fishr".equalsIgnoreCase(source)) base = 20;
        else if ("ncfrs".equalsIgnoreCase(source)) base = 30;
        else    return "Unknown";

        // CORRECTED: base already positions us in the right 2.xx range.
        // Do NOT add +10 — that was the original bug.
        return "2." + (base + idx);
    }

    // ── Batch insert helper ───────────────────────────────────────────────────

    private static void addIssueToBatch(PreparedStatement ps, IssueResult i) throws SQLException {
        ps.setString(1,  i.rsbsaNo);
        ps.setString(2,  i.useCaseId);
        ps.setString(3,  i.originalSurname);
        ps.setString(4,  i.extName);
        ps.setString(5,  i.firstName);
        ps.setString(6,  i.middleName);
        ps.setString(7,  i.sex);
        ps.setObject(8,  i.birthday);
        ps.setString(9,  i.region);
        ps.setString(10, i.province);
        ps.setString(11, i.municipality);
        ps.setString(12, i.barangay);
        ps.setString(13, i.detectedSuffix);
        ps.setString(14, i.issueFlag);
        ps.setString(15, i.errorCode);
        ps.setObject(16, i.dateCreated);
        ps.setObject(17, i.dateUpdated);
        ps.setString(18, i.encoderFullname);
        ps.setString(19, i.dataSource);
        ps.setObject(20, i.age);
        ps.setString(21, i.ageFlag);
        ps.addBatch();
    }

    private static LocalDateTime toLocalDateTime(Timestamp ts) {
        return ts != null ? ts.toLocalDateTime() : null;
    }

    // ── Final Summary ─────────────────────────────────────────────────────────

    private static void printFinalSummary() {
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════════════════╗");
        System.out.println("║   📊  Invalid Data Profiling — Use Case ID Summary (v1.8 Revised)   ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════════╝");
        System.out.println();

        if (globalStats.isEmpty()) {
            System.out.println("  No issues found."); return;
        }

        System.out.printf("  %-12s | %-10s | %-8s | %-6s | %-25s%n",
                "Use Case ID", "Source", "Count", "Code", "Description");
        System.out.println("  ─────────────|────────────|──────────|────────|─────────────────────────");

        globalStats.entrySet().stream()
            .sorted(Comparator.comparingDouble(e -> parseUcId(e.getKey())))
            .forEach(e -> {
                String ucId  = e.getKey();
                long   count = e.getValue();
                String source = "?", code = "?", desc = "?";
                for (Map.Entry<String, String[]> info : USE_CASE_INFO.entrySet()) {
                    if (info.getValue()[0].equals(ucId)) {
                        source = info.getValue()[1];
                        code   = info.getValue()[2];
                        desc   = info.getValue()[3];
                        break;
                    }
                }
                System.out.printf("  %-12s | %-10s | %,8d | %-6s | %-25s%n",
                        ucId, source, count, code, desc);
            });

        System.out.println("  ─────────────|────────────|──────────|────────|─────────────────────────");
        long total = globalStats.values().stream().mapToLong(Long::longValue).sum();
        System.out.printf("  %-12s | %-10s | %,8d%n", "TOTAL", "", total);
        System.out.println();
    }

    private static double parseUcId(String ucId) {
        try { return Double.parseDouble(ucId); }
        catch (NumberFormatException e) { return 999.0; }
    }
}
