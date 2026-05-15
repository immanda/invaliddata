import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Pattern;

public class DataProfiler {

    // === DB CONFIG ===
    private static final String SRC_URL = "jdbc:mysql://172.16.200.181:3306/farmers_regdb?useUnicode=true&characterEncoding=UTF-8&serverTimezone=UTC&useCursorFetch=true";
    private static final String SRC_USER = "ibautista";
    private static final String SRC_PASS = "FRPcfWKsnx!m5DTZQLz4@UjNw";

    private static final String TGT_URL = "jdbc:mysql://localhost:3306/results_db?useUnicode=true&characterEncoding=UTF-8&serverTimezone=UTC";
    private static final String TGT_USER = "ibautista";
    private static final String TGT_PASS = "$olidTigasiMm@n777";

    private static final int BATCH_SIZE = 5000;

    // === Updated Regex per Protocol v1.8 ===
    private static final Pattern NUMERIC_PATTERN = Pattern.compile("\\d+");
    // Allowed: Letters, Spaces, Hyphens, and Apostrophes (')
    private static final Pattern SPECIAL_CHARS_PATTERN = Pattern.compile("[^A-Za-zÑñ\\s\\-']");
    private static final Set<String> PLACEHOLDERS = new HashSet<>(Arrays.asList("N/A", "NA", "-", "NONE"));
    private static final Set<String> ABNORMAL_WORDS = new HashSet<>(Arrays.asList("test", "xxx", "sample"));
    private static final Set<String> SUFFIXES = new HashSet<>(Arrays.asList("JR", "SR", "JR.", "SR.", "II", "III", "IV"));

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

    private static final Map<String, Long> globalStats = new HashMap<>();

