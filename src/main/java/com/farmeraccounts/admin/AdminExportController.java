package com.farmeraccounts.admin;

import com.farmeraccounts.common.ApiException;
import com.farmeraccounts.ledger.LedgerService;
import com.farmeraccounts.ledger.WageCalculator.Summary;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * CSV exports of farmer data for the platform owner. Works regardless of the app status, so data
 * stays available while the service is suspended. Cells are protected against spreadsheet formula
 * injection.
 */
@RestController
@RequestMapping("/api/admin/exports")
public class AdminExportController {

    private interface RowWriter {
        List<Object> row(ResultSet rs) throws SQLException;
    }

    private record Export(String sql, List<String> header, RowWriter writer) {}

    private static final String FARMER_FILTER = " WHERE (CAST(:farmer AS uuid) IS NULL OR x.farmer_id = :farmer) ";

    private final Map<String, Export> exports = new LinkedHashMap<>();
    private final JdbcClient jdbc;
    private final LedgerService ledger;
    private final AdminAuditService audit;

    public AdminExportController(JdbcClient jdbc, LedgerService ledger, AdminAuditService audit) {
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.audit = audit;
        exports.put("farmers", new Export("""
                SELECT x.* FROM (SELECT u.*, u.id AS farmer_id FROM users u) x""" + FARMER_FILTER + "ORDER BY x.created_at",
                List.of("farmer_id", "name", "phone", "status", "block_reason", "language", "registered_at", "last_active_at"),
                rs -> Arrays.asList(rs.getObject("id"), str(rs, "name"), str(rs, "phone"), str(rs, "status"),
                        str(rs, "block_reason"), str(rs, "preferred_language"), str(rs, "created_at"),
                        str(rs, "last_active_at"))));
        exports.put("farms", new Export("""
                SELECT x.*, u.phone FROM farms x JOIN users u ON u.id = x.farmer_id""" + FARMER_FILTER + "ORDER BY u.phone, x.created_at",
                List.of("farmer_phone", "farm_id", "name", "village", "district", "state", "total_acreage", "primary_crop",
                        "deleted"),
                rs -> Arrays.asList(str(rs, "phone"), rs.getObject("id"), str(rs, "name"), str(rs, "village"),
                        str(rs, "district"), str(rs, "state"), str(rs, "total_acreage"), str(rs, "primary_crop"),
                        rs.getTimestamp("deleted_at") != null)));
        exports.put("workers", new Export("""
                SELECT x.*, u.phone AS farmer_phone, f.name AS farm_name FROM workers x
                JOIN users u ON u.id = x.farmer_id JOIN farms f ON f.id = x.farm_id""" + FARMER_FILTER
                + "ORDER BY u.phone, x.name",
                List.of("farmer_phone", "farm", "worker_id", "name", "phone", "daily_wage_rs", "joining_date", "active",
                        "notes"),
                rs -> Arrays.asList(str(rs, "farmer_phone"), str(rs, "farm_name"), rs.getObject("id"), str(rs, "name"),
                        str(rs, "phone"), rupees(rs.getLong("daily_wage_paise")), str(rs, "joining_date"),
                        rs.getBoolean("active"), str(rs, "notes"))));
        exports.put("attendance", new Export("""
                SELECT x.*, u.phone AS farmer_phone, w.name AS worker_name FROM attendance x
                JOIN users u ON u.id = x.farmer_id JOIN workers w ON w.id = x.worker_id""" + FARMER_FILTER
                + "ORDER BY u.phone, x.work_date, w.name",
                List.of("farmer_phone", "worker_id", "worker", "date", "status", "day_value"),
                rs -> Arrays.asList(str(rs, "farmer_phone"), rs.getObject("worker_id"), str(rs, "worker_name"),
                        str(rs, "work_date"), str(rs, "status"), dayValue(rs.getString("status")))));
        exports.put("transactions", new Export("""
                SELECT x.*, u.phone AS farmer_phone, w.name AS worker_name FROM worker_transactions x
                JOIN users u ON u.id = x.farmer_id JOIN workers w ON w.id = x.worker_id""" + FARMER_FILTER
                + "ORDER BY u.phone, x.txn_date, x.created_at",
                List.of("farmer_phone", "transaction_id", "worker_id", "worker", "date", "type", "amount_rs", "note",
                        "cancels_transaction_id", "recorded_at"),
                rs -> Arrays.asList(str(rs, "farmer_phone"), rs.getObject("id"), rs.getObject("worker_id"),
                        str(rs, "worker_name"), str(rs, "txn_date"), str(rs, "type"), rupees(rs.getLong("amount_paise")),
                        str(rs, "note"), str(rs, "reverses_transaction_id"), str(rs, "created_at"))));
        exports.put("expenses", new Export("""
                SELECT x.*, u.phone AS farmer_phone, fl.name AS field_name FROM expenses x
                JOIN users u ON u.id = x.farmer_id LEFT JOIN fields fl ON fl.id = x.field_id""" + FARMER_FILTER
                + "ORDER BY u.phone, x.expense_date",
                List.of("farmer_phone", "expense_id", "date", "category", "description", "amount_rs", "field", "notes",
                        "deleted"),
                rs -> Arrays.asList(str(rs, "farmer_phone"), rs.getObject("id"), str(rs, "expense_date"), str(rs, "category"),
                        str(rs, "description"), rupees(rs.getLong("amount_paise")), str(rs, "field_name"),
                        str(rs, "notes"), rs.getTimestamp("deleted_at") != null)));
        exports.put("sales", new Export("""
                SELECT x.*, u.phone AS farmer_phone FROM sales x JOIN users u ON u.id = x.farmer_id""" + FARMER_FILTER
                + "ORDER BY u.phone, x.sale_date",
                List.of("farmer_phone", "sale_id", "date", "crop", "quantity", "unit", "rate_rs", "total_rs",
                        "received_rs", "pending_rs", "payment_method", "payment_reference", "buyer", "notes", "deleted"),
                rs -> Arrays.asList(str(rs, "farmer_phone"), rs.getObject("id"), str(rs, "sale_date"), str(rs, "crop"),
                        str(rs, "quantity"), str(rs, "unit"), rupees(rs.getLong("rate_paise")),
                        rupees(rs.getLong("total_paise")), rupees(rs.getLong("received_paise")),
                        rupees(rs.getLong("total_paise") - rs.getLong("received_paise")), str(rs, "payment_method"),
                        str(rs, "payment_reference"), str(rs, "buyer_name"),
                        str(rs, "notes"), rs.getTimestamp("deleted_at") != null)));
    }

