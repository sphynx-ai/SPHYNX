package com.sphynx.core

// The app version: written only in app/build.gradle.kts (sphynxVersion), one step up with every merged
// pull request (owner, 2026-11-14). Read here so the screens, recordings and backups all show the same one.
object AppVersion {
    val VERSION: String get() = com.sphynx.BuildConfig.VERSION_NAME
}
