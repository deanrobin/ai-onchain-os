#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
mvn -q clean package -DskipTests
echo "✅ JAR: $(pwd)/target/crypto-qmt-0.1.0-SNAPSHOT.jar"
