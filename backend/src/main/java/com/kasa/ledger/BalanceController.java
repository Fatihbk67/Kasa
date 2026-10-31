package com.kasa.ledger;

import java.util.List;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.kasa.security.CurrentTenant;

@RestController
public class BalanceController {

    private final LedgerService ledger;

    public BalanceController(LedgerService ledger) {
        this.ledger = ledger;
    }

    @GetMapping("/api/v1/balances")
    public List<LedgerService.MerchantBalance> balances(Authentication authentication) {
        return ledger.merchantBalances(CurrentTenant.of(authentication));
    }
}
