package ru.embroidery.shop

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration
import org.springframework.scheduling.annotation.EnableScheduling
import java.net.URI
import java.util.UUID

@SpringBootApplication(exclude = [UserDetailsServiceAutoConfiguration::class])
@ConfigurationPropertiesScan
@EnableScheduling
class ShopApplication

fun main(args: Array<String>) {
    runApplication<ShopApplication>(*args)
}

@ConfigurationProperties("shop")
class ShopSettings(
    val bootstrapStaffTotpAccounts: List<String>,
    val staffTotpEncryptionKey: String,
    val codeSecret: String,
    val mailFrom: String,
    val publicOrigin: URI,
    val storage: Storage
) {
    init {
        require(codeSecret.length >= 32)
        val loopback = publicOrigin.host in setOf("127.0.0.1", "localhost", "[::1]")
        require(publicOrigin.host != null && publicOrigin.rawUserInfo == null && publicOrigin.rawQuery == null &&
            publicOrigin.rawFragment == null && publicOrigin.rawPath in listOf("", "/") &&
            (publicOrigin.port == -1 || publicOrigin.port in 1..65535) &&
            (publicOrigin.scheme == "https" || (publicOrigin.scheme == "http" && loopback))) {
            "Set SHOP_PUBLIC_ORIGIN to the HTTPS origin of KNITSEW or to a loopback HTTP origin for local testing"
        }
    }

    fun orderUrl(orderId: UUID): String = publicOrigin.resolve("/orders/$orderId").toASCIIString()

    class Storage(
        val endpoint: URI,
        val bucket: String,
        val accessKey: String,
        val secretKey: String
    )
}
