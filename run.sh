gh run view 40888280167 -R "$GITHUB_REPOSITORY" --log-failed > full.log 2>&1 || true
grep -E ' e: |error:|What went wrong|Execution failed|Unresolved|Type mismatch|FAILED' full.log | sed 's#.*/nos-mobile/nos-mobile/##' | head -60
echo ----; grep -c . full.log
