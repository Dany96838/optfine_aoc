#!/usr/bin/env bash
set -e

echo "========================================"
echo " AOC - LIMPEZA DE BUILD"
echo "========================================"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

echo "[1/5] Removendo build antigo..."
rm -rf build

echo "[2/5] Removendo caches locais do Gradle do projeto..."
rm -rf .gradle

echo "[3/5] Removendo JARs antigos gerados fora de build..."
find . -maxdepth 3 -type f \
  \( -name "AndroidOptimizationCore-*.jar" -o -name "androidoptimizationcore-*.jar" \) \
  -not -path "./build/*" -delete

echo "[4/5] Removendo classes/artefatos temporários..."
find . -maxdepth 4 -type d \
  \( -name "classes" -o -name "reobf" -o -name "tmp" \) \
  -not -path "./run/*" -prune -exec rm -rf {} +

echo "[5/5] Verificando resultado..."
if [ -d "build" ]; then
    echo "[ERRO] O diretório build ainda existe."
    exit 1
fi

echo
echo "[OK] Limpeza concluída."
echo
echo "Agora compile manualmente com:"
echo "  ./gradlew build"
echo
