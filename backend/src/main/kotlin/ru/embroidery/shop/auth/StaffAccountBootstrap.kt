package ru.embroidery.shop.auth

import jakarta.validation.Validator
import jakarta.validation.constraints.Email
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.env.Environment
import org.springframework.core.env.Profiles
import org.springframework.stereotype.Component
import ru.embroidery.shop.ShopSettings
import java.util.HexFormat

@Component
class StaffAccountBootstrap(
    private val accounts: StaffAccounts,
    private val settings: ShopSettings,
    private val environment: Environment,
    private val validator: Validator
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        accounts.initialize {
            parseBootstrapStaffAccounts(
                settings.bootstrapStaffTotpAccounts,
                environment.acceptsProfiles(Profiles.of("production")),
                validator
            )
        }
    }
}

internal fun parseBootstrapStaffAccounts(entries: List<String>, production: Boolean, validator: Validator): Map<String, String> {
    require(entries.isNotEmpty()) { "Set SHOP_BOOTSTRAP_STAFF_TOTP_ACCOUNTS before initializing staff accounts" }
    val secrets = mutableSetOf<String>()
    return buildMap {
        for (entry in entries) {
            val separator = entry.lastIndexOf('=')
            require(separator > 0) { "Configure SHOP_BOOTSTRAP_STAFF_TOTP_ACCOUNTS as email=Base32-secret pairs" }
            val email = normalizedEmail(entry.substring(0, separator))
            require(email.length <= 254 && '@' in email && validator.validate(BootstrapEmail(email)).isEmpty()) {
                "SHOP_BOOTSTRAP_STAFF_TOTP_ACCOUNTS contains an invalid email"
            }
            val secret = entry.substring(separator + 1)
            val credential = StaffTotp(secret)
            require(!production || (!email.endsWith(".test") &&
                HexFormat.of().formatHex(credential.fingerprint) !in localTestFingerprints)) {
                "Local test accounts and secrets are unavailable in production"
            }
            require(email !in this && secrets.add(secret)) {
                "Each staff account must have a unique email and TOTP secret"
            }
            put(email, secret)
        }
    }
}

private class BootstrapEmail(@field:Email val value: String)

// Reject the public .env.example keys even if they are assigned a different address.
private val localTestFingerprints = setOf(
    "c9927a8a8fa2711ee80aa7231c875b06ecfd7af2de351809063541c28b01f393",
    "aeb7172be4b77998c2bdc11edd759c8815cee7b4574a629c5bc43e1d8e8cfc1f",
    "e9137f754e01d83c2e01b117a67f97c2bdb95e1f1a5218f0ed2ef39d61014ce1",
    "9a459d9b61e01010b3f4a8e5b9a1ed0aab36ff3389b5ceb5e931ef0a34e8e863"
)
