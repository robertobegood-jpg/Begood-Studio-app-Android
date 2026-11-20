import java.io.File
import java.security.KeyStore
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Assinatura: vem de variáveis de ambiente (GitHub Actions) ou de keystore.properties local.
// Igual aos outros apps: basta KEYSTORE_BASE64 + KEYSTORE_PASSWORD no GitHub (mesma chave de todos os apps).
// No PC: arquivo em app/assinatura/studio.jks (ou KEYSTORE_FILE=...) e KEYSTORE_PASSWORD=... no keystore.properties.
// A chave e a senha NUNCA vão para o repositório.
val assinatura = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun segredo(nome: String): String? =
    System.getenv(nome)?.takeIf { it.isNotBlank() } ?: assinatura.getProperty(nome)?.takeIf { it.isNotBlank() }

// O alias é lido do próprio arquivo e a senha da chave é a mesma do arquivo (KEY_ALIAS e KEY_PASSWORD são opcionais).
fun primeiroAlias(arquivo: File, senha: String): String? = runCatching {
    val ks = KeyStore.getInstance(KeyStore.getDefaultType())
    arquivo.inputStream().use { ks.load(it, senha.toCharArray()) }
    ks.aliases().toList().firstOrNull()
}.getOrNull()

android {
    namespace = "br.com.begood.studio"
    compileSdk = 35

    defaultConfig {
        applicationId = "br.com.begood.studio"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.0.1"
    }

    signingConfigs {
        val arquivo = (segredo("KEYSTORE_FILE") ?: "assinatura/studio.jks").let { file(it) }
        val senha = segredo("KEYSTORE_PASSWORD")
        if (arquivo.exists() && senha != null) {
            val alias = segredo("KEY_ALIAS") ?: primeiroAlias(arquivo, senha)
                ?: throw GradleException("Não deu para abrir a chave de assinatura: confira KEYSTORE_PASSWORD.")
            create("oficial") {
                storeFile = arquivo
                storePassword = senha
                keyAlias = alias
                keyPassword = segredo("KEY_PASSWORD") ?: senha
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Sem a chave oficial, sai com a chave de debug (dá para instalar, mas não atualiza o APK oficial).
            signingConfig = signingConfigs.findByName("oficial") ?: signingConfigs.getByName("debug")
        }
        debug {
            signingConfigs.findByName("oficial")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    // Player: mudar tempo e tom sem mexer um no outro, repeat A/B
    val media3 = "1.5.1"
    implementation("androidx.media3:media3-exoplayer:$media3")

    testImplementation("junit:junit:4.13.2")
}
