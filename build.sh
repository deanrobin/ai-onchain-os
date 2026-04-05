#!/bin/bash
# ─────────────────────────────────────────────────────────────────
#  aios-dashboard 构建脚本
#  用法：sh build.sh
#  依赖：JDK 17+、Maven 3.x
# ─────────────────────────────────────────────────────────────────

set -e

# ── 颜色 ──────────────────────────────────────────────────────────
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
BOLD='\033[1m'
RESET='\033[0m'

echo ""
echo -e "${CYAN}${BOLD}╔══════════════════════════════════════════╗${RESET}"
echo -e "${CYAN}${BOLD}║      aios-dashboard  Build Script        ║${RESET}"
echo -e "${CYAN}${BOLD}╚══════════════════════════════════════════╝${RESET}"
echo ""

# ── 检查 JDK ──────────────────────────────────────────────────────
if ! command -v java &>/dev/null; then
    echo -e "${RED}❌ 未找到 java，请安装 JDK 17+${RESET}"
    exit 1
fi
JAVA_VER=$(java -version 2>&1 | head -1 | grep -oP '"\K[^"]+' | cut -d. -f1)
if [ "$JAVA_VER" -lt 17 ] 2>/dev/null; then
    echo -e "${RED}❌ JDK 版本过低（当前 ${JAVA_VER}），需要 JDK 17+${RESET}"
    exit 1
fi
echo -e "☕ Java:   $(java -version 2>&1 | head -1)"

# ── 检查 Maven ────────────────────────────────────────────────────
if ! command -v mvn &>/dev/null; then
    echo -e "${RED}❌ 未找到 mvn，请安装 Maven 3.x${RESET}"
    exit 1
fi
echo -e "📦 Maven:  $(mvn -version 2>&1 | head -1)"
echo ""

# ── 从 pom.xml 读取版本号 ─────────────────────────────────────────
PROJECT_ROOT="$(cd "$(dirname "$0")" && pwd)"
VERSION=$(grep -m1 '<version>' "$PROJECT_ROOT/pom.xml" | sed 's/.*<version>\(.*\)<\/version>.*/\1/' | tr -d '[:space:]')
ARTIFACT="dashboard-${VERSION}.jar"
OUTPUT_DIR="$PROJECT_ROOT/dashboard/target"

echo -e "📂 项目路径：${PROJECT_ROOT}"
echo -e "🏷  构建版本：${BOLD}${VERSION}${RESET}"
echo ""

# ── 执行构建 ──────────────────────────────────────────────────────
echo -e "${YELLOW}▶ 开始构建...${RESET}"
START_TIME=$(date +%s)

mvn -pl dashboard -am clean package -DskipTests -q \
    -f "$PROJECT_ROOT/pom.xml"

END_TIME=$(date +%s)
ELAPSED=$((END_TIME - START_TIME))

# ── 检查产物 ──────────────────────────────────────────────────────
JAR_PATH="$OUTPUT_DIR/$ARTIFACT"
if [ ! -f "$JAR_PATH" ]; then
    echo -e "${RED}❌ 构建失败，未找到 JAR：${JAR_PATH}${RESET}"
    exit 1
fi

JAR_SIZE=$(du -sh "$JAR_PATH" | cut -f1)
BUILD_TIME=$(date '+%Y-%m-%d %H:%M:%S')

echo ""
echo -e "${GREEN}${BOLD}╔══════════════════════════════════════════╗${RESET}"
echo -e "${GREEN}${BOLD}║           ✅  BUILD SUCCESS              ║${RESET}"
echo -e "${GREEN}${BOLD}╚══════════════════════════════════════════╝${RESET}"
echo ""
echo -e "  🏷  版本号：  ${BOLD}${VERSION}${RESET}"
echo -e "  📄 JAR 文件：${JAR_PATH}"
echo -e "  📦 文件大小：${JAR_SIZE}"
echo -e "  🕐 构建时间：${BUILD_TIME}"
echo -e "  ⏱  耗时：    ${ELAPSED}s"
echo ""
echo -e "${CYAN}──────────────────────────────────────────${RESET}"
echo -e "  启动命令："
echo -e "  ${BOLD}sh /root/aios-dashboard/start.sh${RESET}"
echo -e "${CYAN}──────────────────────────────────────────${RESET}"
echo ""
