package com.farmeraccounts.sync;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Synchronised entities. The same records are accepted in push payloads and returned by pull,
 * so the mobile app and server share one JSON shape. Money is always integer paise.
 */
public final class EntityModels {

    private EntityModels() {}

    /** Upper bounds that catch typing mistakes (e.g. an extra zero) and overflow. */
    public static final long MAX_DAILY_WAGE_PAISE = 10_000_00L;      // Rs 10,000
    public static final long MAX_AMOUNT_PAISE = 1_00_00_000_00L;     // Rs 1 crore
    public static final long MAX_SALE_PAISE = 10_00_00_000_00L;      // Rs 10 crore

    public static final String CROP_PATTERN = "ARECANUT|COCONUT|PEPPER|BANANA|COFFEE|PADDY|OTHER";

    public interface Versioned<T> {
        UUID id();

        Long serverVersion();

        T withVersion(Long version);
    }

    public record Farm(@NotNull UUID id,
                       @NotBlank @Size(max = 100) String name,
                       @Size(max = 100) String village,
                       @Size(max = 100) String district,
                       @Size(max = 100) String state,
                       @DecimalMin("0") @DecimalMax("100000") BigDecimal totalAcreage,
                       @NotBlank @Pattern(regexp = CROP_PATTERN) String primaryCrop,
                       @Size(max = 1000) String notes,
                       boolean deleted,
                       Long serverVersion) implements Versioned<Farm> {
        @Override
        public Farm withVersion(Long v) {
            return new Farm(id, name, village, district, state, totalAcreage, primaryCrop, notes, deleted, v);
        }
    }

    public record Field(@NotNull UUID id,
                        @NotNull UUID farmId,
                        @NotBlank @Size(max = 100) String name,
                        @DecimalMin("0") @DecimalMax("100000") BigDecimal areaAcres,
                        @NotBlank @Pattern(regexp = CROP_PATTERN) String crop,
                        @Size(max = 1000) String description,
                        boolean deleted,
                        Long serverVersion) implements Versioned<Field> {
        @Override
        public Field withVersion(Long v) {
            return new Field(id, farmId, name, areaAcres, crop, description, deleted, v);
        }
    }

    public record Worker(@NotNull UUID id,
                         @NotNull UUID farmId,
                         @NotBlank @Size(max = 100) String name,
                         @Size(max = 20) String phone,
                         @NotNull @Min(1) @Max(MAX_DAILY_WAGE_PAISE) Long dailyWagePaise,
                         @NotNull LocalDate joiningDate,
                         boolean active,
                         @Size(max = 1000) String notes,
                         Long serverVersion) implements Versioned<Worker> {
        @Override
        public Worker withVersion(Long v) {
            return new Worker(id, farmId, name, phone, dailyWagePaise, joiningDate, active, notes, v);
        }
    }

    public record WageRate(@NotNull UUID id,
                           @NotNull UUID workerId,
                           @NotNull @Min(1) @Max(MAX_DAILY_WAGE_PAISE) Long dailyWagePaise,
                           @NotNull LocalDate effectiveFrom,
                           @NotNull Instant createdAt,
                           Long serverVersion) implements Versioned<WageRate> {
        @Override
        public WageRate withVersion(Long v) {
            return new WageRate(id, workerId, dailyWagePaise, effectiveFrom, createdAt, v);
        }
    }

    public record Attendance(@NotNull UUID id,
                             @NotNull UUID farmId,
                             @NotNull UUID workerId,
                             @NotNull LocalDate date,
                             @NotNull @Pattern(regexp = "PRESENT|HALF_DAY|ABSENT|SKIP") String status,
                             Long serverVersion) implements Versioned<Attendance> {
        @Override
        public Attendance withVersion(Long v) {
            return new Attendance(id, farmId, workerId, date, status, v);
        }
    }

    public record Transaction(@NotNull UUID id,
                              @NotNull UUID farmId,
                              @NotNull UUID workerId,
                              @NotNull @Pattern(regexp = "ADVANCE|SETTLEMENT|ADJUSTMENT") String type,
                              @NotNull @Min(-MAX_AMOUNT_PAISE) @Max(MAX_AMOUNT_PAISE) Long amountPaise,
                              @NotNull LocalDate date,
                              @Size(max = 500) String note,
                              UUID reversesTransactionId,
                              Long serverVersion) implements Versioned<Transaction> {
        @Override
        public Transaction withVersion(Long v) {
            return new Transaction(id, farmId, workerId, type, amountPaise, date, note, reversesTransactionId, v);
        }
    }

    public record Expense(@NotNull UUID id,
                          @NotNull UUID farmId,
                          UUID fieldId,
                          @NotNull @Pattern(regexp = "FERTILIZER|PESTICIDE|SPRAY|TRACTOR|MACHINERY|TRANSPORT|LABOUR|OTHER")
                          String category,
                          @NotBlank @Size(max = 200) String description,
                          @NotNull @Min(1) @Max(MAX_AMOUNT_PAISE) Long amountPaise,
                          @NotNull LocalDate date,
                          @Size(max = 1000) String notes,
                          boolean deleted,
                          Long serverVersion) implements Versioned<Expense> {
        @Override
        public Expense withVersion(Long v) {
            return new Expense(id, farmId, fieldId, category, description, amountPaise, date, notes, deleted, v);
        }
    }

    public record Sale(@NotNull UUID id,
                       @NotNull UUID farmId,
                       @NotBlank @Pattern(regexp = CROP_PATTERN) String crop,
                       @NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax("1000000") BigDecimal quantity,
                       @NotNull @Pattern(regexp = "KG|QUINTAL|TONNE") String unit,
                       @NotNull @Min(0) @Max(MAX_SALE_PAISE) Long ratePaise,
                       @NotNull @Min(0) @Max(MAX_SALE_PAISE) Long totalPaise,
                       @NotNull @Min(0) @Max(MAX_SALE_PAISE) Long receivedPaise,
                       @NotBlank @Size(max = 100) String buyerName,
                       @NotNull LocalDate date,
                       @Size(max = 1000) String notes,
                       @Pattern(regexp = "CASH|UPI|NEFT") String paymentMethod,
                       @Size(max = 100) String paymentReference,
                       boolean deleted,
                       Long serverVersion) implements Versioned<Sale> {
        @Override
        public Sale withVersion(Long v) {
            return new Sale(id, farmId, crop, quantity, unit, ratePaise, totalPaise, receivedPaise, buyerName, date,
                    notes, paymentMethod, paymentReference, deleted, v);
        }
    }
}
