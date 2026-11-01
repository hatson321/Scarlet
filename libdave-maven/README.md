# Vendored libdave-jvm modules

This directory contains the Maven modules Scarlet uses for Discord Audio and
Video End-to-End Encryption (DAVE) on supported desktop runtimes.

## Modules

```text
libdave-maven/
├── pom.xml          # Parent build
├── api/             # Core DAVE interfaces
├── impl-jni/        # JNI implementation and desktop native loading
└── adapter-jda/     # JDA integration
```

## Build

Install the vendored modules into the same local Maven repository that will be
used to build Scarlet:

```bash
cd libdave-maven
mvn clean install
```

Then build Scarlet from its source root. The Scarlet build supplies the
supported Windows, Linux, and macOS desktop native artifacts.

## Scope

These modules provide the DAVE protocol API, JNI implementation, and JDA
adapter. Mobile packaging, Android native libraries, APK support, and Termux
runtime support are intentionally not part of the active Scarlet source or
build.

## License

This project is based on Discord's libdave-jvm project. Please refer to the original license terms.
