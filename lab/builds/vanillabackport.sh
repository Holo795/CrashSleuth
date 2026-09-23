# Vanilla Backport for NeoForge 1.21.1 as its authors build it, with the unpublished copper chest:
# the build a player tested in https://github.com/ItsBlackGear/VanillaBackport/issues/440.
# Its Platform library is only read from a local folder (/libs), so it is built first.
set -e
cd /work/pf-src && chmod +x gradlew && ./gradlew --no-daemon -q :common:build :neoforge:build -x test
V=$(grep '^mod_version' gradle.properties | sed 's/.*= *//')
mkdir -p /libs /work/out
cp common/build/libs/Platform-common-1.21.1-$V.jar /libs/
cp common/build/libs/Platform-common-1.21.1-$V.jar /libs/Platform-fabric-1.21.1-$V.jar
cp neoforge/build/libs/Platform-neoforge-1.21.1-$V.jar /libs/
cp neoforge/build/libs/Platform-neoforge-1.21.1-$V.jar /work/out/
cd /work/vb-src && sed -i "s/^platform_indev_version.*/platform_indev_version = $V/" gradle.properties
chmod +x gradlew && ./gradlew --no-daemon -q :neoforge:build -x test
cp neoforge/build/libs/VanillaBackport-neoforge-1.21.1-*[0-9].jar /work/out/
