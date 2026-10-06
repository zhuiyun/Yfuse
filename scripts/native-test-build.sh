#!/usr/bin/env bash
# Sourced by the scripts/test-ycore-*.sh native unit tests.
#
# ycore_native_test NAME COMPILER-ARGS... builds a test twice and runs both:
#   - with the release flags (-O2 -DNDEBUG), which every test's checks survive;
#   - under AddressSanitizer and UndefinedBehaviorSanitizer, where the compiler has them, so an
#     out-of-bounds read, a use after free or a signed overflow fails the test instead of passing
#     by luck. YCORE_NATIVE_SANITIZERS=0 skips this build.

ycore_native_sanitizers_available() {
  [ "${YCORE_NATIVE_SANITIZERS:-1}" != "0" ] || return 1
  local probe
  probe="$(mktemp -d)"
  printf 'int main() { return 0; }\n' > "$probe/probe.cpp"
  if "${CXX:-c++}" -fsanitize=address,undefined "$probe/probe.cpp" -o "$probe/probe" >/dev/null 2>&1 &&
    "$probe/probe" >/dev/null 2>&1; then
    rm -rf "$probe"
    return 0
  fi
  rm -rf "$probe"
  return 1
}

YCORE_NATIVE_TEST_DIR="$(mktemp -d)"
trap 'rm -rf "$YCORE_NATIVE_TEST_DIR"' EXIT

ycore_native_test() {
  local name="$1"
  shift
  local build_dir="$YCORE_NATIVE_TEST_DIR"
  "${CXX:-c++}" -std=c++17 -O2 -DNDEBUG -pthread -Wall -Wextra -Werror "$@" -o "$build_dir/$name"
  "$build_dir/$name"
  if ycore_native_sanitizers_available; then
    "${CXX:-c++}" -std=c++17 -O1 -g -fno-omit-frame-pointer \
      -fsanitize=address,undefined -fno-sanitize-recover=all \
      -pthread -Wall -Wextra -Werror "$@" -o "$build_dir/$name-sanitized"
    # Leak checking needs ptrace, which some CI containers deny; the errors above do not.
    ASAN_OPTIONS="detect_leaks=0:abort_on_error=1" UBSAN_OPTIONS="print_stacktrace=1" \
      "$build_dir/$name-sanitized"
    echo "[$name] sanitizer build passed"
  else
    echo "[$name] sanitizers unavailable with ${CXX:-c++}; release build only"
  fi
}
