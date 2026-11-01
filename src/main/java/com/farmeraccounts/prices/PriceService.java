package com.farmeraccounts.prices;

import com.farmeraccounts.common.ApiException;
import com.farmeraccounts.config.AppProperties;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * Crop prices for the app's Prices tab, grouped by our crop codes. Results are cached; if the
 * source is down, the last good result is returned and marked stale.
 */
@Service
public class PriceService {

    /** Our crop code -> words that identify it in AGMARKNET commodity names. */
    static final Map<String, List<String>> CROP_KEYWORDS = new LinkedHashMap<>();

    static {
        CROP_KEYWORDS.put("ARECANUT", List.of("arecanut", "areca", "betelnut", "betel nut", "supari"));
        CROP_KEYWORDS.put("COCONUT", List.of("coconut", "copra"));
        CROP_KEYWORDS.put("PEPPER", List.of("pepper"));
        CROP_KEYWORDS.put("BANANA", List.of("banana"));
        CROP_KEYWORDS.put("COFFEE", List.of("coffee"));
        CROP_KEYWORDS.put("PADDY", List.of("paddy"));
    }

    private static final DateTimeFormatter AGMARK_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    public record MarketPrice(String market, String district, String commodity, String variety, LocalDate arrivalDate,
                              BigDecimal minPrice, BigDecimal maxPrice, BigDecimal modalPrice) {}

    public record CropPrices(String crop, List<MarketPrice> prices) {}

    public record PriceReport(String state, String unit, String source, Instant fetchedAt, boolean stale,
                              List<CropPrices> crops) {}

    private final PriceSource source;
    private final AppProperties.Prices config;
    private final Clock clock;
    private volatile PriceReport cached;

    public PriceService(PriceSource source, AppProperties props, Clock clock) {
        this.source = source;
        this.config = props.prices();
        this.clock = clock;
    }

    public PriceReport current() {
        if (!source.configured()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PRICES_NOT_CONFIGURED",
                    "Crop prices are not set up on the server yet.");
        }
        PriceReport c = cached;
        Instant now = clock.instant();
        if (c != null && c.fetchedAt().plus(config.cacheTtl()).isAfter(now)) {
            return c;
        }
        try {
            PriceReport fresh = build(source.fetch(config.state()), now);
            cached = fresh;
            return fresh;
        } catch (DataGovInPriceSource.PriceUnavailableException e) {
            if (c != null) {
                return new PriceReport(c.state(), c.unit(), c.source(), c.fetchedAt(), true, c.crops());
            }
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PRICES_UNAVAILABLE",
                    "Crop prices could not be loaded right now.");
        }
    }

    PriceReport build(List<JsonNode> records, Instant now) {
        Map<String, List<MarketPrice>> byCrop = new LinkedHashMap<>();
        CROP_KEYWORDS.keySet().forEach(k -> byCrop.put(k, new ArrayList<>()));
        for (JsonNode r : records) {
            if (!config.state().equalsIgnoreCase(text(r, "state"))) {
                continue;
            }
            String commodity = text(r, "commodity");
            String crop = cropOf(commodity);
            BigDecimal modal = number(r, "modal_price");
            if (crop == null || modal == null || modal.signum() <= 0) {
                continue;
            }
            byCrop.get(crop).add(new MarketPrice(text(r, "market"), text(r, "district"), commodity, text(r, "variety"),
                    date(text(r, "arrival_date")), number(r, "min_price"), number(r, "max_price"), modal));
        }
        List<CropPrices> crops = new ArrayList<>();
        byCrop.forEach((crop, list) -> {
            list.sort(Comparator.comparing(MarketPrice::arrivalDate, Comparator.nullsLast(Comparator.reverseOrder()))
                    .thenComparing(MarketPrice::modalPrice, Comparator.reverseOrder()));
            crops.add(new CropPrices(crop, list));
        });
        return new PriceReport(config.state(), "QUINTAL", "AGMARKNET (data.gov.in)", now, false, crops);
    }

    static String cropOf(String commodity) {
        String c = commodity.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, List<String>> e : CROP_KEYWORDS.entrySet()) {
            if (e.getValue().stream().anyMatch(c::contains)) {
                return e.getKey();
            }
        }
        return null;
    }

    private static String text(JsonNode r, String field) {
        JsonNode v = r.get(field);
        return v == null || v.isNull() ? "" : v.asString().trim();
    }

    private static BigDecimal number(JsonNode r, String field) {
        String s = text(r, field).replace(",", "");
        if (s.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static LocalDate date(String s) {
        try {
            return s.contains("/") ? LocalDate.parse(s, AGMARK_DATE) : LocalDate.parse(s);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
