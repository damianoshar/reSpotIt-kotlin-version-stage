#!/bin/sh
# Remove the secret file if it exists
rm -f android/keystore.properties
# Stage all changes
git add -A
# Amend the current commit (if there are changes)
git diff --cached --quiet || git commit --amend --no-edit
# Continue the rebase
git rebase --continue