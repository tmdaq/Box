#!/bin/bash
# 把 dev-workflow 自带的 dev-agent 注册到项目的 .claude/agents/，链接使用相对路径。
set -euo pipefail

root="$(git rev-parse --show-toplevel)"
cd "$root"

src=""
for d in .claude/skills/dev-workflow .agents/skills/dev-workflow; do
    if [ -f "$d/agents/dev-agent.md" ]; then
        src="$d/agents/dev-agent.md"
        break
    fi
done
if [ -z "$src" ]; then
    echo "dev-workflow not found under .claude/skills or .agents/skills; install it first" >&2
    exit 1
fi

mkdir -p .claude/agents
real="$(cd "$(dirname "$src")" && pwd -P)/$(basename "$src")"
agents_dir="$(cd .claude/agents && pwd -P)"
if command -v python3 > /dev/null; then
    link="$(python3 -c 'import os,sys; print(os.path.relpath(sys.argv[1], sys.argv[2]))' "$real" "$agents_dir")"
else
    link="$real"
fi

ln -sfn "$link" .claude/agents/dev-agent.md
echo "linked .claude/agents/dev-agent.md -> $link"