    @GetMapping("/{type}")
    public void export(@AuthenticationPrincipal AdminPrincipal admin, @PathVariable String type,
                       @RequestParam(required = false) UUID farmerId, HttpServletResponse response) throws IOException {
        boolean ledgerExport = "wage-ledger".equals(type);
        Export export = exports.get(type);
        if (export == null && !ledgerExport) {
            throw ApiException.notFound("Unknown export");
        }
        audit.log(admin, "DATA_EXPORT", "EXPORT", type, null,
                farmerId == null ? Map.of() : Map.of("farmerId", farmerId.toString()));

        String filename = type + (farmerId == null ? "" : "-" + farmerId) + "-" + LocalDate.now() + ".csv";
        response.setContentType("text/csv; charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");
        response.setHeader("Cache-Control", "no-store");
        PrintWriter out = new PrintWriter(new OutputStreamWriter(response.getOutputStream(), StandardCharsets.UTF_8));
        out.write('﻿'); // BOM so spreadsheet apps detect UTF-8 (Kannada names)
        if (ledgerExport) {
            writeLedger(out, farmerId);
        } else {
            writeLine(out, new ArrayList<>(export.header()));
            jdbc.sql(export.sql()).param("farmer", farmerId).query(rs -> {
                writeLine(out, export.writer().row(rs));
            });
        }
        out.flush();
    }

    /** One line per worker with the totals recomputed from attendance and transactions. */
    private void writeLedger(PrintWriter out, UUID farmerId) {
        writeLine(out, List.of("farmer_phone", "worker_id", "worker", "active", "current_daily_wage_rs", "full_days",
                "half_days", "absent_days", "skipped_days", "earned_rs", "advances_rs", "settlements_rs", "adjustments_rs", "pending_rs"));
        record W(UUID id, String farmerPhone, String name, boolean active, long wage) {}
        List<W> workers = jdbc.sql("""
                SELECT x.id, x.name, x.active, x.daily_wage_paise, u.phone FROM workers x JOIN users u ON u.id = x.farmer_id
                """ + FARMER_FILTER + "ORDER BY u.phone, x.name")
                .param("farmer", farmerId)
                .query((rs, n) -> new W(rs.getObject("id", UUID.class), rs.getString("phone"), rs.getString("name"),
                        rs.getBoolean("active"), rs.getLong("daily_wage_paise")))
                .list();
        for (W w : workers) {
            Summary s = ledger.summarize(w.id(), w.wage(), null, null);
            writeLine(out, Arrays.asList(w.farmerPhone(), w.id(), w.name(), w.active(), rupees(w.wage()), s.fullDays(),
                    s.halfDays(), s.absentDays(), s.skippedDays(), rupees(s.earnedPaise()), rupees(s.advancesPaise()),
                    rupees(s.settlementsPaise()), rupees(s.adjustmentsPaise()), rupees(s.pendingPaise())));
        }
    }

    private static void writeLine(PrintWriter out, List<?> cells) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(csv(cells.get(i)));
        }
        sb.append("\r\n");
        out.write(sb.toString());
        if (out.checkError()) {
            throw new UncheckedIOException(new IOException("Client closed the download"));
        }
    }

    static String csv(Object value) {
        if (value == null) {
            return "";
        }
        String s = value.toString();
        // Neutralise spreadsheet formulas in user-entered text.
        if (!(value instanceof Number) && !s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0) {
            s = "'" + s;
        }
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r") || s.startsWith("'")) {
            s = "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    private static Object str(ResultSet rs, String column) throws SQLException {
        Object v = rs.getObject(column);
        return v == null ? null : v.toString();
    }

    static BigDecimal rupees(long paise) {
        return BigDecimal.valueOf(paise, 2);
    }

    private static String dayValue(String status) {
        return switch (status) {
            case "PRESENT" -> "1";
            case "HALF_DAY" -> "0.5";
            default -> "0";
        };
    }
}
