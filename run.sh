set -u
git fetch -q origin '+refs/heads/tmp/*:refs/remotes/origin/tmp/*'
for b in $(git for-each-ref --format='%(refname:strip=3)' refs/remotes/origin/tmp/); do
  case "$b" in tmp/cleanup*) continue ;; esac
  git push -q origin --delete "$b" && echo "deleted $b"
done
