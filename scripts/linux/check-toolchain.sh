#!/bin/sh

set -eu

failures=0

pass() {
    printf 'PASS  %s\n' "$1"
}

fail() {
    printf 'FAIL  %s\n' "$1" >&2
    failures=$((failures + 1))
}

version_at_least() {
    actual=$1
    required=$2
    awk -v actual="$actual" -v required="$required" 'BEGIN {
        split(actual, a, /[.]/)
        split(required, r, /[.]/)
        length_max = length(a) > length(r) ? length(a) : length(r)
        for (i = 1; i <= length_max; i++) {
            av = (a[i] == "" ? 0 : a[i]) + 0
            rv = (r[i] == "" ? 0 : r[i]) + 0
            if (av > rv) exit 0
            if (av < rv) exit 1
        }
        exit 0
    }'
}

printf '%s\n' 'Scarlet Linux maintainer toolchain check'
printf '%s\n' '----------------------------------------'

kernel=unknown
architecture=unknown
if command -v uname >/dev/null 2>&1; then
    kernel=$(uname -s 2>/dev/null || printf unknown)
    architecture=$(uname -m 2>/dev/null || printf unknown)
else
    fail "uname is missing"
fi
if [ "$kernel" = "Linux" ]; then
    pass "Operating system: Linux"
else
    fail "Operating system is $kernel; supported maintainer builds require Linux"
fi

case "$architecture" in
    x86_64|amd64)
        pass "CPU architecture: x86-64"
        ;;
    aarch64|arm64)
        pass "CPU architecture: AArch64"
        ;;
    ppc|powerpc|ppc64|ppc64le)
        fail "CPU architecture is $architecture; PowerPC remains experimental and is outside the supported build gate"
        ;;
    *)
        fail "CPU architecture is $architecture; supported release builds require x86-64 or AArch64"
        ;;
esac

parsing_tools_ready=1
for command_name in java javac jar mvn sed awk tr readelf; do
    if command -v "$command_name" >/dev/null 2>&1; then
        pass "$command_name is available: $(command -v "$command_name")"
    else
        fail "$command_name is missing"
        case "$command_name" in
            sed|awk) parsing_tools_ready=0 ;;
        esac
    fi
done

if command -v java >/dev/null 2>&1 && [ "$parsing_tools_ready" -eq 1 ]; then
    java_specification=$(
        java -XshowSettings:properties -version 2>&1 |
        sed -n 's/^[[:space:]]*java.specification.version = //p' |
        sed -n '1p'
    )
    java_major=$(printf '%s\n' "$java_specification" | sed 's/^1[.]//; s/[.].*$//')
    if [ "$java_major" = "21" ]; then
        pass "Java specification version: $java_specification"
    else
        fail "Java specification version is ${java_specification:-unknown}; use JDK 21"
    fi
fi

if command -v javac >/dev/null 2>&1 && [ "$parsing_tools_ready" -eq 1 ]; then
    javac_version=$(javac -version 2>&1 | sed -n 's/^javac[[:space:]]*//p' | sed -n '1p')
    javac_major=$(printf '%s\n' "$javac_version" | sed 's/^1[.]//; s/[.].*$//')
    if [ "$javac_major" = "21" ]; then
        pass "Java compiler version: $javac_version"
    else
        fail "Java compiler version is ${javac_version:-unknown}; use the JDK 21 compiler"
    fi
fi

if command -v mvn >/dev/null 2>&1 && [ "$parsing_tools_ready" -eq 1 ]; then
    maven_version=$(mvn -version 2>&1 | sed -n 's/^Apache Maven[[:space:]]*\([0-9][0-9.]*\).*/\1/p' | sed -n '1p')
    if [ -n "$maven_version" ] && version_at_least "$maven_version" "3.9"; then
        pass "Apache Maven version: $maven_version"
    else
        fail "Apache Maven version is ${maven_version:-unknown}; use Maven 3.9 or newer"
    fi
fi

glibc_version=""
if command -v getconf >/dev/null 2>&1 && [ "$parsing_tools_ready" -eq 1 ]; then
    glibc_version=$(getconf GNU_LIBC_VERSION 2>/dev/null | sed -n 's/^glibc[[:space:]]*//p' || true)
