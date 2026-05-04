import java.time.LocalDate;
import java.time.LocalDateTime;

public class IssueResult {
    public String rsbsaNo;
    public String originalSurname;
    public String extName;
    public String firstName;
    public String middleName;
    public String sex;
    public LocalDate birthday;

    public String region;
    public String province;
    public String municipality;
    public String barangay;

    public String detectedSuffix;
    public String issueFlag;   // Comma-separated
    public String errorCode;   // Comma-separated

    public LocalDateTime dateCreated;
    public LocalDateTime dateUpdated;

    public String encoderFullname;
    public String dataSource;

    public Integer age;
    public String ageFlag;
}