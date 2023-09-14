plugins {
    `java-library`
    `maven-publish`
}

repositories {
    mavenLocal()
    maven {
        url = uri("https://repo.maven.apache.org/maven2/")
    }
}

dependencies {
    api("com.squareup.okhttp3:okhttp:4.2.2")
    api("org.springframework:spring-web:6.0.12")
    api("org.apache.httpcomponents:httpclient:4.5.14")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.0")
    testImplementation("org.junit.jupiter:junit-jupiter-engine:5.10.0")
    testImplementation("org.assertj:assertj-core:3.24.2")
    testImplementation("org.mockito:mockito-core:3.1.0")
}

group = "xyz.kyngs"
version = "1.0.0-SNAPSHOT"
description = "Java sitemap generator"
java.sourceCompatibility = JavaVersion.VERSION_17

java {
    withSourcesJar()
}

publishing {
    repositories {
        maven {
            name = "kyngsRepo"
            url = uri(
                    "https://repo.kyngs.xyz/" + (if (project.version.toString()
                                    .contains("SNAPSHOT")
                    ) "snapshots" else "releases") + "/"
            )
            credentials(PasswordCredentials::class)
            authentication {
                create<BasicAuthentication>("basic")
            }
        }
    }
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}

tasks.withType<JavaCompile>() {
    options.encoding = "UTF-8"
}

tasks.withType<Javadoc>() {
    options.encoding = "UTF-8"
}