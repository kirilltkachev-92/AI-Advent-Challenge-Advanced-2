import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Минимальный проект-жертва для tests-gate лупа: только stdlib, компиляция —
// через дневной ./gradlew -p output/workspace compileKotlin.
plugins {
    kotlin("jvm") version "2.0.21"
}

repositories {
    mavenCentral()
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
