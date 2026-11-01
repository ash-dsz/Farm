package com.farmeraccounts.sync;

import static org.assertj.core.api.Assertions.assertThat;

import com.farmeraccounts.support.IntegrationTest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class SyncTest extends IntegrationTest {

    private static final LocalDate DAY = LocalDate.now().minusDays(3);

    @Test
    void createsWorkerAndAttendance() throws Exception {
        Setup s = setupFarmWithWorker();
        UUID attId = UUID.randomUUID();
        JsonNode r = pushOne(s.farmer(), op("attendance.upsert", attId, null,
                attendance(attId, s.farmId(), s.workerId(), DAY, "PRESENT")));
        assertThat(r.get("status").asString()).isEqualTo("APPLIED");
        assertThat(r.get("entity").get("serverVersion").asLong()).isPositive();

        Res list = farmerGet(s.farmer(), "/api/attendance?farmId=" + s.farmId() + "&date=" + DAY);
        assertThat(list.body()).hasSize(1);
        assertThat(list.body().get(0).get("status").asString()).isEqualTo("PRESENT");
    }

    @Test
    void repeatedOperationIdIsIdempotent() throws Exception {
        Setup s = setupFarmWithWorker();
        UUID txnId = UUID.randomUUID();
        Map<String, Object> advance = op("transaction.create", txnId, null,
                txn(txnId, s.farmId(), s.workerId(), "ADVANCE", 500_00, DAY.toString(), null, null));

        assertThat(pushOne(s.farmer(), advance).get("status").asString()).isEqualTo("APPLIED");
        // The phone did not get the response and sends the same operation again
        assertThat(pushOne(s.farmer(), advance).get("status").asString()).isEqualTo("DUPLICATE");

        long count = jdbc.sql("SELECT count(*) FROM worker_transactions WHERE worker_id = :w")
                .param("w", s.workerId()).query(Long.class).single();
        assertThat(count).isEqualTo(1);
    }

    @Test
    void sameTransactionWithNewOperationIdIsNotDuplicated() throws Exception {
        Setup s = setupFarmWithWorker();
        UUID txnId = UUID.randomUUID();
        Map<String, Object> payload = txn(txnId, s.farmId(), s.workerId(), "SETTLEMENT", 1_000_00, DAY.toString(), null, null);
        pushOne(s.farmer(), op("transaction.create", txnId, null, payload));
        JsonNode again = pushOne(s.farmer(), op("transaction.create", txnId, null, payload));
        assertThat(again.get("status").asString()).isEqualTo("APPLIED");
        assertThat(jdbc.sql("SELECT count(*) FROM worker_transactions WHERE id = :id").param("id", txnId)
                .query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void financialTransactionsCanNeverBeOverwritten() throws Exception {
        Setup s = setupFarmWithWorker();
        UUID txnId = UUID.randomUUID();
        pushOne(s.farmer(), op("transaction.create", txnId, null,
                txn(txnId, s.farmId(), s.workerId(), "ADVANCE", 500_00, DAY.toString(), null, null)));
        JsonNode r = pushOne(s.farmer(), op("transaction.create", txnId, null,
                txn(txnId, s.farmId(), s.workerId(), "ADVANCE", 50_00, DAY.toString(), null, null)));
        assertThat(r.get("status").asString()).isEqualTo("REJECTED");
        assertThat(r.get("code").asString()).isEqualTo("ID_REUSED");

        // The database itself refuses updates and deletes of the ledger
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                jdbc.sql("UPDATE worker_transactions SET amount_paise = 1 WHERE id = :id").param("id", txnId).update())
                .hasMessageContaining("append-only");
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                jdbc.sql("DELETE FROM worker_transactions WHERE id = :id").param("id", txnId).update())
                .hasMessageContaining("append-only");
    }

    @Test
    void duplicateAttendanceFromTwoDevicesKeepsOneRecord() throws Exception {
        Setup s = setupFarmWithWorker();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        pushOne(s.farmer(), op("attendance.upsert", first, null, attendance(first, s.farmId(), s.workerId(), DAY, "PRESENT")));

        // Another device marked the same worker and day independently, with the same answer
        JsonNode same = pushOne(s.farmer(), op("attendance.upsert", second, null,
                attendance(second, s.farmId(), s.workerId(), DAY, "PRESENT")));
        assertThat(same.get("status").asString()).isEqualTo("APPLIED");
        assertThat(same.get("entity").get("id").asString()).isEqualTo(first.toString());

        // ... and with a different answer: a conflict, nothing overwritten
        JsonNode different = pushOne(s.farmer(), op("attendance.upsert", second, null,
                attendance(second, s.farmId(), s.workerId(), DAY, "HALF_DAY")));
        assertThat(different.get("status").asString()).isEqualTo("CONFLICT");
        assertThat(different.get("entity").get("status").asString()).isEqualTo("PRESENT");

        long rows = jdbc.sql("SELECT count(*) FROM attendance WHERE worker_id = :w AND work_date = :d")
                .param("w", s.workerId()).param("d", java.sql.Date.valueOf(DAY)).query(Long.class).single();
        assertThat(rows).isEqualTo(1);
    }

    @Test
    void skipIsAcceptedAndEarnsNothing() throws Exception {
        Setup s = setupFarmWithWorker();
        UUID id = UUID.randomUUID();
        JsonNode r = pushOne(s.farmer(), op("attendance.upsert", id, null,
                attendance(id, s.farmId(), s.workerId(), DAY, "SKIP")));
        assertThat(r.get("status").asString()).isEqualTo("APPLIED");
        JsonNode all = farmerGet(s.farmer(), "/api/workers/" + s.workerId() + "/ledger").body().get("allTime");
        assertThat(all.get("earnedPaise").asLong()).isZero();
        assertThat(all.get("skippedDays").asInt()).isEqualTo(1);
        assertThat(all.get("absentDays").asInt()).isZero();

        UUID bad = UUID.randomUUID();
        assertThat(pushOne(s.farmer(), op("attendance.upsert", bad, null,
                attendance(bad, s.farmId(), s.workerId(), DAY.minusDays(1), "HOLIDAY"))).get("code").asString())
                .isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void attendanceEditNeedsTheCurrentVersion() throws Exception {
        Setup s = setupFarmWithWorker();
        UUID id = UUID.randomUUID();
        JsonNode created = pushOne(s.farmer(), op("attendance.upsert", id, null,
                attendance(id, s.farmId(), s.workerId(), DAY, "PRESENT")));
        long v1 = created.get("entity").get("serverVersion").asLong();

        JsonNode edited = pushOne(s.farmer(), op("attendance.upsert", id, v1,
                attendance(id, s.farmId(), s.workerId(), DAY, "HALF_DAY")));
        assertThat(edited.get("status").asString()).isEqualTo("APPLIED");
        long v2 = edited.get("entity").get("serverVersion").asLong();
        assertThat(v2).isGreaterThan(v1);

        // A device still holding version 1 must not silently overwrite the change
        JsonNode stale = pushOne(s.farmer(), op("attendance.upsert", id, v1,
                attendance(id, s.farmId(), s.workerId(), DAY, "ABSENT")));
        assertThat(stale.get("status").asString()).isEqualTo("CONFLICT");
        assertThat(stale.get("entity").get("status").asString()).isEqualTo("HALF_DAY");

        // Every change is in the audit trail with old and new values
        long audits = jdbc.sql("SELECT count(*) FROM record_audit_log WHERE entity_id = :id AND action = 'UPDATE'")
                .param("id", id).query(Long.class).single();
        assertThat(audits).isEqualTo(1);
    }

    @Test
    void workerEditConflictsAreDetected() throws Exception {
        Setup s = setupFarmWithWorker();
        Res workers = farmerGet(s.farmer(), "/api/workers");
        long v = workers.body().get(0).get("serverVersion").asLong();

        Map<String, Object> renamed = worker(s.workerId(), s.farmId(), "Ramesh K", 600_00);
        assertThat(pushOne(s.farmer(), op("worker.upsert", s.workerId(), v, renamed)).get("status").asString())
                .isEqualTo("APPLIED");
        Map<String, Object> otherEdit = worker(s.workerId(), s.farmId(), "Ramesh Gowda", 600_00);
        JsonNode conflict = pushOne(s.farmer(), op("worker.upsert", s.workerId(), v, otherEdit));
        assertThat(conflict.get("status").asString()).isEqualTo("CONFLICT");
        assertThat(conflict.get("entity").get("name").asString()).isEqualTo("Ramesh K");
    }

    @Test
    void childBeforeParentIsRetryable() throws Exception {
        Farmer f = registerFarmer();
        UUID farmId = UUID.randomUUID();
        UUID workerId = UUID.randomUUID();
        JsonNode r = pushOne(f, op("worker.upsert", workerId, null, worker(workerId, farmId, "Early", 500_00)));
        assertThat(r.get("status").asString()).isEqualTo("REJECTED");
        assertThat(r.get("code").asString()).isEqualTo("MISSING_PARENT");
        assertThat(r.get("retryable").asBoolean()).isTrue();

        pushOne(f, op("farm.upsert", farmId, null, farm(farmId, "Farm")));
        assertThat(pushOne(f, op("worker.upsert", workerId, null, worker(workerId, farmId, "Early", 500_00)))
                .get("status").asString()).isEqualTo("APPLIED");
    }

    @Test
    void invalidFinancialValuesAreRejected() throws Exception {
        Setup s = setupFarmWithWorker();
        UUID a = UUID.randomUUID();
        JsonNode negative = pushOne(s.farmer(), op("transaction.create", a, null,
                txn(a, s.farmId(), s.workerId(), "ADVANCE", -100_00, DAY.toString(), null, null)));
        assertThat(negative.get("code").asString()).isEqualTo("VALIDATION_FAILED");

        UUID b = UUID.randomUUID();
        JsonNode huge = pushOne(s.farmer(), op("transaction.create", b, null,
                txn(b, s.farmId(), s.workerId(), "SETTLEMENT", 5_00_00_000_00L, DAY.toString(), null, null)));
        assertThat(huge.get("code").asString()).isEqualTo("VALIDATION_FAILED");

        UUID c = UUID.randomUUID();
        JsonNode future = pushOne(s.farmer(), op("transaction.create", c, null,
                txn(c, s.farmId(), s.workerId(), "ADVANCE", 100_00, LocalDate.now().plusDays(10).toString(), null, null)));
        assertThat(future.get("code").asString()).isEqualTo("VALIDATION_FAILED");

        UUID d = UUID.randomUUID();
        JsonNode noReason = pushOne(s.farmer(), op("transaction.create", d, null,
                txn(d, s.farmId(), s.workerId(), "ADJUSTMENT", 100_00, DAY.toString(), null, null)));
        assertThat(noReason.get("code").asString()).isEqualTo("VALIDATION_FAILED");

        UUID e = UUID.randomUUID();
        Map<String, Object> badWage = worker(e, s.farmId(), "Zero", 0);
        assertThat(pushOne(s.farmer(), op("worker.upsert", e, null, badWage)).get("code").asString())
                .isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void cancellingAnEntryIsAuditableAndOnlyOnce() throws Exception {
        Setup s = setupFarmWithWorker();
        UUID adv = UUID.randomUUID();
        pushOne(s.farmer(), op("transaction.create", adv, null,
                txn(adv, s.farmId(), s.workerId(), "ADVANCE", 1_000_00, DAY.toString(), null, null)));

        UUID wrongAmount = UUID.randomUUID();
        assertThat(pushOne(s.farmer(), op("transaction.create", wrongAmount, null,
                txn(wrongAmount, s.farmId(), s.workerId(), "ADJUSTMENT", 500_00, DAY.toString(), adv, "Entered by mistake")))
                .get("code").asString()).isEqualTo("VALIDATION_FAILED");

        UUID cancel = UUID.randomUUID();
        assertThat(pushOne(s.farmer(), op("transaction.create", cancel, null,
                txn(cancel, s.farmId(), s.workerId(), "ADJUSTMENT", 1_000_00, DAY.toString(), adv, "Entered by mistake")))
                .get("status").asString()).isEqualTo("APPLIED");

        UUID again = UUID.randomUUID();
        assertThat(pushOne(s.farmer(), op("transaction.create", again, null,
                txn(again, s.farmId(), s.workerId(), "ADJUSTMENT", 1_000_00, DAY.toString(), adv, "Again")))
                .get("code").asString()).isEqualTo("ALREADY_CANCELLED");

        // Both the original and the cancellation remain on record
        assertThat(farmerGet(s.farmer(), "/api/workers/" + s.workerId() + "/transactions").body()).hasSize(2);
    }

    @Test
    void ledgerMatchesTheSpecificationExample() throws Exception {
        Setup s = setupFarmWithWorker();
        List<Map<String, Object>> ops = new ArrayList<>();
        LocalDate start = LocalDate.now().minusDays(40);
        for (int i = 0; i < 22; i++) {
            UUID id = UUID.randomUUID();
            String status = i < 20 ? "PRESENT" : "HALF_DAY";
            ops.add(op("attendance.upsert", id, null, attendance(id, s.farmId(), s.workerId(), start.plusDays(i), status)));
        }
        UUID adv = UUID.randomUUID();
        UUID pay = UUID.randomUUID();
        ops.add(op("transaction.create", adv, null,
                txn(adv, s.farmId(), s.workerId(), "ADVANCE", 3_000_00, start.toString(), null, null)));
        ops.add(op("transaction.create", pay, null,
                txn(pay, s.farmId(), s.workerId(), "SETTLEMENT", 2_000_00, start.plusDays(10).toString(), null, null)));
        push(s.farmer(), ops);

        JsonNode all = farmerGet(s.farmer(), "/api/workers/" + s.workerId() + "/ledger").body().get("allTime");
        assertThat(all.get("earnedPaise").asLong()).isEqualTo(12_600_00);
        assertThat(all.get("advancesPaise").asLong()).isEqualTo(3_000_00);
        assertThat(all.get("settlementsPaise").asLong()).isEqualTo(2_000_00);
        assertThat(all.get("pendingPaise").asLong()).isEqualTo(7_600_00);
    }

    @Test
    void wageRateChangeDoesNotRewritePastDays() throws Exception {
        Setup s = setupFarmWithWorker();
        LocalDate past = LocalDate.now().minusDays(5);
        UUID a1 = UUID.randomUUID();
        pushOne(s.farmer(), op("attendance.upsert", a1, null, attendance(a1, s.farmId(), s.workerId(), past, "PRESENT")));

        // Wage raised to 700 from today; the worker record carries the new current wage
        Map<String, Object> rate = wageRate(s.workerId(), 700_00, LocalDate.now().toString());
        pushOne(s.farmer(), op("wageRate.create", UUID.fromString((String) rate.get("id")), null, rate));
        long v = farmerGet(s.farmer(), "/api/workers").body().get(0).get("serverVersion").asLong();
        pushOne(s.farmer(), op("worker.upsert", s.workerId(), v, worker(s.workerId(), s.farmId(), "Ramesh", 700_00)));

        UUID a2 = UUID.randomUUID();
        pushOne(s.farmer(), op("attendance.upsert", a2, null,
                attendance(a2, s.farmId(), s.workerId(), LocalDate.now(), "PRESENT")));

        JsonNode all = farmerGet(s.farmer(), "/api/workers/" + s.workerId() + "/ledger").body().get("allTime");
        assertThat(all.get("earnedPaise").asLong()).isEqualTo(600_00 + 700_00);
    }

    @Test
    void pullReturnsChangesInVersionOrderWithPaging() throws Exception {
        Setup s = setupFarmWithWorker();
        List<Map<String, Object>> ops = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            UUID id = UUID.randomUUID();
            ops.add(op("attendance.upsert", id, null,
                    attendance(id, s.farmId(), s.workerId(), DAY.minusDays(i), "PRESENT")));
        }
        push(s.farmer(), ops);

        // farm + worker + wage rate + 5 attendance = 8 changes, read 3 at a time
        List<String> types = new ArrayList<>();
        long cursor = 0;
        long lastVersion = 0;
        boolean more = true;
        int pages = 0;
        while (more) {
            JsonNode page = farmerGet(s.farmer(), "/api/sync/pull?limit=3&cursor=" + cursor).body();
            for (JsonNode change : page.get("changes")) {
                long version = change.get("data").get("serverVersion").asLong();
                assertThat(version).isGreaterThan(lastVersion);
                lastVersion = version;
                types.add(change.get("type").asString());
            }
            cursor = page.get("cursor").asLong();
            more = page.get("hasMore").asBoolean();
            pages++;
        }
        assertThat(types).hasSize(8);
        assertThat(pages).isEqualTo(3);
        assertThat(types.get(0)).isEqualTo("farm");
        assertThat(farmerGet(s.farmer(), "/api/sync/pull?cursor=" + cursor).body().get("changes")).isEmpty();
    }

    @Test
    void expensesAndSalesSync() throws Exception {
        Setup s = setupFarmWithWorker();
        UUID expenseId = UUID.randomUUID();
        Map<String, Object> expense = new java.util.LinkedHashMap<>(Map.of(
                "id", expenseId.toString(), "farmId", s.farmId().toString(), "category", "FERTILIZER",
                "description", "=HYPERLINK(\"x\")", "amountPaise", 2_500_00, "date", DAY.toString(), "deleted", false));
        assertThat(pushOne(s.farmer(), op("expense.upsert", expenseId, null, expense)).get("status").asString())
                .isEqualTo("APPLIED");

        UUID saleId = UUID.randomUUID();
        Map<String, Object> sale = new java.util.LinkedHashMap<>();
        sale.put("id", saleId.toString());
        sale.put("farmId", s.farmId().toString());
        sale.put("crop", "ARECANUT");
        sale.put("quantity", 2.5);
        sale.put("unit", "QUINTAL");
        sale.put("ratePaise", 48_000_00);
        sale.put("totalPaise", 1_20_000_00);
        sale.put("receivedPaise", 1_00_000_00);
        sale.put("buyerName", "Puttur Mandi");
        sale.put("date", DAY.toString());
        sale.put("deleted", false);
        JsonNode created = pushOne(s.farmer(), op("sale.upsert", saleId, null, sale));
        assertThat(created.get("status").asString()).isEqualTo("APPLIED");

        // Wrong total is refused
        UUID bad = UUID.randomUUID();
        Map<String, Object> wrong = new java.util.LinkedHashMap<>(sale);
        wrong.put("id", bad.toString());
        wrong.put("totalPaise", 1_00_00);
        assertThat(pushOne(s.farmer(), op("sale.upsert", bad, null, wrong)).get("code").asString())
                .isEqualTo("VALIDATION_FAILED");

        // Recording the rest of the buyer payment is a versioned edit, with how it was paid
        sale.put("receivedPaise", 1_20_000_00);
        sale.put("paymentMethod", "UPI");
        sale.put("paymentReference", "  412398765432  ");
        long v = created.get("entity").get("serverVersion").asLong();
        JsonNode paid = pushOne(s.farmer(), op("sale.upsert", saleId, v, sale));
        assertThat(paid.get("status").asString()).isEqualTo("APPLIED");
        assertThat(paid.get("entity").get("paymentMethod").asString()).isEqualTo("UPI");
        assertThat(paid.get("entity").get("paymentReference").asString()).isEqualTo("412398765432");

        sale.put("paymentMethod", "NEFT");
        sale.put("paymentReference", null);
        JsonNode neft = pushOne(s.farmer(), op("sale.upsert", saleId, paid.get("entity").get("serverVersion").asLong(), sale));
        assertThat(neft.get("entity").get("paymentMethod").asString()).isEqualTo("NEFT");
        assertThat(neft.get("entity").get("paymentReference").isNull()).isTrue();

        // Cash never keeps a reference; unknown methods are refused
        sale.put("paymentMethod", "CASH");
        JsonNode cash = pushOne(s.farmer(), op("sale.upsert", saleId, neft.get("entity").get("serverVersion").asLong(), sale));
        assertThat(cash.get("entity").get("paymentReference").isNull()).isTrue();
        UUID other = UUID.randomUUID();
        Map<String, Object> barter = new java.util.LinkedHashMap<>(sale);
        barter.put("id", other.toString());
        barter.put("paymentMethod", "BARTER");
        assertThat(pushOne(s.farmer(), op("sale.upsert", other, null, barter)).get("code").asString())
                .isEqualTo("VALIDATION_FAILED");
    }
}
