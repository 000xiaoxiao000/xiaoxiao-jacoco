#!/usr/bin/env bash
# 首次在某台机器上用 Maven 构建 peruser-jacoco 前，需要先把手工程 lib/ 里的
# 【定制版】jacoco（版本号带日期后缀）和 asm 装进本地 .m2。
# 标准 Maven Central 的 org.jacoco:org.jacoco.core:0.8.15 是另一份字节码，
# 可能与本项目依赖的定制版内部 API / 覆盖率格式不一致，请勿替换。
#
# 用法：  bash setup-m2.sh
set -e
DIR="$(cd "$(dirname "$0")" && pwd)"
MVN="${MVN:-mvn}"

if [ ! -d "$DIR/lib" ]; then
  echo "[setup-m2] 找不到 lib/ 目录（应在工程根）"; exit 1
fi

"$MVN" install:install-file -Dfile="$DIR/lib/asm-9.10.1.jar" \
  -DgroupId=org.ow2.asm -DartifactId=asm -Dversion=9.10.1 -Dpackaging=jar

"$MVN" install:install-file -Dfile="$DIR/lib/org.jacoco.core-0.8.15.202606040825.jar" \
  -DgroupId=org.jacoco -DartifactId=org.jacoco.core -Dversion=0.8.15.202606040825 -Dpackaging=jar

"$MVN" install:install-file -Dfile="$DIR/lib/org.jacoco.report-0.8.15.202606040825.jar" \
  -DgroupId=org.jacoco -DartifactId=org.jacoco.report -Dversion=0.8.15.202606040825 -Dpackaging=jar

echo "[setup-m2] 完成。现在可以运行： mvn clean package"
