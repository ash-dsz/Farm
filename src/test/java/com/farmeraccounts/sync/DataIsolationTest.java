package com.farmeraccounts.sync;

import static org.assertj.core.api.Assertions.assertThat;

import com.farmeraccounts.support.IntegrationTest;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** One farmer must never be able to read or change another farmer's records. */
class DataIsolationTest extends IntegrationTest {

    @Test
    void farmerCannotReadAnotherFarmersData() throws Exception {
        Setup a = setupFarmWithWorker();
        Farmer b = registerFarmer();

        assertThat(farmerGet(b, "/api/farms").body()).isEmpty();
        assertThat(farmerGet(b, "/api/workers").body()).isEmpty();
        assertThat(farmerGet(b, "/api/workers/" + a.workerId() + "/ledger").status()).isEqualTo(404);
        assertThat(farmerGet(b, "/api/workers/" + a.workerId() + "/transactions").body()).isEmpty();
        assertThat(farmerGet(b, "/api/attendance?farmId=" + a.farmId() + "&date=" + LocalDate.now()).body()).isEmpty();
        assertThat(farmerGet(b, "/api/sync/pull").body().get("changes")).isEmpty();
    }

    @Test
    void farmerCannotModifyAnotherFarmersRecords() throws Exception {
        Setup a = setupFarmWithWorker();
        Farmer b = registerFarmer();

        JsonNode hijackWorker = pushOne(b, op("worker.upsert", a.workerId(), null,
                worker(a.workerId(), a.farmId(), "Stolen", 100_00)));
        assertThat(hijackWorker.get("code").asString()).isEqualTo("FORBIDDEN");

        JsonNode hijackFarm = pushOne(b, op("farm.upsert", a.farmId(), null, farm(a.farmId(), "Mine now")));
        assertThat(hijackFarm.get("code").asString()).isEqualTo("FORBIDDEN");

        UUID att = UUID.randomUUID();
        JsonNode attendance = pushOne(b, op("attendance.upsert", att, null,
                attendance(att, a.farmId(), a.workerId(), LocalDate.now(), "PRESENT")));
        assertThat(attendance.get("code").asString()).isEqualTo("FORBIDDEN");

        UUID txn = UUID.randomUUID();
        JsonNode payment = pushOne(b, op("transaction.create", txn, null,
                txn(txn, a.farmId(), a.workerId(), "SETTLEMENT", 100_00, LocalDate.now().toString(), null, null)));
        assertThat(payment.get("code").asString()).isEqualTo("FORBIDDEN");

        // Farmer B cannot attach their own worker to A's farm either
        UUID bWorker = UUID.randomUUID();
        JsonNode attach = pushOne(b, op("worker.upsert", bWorker, null, worker(bWorker, a.farmId(), "Sneaky", 100_00)));
        assertThat(attach.get("code").asString()).isEqualTo("FORBIDDEN");

        // A's data is unchanged
        JsonNode workers = farmerGet(a.farmer(), "/api/workers").body();
        assertThat(workers).hasSize(1);
        assertThat(workers.get(0).get("name").asString()).isEqualTo("Ramesh");
    }

    @Test
    void operationIdsAreScopedToTheirOwner() throws Exception {
        Setup a = setupFarmWithWorker();
        Farmer b = registerFarmer();
        UUID txnId = UUID.randomUUID();
        var advance = op("transaction.create", txnId, null,
                txn(txnId, a.farmId(), a.workerId(), "ADVANCE", 100_00, LocalDate.now().toString(), null, null));
        pushOne(a.farmer(), advance);
        // Replaying A's operation id as B must not reveal A's stored result
        JsonNode replay = pushOne(b, advance);
        assertThat(replay.get("status").asString()).isEqualTo("REJECTED");
        assertThat(replay.get("entity")).isNull();
    }
}
