#!/usr/bin/env bash
# 用法: tools/git-mirror.sh <git 子命令及参数...>
# 例:   tools/git-mirror.sh fetch upstream
#       tools/git-mirror.sh submodule update --init --recursive --depth 1
#
# 背景：本机 github.com 不可达（HTTPS 返回 502 / 直连超时），仅镜像站可用。
# 仓库级 url.<mirror>.insteadOf 只对主仓库生效，git submodule 派生的子进程
# 读不到它；这里改用 GIT_CONFIG_* 环境变量注入，子进程同样继承。
set -euo pipefail

mirror="${GITHUB_GIT_MIRROR:-ghfast.top}"

export GIT_CONFIG_COUNT=1
export GIT_CONFIG_KEY_0="url.https://${mirror}/https://github.com/.insteadOf"
export GIT_CONFIG_VALUE_0="https://github.com/"

exec git "$@"
