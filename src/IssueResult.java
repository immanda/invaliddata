import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * IssueResult.java  — REVISED
 * DA-ICTS Database Management Division
 * Invalid Data Profiling — Protocol v1.8 (Corrected)
 *
 * Data model for a single flagged RSBSA record.
 * Populated by DataProfiler.validateRow() and written to surname_issues.
 *
 * useCaseId is assigned using the corrected getUseCaseId() formula:
 *   FFRS   → 1.1–1.9, 2.0
 *   NFFIS  → 2.10–2.19
 *   FiSHR  → 2.20–2.29
 *   NCFRS  → 2.30–2.39
 */
public class IssueResult {
    public String    rsbsaNo;
    public String    useCaseId;          // Protocol v1.8 use case (corrected)
    public String    originalSurname;
    public String    extName;
    public String    firstName;
    public String    middleName;
    public String    sex;
    public LocalDate birthday;

    public String    region;
    public String    province;
    public String    municipality;
    public String    barangay;

    public String    detectedSuffix;
    public String    issueFlag;          // Comma-separated issue descriptions
    public String    errorCode;          // Comma-separated error code letters

    public LocalDateTime dateCreated;
    public LocalDateTime dateUpdated;

    public String    encoderFullname;
    public String    dataSource;         // ffrs / nffis / fishr / ncfrs

    public Integer   age;
    public String    ageFlag;
}
