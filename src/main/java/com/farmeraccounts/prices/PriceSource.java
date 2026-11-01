package com.farmeraccounts.prices;

import java.util.List;
import tools.jackson.databind.JsonNode;

/** Raw daily mandi price records for one state. */
public interface PriceSource {

    boolean configured();

    List<JsonNode> fetch(String state);
}
