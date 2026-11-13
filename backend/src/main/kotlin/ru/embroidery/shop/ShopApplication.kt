package ru.embroidery.shop

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling
import java.net.URI

@SpringBootApplication(exclude = [UserDetailsServiceAutoConfiguration::class])
@ConfigurationPropertiesScan
@EnableScheduling
class ShopApplication

fun main(args: Array<String>) {
    runApplication<ShopApplication>(*args)
}

@ConfigurationProperties("shop")
class ShopSettings(
    val staffEmails: List<String>,
    val codeSecret: String,
    val mailFrom: String,
    val storage: Storage
) {
    init {
        require(staffEmails.isNotEmpty() && staffEmails.all { '@' in it })
        require(codeSecret.length >= 32)
    }

    class Storage(
        val endpoint: URI,
        val bucket: String,
        val accessKey: String,
        val secretKey: String
    )
}
