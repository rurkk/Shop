import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.spring") version "2.3.21"
    id("org.springframework.boot") version "4.1.2"
}

group = "ru.embroidery.shop"
version = "0.1.0"

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(platform(SpringBootPlugin.BOM_COORDINATES))
    // Jackson 3.1.8 fixes CVE-2026-110879. AWS SDK manages its own shaded Jackson core.
    implementation(platform("tools.jackson:jackson-bom:3.1.8"))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-mail")
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation(kotlin("reflect"))
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    constraints {
        for (module in listOf("tomcat-embed-core", "tomcat-embed-el", "tomcat-embed-websocket")) {
            implementation("org.apache.tomcat.embed:$module:11.0.29")
        }
    }

    implementation(platform("software.amazon.awssdk:bom:2.59.1"))
    implementation("software.amazon.awssdk:s3") {
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
        exclude(group = "software.amazon.awssdk", module = "apache-client")
    }
    implementation("software.amazon.awssdk:url-connection-client")

    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.bootJar {
    archiveFileName = "shop.jar"
}

tasks.test {
    useJUnitPlatform()
}