fi
if [ -n "$glibc_version" ]; then
    if version_at_least "$glibc_version" "2.38"; then
        pass "glibc version: $glibc_version"
    else
        fail "glibc version is $glibc_version; the packaged DAVE library requires glibc 2.38 or newer"
    fi
else
    fail "Could not confirm glibc; musl-based or unidentified C libraries are outside the supported release target"
fi

# Resolve declared native dependencies from the loader cache without executing the
# packaged library (unlike a load/voice smoke test, this is a read-only ABI preflight).
ldconfig_command=$(command -v ldconfig || true)
if [ -z "$ldconfig_command" ] && [ -x /sbin/ldconfig ]; then ldconfig_command=/sbin/ldconfig; fi
native_cache=""
if [ -n "$ldconfig_command" ]; then
    native_cache=$(LC_ALL=C "$ldconfig_command" -p 2>/dev/null || true)
else
    fail "ldconfig is missing; cannot resolve the host C/C++ runtime libraries"
fi

find_native_library() {
    library_name=$1
    case "$architecture" in
        x86_64|amd64) cache_arch='x86-64' ;;
        aarch64|arm64) cache_arch='AArch64' ;;
        *) return 1 ;;
    esac
    printf '%s\n' "$native_cache" | awk -v name="$library_name" -v arch="$cache_arch" \
        '$1 == name && index($0, arch) { print $NF; exit }'
}

if command -v readelf >/dev/null 2>&1 && [ "$parsing_tools_ready" -eq 1 ]; then
    script_root=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
    case "$architecture" in
        x86_64|amd64) native_arch=linux-x86-64; loader=ld-linux-x86-64.so.2 ;;
        aarch64|arm64) native_arch=linux-aarch64; loader=ld-linux-aarch64.so.1 ;;
        *) native_arch=unsupported; loader=unsupported ;;
    esac
    native_payload="$script_root/src/main/resources/$native_arch/libdave.so"
    if [ -f "$native_payload" ]; then
        needed=$(LC_ALL=C readelf -d "$native_payload" | sed -n 's/.*Shared library: \[\([^]]*\)\].*/\1/p')
        # Preferred x86-64 JNI DAVE additionally depends on libm, libpthread and the loader.
        for library in $needed libm.so.6 libpthread.so.0 "$loader"; do
            library_path=$(find_native_library "$library")
            if [ -n "$library_path" ] && [ -r "$library_path" ]; then
                pass "Native runtime $library: $library_path"
            else
                fail "Required $architecture native runtime is missing: $library"
            fi
        done
        requirements=$(LC_ALL=C readelf --version-info "$native_payload" | awk '
            /Version needs section/ { needs = 1 }
            needs { for (i = 1; i <= NF; i++) {
                if ($i == "File:") library = $(i + 1)
                if ($i == "Name:") print library, $(i + 1)
            } }')
        if [ -z "$requirements" ]; then
            fail "Could not inspect native version requirements: $native_payload"
        else
            while read -r library required_symbol; do
                library_path=$(find_native_library "$library")
                if [ -n "$library_path" ] && [ -r "$library_path" ] &&
                    LC_ALL=C readelf --version-info "$library_path" 2>/dev/null |
                    awk -v expected="$required_symbol" '
                        /Version definition section/ { definitions = 1; next }
                        /Version needs section/ { definitions = 0 }
                        definitions { for (i = 1; i <= NF; i++) if ($i == "Name:" && $(i + 1) == expected) found = 1 }
                        END { exit !found }'; then
                    pass "$library exports required $required_symbol"
                else
                    fail "$library is missing required $required_symbol; install a compatible distribution runtime"
                fi
            done <<REQUIRED_NATIVE_SYMBOLS
$requirements
REQUIRED_NATIVE_SYMBOLS
        fi
    else
        fail "Packaged fallback voice payload is missing for $architecture"
    fi
fi

if [ "$failures" -ne 0 ]; then
    printf '\nToolchain check failed with %s problem(s).\n' "$failures" >&2
    exit 1
fi

printf '\nLinux toolchain and declared host-native requirements passed.\n'
printf '%s\n' 'This is not a native-load, Discord voice, or other-architecture runtime test.'
