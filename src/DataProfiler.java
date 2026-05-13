import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.Date;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class DataProfiler {

    // === DB CONFIG (Move to environment variables in production) ===
    private static final String SRC_URL = "jdbc:mysql://172.16.200.181:3306/farmers_regdb?useUnicode=true&characterEncoding=UTF-8&serverTimezone=UTC&useCursorFetch=true";
    private static final String SRC_USER = "ibautista";
    private static final String SRC_PASS = "FRPcfWKsnx!m5DTZQLz4@UjNw";

    private static final String TGT_URL = "jdbc:mysql://localhost:3306/results_db?useUnicode=true&characterEncoding=UTF-8&serverTimezone=UTC";
    private static final String TGT_USER = "ibautista";
    private static final String TGT_PASS = "$olidTigasiMm@n777";

    private static final int BATCH_SIZE = 5000;

    // === Pre-compiled Regex Patterns (Performance boost) ===
    private static final Pattern NUMERIC_PATTERN = Pattern.compile("\\d+");
    private static final Pattern SPECIAL_CHARS_PATTERN = Pattern.compile("[^A-Za-zÑñ\\s-]");
    private static final Set<String> PLACEHOLDERS = new HashSet<>(Arrays.asList("N/A", "NA", "-", "NONE"));
    private static final Set<String> ABNORMAL_WORDS = new HashSet<>(Arrays.asList("test", "xxx", "sample"));
    private static final Set<String> SUFFIXES = new HashSet<>(Arrays.asList("JR", "SR", "JR.", "SR.", "II", "III", "IV"));

    // === Issue Codes ===
    private static final Map<String, String> ISSUE_CODE_MAP = new HashMap<>();
    static {
        ISSUE_CODE_MAP.put("blank", "A");
        ISSUE_CODE_MAP.put("numeric_only", "B");
        ISSUE_CODE_MAP.put("special_chars", "C");
        ISSUE_CODE_MAP.put("has_suffix_in_surname", "D");
        ISSUE_CODE_MAP.put("placeholder_value", "F");
        ISSUE_CODE_MAP.put("abnormal_value", "G");
        ISSUE_CODE_MAP.put("null_value", "H");
        ISSUE_CODE_MAP.put("invalid_age", "I");
        ISSUE_CODE_MAP.put("minor_age", "J");
        ISSUE_CODE_MAP.put("senior_age", "K");
    }

    // Global Statistics (Saves memory over storing all objects)
    private static final Map<String, Long> globalStats = new HashMap<>();

    public static void main(String[] args) {
        long startTime = System.currentTimeMillis();

        try {
            setupTargetTable();
            processData();
            printFinalSummary();
        } catch (Exception e) {
            e.printStackTrace();
        }

        long endTime = System.currentTimeMillis();
        System.out.printf("\n🚀 Total Execution Time: %d ms\n", (endTime - startTime));
    }

    private static void setupTargetTable() throws SQLException {
        try (Connection conn = DriverManager.getConnection(TGT_URL, TGT_USER, TGT_PASS);
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("DROP TABLE IF EXISTS surname_issues");
            String sql = "CREATE TABLE surname_issues (" +
                    "rsbsa_no VARCHAR(255) PRIMARY KEY, original_surname VARCHAR(255), ext_name VARCHAR(255), " +
                    "first_name VARCHAR(255), middle_name VARCHAR(255), sex VARCHAR(10), birthday DATE, " +
                    "region VARCHAR(255), province VARCHAR(255), municipality VARCHAR(255), barangay VARCHAR(255), " +
                    "detected_suffix VARCHAR(50), issue_flag TEXT, error_code TEXT, date_created DATETIME, " +
                    "date_updated DATETIME, encoder_fullname VARCHAR(255), data_source VARCHAR(255), " +
                    "age INT, age_flag TEXT)";
            stmt.executeUpdate(sql);
            System.out.println("✅ Target table recreated.");
        }
    }

    private static void processData() throws SQLException {
        String query = "SELECT k1.rsbsa_no, k1.surname, k1.ext_name, k1.reg, k1.prv, k1.first_name, k1.middle_name, " +
                "k1.sex, k1.birthday, k1.data_source, k1.mun, k1.brgy, k4.date_created, k4.date_updated, k4.encoder_fullname " +
                "FROM farmers_kyc1 k1 " +
                "INNER JOIN farmers_kyc4 k4 ON k1.rsbsa_no = k4.rsbsa_no " +
                "INNER JOIN intervention k5 ON k1.rsbsa_no = k5.rsbsa_no " +
                "WHERE k4.duplicated = '0' AND k4.ch_occupation = 'active' AND k4.deceased = '0'";

        String insertSql = "INSERT INTO surname_issues (rsbsa_no, original_surname, ext_name, first_name, middle_name, sex, birthday, " +
                "region, province, municipality, barangay, detected_suffix, issue_flag, error_code, date_created, " +
                "date_updated, encoder_fullname, data_source, age, age_flag) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";

        try (Connection srcConn = DriverManager.getConnection(SRC_URL, SRC_USER, SRC_PASS);
             Connection tgtConn = DriverManager.getConnection(TGT_URL, TGT_USER, TGT_PASS);
             PreparedStatement selectStmt = srcConn.prepareStatement(query, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
             PreparedStatement insertStmt = tgtConn.prepareStatement(insertSql)) {

            // Critical for MySQL streaming
            selectStmt.setFetchSize(Integer.MIN_VALUE);

            ResultSet rs = selectStmt.executeQuery();
            int count = 0;
            int issuesFound = 0;

            while (rs.next()) {
                IssueResult issue = validateRow(rs);

                if (issue != null) {
                    addIssueToBatch(insertStmt, issue);
                    issuesFound++;

                    // Track stats incrementally (Memory Efficient)
                    for (String code : issue.errorCode.split(", ")) {
                        globalStats.merge(code, 1L, Long::sum);
                    }

                    if (issuesFound % BATCH_SIZE == 0) {
                        insertStmt.executeBatch();
                    }
                }

                if (++count % 10000 == 0) {
                    System.out.println("Processed " + count + " rows...");
                }
            }
            insertStmt.executeBatch(); // Final batch
            System.out.println("✅ Processing complete. Total rows scanned: " + count + ". Total issues: " + issuesFound);
        }
    }

    private static IssueResult validateRow(ResultSet rs) throws SQLException {
        List<String> issues = new ArrayList<>();
        List<String> codes = new ArrayList<>();

        // 1. Extract raw data from ResultSet
        String rsbsaNo = rs.getString("rsbsa_no");
        String surname = rs.getString("surname");
        String firstName = rs.getString("first_name");
        String middleName = rs.getString("middle_name");
        String sex = rs.getString("sex");
        LocalDate bday = rs.getObject("birthday", LocalDate.class);

        // 2. Initialize the Result Object and fill basic info
        IssueResult res = new IssueResult();
        res.rsbsaNo = rsbsaNo;
        res.originalSurname = surname;
        res.extName = rs.getString("ext_name");
        res.firstName = firstName;
        res.middleName = middleName;
        res.sex = sex;
        res.birthday = bday;
        res.region = rs.getString("reg");
        res.province = rs.getString("prv");
        res.municipality = rs.getString("mun");
        res.barangay = rs.getString("brgy");
        res.dateCreated = getLocalDateTime(rs.getTimestamp("date_created"));
        res.dateUpdated = getLocalDateTime(rs.getTimestamp("date_updated"));
        res.encoderFullname = rs.getString("encoder_fullname");
        res.dataSource = rs.getString("data_source");

        // 3. Run Validation Logic (This fills the 'issues' and 'codes' lists)
        validateField(surname, "surname", issues, codes);
        validateField(firstName, "first_name", issues, codes);
        validateField(middleName, "middle_name", issues, codes);
        validateRequired(rsbsaNo, "rsbsa_no", issues, codes);
        validateRequired(res.region, "region", issues, codes);
        validateRequired(res.province, "province", issues, codes);
        validateSex(sex, issues, codes);
        validateBirthdayAndAge(bday, issues, codes, res);

        // 4. Suffix Extraction Logic
        if (surname != null && !surname.isEmpty()) {
            String[] parts = surname.trim().split("\\s+");
            if (parts.length > 1) {
                String lastPart = parts[parts.length - 1].toUpperCase();
                if (SUFFIXES.contains(lastPart)) {
                    res.detectedSuffix = lastPart; // Store the found suffix
                    issues.add("surname: has_suffix_in_surname");
                    codes.add(ISSUE_CODE_MAP.get("has_suffix_in_surname"));
                }
            }
        }

        // 5. If no issues were found at all, return null (skip this record)
        if (issues.isEmpty()) {
            return null;
        }

        // 6. Finalize: Join the lists into single strings for the database columns
        res.issueFlag = String.join(", ", issues);
        res.errorCode = String.join(", ", codes);

        return res;
    }

    private static void validateField(String val, String name, List<String> issues, List<String> codes) {
        if (val == null || val.trim().isEmpty()) {
            issues.add(name + ": blank");
            codes.add(ISSUE_CODE_MAP.get("blank"));
            return;
        }
        if (NUMERIC_PATTERN.matcher(val).matches()) {
            issues.add(name + ": numeric_only");
            codes.add(ISSUE_CODE_MAP.get("numeric_only"));
        }
        if (SPECIAL_CHARS_PATTERN.matcher(val).find()) {
            issues.add(name + ": special_chars");
            codes.add(ISSUE_CODE_MAP.get("special_chars"));
        }
        if (PLACEHOLDERS.contains(val.trim().toUpperCase())) {
            issues.add(name + ": placeholder_value");
            codes.add(ISSUE_CODE_MAP.get("placeholder_value"));
        }
        String lower = val.trim().toLowerCase();
        if (lower.length() <= 1 || ABNORMAL_WORDS.contains(lower)) {
            issues.add(name + ": abnormal_value");
            codes.add(ISSUE_CODE_MAP.get("abnormal_value"));
        }
    }

    private static void validateRequired(String val, String name, List<String> issues, List<String> codes) {
        if (val == null) {
            issues.add(name + ": null_value");
            codes.add(ISSUE_CODE_MAP.get("null_value"));
        }
    }

    private static void validateSex(String val, List<String> issues, List<String> codes) {
        if (val == null || val.trim().isEmpty()) {
            issues.add("sex: blank");
            codes.add(ISSUE_CODE_MAP.get("blank"));
        } else {
            String n = val.trim();
            // Valid values are "1" (Male) and "2" (Female)
            if (!n.equals("1") && !n.equals("2")) {
                issues.add("sex: invalid_value");
                codes.add(ISSUE_CODE_MAP.get("abnormal_value"));
            }
        }
    }

    private static void validateBirthdayAndAge(LocalDate bday, List<String> issues, List<String> codes, IssueResult res) {
        if (bday == null) {
            issues.add("birthday: null_value");
            codes.add(ISSUE_CODE_MAP.get("null_value")); // Maps to "H"
            return;
        }

        // Compute age based on current date
        int age = java.time.Period.between(bday, LocalDate.now()).getYears();
        res.age = age; // Store the actual calculated age

        // Map to your new specific categories
        if (age > 100) {
            issues.add("age: invalid_age");
            codes.add(ISSUE_CODE_MAP.get("invalid_age")); // Maps to "I"
            res.ageFlag = "age: invalid_age";
        }
        else if (age >= 90 && age <= 99) {
            issues.add("age: senior_age");
            codes.add(ISSUE_CODE_MAP.get("senior_age")); // Maps to "K"
            res.ageFlag = "age: senior_age";
        }
        else if (age <= 10) {
            issues.add("age: minor_age");
            codes.add(ISSUE_CODE_MAP.get("minor_age")); // Maps to "J"
            res.ageFlag = "age: minor_age";
        }

        // Additional sanity check for future dates (retains Code G if desired)
        if (bday.isAfter(LocalDate.now())) {
            issues.add("birthday: future_date");
            codes.add(ISSUE_CODE_MAP.get("abnormal_value")); // Maps to "G"
        }
    }

    private static void addIssueToBatch(PreparedStatement ps, IssueResult i) throws SQLException {
        ps.setString(1, i.rsbsaNo);
        ps.setString(2, i.originalSurname);
        ps.setString(3, i.extName);
        ps.setString(4, i.firstName);
        ps.setString(5, i.middleName);
        ps.setString(6, i.sex);
        ps.setObject(7, i.birthday);
        ps.setString(8, i.region);
        ps.setString(9, i.province);
        ps.setString(10, i.municipality);
        ps.setString(11, i.barangay);
        ps.setString(12, i.detectedSuffix);
        ps.setString(13, i.issueFlag);
        ps.setString(14, i.errorCode);
        ps.setObject(15, i.dateCreated);
        ps.setObject(16, i.dateUpdated);
        ps.setString(17, i.encoderFullname);
        ps.setString(18, i.dataSource);
        ps.setObject(19, i.age);
        ps.setString(20, i.ageFlag);
        ps.addBatch();
    }

    private static LocalDateTime getLocalDateTime(Timestamp ts) {
        return (ts != null) ? ts.toLocalDateTime() : null;
    }

    private static void printFinalSummary() {
        System.out.println("\n📊 Aggregated Issue Summary:");
        System.out.println("Code | Count | Description");
        System.out.println("---------------------------");
        globalStats.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> System.out.printf("%-4s | %-5d | %s\n", e.getKey(), e.getValue(), getDesc(e.getKey())));
    }

    private static String getDesc(String code) {
        return ISSUE_CODE_MAP.entrySet().stream()
                .filter(e -> e.getValue().equals(code))
                .map(Map.Entry::getKey)
                .findFirst().orElse("Unknown");
    }
}