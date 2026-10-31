#!/bin/bash
# Checks the .deb and .rpm in build/jpackage declare this fork's package name and the release as their revision.
#
# Both matter for installing. Under upstream's name the package replaces upstream Sparrow; with a revision that does not
# rise, apt and dnf see a newer release as the same version and do not upgrade. The expected values are read from the
# same places the build reads them, build.gradle and SparrowWallet.java, so this cannot drift from what the build means.
#
# Usage: check-linux-packages.sh <package-name>, e.g. sparrowwallet-blake2b or sparrowserver-blake2b
set -euo pipefail

name="$1"
version=$(sed -n "s/^version = '\([^']*\)'/\1/p" build.gradle)
release=$(sed -n 's/.*APP_VERSION_SUFFIX = "-blake2b\.\([0-9]*\)";.*/\1/p' src/main/java/com/sparrowwallet/sparrow/SparrowWallet.java)
[ -n "$version" ] && [ -n "$release" ] || { echo "could not read the version and release"; exit 1; }

fail() { echo "FAIL: $*"; exit 1; }

debs=(build/jpackage/*.deb)
[ "${#debs[@]}" -eq 1 ] && [ -f "${debs[0]}" ] || fail "expected one .deb, found: ${debs[*]}"
deb="${debs[0]}"
echo "== $deb"
dpkg-deb -f "$deb" Package Version Maintainer
[ "$(dpkg-deb -f "$deb" Package)" = "$name" ] || fail "package name is $(dpkg-deb -f "$deb" Package), expected $name"
[ "$(dpkg-deb -f "$deb" Version)" = "${version}-${release}" ] || fail "version is $(dpkg-deb -f "$deb" Version), expected ${version}-${release}"
case "$(basename "$deb")" in "${name}_${version}-${release}_"*) ;; *) fail "file name $(basename "$deb") does not name ${name} ${version}-${release}";; esac
deb_members=$(ar t "$deb")
grep -qx 'data.tar.xz' <<< "$deb_members" || fail "the .deb is not xz compressed, so older dpkg cannot read it"
#Listed into a variable first: grep -q stops reading at its first match, and under pipefail the writer it leaves
#behind fails the pipeline
deb_files=$(dpkg-deb -c "$deb")
grep -q "/opt/${name}/" <<< "$deb_files" || fail "the .deb does not install to /opt/${name}"

rpms=(build/jpackage/*.rpm)
[ "${#rpms[@]}" -eq 1 ] && [ -f "${rpms[0]}" ] || fail "expected one .rpm, found: ${rpms[*]}"
rpm="${rpms[0]}"
echo "== $rpm"
actual=$(rpm -qp --qf '%{NAME} %{VERSION} %{RELEASE}' "$rpm")
echo "$actual"
[ "$actual" = "${name} ${version} ${release}" ] || fail "rpm declares '$actual', expected '${name} ${version} ${release}'"
rpm_files=$(rpm -qlp "$rpm")
grep -q "^/opt/${name}/" <<< "$rpm_files" || fail "the .rpm does not install to /opt/${name}"

echo "OK: ${name} ${version}-${release}"
