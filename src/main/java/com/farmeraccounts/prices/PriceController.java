package com.farmeraccounts.prices;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Daily mandi prices for the farmer's crops (behind the same login and kill switch as everything else). */
@RestController
public class PriceController {

    private final PriceService prices;

    public PriceController(PriceService prices) {
        this.prices = prices;
    }

    @GetMapping("/api/prices")
    public PriceService.PriceReport prices() {
        return prices.current();
    }
}
