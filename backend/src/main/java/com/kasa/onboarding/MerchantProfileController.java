package com.kasa.onboarding;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.kasa.security.CurrentTenant;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Under /settings, so only the business owner can read or send the application. */
@RestController
public class MerchantProfileController {

    public record ProfileRequest(
            @NotBlank String businessType,
            @NotBlank @Size(max = 200) String legalName,
            @NotBlank @Size(max = 11) String taxId,
            @NotBlank @Size(max = 100) String taxOffice,
            @NotBlank String sector,
            @Size(max = 200) String website,
            @NotBlank @Size(max = 30) String phone,
            @NotBlank @Size(max = 500) String address,
            @NotBlank @Size(max = 100) String city,
            @NotBlank @Size(max = 40) String iban) {
    }

    private final MerchantProfileService profiles;

    public MerchantProfileController(MerchantProfileService profiles) {
        this.profiles = profiles;
    }

    @GetMapping("/api/v1/settings/business-profile")
    public ResponseEntity<MerchantProfileService.Profile> get(Authentication authentication) {
        return profiles.get(CurrentTenant.of(authentication)).map(ResponseEntity::ok).orElse(ResponseEntity.noContent().build());
    }

    @PutMapping("/api/v1/settings/business-profile")
    public MerchantProfileService.Profile save(Authentication authentication, @Valid @RequestBody ProfileRequest request) {
        return profiles.save(CurrentTenant.of(authentication), new MerchantProfileService.ProfileInput(request.businessType(),
                request.legalName(), request.taxId(), request.taxOffice(), request.sector(), request.website(), request.phone(),
                request.address(), request.city(), request.iban()));
    }
}
