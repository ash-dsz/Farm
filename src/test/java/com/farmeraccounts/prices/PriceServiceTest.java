package com.farmeraccounts.prices;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.farmeraccounts.common.ApiException;
import com.farmeraccounts.config.AppProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class PriceServiceTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static JsonNode rec(String state, String commodity, String market, String date, String modal) {
        return JSON.readTree("""
                {"state":"%s","district":"Dakshina Kannada","market":"%s","commodity":"%s","variety":"Rashi",
                 "arrival_date":"%s","min_price":"40000","max_price":"52000","modal_price":"%s"}
                """.formatted(state, market, commodity, date, modal));
    }

    private static AppProperties props(String key) {
        return new AppProperties(null, null, null, null,
                new AppProperties.Prices(key, "http://unused", "Karnataka", Duration.ofHours(3)), null);
    }

    private static class FakeSource implements PriceSource {
        final AtomicInteger calls = new AtomicInteger();
        final AtomicBoolean down = new AtomicBoolean();
        List<JsonNode> records = List.of();

        @Override
        public boolean configured() {
            return true;
        }

        @Override
        public List<JsonNode> fetch(String state) {
            calls.incrementAndGet();
            if (down.get()) {
                throw new DataGovInPriceSource.PriceUnavailableException();
            }
            return records;
        }
    }

    @Test
    void groupsKarnatakaRecordsByCropNewestFirst() {
        FakeSource source = new FakeSource();
        source.records = List.of(
                rec("Karnataka", "Arecanut(Betelnut/Supari)", "Puttur", "30/10/2026", "48500"),
                rec("Karnataka", "Arecanut(Betelnut/Supari)", "Sirsi", "31/10/2026", "47000"),
                rec("Karnataka", "Coconut", "Tiptur", "31/10/2026", "2800"),
                rec("Karnataka", "Black pepper", "Sakleshpur", "31/10/2026", "62000"),
                rec("Karnataka", "Paddy(Dhan)(Common)", "Mandya", "31/10/2026", "2300"),
                rec("Karnataka", "Onion", "Bangalore", "31/10/2026", "1800"),        // not one of our crops
                rec("Kerala", "Arecanut(Betelnut/Supari)", "Kasaragod", "31/10/2026", "50000"), // other state
                rec("Karnataka", "Banana", "Mysore", "31/10/2026", ""));             // no price
        PriceService service = new PriceService(source, props("key"), Clock.systemUTC());

        PriceService.PriceReport report = service.current();
        List<PriceService.MarketPrice> areca = report.crops().get(0).prices();
        assertThat(report.crops().get(0).crop()).isEqualTo("ARECANUT");
        assertThat(areca).extracting(PriceService.MarketPrice::market).containsExactly("Sirsi", "Puttur");
        assertThat(areca.get(0).arrivalDate()).isEqualTo(LocalDate.of(2026, 10, 31));
        assertThat(report.crops()).extracting(PriceService.CropPrices::crop)
                .containsExactly("ARECANUT", "COCONUT", "PEPPER", "BANANA", "COFFEE", "PADDY");
        assertThat(report.crops().get(3).prices()).isEmpty(); // banana without a price is skipped
        assertThat(report.unit()).isEqualTo("QUINTAL");
    }

    @Test
    void cachesAndServesStaleDataWhenTheSourceIsDown() {
        FakeSource source = new FakeSource();
        source.records = List.of(rec("Karnataka", "Coffee", "Chikmagalur", "31/10/2026", "21000"));
        Instant[] now = {Instant.parse("2026-10-31T06:00:00Z")};
        Clock clock = new Clock() {
            public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(java.time.ZoneId z) { return this; }
            public Instant instant() { return now[0]; }
        };
        PriceService service = new PriceService(source, props("key"), clock);
        service.current();
        service.current();
        assertThat(source.calls.get()).isEqualTo(1);

        now[0] = now[0].plus(Duration.ofHours(4));
        source.down.set(true);
        PriceService.PriceReport stale = service.current();
        assertThat(stale.stale()).isTrue();
        assertThat(stale.crops().get(4).prices()).hasSize(1);
    }

    @Test
    void explainsWhenNotConfiguredOrUnavailable() {
        PriceSource unconfigured = new PriceSource() {
            public boolean configured() { return false; }
            public List<JsonNode> fetch(String state) { return List.of(); }
        };
        assertThatThrownBy(() -> new PriceService(unconfigured, props(""), Clock.systemUTC()).current())
                .isInstanceOf(ApiException.class).hasFieldOrPropertyWithValue("code", "PRICES_NOT_CONFIGURED");

        FakeSource down = new FakeSource();
        down.down.set(true);
        assertThatThrownBy(() -> new PriceService(down, props("key"), Clock.systemUTC()).current())
                .isInstanceOf(ApiException.class).hasFieldOrPropertyWithValue("code", "PRICES_UNAVAILABLE");
    }

    @Test
    void recognisesCommodityNames() {
        assertThat(PriceService.cropOf("Arecanut(Betelnut/Supari)")).isEqualTo("ARECANUT");
        assertThat(PriceService.cropOf("Copra")).isEqualTo("COCONUT");
        assertThat(PriceService.cropOf("Black pepper")).isEqualTo("PEPPER");
        assertThat(PriceService.cropOf("Paddy(Dhan)(Basmati)")).isEqualTo("PADDY");
        assertThat(PriceService.cropOf("Tomato")).isNull();
    }
}
