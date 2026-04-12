#!/usr/bin/env bash
# smoke-test.sh — Run against a locally running doc-processor service.
# Usage: ./smoke-test.sh [host:port]
# Requires: curl, jq

BASE="${1:-http://localhost:8080}"
PASS=0; FAIL=0

ok()   { echo "  ✅  $1"; PASS=$((PASS+1)); }
fail() { echo "  ❌  $1"; FAIL=$((FAIL+1)); }

echo "=== DocPilot Doc-Processor Smoke Test | $BASE ==="

# 1. Health
status=$(curl -s -o /dev/null -w "%{http_code}" "$BASE/actuator/health")
[ "$status" = "200" ] && ok "GET /actuator/health → 200" || fail "GET /actuator/health → $status"

# 2. Swagger UI accessible
status=$(curl -s -o /dev/null -w "%{http_code}" "$BASE/swagger-ui/index.html")
[ "$status" = "200" ] && ok "GET /swagger-ui/index.html → 200" || fail "Swagger UI → $status"

# 3. OpenAPI JSON exists
status=$(curl -s -o /dev/null -w "%{http_code}" "$BASE/api-docs")
[ "$status" = "200" ] && ok "GET /api-docs → 200" || fail "API docs → $status"

# 4. Styles unknown docId → 404
body=$(curl -s "$BASE/api/styles/does-not-exist")
err=$(echo "$body" | jq -r '.status' 2>/dev/null)
[ "$err" = "404" ] && ok "GET /api/styles/unknown → 404 JSON" || fail "GET /api/styles/unknown → unexpected: $body"

# 5. Structure unknown docId → 404
body=$(curl -s "$BASE/api/structure/does-not-exist")
err=$(echo "$body" | jq -r '.status' 2>/dev/null)
[ "$err" = "404" ] && ok "GET /api/structure/unknown → 404 JSON" || fail "GET /api/structure/unknown → unexpected: $body"

# 6. html-to-docx with empty JSON → 400
status=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE/api/convert/html-to-docx" \
  -H "Content-Type: application/json" -d '{}')
[ "$status" = "400" ] && ok "POST /api/convert/html-to-docx empty body → 400" || fail "Expected 400, got $status"

echo ""
echo "Results: $PASS passed, $FAIL failed"
[ "$FAIL" = "0" ] && exit 0 || exit 1
