import java.time.LocalDate;
import java.time.LocalDateTime;

public class FarmerKyc1Data {
    public String rsbsaNo;
    public String surname;
    public String extName;
    public String region;
    public String firstName;
    public String middleName;
    public String sex;
    public LocalDate birthday;
    public String dataSource;
    public String municipality;
    public String barangay;
    public String province; // <--- ADDED THIS
    public LocalDateTime dateCreated;
    public LocalDateTime dateUpdated;
    public String encoderFullname;

    // Getters and Setters (or make fields public for simplicity, as done here)
}