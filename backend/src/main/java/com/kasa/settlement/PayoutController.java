package com.kasa.settlement;

import java.util.List;
import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.kasa.security.CurrentTenant;
import com.kasa.settlement.PayoutViews.PayoutDetail;
import com.kasa.settlement.PayoutViews.PayoutView;

@RestController
@RequestMapping("/api/v1/payouts")
public class PayoutController {

    private final SettlementService settlement;

    public PayoutController(SettlementService settlement) {
        this.settlement = settlement;
    }

    @GetMapping
    public List<PayoutView> list(Authentication authentication) {
        return settlement.list(CurrentTenant.of(authentication));
    }

    @GetMapping("/{id}")
    public PayoutDetail get(Authentication authentication, @PathVariable UUID id) {
        return settlement.get(CurrentTenant.of(authentication), id);
    }
}
