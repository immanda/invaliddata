DA-ICTS INVALID DATA PROFILER — REVISED (Protocol v1.8 Corrected)
ICTS - Database Management Division
===============================================================

REVISION NOTES
--------------
  This is the corrected version of the Invalid Data Profiler.

  KEY FIX — Use Case ID offset formula in DataProfiler.java (getUseCaseId):
    OLD (wrong) : return "2." + (base + idx + 10);
                  Results produced in the DB were wrong:
                    NFFIS → 2.20–2.29  (should be 2.10–2.19)
                    FiSHR → 2.30–2.39  (should be 2.20–2.29)
                    NCFRS → 2.40–2.49  (should be 2.30–2.39)

    NEW (correct): return "2." + (base + idx);
                    NFFIS → 2.10–2.19  ✓
                    FiSHR → 2.20–2.29  ✓
                    NCFRS → 2.30–2.39  ✓

  The dashboard UC_MAP in index.html has also been updated to match
  these corrected ranges.


OVERVIEW
--------
Standalone batch profiling tool for Invalid Data detection in the RSBSA.
Runs the Invalid Data Profiling process independently — no Deduplication
step is included, so this runs significantly faster when you only need
the invalid data results.

Output table: surname_issues  (in results_db)

Reference protocol: Protocols on Data Cleansing for Invalid Data v1.8


SOURCE FILES
------------
  Main.java         -- Entry point (standalone, no dedup dependency)
  DBConnection.java -- Lightweight config loader (no dedup references)
  DataProfiler.java -- Core invalid data profiling logic (CORRECTED v1.8)
  IssueResult.java  -- Data model for a flagged record
  config.properties -- Database credentials (classpath resource)


USE CASE ID MAPPING  (Protocol v1.8 — CORRECTED)
-------------------------------------------------
  FFRS   : 1.1(A)  1.2(B)  1.3(C)  1.4(D)  1.5(F)
            1.6(G)  1.7(H)  1.8(I)  1.9(J)  2.0(K)

  NFFIS  : 2.10(A) 2.11(B) 2.12(C) 2.13(D) 2.14(F)
            2.15(G) 2.16(H) 2.17(I) 2.18(J) 2.19(K)

  FiSHR  : 2.20(A) 2.21(B) 2.22(C) 2.23(D) 2.24(F)
            2.25(G) 2.26(H) 2.27(I) 2.28(J) 2.29(K)

  NCFRS  : 2.30(A) 2.31(B) 2.32(C) 2.33(D) 2.34(F)
            2.35(G) 2.36(H) 2.37(I) 2.38(J) 2.39(K)

  Error codes  (note: no Code E in Protocol v1.8):
    A = blank                  F = placeholder_value
    B = numeric_only           G = abnormal_value
    C = special_chars          H = null_value
    D = has_suffix_in_surname
    I = invalid_age  (age > 100)
    J = minor_age    (age <= 10)
    K = senior_age   (age 90–99)


HOW TO COMPILE  (requires Java 11+ and MySQL Connector/J on classpath)
-----------------------------------------------------------------------
  Windows:
    javac -cp ".;mysql-connector-j-*.jar" src\*.java -d out

  Linux / Mac:
    javac -cp ".:mysql-connector-j-*.jar" src/*.java -d out


HOW TO RUN
----------
  Windows:
    java -cp "out;mysql-connector-j-*.jar;." Main

  Linux / Mac:
    java -cp "out:mysql-connector-j-*.jar:." Main

  config.properties must be in the working directory (the "." classpath
  entry covers this when you run from the InvalidDataProfiler_Revised folder).


WHAT THE PROGRAM DOES
----------------------
  1. Reads credentials from config.properties.
  2. Drops and recreates surname_issues in results_db (clean slate per run).
  3. Connects to the RSBSA source DB (farmers_regdb) — requires VPN.
  4. Streams all records where:
       - duplicated    = '0'
       - ch_occupation = 'active'
       - deceased      = '0'
  5. Validates each record against all 10 error codes (A, B, C, D, F–K).
  6. Writes flagged records to surname_issues with the corrected use_case_id.
  7. Prints a final summary grouped by use_case_id showing count, source,
     code, and description for every use case that has at least one record.

  WARNING: surname_issues is DROPPED and RECREATED at the start of each run.
  Do not use it as permanent storage for resolved records.


DIFFERENCES FROM DataCleansingBatch
-------------------------------------
  This project contains ONLY the invalid data profiling files.
  Deduplication (CaseClassifier, DeduplicationProcess, JaroWinkler,
  Record, ResultTable, FarmerKyc1Data) is NOT included here.

  Use DataCleansingBatch if you want to run both Deduplication AND
  Invalid Data Profiling in a single execution.


PROTOCOL REFERENCE
------------------
  Protocols on Data Cleansing for Invalid Data v1.8
===============================================================
