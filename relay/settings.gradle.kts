pluginManagement { repositories { mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { mavenCentral() } }
rootProject.name = "HongguoRelay"
include(":core", ":relay")
project(":core").projectDir = file("../kotlin-tv/core")
project(":relay").projectDir = file("backend")
