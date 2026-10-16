@file:Suppress("UnstableApiUsage")

pluginManagement {
    repositories {
        google {
            content {
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
                includeGroupAndSubgroups("androidx")
            }
        }

        maven {
            name = "GcsCentral"
            setUrl("https://maven-central.storage-download.googleapis.com/maven2/")
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)

    repositories {
        google()

        maven {
            name = "GcsCentral"
            setUrl("https://maven-central.storage-download.googleapis.com/maven2/")
        }
        mavenCentral {
            mavenContent {
                releasesOnly()
            }
        }
        // The accompanist lyrics libraries publish RC builds (2.0.0-rc.x) that
        // mavenCentral's releasesOnly() filter refuses to serve, and the GCS
        // Central mirror can lag a freshly published release by hours — route
        // the group straight at repo1.maven.org so pre-release lyrics builds
        // resolve the moment they exist.
        exclusiveContent {
            forRepository {
                maven {
                    name = "AccompanistCentral"
                    setUrl("https://repo1.maven.org/maven2/")
                }
            }
            filter {
                includeGroup("com.mocharealm.accompanist")
                includeGroup("com.mocharealm.gaze")
            }
        }
        exclusiveContent {
            forRepository {
                maven {
                    name = "JitPack"
                    setUrl("https://jitpack.io")
                }
            }
            filter {
                includeGroup("com.github.therealbush")
                includeGroup("com.github.TeamNewPipe")

                includeGroup("com.github.amitshekhariitbhu")

                includeGroup("com.github.RouHim")

                includeGroup("com.github.evermind-zz")

                includeGroup("com.github.maxrave-dev")
                includeGroup("com.github.maxrave-dev.PipePipeExtractor")
                includeGroup("com.github.maxrave-dev.BravePipeExtractor")
            }
        }
        // Media3 FFmpeg decoder extension (Dolby AC-4, DTS and other formats
        // the platform decoders lack), prebuilt by the dmt project
        // (github.com/imjyotiraditya/dmt-media3 — official androidx.media3
        // ffmpeg module built with native libs). Scoped to ONLY the ffmpeg
        // artifact: every other media3 module keeps resolving from
        // Google Maven / the GCS mirror at the app's pinned version.
        exclusiveContent {
            forRepository {
                maven {
                    name = "DmtMedia3"
                    setUrl("https://raw.githubusercontent.com/imjyotiraditya/dmt-media3/maven/maven")
                }
            }
            filter {
                includeModule("androidx.media3", "media3-decoder-ffmpeg")
            }
        }
    }
}

rootProject.name = "ArchiveTune"
include(":app")
include(":core")
include(":audio:decent-usb-audio-driver")
include(":lyrics:kugou")
include(":lyrics:lrclib")

include(":lyrics:betterlyrics")
include(":lyrics:unison")
include(":lyrics:youlyplus")
include(":musixmatch")
include(":lastfm")
include(":canvas")
include(":shazamkit")
include(":spotifycore")
include(":morideobfuscator")
include(":jiosaavn")
include(":moriextractor")
