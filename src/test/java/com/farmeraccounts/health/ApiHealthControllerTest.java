package com.farmeraccounts.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.farmeraccounts.support.IntegrationTest;
import org.junit.jupiter.api.Test;

class ApiHealthControllerTest extends IntegrationTest {

    @Test
    void healthCheckIsPublicAndReportsApplicationHealth() throws Exception {
        Res response = call(get("/api/health"));

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body().get("status").asString()).isEqualTo("UP");
    }
}
