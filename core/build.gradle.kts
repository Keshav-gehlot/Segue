// Protocol core: pure Kotlin/JVM, no Android dependencies. Same sources as the paper's artifact.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

// The simulator and self-tests live in src/test. They are plain programs, not JUnit tests.
val selfTest by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Protocol self-tests (20 checks)."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.segue.sim.SelfTestKt")
}

val experiments by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Reproduce the paper's handoff simulation (writes build/results/handoff_sim.csv)."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.segue.sim.ExperimentsKt")
    maxHeapSize = "3g"
    args("50000", layout.buildDirectory.dir("results").get().asFile.path)
}

tasks.named("check") { dependsOn(selfTest) }
