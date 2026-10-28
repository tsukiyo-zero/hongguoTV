// SPDX-License-Identifier: GPL-3.0-only
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "GuoyingTVWindows"

// The Windows client reuses the shared content core and the loopback bridge that
// already backs the Mac client, so both desktop clients speak one protocol.
include(":core", ":bridge", ":app")
project(":core").projectDir = file("../kotlin-tv/core")
project(":bridge").projectDir = file("../desktop-mac/backend")
