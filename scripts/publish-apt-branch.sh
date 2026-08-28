#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "Usage: $0 <repository-directory> <commit-message>" >&2
  exit 64
fi

repository_directory=$1
commit_message=$2
[[ -d "$repository_directory" ]] || { echo "APT repository directory not found" >&2; exit 66; }
repository_directory=$(cd "$repository_directory" && pwd)

publication_worktree=$(mktemp -d)
rmdir "$publication_worktree"
cleanup() {
  git worktree remove --force "$publication_worktree" 2>/dev/null || true
}
trap cleanup EXIT

git worktree add --detach "$publication_worktree"
(
  cd "$publication_worktree"
  git switch --orphan apt
  git rm -r --ignore-unmatch .
  cp -a "$repository_directory"/. .
  git add --all
  git config user.name "github-actions[bot]"
  git config user.email "41898282+github-actions[bot]@users.noreply.github.com"
  git commit --message "$commit_message"
  git push --force origin HEAD:apt
)
