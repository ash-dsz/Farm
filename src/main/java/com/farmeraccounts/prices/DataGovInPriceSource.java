package com.farmeraccounts.prices;

import com.farmeraccounts.config.AppProperties;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * "Current Daily Price of Various Commodities from Various Markets (Mandi)" from data.gov.in
 * (AGMARKNET). The key stays on the server; the app only talks to our backend.
 */
@Component
public class DataGovInPriceSource implements PriceSource {

    private static final Logger log = LoggerFactory.getLogger(DataGovInPriceSource.class);
    private static final int PAGE = 1000;
    private static final int MAX_PAGES = 10;

    private final AppProperties.Prices config;
    private final ObjectMapper json;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public DataGovInPriceSource(AppProperties props, ObjectMapper json) {
        this.config = props.prices();
        this.json = json;
    }

    @Override
    public boolean configured() {
        return config.apiKey() != null && !config.apiKey().isBlank();
    }

    @Override
    public List<JsonNode> fetch(String state) {
        // The filter field name differs between portal versions; try the newer one first.
        List<JsonNode> records = fetchAll("filters[state.keyword]", state);
        if (records.isEmpty()) {
            records = fetchAll("filters[state]", state);
        }
        return records;
    }

    private List<JsonNode> fetchAll(String filterParam, String state) {
        List<JsonNode> out = new ArrayList<>();
        for (int page = 0; page < MAX_PAGES; page++) {
            String url = config.url()
                    + "?api-key=" + enc(config.apiKey())
                    + "&format=json&limit=" + PAGE + "&offset=" + (page * PAGE)
                    + "&" + enc(filterParam) + "=" + enc(state);
            JsonNode body = get(url);
            JsonNode records = body.path("records");
            if (!records.isArray() || records.isEmpty()) {
                break;
            }
            records.forEach(out::add);
            if (records.size() < PAGE) {
                break;
            }
        }
        return out;
    }

    private JsonNode get(String url) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/json")
                .GET()
                .build();
        try {
            HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                log.warn("Price API returned HTTP {}", res.statusCode());
                throw new PriceUnavailableException();
            }
            return json.readTree(res.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PriceUnavailableException();
        } catch (IOException | RuntimeException e) {
            if (e instanceof PriceUnavailableException p) {
                throw p;
            }
            log.warn("Price API not reachable: {}", e.getClass().getSimpleName());
            throw new PriceUnavailableException();
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    public static class PriceUnavailableException extends RuntimeException {
        public PriceUnavailableException() {
            super("Price source unavailable");
        }
    }
}
