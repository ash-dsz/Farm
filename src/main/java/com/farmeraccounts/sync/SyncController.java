package com.farmeraccounts.sync;

import com.farmeraccounts.auth.FarmerPrincipal;
import com.farmeraccounts.common.ClientInfo;
import com.farmeraccounts.sync.SyncModels.PullResponse;
import com.farmeraccounts.sync.SyncModels.PushRequest;
import com.farmeraccounts.sync.SyncModels.PushResponse;
import com.farmeraccounts.sync.SyncModels.SyncContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Offline-first synchronisation. All farmer data writes go through {@code push}; the access guard
 * has already verified platform and account status before either endpoint runs.
 */
@RestController
public class SyncController {

    private final SyncService sync;

    public SyncController(SyncService sync) {
        this.sync = sync;
    }

    @PostMapping("/api/sync/push")
    public PushResponse push(FarmerPrincipal farmer, @Validated @RequestBody PushRequest body, HttpServletRequest req) {
        return sync.push(context(farmer, req), body.operations());
    }

    @GetMapping("/api/sync/pull")
    public PullResponse pull(FarmerPrincipal farmer,
                             @RequestParam(defaultValue = "0") long cursor,
                             @RequestParam(defaultValue = "500") int limit,
                             HttpServletRequest req) {
        return sync.pull(context(farmer, req), Math.max(0, cursor), limit, req.getHeader(ClientInfo.VERSION_HEADER));
    }

    private static SyncContext context(FarmerPrincipal farmer, HttpServletRequest req) {
        String device = ClientInfo.deviceId(req);
        return new SyncContext(farmer.userId(), device != null ? device : farmer.deviceId());
    }
}
