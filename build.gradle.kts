/*
 * Copyright the original author or authors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

plugins {
    kotlin("multiplatform") version "2.0.20"
    kotlin("plugin.serialization") version "2.0.20"
    id("maven-publish")
}

group = "de.libf.ptek"
version = "0.0.5"
val archivesBaseName = "public-transport-enabler-ktx"

repositories {
    mavenCentral()
}

kotlin {
    jvm {
        compilations.all {
            kotlinOptions.jvmTarget = "17"
        }
        withJava()
        testRuns["test"].executionTask.configure {
            useJUnitPlatform()
        }
    }

    iosX64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0-RC.2")
                implementation("io.ktor:ktor-client-core:3.0.0-rc-1")
                implementation("io.ktor:ktor-client-cio:3.0.0-rc-1")
                implementation("io.ktor:ktor-client-content-negotiation:3.0.0-rc-1")
                implementation("io.ktor:ktor-serialization-kotlinx-json:3.0.0-rc-1")
                implementation("io.ktor:ktor-serialization-kotlinx-xml:3.0.0-rc-1")
                implementation("io.ktor:ktor-client-content-negotiation:3.0.0-rc-1")

                implementation("net.thauvin.erik.urlencoder:urlencoder-lib:1.5.0")

                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")

                implementation("org.kotlincrypto.hash:md:0.5.3")

                // TODO: Find an alternative, slimmer library
                //implementation("com.ionspin.kotlin:multiplatform-crypto-libsodium-bindings:0.9.2")

                implementation("io.github.aakira:napier:2.7.1")
                //implementation("org.json:json:20090211") // provided by Android
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.2")
//                implementation("net.sf.kxml:kxml2:2.3.0") // provided by Android
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("junit:junit:4.13.2")
                implementation("org.slf4j:slf4j-jdk14:2.0.16")
            }
        }
//        val jvmMain by getting {
//            kotlin.srcDirs("src")
//            resources.srcDirs("src")
//        }
//        val jvmTest by getting {
//            kotlin.srcDirs("test")
//            resources.srcDirs("test")
//        }
    }
}

tasks.named<Test>("jvmTest") {
    useJUnitPlatform()
    exclude("de/schildbach/pte/live/**")
}

publishing {
    repositories {
        maven {
            name = "LocalRepo"
            url = uri("file://${layout.buildDirectory.get()}/repo")
        }
    }
}