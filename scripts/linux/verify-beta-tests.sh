#!/bin/sh
set -eu
script_directory=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
repository_root=$(CDPATH= cd -- "$script_directory/../.." && pwd)
maven_repository=${SCARLET_MAVEN_REPOSITORY:-"$repository_root/.build-cache/maven"}
version=$(tr -d '\r\n' < "$repository_root/scarlet.version")
candidate=${1:-"$repository_root/target/scarlet-$version.jar"}
classpath="$candidate:$repository_root/target/test-classes"
for entry in \
 org/junit/platform/junit-platform-launcher/1.11.4/junit-platform-launcher-1.11.4.jar \
 org/junit/platform/junit-platform-engine/1.11.4/junit-platform-engine-1.11.4.jar \
 org/junit/platform/junit-platform-commons/1.11.4/junit-platform-commons-1.11.4.jar \
 org/junit/jupiter/junit-jupiter-api/5.11.4/junit-jupiter-api-5.11.4.jar \
 org/junit/jupiter/junit-jupiter-engine/5.11.4/junit-jupiter-engine-5.11.4.jar \
 org/junit/jupiter/junit-jupiter-params/5.11.4/junit-jupiter-params-5.11.4.jar \
 org/opentest4j/opentest4j/1.3.0/opentest4j-1.3.0.jar \
 org/apiguardian/apiguardian-api/1.1.2/apiguardian-api-1.1.2.jar
do
    test -f "$maven_repository/$entry" || { printf 'Missing test library: %s\n' "$entry" >&2; exit 1; }
    classpath="$classpath:$maven_repository/$entry"
done
cd "$repository_root"
test_root=${SCARLET_TEST_DIRECTORY:-"$repository_root/target/packaged-test-data"}
mkdir -p -- "$test_root"
test_root=$(CDPATH= cd -- "$test_root" && pwd)
exec java -Djava.awt.headless=true "-DSCARLET_HOME=$test_root/home" \
 "-Djava.io.tmpdir=$test_root" "-Duser.home=$test_root/user" \
 -Djava.util.prefs.PreferencesFactory=net.sybyline.scarlet.TestPreferencesFactory \
 -Djdk.net.URLClassPath.disableClassPathURLCheck=true -cp "$classpath" "$repository_root/scripts/VerifyBetaTests.java"