    public static void main(String[] args) {
        try {
            setupTargetTable();
            processData();
            printFinalSummary();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void setupTargetTable() throws SQLException {
        try (Connection conn = DriverManager.getConnection(TGT_URL, TGT_USER, TGT_PASS);
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("DROP TABLE IF EXISTS surname_issues");
            String sql = "CREATE TABLE surname_issues (" +
                    "rsbsa_no VARCHAR(255) PRIMARY KEY, " +
                    "use_case_id VARCHAR(50), " + // Added for Protocol audit
                    "original_surname VARCHAR(255), ext_name VARCHAR(255), " +
                    "first_name VARCHAR(255), middle_name VARCHAR(255), sex VARCHAR(10), birthday DATE, " +
                    "region VARCHAR(255), province VARCHAR(255), municipality VARCHAR(255), barangay VARCHAR(255), " +
                    "detected_suffix VARCHAR(50), issue_flag TEXT, error_code TEXT, date_created DATETIME, " +
                    "date_updated DATETIME, encoder_fullname VARCHAR(255), data_source VARCHAR(255), " +
                    "age INT, age_flag TEXT)";
            stmt.executeUpdate(sql);
            System.out.println("✅ Target table recreated with Protocol v1.8 schema.");
        }
    }

    private static void processData() throws SQLException {
        String query = "SELECT k1.rsbsa_no, k1.surname, k1.ext_name, k1.reg, k1.prv, k1.first_name, k1.middle_name, " +
                "k1.sex, k1.birthday, k1.data_source, k1.mun, k1.brgy, k4.date_created, k4.date_updated, k4.encoder_fullname " +
                "FROM farmers_kyc1 k1 " +
                "INNER JOIN farmers_kyc4 k4 ON k1.rsbsa_no = k4.rsbsa_no " +
                "INNER JOIN intervention k5 ON k1.rsbsa_no = k5.rsbsa_no " +
                "WHERE k4.duplicated = '0' AND k4.ch_occupation = 'active' AND k4.deceased = '0'";

        String insertSql = "INSERT INTO surname_issues (rsbsa_no, use_case_id, original_surname, ext_name, first_name, middle_name, sex, birthday, " +
                "region, province, municipality, barangay, detected_suffix, issue_flag, error_code, date_created, " +
                "date_updated, encoder_fullname, data_source, age, age_flag) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";

        try (Connection srcConn = DriverManager.getConnection(SRC_URL, SRC_USER, SRC_PASS);
             Connection tgtConn = DriverManager.getConnection(TGT_URL, TGT_USER, TGT_PASS);
             PreparedStatement selectStmt = srcConn.prepareStatement(query, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
             PreparedStatement insertStmt = tgtConn.prepareStatement(insertSql)) {

            selectStmt.setFetchSize(Integer.MIN_VALUE);
            ResultSet rs = selectStmt.executeQuery();
            int count = 0, issuesFound = 0;

            while (rs.next()) {
                IssueResult issue = validateRow(rs);
                if (issue != null) {
                    addIssueToBatch(insertStmt, issue);
                    issuesFound++;
                    for (String code : issue.errorCode.split(", ")) {
                        globalStats.merge(code, 1L, Long::sum);
                    }
                    if (issuesFound % BATCH_SIZE == 0) insertStmt.executeBatch();
                }
                if (++count % 10000 == 0) System.out.println("Processed " + count + " rows...");
            }
            insertStmt.executeBatch();
            System.out.println("✅ Complete. Total scanned: " + count + ". Total issues: " + issuesFound);
        }
    }

    private static IssueResult validateRow(ResultSet rs) throws SQLException {
        List<String> issues = new ArrayList<>();
        List<String> codes = new ArrayList<>();

        String ds = rs.getString("data_source");
        String rsbsaNo = rs.getString("rsbsa_no");
        String surname = rs.getString("surname");
        String firstName = rs.getString("first_name");
        String middleName = rs.getString("middle_name");
        String sex = rs.getString("sex");
        LocalDate bday = rs.getObject("birthday", LocalDate.class);

        IssueResult res = new IssueResult();
        res.rsbsaNo = rsbsaNo;
        res.dataSource = ds;
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

        // 1. Name Validations (Codes A, B, C, F, G, H)
        validateField(surname, "surname", issues, codes);
        validateField(firstName, "first_name", issues, codes);
        validateField(middleName, "middle_name", issues, codes);

        // 2. Required/Sex Validations
        if (rsbsaNo == null) { issues.add("rsbsa_no: null_value"); codes.add("H"); }
        validateSex(sex, issues, codes);

        // 3. Birthday and Age (Codes I, J, K, H)
        validateBirthdayAndAge(bday, issues, codes, res);

        // 4. Suffix Extraction (Code D)
        if (surname != null && !surname.isEmpty()) {
            String[] parts = surname.trim().split("\\s+");
            if (parts.length > 1 && SUFFIXES.contains(parts[parts.length - 1].toUpperCase())) {
                res.detectedSuffix = parts[parts.length - 1].toUpperCase();
                issues.add("surname: has_suffix_in_surname");
                codes.add("D");
            }
        }

        if (issues.isEmpty()) return null;

        res.issueFlag = String.join(", ", issues);
        res.errorCode = String.join(", ", codes);
        // Protocol Audit: Get the first error's use case ID
        res.useCaseId = getUseCaseId(ds, codes.get(0));

        return res;
    }

    private static void validateField(String val, String name, List<String> issues, List<String> codes) {
        if (val == null) {
            issues.add(name + ": null_value");
            codes.add("H");
            return;
        }
        if (val.trim().isEmpty()) {
            issues.add(name + ": blank");
            codes.add("A");
            return;
        }
        if (NUMERIC_PATTERN.matcher(val).matches()) {
            issues.add(name + ": numeric_only");
            codes.add("B");
        }
        if (SPECIAL_CHARS_PATTERN.matcher(val).find()) {
            issues.add(name + ": special_chars");
            codes.add("C");
        }
        if (PLACEHOLDERS.contains(val.trim().toUpperCase())) {
            issues.add(name + ": placeholder_value");
            codes.add("F");
        }
        String lower = val.trim().toLowerCase();
        if (lower.length() <= 1 || ABNORMAL_WORDS.contains(lower)) {
            issues.add(name + ": abnormal_value");
            codes.add("G");
        }
    }

    private static void validateSex(String val, List<String> issues, List<String> codes) {
        if (val == null) { codes.add("H"); issues.add("sex: null_value"); }
        else if (val.trim().isEmpty()) { codes.add("A"); issues.add("sex: blank"); }
        else if (!val.trim().equals("1") && !val.trim().equals("2")) {
            codes.add("G"); issues.add("sex: invalid_value");
        }
    }

    private static void validateBirthdayAndAge(LocalDate bday, List<String> issues, List<String> codes, IssueResult res) {
        if (bday == null) {
            issues.add("birthday: null_value");
            codes.add("H");
            return;
        }
        int age = java.time.Period.between(bday, LocalDate.now()).getYears();
        res.age = age;

        if (age > 100) {
            issues.add("age: invalid_age");
            codes.add("I");
            res.ageFlag = "age: invalid_age";
        } else if (age >= 90 && age <= 99) {
            issues.add("age: senior_age");
            codes.add("K");
            res.ageFlag = "age: senior_age";
        } else if (age <= 10) {
            issues.add("age: minor_age");
            codes.add("J");
            res.ageFlag = "age: minor_age";
        }
    }

    private static String getUseCaseId(String source, String code) {
        String prefix = "ffrs".equalsIgnoreCase(source) ? "1." : "2.";
        int base = 0;
        if ("nffis".equalsIgnoreCase(source)) base = 10;
        else if ("fishr".equalsIgnoreCase(source)) base = 20;
        else if ("ncfrs".equalsIgnoreCase(source)) base = 30;

        // Map A=0, B=1, C=2, D=3, F=4, G=5, H=6, I=7, J=8, K=9
        int codeOffset = "ABCDFGH IJK".indexOf(code);
        if (codeOffset == -1) return "Unknown";

        // Handle the non-sequential space in the string above
        if (codeOffset > 5) codeOffset--;

        return prefix + (base + codeOffset);
    }

    private static void addIssueToBatch(PreparedStatement ps, IssueResult i) throws SQLException {
        ps.setString(1, i.rsbsaNo);
        ps.setString(2, i.useCaseId);
        ps.setString(3, i.originalSurname);
        ps.setString(4, i.extName);
        ps.setString(5, i.firstName);
        ps.setString(6, i.middleName);
        ps.setString(7, i.sex);
        ps.setObject(8, i.birthday);
        ps.setString(9, i.region);
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

    private static LocalDateTime getLocalDateTime(Timestamp ts) {
        return (ts != null) ? ts.toLocalDateTime() : null;
    }

    private static void printFinalSummary() {
        System.out.println("\n📊 Aggregated Issue Summary (Protocol v1.8):");
        globalStats.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(e -> System.out.printf("%s: %d\n", e.getKey(), e.getValue()));
    }

    private static String getDesc(String code) {
        return ISSUE_CODE_MAP.entrySet().stream()
                .filter(e -> e.getValue().equals(code))
                .map(Map.Entry::getKey).findFirst().orElse("Unknown");
    }
}