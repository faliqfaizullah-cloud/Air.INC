#!/data/data/com.termux/files/usr/bin/bash
# Usage: bash publish.sh <github-username> [repo-name]
pkg install -y git gh
git init -b main
git add . && git commit -m "Air.INC v1.0"
gh auth login
gh repo create "${2:-Air.INC}" --public --source=. --push
git tag v1.0 && git push origin v1.0
echo "Done. GitHub Actions builds the APK -> Releases tab / Actions artifacts."
