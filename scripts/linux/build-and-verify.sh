#!/bin/sh

set -eu

script_directory=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
repository_root=$(CDPATH= cd -- "$script_directory/../.." && pwd)
maven_repository=${SCARLET_MAVEN_REPOSITORY:-"$repository_root/.build-cache/maven"}

sh "$script_directory/check-toolchain.sh"

mkdir -p "$maven_repository"
maven_repository_argument="-Dmaven.repo.local=$maven_repository"
offline_argument=""
if [ "${SCARLET_MAVEN_OFFLINE:-0}" = "1" ]; then
    offline_argument="--offline"
    printf '%s\n' 'Maven offline mode enabled.'
fi

printf '\n%s\n' 'Preparing the checksum-pinned upstream VRChat SDK source...'
(
    cd "$repository_root"
    java scripts/BootstrapVrchatSdk.java $offline_argument
    mvn "$maven_repository_argument" -B -ntp $offline_argument \
        -f .build-cache/vrchat-sdk/source/vrchatapi-java-1.23.0/pom.xml \
        -Dmaven.test.skip=true -Dmaven.javadoc.skip=true clean install
)

printf '\n%s\n' 'Building and installing vendored libdave modules...'
(
    cd "$repository_root/libdave-maven"
    mvn "$maven_repository_argument" -B -ntp $offline_argument clean install
)

printf '\n%s\n' 'Building and verifying Scarlet...'
(
    cd "$repository_root"
    mvn "$maven_repository_argument" -B -ntp $offline_argument clean verify
)

version=$(
    sed -n 's|^[[:space:]]*<version>\([^<]*\)</version>[[:space:]]*<!--scarlet-version-->.*$|\1|p' \
        "$repository_root/pom.xml" |
    sed -n '1p'
)
if [ -z "$version" ]; then
    printf '%s\n' 'Could not read the Scarlet artifact version from pom.xml.' >&2
    exit 1
fi

printf '\n%s\n' 'Inspecting Linux distribution payloads...'
java "$script_directory/VerifyLinuxPackage.java" \
    "$repository_root/target/scarlet-$version.jar" \
    "$repository_root/target/scarlet-$version-debug.jar" \
    "$repository_root/target/scarlet-$version-ppc.jar" \
    "$repository_root/target/SHA256SUMS-linux.txt"

printf '\nBuild and supported Linux package verification completed successfully.\n'
