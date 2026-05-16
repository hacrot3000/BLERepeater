#!/usr/bin/env bash
set -euo pipefail

OUT="${1:-staged_changes_$(date +'%d_%m_%Y_%H_%M').zip}"

# Kiểm tra đang ở trong git repo
if ! git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    echo "ERROR: Không nằm trong git repository"
    exit 1
fi

# Lấy danh sách file đã add/staged, chỉ lấy Added/Copied/Modified/Renamed
# Sau đó lọc java, sh, xml
mapfile -t FILES < <(
    git diff --cached --name-only --diff-filter=ACMR |
    grep -E '\.(java|sh|xml|kt)$' || true
)

if [ "${#FILES[@]}" -eq 0 ]; then
    echo "Không có file .java/.sh/.xml nào đang staged để nén."
    exit 0
fi

# Xóa file zip cũ nếu có
rm -f "$OUT"

# Nén giữ nguyên cấu trúc thư mục
zip -q "$OUT" "${FILES[@]}"

echo "Đã nén ${#FILES[@]} file vào: $OUT"
echo
printf '%s\n' "${FILES[@]}"