// Stand-in "WhatsApp" for testing Chat Translator on an emulator. Separate build so it never
// ships with the app. Run from this folder: ./gradlew :fake-whatsapp:assembleDebug
pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositories { google(); mavenCentral() } }
rootProject.name = "ChatTranslatorTesting"
include(":fake-whatsapp")
