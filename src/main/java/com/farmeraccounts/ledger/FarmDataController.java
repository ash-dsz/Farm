package com.farmeraccounts.ledger;

import static com.farmeraccounts.sync.SyncSupport.sqlDate;

import com.farmeraccounts.auth.FarmerPrincipal;
import com.farmeraccounts.sync.EntityModels.Attendance;
import com.farmeraccounts.sync.EntityModels.Farm;
import com.farmeraccounts.sync.EntityModels.Field;
import com.farmeraccounts.sync.EntityModels.Transaction;
import com.farmeraccounts.sync.EntityModels.Worker;
import com.farmeraccounts.sync.FarmHandlers;
import com.farmeraccounts.sync.LedgerHandlers;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only REST views of the farmer's own data. Every query is scoped by the authenticated
 * farmer id, so one farmer can never read another's records. Writes go through /api/sync/push.
 */
@RestController
public class FarmDataController {

    private final JdbcClient jdbc;
    private final LedgerService ledger;

    public FarmDataController(JdbcClient jdbc, LedgerService ledger) {
        this.jdbc = jdbc;
        this.ledger = ledger;
    }

    @GetMapping("/api/farms")
    public List<Farm> farms(FarmerPrincipal farmer) {
        return jdbc.sql("SELECT * FROM farms WHERE farmer_id = :f AND deleted_at IS NULL ORDER BY created_at")
                .param("f", farmer.userId())
                .query((rs, n) -> FarmHandlers.FarmHandler.map(rs)).list();
    }

    @GetMapping("/api/farms/{farmId}/fields")
    public List<Field> fields(FarmerPrincipal farmer, @PathVariable UUID farmId) {
        return jdbc.sql("SELECT * FROM fields WHERE farmer_id = :f AND farm_id = :farm AND deleted_at IS NULL ORDER BY name")
                .param("f", farmer.userId()).param("farm", farmId)
                .query((rs, n) -> FarmHandlers.FieldHandler.map(rs)).list();
    }

    @GetMapping("/api/workers")
    public List<Worker> workers(FarmerPrincipal farmer, @RequestParam(required = false) UUID farmId,
                                @RequestParam(defaultValue = "false") boolean includeInactive) {
        return jdbc.sql("""
                SELECT * FROM workers WHERE farmer_id = :f
                  AND (CAST(:farm AS uuid) IS NULL OR farm_id = :farm)
                  AND (:all OR active)
                ORDER BY name
                """)
                .param("f", farmer.userId()).param("farm", farmId).param("all", includeInactive)
                .query((rs, n) -> FarmHandlers.WorkerHandler.map(rs)).list();
    }

    @GetMapping("/api/attendance")
    public List<Attendance> attendance(FarmerPrincipal farmer, @RequestParam UUID farmId,
                                       @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return jdbc.sql("SELECT * FROM attendance WHERE farmer_id = :f AND farm_id = :farm AND work_date = :d")
                .param("f", farmer.userId()).param("farm", farmId).param("d", sqlDate(date))
                .query((rs, n) -> LedgerHandlers.AttendanceHandler.map(rs)).list();
    }

    @GetMapping("/api/workers/{workerId}/transactions")
    public List<Transaction> transactions(FarmerPrincipal farmer, @PathVariable UUID workerId) {
        return jdbc.sql("""
                SELECT * FROM worker_transactions WHERE farmer_id = :f AND worker_id = :w
                ORDER BY txn_date DESC, created_at DESC
                """)
                .param("f", farmer.userId()).param("w", workerId)
                .query((rs, n) -> LedgerHandlers.TransactionHandler.map(rs)).list();
    }

    @GetMapping("/api/workers/{workerId}/ledger")
    public LedgerService.WorkerLedger workerLedger(FarmerPrincipal farmer, @PathVariable UUID workerId,
                                                   @RequestParam(required = false) String month) {
        return ledger.ledger(farmer.userId(), workerId, month);
    }
}
