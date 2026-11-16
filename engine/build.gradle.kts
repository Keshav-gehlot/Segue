// Engine: links, pairing, write-ahead persistence, coordinator glue. Pure Kotlin/JVM.
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

dependencies {
    api(project(":core"))
}

val selfTest by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "End-to-end engine tests with simulated phones (also writes sample logs)."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.segue.engine.EngineSelfTestKt")
    args(layout.buildDirectory.dir("engine-test").get().asFile.path)
}

tasks.named("check") { dependsOn(selfTest) }
