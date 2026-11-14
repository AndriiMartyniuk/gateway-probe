plugins {
	java
	id("org.springframework.boot") version "4.1.2"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "ua.kpi.iot"
version = "0.0.1-SNAPSHOT"
description = "Minimal measurement gateway with per-phase counters (article 1)"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(25)
	}
}

repositories {
	// shared research libraries (lwm2m-objects), published with ./gradlew publishToMavenLocal
	mavenLocal {
		content {
			includeGroup("ua.kpi.iot")
		}
	}
	mavenCentral()
}

val leshanVersion = "2.0.0-M18"

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	// Eclipse Leshan LwM2M server + CoAP over TCP/TLS transport (java-coap + Netty)
	implementation("org.eclipse.leshan:leshan-lwm2m-server:$leshanVersion")
	implementation("org.eclipse.leshan:leshan-tl-jc-server-coaptcp:$leshanVersion")
	// REDIS placement: Leshan's RedisRegistrationStore (Jedis); REDIS_CACHED reuses only its serialisers (Lettuce)
	implementation("org.eclipse.leshan:leshan-lwm2m-server-redis:$leshanVersion")
	implementation("ua.kpi.iot:lwm2m-objects:0.1.0-SNAPSHOT")
	// Netty version is managed by the Spring Boot BOM
	implementation("io.netty:netty-handler")
	implementation("io.netty:netty-codec")
	implementation("org.springframework.boot:spring-boot-starter-data-redis")
	runtimeOnly("io.micrometer:micrometer-registry-prometheus")
	annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")
	testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.springframework.boot:spring-boot-starter-data-redis-test")
	testImplementation("org.springframework.boot:spring-boot-testcontainers")
	testImplementation("org.testcontainers:testcontainers-junit-jupiter")
	// Leshan client over CoAP over TCP/TLS, used only to verify registration in tests
	testImplementation("org.awaitility:awaitility")
	testImplementation("org.eclipse.leshan:leshan-lwm2m-client:$leshanVersion")
	testImplementation("org.eclipse.leshan:leshan-tl-jc-client-coaptcp:$leshanVersion")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
	useJUnitPlatform()
}
